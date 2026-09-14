param(
    [string]$GatewayBaseUrl = 'http://127.0.0.1:6085',
    [string]$MySqlContainer = 'stellaris-local-mysql-1',
    [string]$RedisContainer = 'stellaris-local-redis-1',
    [string]$MySqlPassword = 'mysql123',
    [string]$RedisPassword = 'redis123',
    [ValidateRange(10, 180)][int]$TimeoutSeconds = 45
)

$ErrorActionPreference = 'Stop'
$programId = 900000L
$categoryId = 900001L
$userId = 910000000000000000L
$otherUserId = $userId + 1
$ticketUserId = 920000000000000000L
$saleShard = $programId % 16
$keyPrefix = "stellaris:{sale:$saleShard}:program:$programId" + ':seat:'
$stream = "stellaris:{sale:$saleShard}:reservation:event:stream"
$streamGroup = 'stellaris-order-service'
$headers = @{ no_verify = 'true'; 'X-Stellaris-Demo-User-Id' = "$userId" }

function Assert-ContainerRunning {
    param([string]$Name)
    $running = & docker inspect -f '{{.State.Running}}' $Name 2>$null
    if ($LASTEXITCODE -ne 0 -or $running -ne 'true') { throw "Container is not running: $Name" }
}

function Invoke-MySqlScalar {
    param([string]$Query)
    $value = & docker exec $MySqlContainer mysql -uroot "-p$MySqlPassword" -N -e $Query 2>$null
    if ($LASTEXITCODE -ne 0) { throw "MySQL query failed" }
    return "$($value | Select-Object -First 1)"
}

function Invoke-Redis {
    param([string[]]$Command)
    $arguments = @('exec', '-e', "REDISCLI_AUTH=$RedisPassword", $RedisContainer, 'redis-cli', '--raw') + $Command
    $value = & docker @arguments 2>$null
    if ($LASTEXITCODE -ne 0) { throw "Redis command failed: $($Command -join ' ')" }
    return @($value)
}

function Get-RedisLong {
    param([string[]]$Command)
    $values = @(Invoke-Redis -Command $Command)
    if ($values.Count -eq 0 -or [string]::IsNullOrWhiteSpace("$($values[0])")) { return 0L }
    return [long]$values[0]
}

function Invoke-JsonPost {
    param([string]$Path, [hashtable]$Body, [hashtable]$RequestHeaders = $headers)
    return Invoke-RestMethod -Method Post -Uri "$GatewayBaseUrl$Path" -ContentType 'application/json;charset=UTF-8' -Headers $RequestHeaders -Body ($Body | ConvertTo-Json -Depth 8 -Compress)
}

function Wait-Until {
    param([scriptblock]$Condition, [string]$Failure)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if (& $Condition) { return }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw $Failure
}

function Wait-PipelineDrained {
    Wait-Until -Failure "Stream did not drain within $TimeoutSeconds seconds" -Condition {
        $length = Get-RedisLong -Command @('XLEN', $stream)
        $pending = Get-RedisLong -Command @('XPENDING', $stream, $streamGroup)
        return $length -eq 0 -and $pending -eq 0
    }
}

Assert-ContainerRunning -Name $MySqlContainer
Assert-ContainerRunning -Name $RedisContainer

$ready = Get-RedisLong -Command @('EXISTS', ($keyPrefix + 'ready'))
if ($ready -ne 1) { throw "Benchmark program is not preheated: $programId" }

$baselineAvailable = [long](Invoke-MySqlScalar -Query "SELECT COUNT(*) FROM stellaris_trade.t_seat_inventory WHERE program_id=$programId AND ticket_category_id=$categoryId AND sell_status=1;")
$baselineRedisAvailable = Get-RedisLong -Command @('ZCARD', ($keyPrefix + "available:$categoryId"))
$baselineOwners = Get-RedisLong -Command @('HLEN', ($keyPrefix + 'owner'))
$baselineReservations = Get-RedisLong -Command @('HLEN', ($keyPrefix + 'reservation'))
$baselineAccount = [long](Invoke-MySqlScalar -Query "SELECT COALESCE(MAX(purchase_count),0) FROM stellaris_trade.t_account_program_purchase WHERE program_id=$programId AND user_id=$userId;")
if ($baselineAvailable -ne $baselineRedisAvailable) {
    throw "Fixture mismatch: tradeAvailable=$baselineAvailable redisAvailable=$baselineRedisAvailable"
}

Write-Host 'Checking Gateway health and internal route boundary...'
Invoke-RestMethod -Uri "$GatewayBaseUrl/actuator/health" | Out-Null
$blocked = Invoke-WebRequest -Method Post -Uri "$GatewayBaseUrl/stellaris/order/order/interior/reference/inventory/current" -ContentType 'application/json' -Headers $headers -Body '{}' -SkipHttpErrorCheck
if ($blocked.StatusCode -ne 404) { throw "Interior endpoint must return 404; actual=$($blocked.StatusCode)" }

$requestId = [guid]::NewGuid().ToString()
$body = @{
    requestId = $requestId
    programId = $programId
    userId = $userId
    ticketUserIdList = @($ticketUserId)
    ticketCategoryId = $categoryId
    ticketCount = 1
}

Write-Host 'Creating and replaying one request...'
$created = Invoke-JsonPost -Path '/stellaris/program/program/order/create/v5' -Body $body
if ("$($created.code)" -ne '0' -or "$($created.data)" -notmatch '^\d+$') {
    throw "Create was not accepted: $($created | ConvertTo-Json -Compress)"
}
$orderNumber = "$($created.data)"
$replayed = Invoke-JsonPost -Path '/stellaris/program/program/order/create/v5' -Body $body
if ("$($replayed.code)" -ne '0' -or "$($replayed.data)" -ne $orderNumber) {
    throw 'Idempotent retry returned a different order number'
}

$owned = $null
Wait-Until -Failure "Order did not materialize within $TimeoutSeconds seconds" -Condition {
    $script:owned = Invoke-JsonPost -Path '/stellaris/order/order/get' -Body @{ orderNumber = $orderNumber }
    return "$($script:owned.code)" -eq '0'
}
Wait-PipelineDrained

$requestRow = Invoke-MySqlScalar -Query "SELECT CONCAT(result_status,':',reservation_id) FROM stellaris_trade.t_order_request WHERE order_number=$orderNumber AND user_id=$userId;"
if (-not $requestRow.StartsWith('CREATED:')) { throw "Unexpected request result: $requestRow" }
$reservationId = $requestRow.Substring('CREATED:'.Length)
$orderCopies = [long](Invoke-MySqlScalar -Query "SELECT COUNT(*) FROM stellaris_trade.d_order WHERE order_number=$orderNumber AND user_id=$userId;")
$lockedSeats = [long](Invoke-MySqlScalar -Query "SELECT COUNT(*) FROM stellaris_trade.t_seat_inventory WHERE program_id=$programId AND order_number=$orderNumber AND reservation_id='$reservationId' AND sell_status=2;")
$accountAfterCreate = [long](Invoke-MySqlScalar -Query "SELECT purchase_count FROM stellaris_trade.t_account_program_purchase WHERE program_id=$programId AND user_id=$userId;")
if ($orderCopies -ne 1 -or $lockedSeats -ne 1 -or $accountAfterCreate -ne ($baselineAccount + 1)) {
    throw "Trade transaction mismatch: orders=$orderCopies locked=$lockedSeats account=$accountAfterCreate"
}

Write-Host 'Checking identity isolation...'
$missingIdentity = Invoke-JsonPost -Path '/stellaris/order/order/get' -Body @{ orderNumber = $orderNumber } -RequestHeaders @{ no_verify = 'true' }
if ("$($missingIdentity.code)" -ne '1001') { throw 'Missing identity was not rejected' }
$notOwner = Invoke-JsonPost -Path '/stellaris/order/order/get' -Body @{ orderNumber = $orderNumber } -RequestHeaders @{ no_verify = 'true'; 'X-Stellaris-Demo-User-Id' = "$otherUserId" }
if ("$($notOwner.code)" -ne '40015') { throw 'Cross-user order access was not hidden' }

Write-Host 'Cancelling and checking trade/Redis convergence...'
$cancelled = Invoke-JsonPost -Path '/stellaris/order/order/cancel' -Body @{ orderNumber = $orderNumber }
if ("$($cancelled.code)" -ne '0' -or -not [bool]$cancelled.data) { throw 'Cancel failed' }

Wait-Until -Failure "Cancel state did not converge within $TimeoutSeconds seconds" -Condition {
    $orderStatus = Invoke-MySqlScalar -Query "SELECT order_status FROM stellaris_trade.d_order WHERE order_number=$orderNumber;"
    $tradeAvailable = [long](Invoke-MySqlScalar -Query "SELECT COUNT(*) FROM stellaris_trade.t_seat_inventory WHERE program_id=$programId AND ticket_category_id=$categoryId AND sell_status=1;")
    $redisAvailable = Get-RedisLong -Command @('ZCARD', ($keyPrefix + "available:$categoryId"))
    $owners = Get-RedisLong -Command @('HLEN', ($keyPrefix + 'owner'))
    $reservations = Get-RedisLong -Command @('HLEN', ($keyPrefix + 'reservation'))
    $account = [long](Invoke-MySqlScalar -Query "SELECT purchase_count FROM stellaris_trade.t_account_program_purchase WHERE program_id=$programId AND user_id=$userId;")
    return $orderStatus -eq '2' -and $tradeAvailable -eq $baselineAvailable -and $redisAvailable -eq $baselineRedisAvailable -and $owners -eq $baselineOwners -and $reservations -eq $baselineReservations -and $account -eq $baselineAccount
}

[pscustomobject]@{
    gatewayBoundary = 'PASS'
    requestIdempotency = 'PASS'
    tradeTransaction = 'PASS'
    streamAndPel = 'PASS'
    ownership = 'PASS'
    cancelAndRedisSync = 'PASS'
    orderNumber = $orderNumber
    reservationId = $reservationId
} | Format-List
