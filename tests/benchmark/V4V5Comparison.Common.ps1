Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'StellarisV5Benchmark.Common.ps1')

$script:ComparisonProgramId = 900100L
$script:ComparisonCategoryId = 900101L
$script:ComparisonExpectedSeats = 10000
$script:ComparisonUserCount = 5000
$script:ComparisonSaleShard = $script:ComparisonProgramId % 16
$script:ComparisonTicketUserCachePrefix = 'stellaris-ticket_user_list_'

function Initialize-ComparisonTicketUserHotCache {
    param(
        [Parameter(Mandatory = $true)][string]$CsvPath,
        [string]$Container = 'stellaris-interview-redis-1',
        [string]$Password = 'redis123'
    )
    $users = @(Import-Csv -LiteralPath $CsvPath)
    if ($users.Count -ne $script:ComparisonUserCount) {
        throw "Hot-cache preparation requires exactly $script:ComparisonUserCount benchmark users; found $($users.Count)."
    }

    $seenUsers = [System.Collections.Generic.HashSet[string]]::new()
    $seenTicketUsers = [System.Collections.Generic.HashSet[string]]::new()
    foreach ($user in $users) {
        $userId = "$($user.userId)"
        $ticketUserId = "$($user.ticketUserId)"
        $userOffset = [long]$userId - 910000000000000000L
        $ticketUserOffset = [long]$ticketUserId - 920000000000000000L
        if ($userId -notmatch '^\d{18}$' -or $ticketUserId -notmatch '^\d{18}$' -or
                $userOffset -lt 0 -or $userOffset -ge $script:ComparisonUserCount -or
                $ticketUserOffset -ne $userOffset) {
            throw "Unexpected benchmark identity in users.csv: userId=$userId ticketUserId=$ticketUserId"
        }
        if (-not $seenUsers.Add($userId) -or -not $seenTicketUsers.Add($ticketUserId)) {
            throw "Duplicate benchmark identity in users.csv: userId=$userId ticketUserId=$ticketUserId"
        }
    }

    # RedisCacheImpl stores List<T> as a Fastjson JSON string. Only id/userId are
    # required by ProgramUserExistCheckHandler, so do not copy PII into Redis.
    for ($offset = 0; $offset -lt $users.Count; $offset += 50) {
        $last = [Math]::Min($users.Count - 1, $offset + 49)
        $command = [System.Collections.Generic.List[string]]::new()
        $command.Add('MSET')
        foreach ($user in @($users[$offset..$last])) {
            $userId = "$($user.userId)"
            $ticketUserId = "$($user.ticketUserId)"
            $command.Add("$script:ComparisonTicketUserCachePrefix$userId")
            $command.Add("[{`"id`":$ticketUserId,`"userId`":$userId}]")
        }
        Invoke-StellarisRedisRaw -Container $Container -Password $Password -Command @($command) | Out-Null
    }
    Assert-ComparisonTicketUserHotCache -CsvPath $CsvPath -Container $Container -Password $Password
}

function Assert-ComparisonTicketUserHotCache {
    param(
        [Parameter(Mandatory = $true)][string]$CsvPath,
        [string]$Container = 'stellaris-interview-redis-1',
        [string]$Password = 'redis123'
    )
    $users = @(Import-Csv -LiteralPath $CsvPath)
    if ($users.Count -ne $script:ComparisonUserCount) { throw "Expected $script:ComparisonUserCount benchmark users; found $($users.Count)." }

    $verified = 0
    for ($offset = 0; $offset -lt $users.Count; $offset += 50) {
        $last = [Math]::Min($users.Count - 1, $offset + 49)
        $batch = @($users[$offset..$last])
        $keys = @($batch | ForEach-Object { "$script:ComparisonTicketUserCachePrefix$($_.userId)" })
        $values = @(Invoke-StellarisRedisRaw -Container $Container -Password $Password -Command (@('MGET') + $keys))
        if ($values.Count -ne $batch.Count) {
            throw "Ticket-user cache MGET returned $($values.Count) values for $($batch.Count) keys."
        }
        for ($index = 0; $index -lt $batch.Count; $index++) {
            $expected = "[{`"id`":$($batch[$index].ticketUserId),`"userId`":$($batch[$index].userId)}]"
            if ("$($values[$index])" -ne $expected) {
                throw "Ticket-user hot cache is missing or invalid for userId=$($batch[$index].userId)."
            }
            $verified++
        }
    }
    if ($verified -ne $script:ComparisonUserCount) { throw "Only $verified/$script:ComparisonUserCount ticket-user cache entries were verified." }
    Write-Host "PASS: $verified/$script:ComparisonUserCount benchmark ticket-user caches are ready." -ForegroundColor Green
}

function Get-ComparisonTicketUserRpcCount {
    param([string]$UserServiceBaseUrl = 'http://127.0.0.1:6082')
    $uri = "$UserServiceBaseUrl/actuator/metrics/http.server.requests?tag=uri:%2Fticket%2Fuser%2Flist"
    try {
        $metric = Invoke-RestMethod -Method Get -Uri $uri -TimeoutSec 5
    } catch {
        # /actuator/metrics is optional in the local profile. Cache contents are
        # still verified directly; an unavailable optional metric must not stop load.
        Write-Warning "Ticket-user RPC metric is unavailable; RPC delta guard will be recorded as UNAVAILABLE: $($_.Exception.Message)"
        return $null
    }
    $count = @($metric.measurements | Where-Object { $_.statistic -eq 'COUNT' } | Select-Object -First 1)
    if ($count.Count -ne 1) {
        Write-Warning 'Ticket-user RPC COUNT measurement is unavailable; RPC delta guard will be recorded as UNAVAILABLE.'
        return $null
    }
    return [double]$count[0].value
}

function Add-ComparisonProgramToBloomFilter {
    param(
        [string]$RedisHost = '127.0.0.1',
        [int]$RedisPort = 6380,
        [string]$RedisPassword = 'redis123',
        [string]$JdkHome = 'C:\Users\X\.jdks\ms-17.0.17'
    )
    $source = Join-Path $PSScriptRoot 'tools\BloomFilterSeeder.java'
    $toolPom = Join-Path $PSScriptRoot 'tools\bloom-seeder-pom.xml'
    $toolOutput = Join-Path $PSScriptRoot 'results\.comparison-tools'
    $classPathFile = Join-Path $toolOutput 'program-service-classpath.txt'
    $classFile = Join-Path $toolOutput 'BloomFilterSeeder.class'
    $javac = Join-Path $JdkHome 'bin\javac.exe'
    $java = Join-Path $JdkHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $javac) -or -not (Test-Path -LiteralPath $java)) {
        throw "JDK 17 compiler/runtime not found: $JdkHome"
    }
    New-Item -ItemType Directory -Path $toolOutput -Force | Out-Null

    if (-not (Test-Path -LiteralPath $classPathFile)) {
        $previous = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            & mvn -q -f $toolPom dependency:build-classpath `
                "-Dmdep.outputFile=$classPathFile" '-Dmdep.outputAbsoluteArtifactFilename=true'
            $mavenExitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previous
        }
        if ($mavenExitCode -ne 0 -or -not (Test-Path -LiteralPath $classPathFile)) {
            throw 'Unable to build the Program Service dependency classpath for the benchmark Bloom filter seeder.'
        }
    }

    $dependencyClassPath = (Get-Content -Raw -LiteralPath $classPathFile).Trim()
    $mustCompile = -not (Test-Path -LiteralPath $classFile)
    if (-not $mustCompile) {
        $mustCompile = (Get-Item -LiteralPath $source).LastWriteTimeUtc -gt (Get-Item -LiteralPath $classFile).LastWriteTimeUtc
    }
    if ($mustCompile) {
        $previous = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            & $javac -encoding UTF-8 -cp $dependencyClassPath -d $toolOutput $source
            $compileExitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previous
        }
        if ($compileExitCode -ne 0) { throw 'Unable to compile the benchmark Bloom filter seeder.' }
    }

    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $seederOutput = @(& $java -cp "$toolOutput;$dependencyClassPath" BloomFilterSeeder `
            $RedisHost "$RedisPort" $RedisPassword 'stellaris-program-detail-bloom-filter' "$script:ComparisonProgramId" 2>&1)
        $seederExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($seederExitCode -ne 0) { throw "Bloom filter seeding failed: $($seederOutput -join ' ')" }
    if (($seederOutput -join ' ') -notmatch 'contains=true') { throw "Bloom filter did not contain comparison program: $($seederOutput -join ' ')" }
}

function Get-ComparisonOrderNumbers {
    param(
        [string]$Container = 'stellaris-interview-mysql-1',
        [string]$Password = 'mysql123',
        [int]$OrderStatus = 1
    )
    $selects = New-Object System.Collections.Generic.List[string]
    foreach ($database in 0..1) {
        foreach ($table in 0..3) {
            $selects.Add("SELECT order_number,order_status FROM stellaris_order_${database}.d_order_${table} WHERE program_id=$script:ComparisonProgramId")
        }
    }
    $query = "SELECT order_number FROM ($($selects -join ' UNION ALL ')) x WHERE order_status=$OrderStatus;"
    return @(Invoke-StellarisMySqlQuery -Container $Container -Password $Password -Query $query |
        Where-Object { $_ -match '^\d+$' })
}

function Get-ComparisonOrderCount {
    param(
        [string]$Container = 'stellaris-interview-mysql-1',
        [string]$Password = 'mysql123',
        [int]$OrderStatus = 1
    )
    return @(Get-ComparisonOrderNumbers -Container $Container -Password $Password -OrderStatus $OrderStatus).Count
}

function Clear-ComparisonRedis {
    param(
        [string]$Container = 'stellaris-interview-redis-1',
        [string]$Password = 'redis123'
    )
    $programId = $script:ComparisonProgramId
    $categoryId = $script:ComparisonCategoryId
    $pattern = "stellaris:{sale:$script:ComparisonSaleShard}:program:$programId`:seat:*"
    $scanArguments = @('exec', '-e', "REDISCLI_AUTH=$Password", $Container,
        'redis-cli', '--raw', '--scan', '--pattern', $pattern)
    $keys = @(& docker @scanArguments 2>$null)
    $keys += @(
        "stellaris-program_$programId",
        "stellaris-program_group_$programId",
        "stellaris-program_show_time_$programId",
        "stellaris-program_ticket_category_list_$programId",
        "stellaris-program_record_$programId",
        "stellaris-program_record_finish_$programId",
        "stellaris-discard_order_$programId",
        "stellaris-program_seat_no_sold_resolution_hash_${programId}_${categoryId}",
        "stellaris-program_seat_lock_resolution_hash_${programId}_${categoryId}",
        "stellaris-program_seat_sold_resolution_hash_${programId}_${categoryId}",
        "stellaris-program_ticket_remain_number_hash_resolution_${programId}_${categoryId}"
    )
    for ($index = 0; $index -lt $script:ComparisonUserCount; $index++) {
        $userId = 910000000000000000L + $index
        $keys += "stellaris-account_order_count_${userId}_${programId}"
    }
    Remove-StellarisRedisKeys -Container $Container -Password $Password -Keys $keys
}

function Invoke-ComparisonPreheat {
    param([string]$ProgramServiceBaseUrl = 'http://127.0.0.1:6086')
    $body = @{ programId = $script:ComparisonProgramId } | ConvertTo-Json
    $response = Invoke-RestMethod -Method Post -Uri "$ProgramServiceBaseUrl/program/data/preheat" `
        -ContentType 'application/json;charset=UTF-8' -Headers @{ no_verify = 'true' } -Body $body
    if ("$($response.code)" -ne '0') {
        throw "Comparison preheat failed: $($response | ConvertTo-Json -Depth 10 -Compress)"
    }
}

function Stop-ComparisonOpenOrders {
    param(
        [string]$MySqlContainer = 'stellaris-interview-mysql-1',
        [string]$MySqlPassword = 'mysql123',
        [string]$OrderServiceBaseUrl = 'http://127.0.0.1:8081',
        [int]$TimeoutSeconds = 180
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $cancelled = New-Object System.Collections.Generic.HashSet[string]
    do {
        $openOrders = @(Get-ComparisonOrderNumbers -Container $MySqlContainer -Password $MySqlPassword -OrderStatus 1)
        foreach ($orderNumber in $openOrders) {
            if (-not $cancelled.Add($orderNumber)) { continue }
            $body = @{ orderNumber = $orderNumber } | ConvertTo-Json
            $response = Invoke-RestMethod -Method Post -Uri "$OrderServiceBaseUrl/order/cancel" `
                -ContentType 'application/json;charset=UTF-8' -Headers @{ no_verify = 'true' } -Body $body
            if ("$($response.code)" -ne '0') {
                throw "Comparison cancel failed for $orderNumber`: $($response | ConvertTo-Json -Depth 10 -Compress)"
            }
        }
        $remaining = Get-ComparisonOrderCount -Container $MySqlContainer -Password $MySqlPassword -OrderStatus 1
        if ($remaining -eq 0) { return $cancelled.Count }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw "Comparison cancellation did not converge before ${TimeoutSeconds}s."
}

function Wait-ComparisonOrdersVisible {
    param(
        [string[]]$OrderNumbers,
        [string]$Container = 'stellaris-interview-mysql-1',
        [string]$Password = 'mysql123',
        [int]$TimeoutSeconds = 60
    )
    if ($OrderNumbers.Count -eq 0) { return 0L }
    foreach ($number in $OrderNumbers) {
        if ($number -notmatch '^\d+$') { throw "Invalid comparison order number: $number" }
    }
    $start = Get-Date
    $deadline = $start.AddSeconds($TimeoutSeconds)
    do {
        $count = 0
        for ($offset = 0; $offset -lt $OrderNumbers.Count; $offset += 100) {
            $last = [Math]::Min($offset + 99, $OrderNumbers.Count - 1)
            $batch = @($OrderNumbers[$offset..$last])
            $inList = $batch -join ','
            $selects = New-Object System.Collections.Generic.List[string]
            foreach ($database in 0..1) {
                foreach ($table in 0..3) {
                    $selects.Add("SELECT order_number FROM stellaris_order_${database}.d_order_${table} WHERE program_id=$script:ComparisonProgramId AND order_number IN ($inList)")
                }
            }
            $query = "SELECT COUNT(DISTINCT order_number) FROM ($($selects -join ' UNION ALL ')) x;"
            $raw = @(Invoke-StellarisMySqlQueryStdin -Container $Container -Password $Password -Query $query)
            if ($raw.Count -gt 0) { $count += [int]$raw[0] }
        }
        if ($count -eq $OrderNumbers.Count) {
            return [long]((Get-Date) - $start).TotalMilliseconds
        }
        Start-Sleep -Milliseconds 200
    } while ((Get-Date) -lt $deadline)
    throw "Only $count/$($OrderNumbers.Count) comparison orders became visible in MySQL."
}

function Remove-ComparisonOrderHistory {
    param(
        [string]$Container = 'stellaris-interview-mysql-1',
        [string]$Password = 'mysql123'
    )
    $query = "SELECT table_schema,table_name FROM information_schema.columns " +
             "WHERE table_schema IN ('stellaris_order_0','stellaris_order_1') AND column_name='program_id' " +
             "AND (table_name LIKE 'd_order%' OR table_name LIKE 'd_reservation_transition_event%') " +
             "ORDER BY table_schema,table_name;"
    $tables = @(Invoke-StellarisMySqlQuery -Container $Container -Password $Password -Query $query)
    foreach ($row in $tables) {
        $parts = $row -split "`t"
        if ($parts.Count -ne 2 -or $parts[0] -notmatch '^stellaris_order_[01]$' -or
                $parts[1] -notmatch '^(d_order|d_reservation_transition_event)') {
            throw "Unexpected order table returned while purging comparison data: $row"
        }
        Invoke-StellarisMySqlQuery -Container $Container -Password $Password `
            -Query "DELETE FROM $($parts[0]).$($parts[1]) WHERE program_id=$script:ComparisonProgramId;" | Out-Null
    }
}
