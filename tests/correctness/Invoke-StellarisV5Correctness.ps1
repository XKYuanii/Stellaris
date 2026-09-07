param(
    [string]$GatewayBaseUrl = 'http://127.0.0.1:6085',
    [string]$MySqlContainer = 'stellaris-interview-mysql-1',
    [string]$RedisContainer = 'stellaris-interview-redis-1',
    [string]$KafkaContainer = 'stellaris-interview-kafka-1',
    [string]$MySqlPassword = 'mysql123',
    [string]$RedisPassword = 'redis123',
    [string]$KafkaTopic = 'stellaris-create_order',
    [string]$KafkaGroup = 'create_order_data',
    [ValidateRange(10, 180)][int]$TimeoutSeconds = 45,
    [switch]$KeepOrders
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\benchmark\StellarisV5Benchmark.Common.ps1')

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$programId = $script:StellarisBenchmarkProgramId
$categoryId = $script:StellarisBenchmarkCategoryId
$userId = $script:StellarisBenchmarkUserBase
$otherUserId = $userId + 1
$ticketUserId = $script:StellarisBenchmarkTicketUserBase
$saleShard = $programId % 16
$prefix = "stellaris:{sale:$saleShard}:program:$programId`:seat:"
$stream = "stellaris:{sale:$saleShard}:reservation:event:stream"
$deadStream = "stellaris:{sale:$saleShard}:reservation:event:dead-stream"
$streamGroup = 'stellaris-order-relay'
$headers = @{
    no_verify = 'true'
    'X-Stellaris-Demo-User-Id' = "$userId"
}
$requestId = [guid]::NewGuid().ToString()
$orderNumber = $null
$reservationId = $null

function Invoke-JsonPost {
    param([string]$Path, [hashtable]$Body, [hashtable]$RequestHeaders = $headers)
    return Invoke-RestMethod -Method Post -Uri "$GatewayBaseUrl$Path" `
        -ContentType 'application/json;charset=UTF-8' -Headers $RequestHeaders `
        -Body ($Body | ConvertTo-Json -Depth 8 -Compress)
}

function Get-RedisLongOrZero {
    param([string[]]$Command)
    $values = @(Invoke-StellarisRedisRaw -Container $RedisContainer -Password $RedisPassword -Command $Command)
    if ($values.Count -eq 0 -or [string]::IsNullOrWhiteSpace("$($values[0])")) { return 0L }
    return [long]$values[0]
}

function Get-MySqlLong {
    param([string]$Query)
    $values = @(Invoke-StellarisMySqlQuery -Container $MySqlContainer -Password $MySqlPassword -Query $Query)
    if ($values.Count -eq 0 -or "$($values[0])" -notmatch '^\d+$') {
        throw "Expected one numeric MySQL result; actual=$($values -join ';')"
    }
    return [long]$values[0]
}

function Get-KafkaLag {
    $output = @(& docker exec $KafkaContainer /opt/kafka/bin/kafka-consumer-groups.sh `
        --bootstrap-server localhost:9092 --group $KafkaGroup --describe 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Kafka group describe failed: $($output -join ' ')"
    }
    $rows = @($output | Where-Object {
        $_ -match "^\s*$([regex]::Escape($KafkaGroup))\s+$([regex]::Escape($KafkaTopic))\s+"
    })
    if ($rows.Count -eq 0) {
        throw "Kafka group/topic row not found: group=$KafkaGroup topic=$KafkaTopic"
    }
    $lags = foreach ($row in $rows) {
        $parts = @(-split $row)
        if ($parts.Count -lt 6 -or $parts[5] -notmatch '^\d+$') {
            throw "Cannot parse Kafka LAG row: $row"
        }
        [long]$parts[5]
    }
    return [long](($lags | Measure-Object -Sum).Sum)
}

function Wait-PipelineDrained {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $stable = 0
    do {
        $streamLength = Get-RedisLongOrZero -Command @('XLEN', $stream)
        $pending = Get-RedisLongOrZero -Command @('XPENDING', $stream, $streamGroup)
        $deadLength = Get-RedisLongOrZero -Command @('XLEN', $deadStream)
        $kafkaLag = Get-KafkaLag
        if ($streamLength -eq 0 -and $pending -eq 0 -and $deadLength -eq 0 -and $kafkaLag -eq 0) {
            $stable++
        } else {
            $stable = 0
        }
        if ($stable -lt 2) { Start-Sleep -Seconds 1 }
    } while ($stable -lt 2 -and (Get-Date) -lt $deadline)
    if ($stable -lt 2) {
        throw "Pipeline did not drain: stream=$streamLength pending=$pending dead=$deadLength kafkaLag=$kafkaLag"
    }
}

function Get-OrderFact {
    param([string]$Number)
    $selects = [System.Collections.Generic.List[string]]::new()
    foreach ($database in 0..1) {
        foreach ($table in 0..3) {
            $selects.Add("SELECT order_number,user_id,intent_id,order_status FROM stellaris_order_${database}.d_order_${table}")
        }
    }
    $query = "SELECT COUNT(*),COUNT(DISTINCT intent_id),COALESCE(MIN(intent_id),'') " +
             "FROM ($($selects -join ' UNION ALL ')) all_orders " +
             "WHERE order_number=$Number AND user_id=$userId;"
    $rows = @(Invoke-StellarisMySqlQuery -Container $MySqlContainer -Password $MySqlPassword -Query $query)
    if ($rows.Count -ne 1) { throw "Cannot locate one order fact row for $Number" }
    $columns = @("$($rows[0])" -split "`t")
    if ($columns.Count -ne 3) { throw "Unexpected order fact result: $($rows[0])" }
    return [pscustomobject]@{
        copies = [int]$columns[0]
        reservationCopies = [int]$columns[1]
        reservationId = "$($columns[2])"
    }
}

function Assert-RestoredState {
    param(
        [long]$ExpectedAvailable,
        [long]$ExpectedCategoryRemain,
        [long]$ExpectedRedisAvailable,
        [long]$ExpectedOwners,
        [long]$ExpectedReservations,
        [long]$ExpectedAccountCount
    )
    $actualAvailable = Get-MySqlLong -Query "SELECT COUNT(*) FROM stellaris_program_0.d_seat_0 WHERE program_id=$programId AND sell_status=1 AND reservation_id IS NULL;"
    $actualCategoryRemain = Get-MySqlLong -Query "SELECT remain_number FROM stellaris_program_0.d_ticket_category_0 WHERE program_id=$programId AND id=$categoryId;"
    $actualRedisAvailable = Get-RedisLongOrZero -Command @('ZCARD', "${prefix}available:$categoryId")
    $actualOwners = Get-RedisLongOrZero -Command @('HLEN', "${prefix}owner")
    $actualReservations = Get-RedisLongOrZero -Command @('HLEN', "${prefix}reservation")
    $actualAccountCount = Get-RedisLongOrZero -Command @('HGET', "${prefix}account-count", "$userId")
    if ($actualAvailable -ne $ExpectedAvailable -or
            $actualCategoryRemain -ne $ExpectedCategoryRemain -or
            $actualRedisAvailable -ne $ExpectedRedisAvailable -or
            $actualOwners -ne $ExpectedOwners -or
            $actualReservations -ne $ExpectedReservations -or
            $actualAccountCount -ne $ExpectedAccountCount) {
        throw "State was not restored: mysqlSeats=$actualAvailable/$ExpectedAvailable " +
              "mysqlRemain=$actualCategoryRemain/$ExpectedCategoryRemain " +
              "redisAvailable=$actualRedisAvailable/$ExpectedRedisAvailable " +
              "owners=$actualOwners/$ExpectedOwners reservations=$actualReservations/$ExpectedReservations " +
              "accountCount=$actualAccountCount/$ExpectedAccountCount"
    }
}

Assert-StellarisContainerRunning -Name $MySqlContainer
Assert-StellarisContainerRunning -Name $RedisContainer
Assert-StellarisContainerRunning -Name $KafkaContainer

Write-Host 'Checking the isolated fixture before correctness verification...'
& (Join-Path $repositoryRoot 'tests\benchmark\verify-reset.ps1') `
    -MySqlContainer $MySqlContainer -RedisContainer $RedisContainer -KafkaContainer $KafkaContainer `
    -MySqlPassword $MySqlPassword -RedisPassword $RedisPassword `
    -KafkaTopic $KafkaTopic -KafkaGroup $KafkaGroup

$baselineAvailable = Get-MySqlLong -Query "SELECT COUNT(*) FROM stellaris_program_0.d_seat_0 WHERE program_id=$programId AND sell_status=1 AND reservation_id IS NULL;"
$baselineCategoryRemain = Get-MySqlLong -Query "SELECT remain_number FROM stellaris_program_0.d_ticket_category_0 WHERE program_id=$programId AND id=$categoryId;"
$baselineRedisAvailable = Get-RedisLongOrZero -Command @('ZCARD', "${prefix}available:$categoryId")
$baselineOwners = Get-RedisLongOrZero -Command @('HLEN', "${prefix}owner")
$baselineReservations = Get-RedisLongOrZero -Command @('HLEN', "${prefix}reservation")
$baselineAccountCount = Get-RedisLongOrZero -Command @('HGET', "${prefix}account-count", "$userId")

try {
    Write-Host 'Checking Gateway health and public/internal route boundary...'
    Invoke-RestMethod -Uri "$GatewayBaseUrl/actuator/health" | Out-Null
    $blocked = Invoke-WebRequest -Method Post `
        -Uri "$GatewayBaseUrl/stellaris/order/order/account/order/count" `
        -ContentType 'application/json' -Headers $headers -Body '{}' -SkipHttpErrorCheck
    if ($blocked.StatusCode -ne 404) {
        throw "Internal order endpoint must return HTTP 404 through Gateway; actual=$($blocked.StatusCode)"
    }

    $createBody = @{
        requestId = $requestId
        programId = $programId
        userId = $userId
        ticketUserIdList = @($ticketUserId)
        ticketCategoryId = $categoryId
        ticketCount = 1
    }

    Write-Host 'Creating one order through Gateway and checking request idempotency...'
    $created = Invoke-JsonPost -Path '/stellaris/program/program/order/create/v5' -Body $createBody
    if ("$($created.code)" -ne '0' -or [string]::IsNullOrWhiteSpace("$($created.data)")) {
        throw "Create failed: $($created | ConvertTo-Json -Depth 8 -Compress)"
    }
    $orderNumber = "$($created.data)"
    $replayed = Invoke-JsonPost -Path '/stellaris/program/program/order/create/v5' -Body $createBody
    if ("$($replayed.code)" -ne '0' -or "$($replayed.data)" -ne $orderNumber) {
        throw "Idempotent retry returned a different result: $($replayed | ConvertTo-Json -Depth 8 -Compress)"
    }

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $owned = Invoke-JsonPost -Path '/stellaris/order/order/get' -Body @{ orderNumber = $orderNumber }
        if ("$($owned.code)" -eq '0') { break }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    if ("$($owned.code)" -ne '0') {
        throw "Order did not become queryable within $TimeoutSeconds seconds: $($owned | ConvertTo-Json -Depth 8 -Compress)"
    }

    Write-Host 'Checking reservation identity and asynchronous pipeline convergence...'
    Wait-PipelineDrained
    $fact = Get-OrderFact -Number $orderNumber
    $reservationId = $fact.reservationId
    if ($fact.copies -ne 1 -or $fact.reservationCopies -ne 1 -or
            $reservationId -notmatch '^[A-Za-z0-9-]{16,128}$') {
        throw "Order/reservation uniqueness failed: copies=$($fact.copies) reservationCopies=$($fact.reservationCopies) reservationId=$reservationId"
    }
    $lockedSeats = Get-MySqlLong -Query "SELECT COUNT(*) FROM stellaris_program_0.d_seat_0 WHERE program_id=$programId AND reservation_id='$reservationId' AND sell_status=2;"
    $ownerValues = @(Invoke-StellarisRedisRaw -Container $RedisContainer -Password $RedisPassword -Command @('HVALS', "${prefix}owner"))
    $matchingOwners = @($ownerValues | Where-Object { "$_" -eq $reservationId }).Count
    $hasReservation = Get-RedisLongOrZero -Command @('HEXISTS', "${prefix}reservation", $reservationId)
    $accountCount = Get-RedisLongOrZero -Command @('HGET', "${prefix}account-count", "$userId")
    if ($lockedSeats -ne 1 -or $matchingOwners -ne 1 -or $hasReservation -ne 1 -or
            $accountCount -ne ($baselineAccountCount + 1)) {
        throw "Reservation state mismatch: mysqlLocked=$lockedSeats owners=$matchingOwners reservation=$hasReservation accountCount=$accountCount"
    }

    Write-Host 'Checking missing identity and cross-user order access...'
    $missingIdentity = Invoke-JsonPost -Path '/stellaris/order/order/get' `
        -Body @{ orderNumber = $orderNumber } -RequestHeaders @{ no_verify = 'true' }
    if ("$($missingIdentity.code)" -ne '1001') {
        throw "Missing identity must return USER_NOT_LOGIN(1001): $($missingIdentity | ConvertTo-Json -Depth 8 -Compress)"
    }
    $notOwner = Invoke-JsonPost -Path '/stellaris/order/order/get' `
        -Body @{ orderNumber = $orderNumber } `
        -RequestHeaders @{ no_verify = 'true'; 'X-Stellaris-Demo-User-Id' = "$otherUserId" }
    if ("$($notOwner.code)" -ne '40015') {
        throw "Cross-user access must be hidden as ORDER_NOT_EXIST(40015): $($notOwner | ConvertTo-Json -Depth 8 -Compress)"
    }

    Write-Host 'Cancelling through the public boundary and checking complete state restoration...'
    $cancelled = Invoke-JsonPost -Path '/stellaris/order/order/cancel' -Body @{ orderNumber = $orderNumber }
    if ("$($cancelled.code)" -ne '0' -or -not [bool]$cancelled.data) {
        throw "Cancel failed: $($cancelled | ConvertTo-Json -Depth 8 -Compress)"
    }
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $cancelledOrder = Invoke-JsonPost -Path '/stellaris/order/order/get' -Body @{ orderNumber = $orderNumber }
        if ("$($cancelledOrder.code)" -eq '0' -and "$($cancelledOrder.data.orderStatus)" -eq '2') {
            try {
                Assert-RestoredState -ExpectedAvailable $baselineAvailable `
                    -ExpectedCategoryRemain $baselineCategoryRemain `
                    -ExpectedRedisAvailable $baselineRedisAvailable `
                    -ExpectedOwners $baselineOwners -ExpectedReservations $baselineReservations `
                    -ExpectedAccountCount $baselineAccountCount
                $restored = $true
            } catch {
                $restored = $false
            }
            if ($restored) { break }
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    Assert-RestoredState -ExpectedAvailable $baselineAvailable `
        -ExpectedCategoryRemain $baselineCategoryRemain `
        -ExpectedRedisAvailable $baselineRedisAvailable `
        -ExpectedOwners $baselineOwners -ExpectedReservations $baselineReservations `
        -ExpectedAccountCount $baselineAccountCount
    Wait-PipelineDrained

    [pscustomobject]@{
        gatewayBoundary = 'PASS'
        createAndIdempotency = 'PASS'
        authenticatedOwnership = 'PASS'
        reservationConsistency = 'PASS'
        streamPelDeadAndKafka = 'PASS'
        cancelAndInventoryRestore = 'PASS'
        orderNumber = $orderNumber
        reservationId = $reservationId
        cleaned = -not [bool]$KeepOrders
    } | Format-List
} finally {
    if (-not $KeepOrders -and -not [string]::IsNullOrWhiteSpace("$orderNumber")) {
        Write-Host 'Cleaning the isolated correctness dataset...'
        & (Join-Path $repositoryRoot 'tests\benchmark\Cleanup-StellarisV5Benchmark.ps1') `
            -MySqlContainer $MySqlContainer -RedisContainer $RedisContainer `
            -MySqlPassword $MySqlPassword -RedisPassword $RedisPassword
    }
}
