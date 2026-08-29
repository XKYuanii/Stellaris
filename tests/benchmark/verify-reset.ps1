param(
    [string]$MySqlContainer = 'stellaris-interview-mysql-1',
    [string]$RedisContainer = 'stellaris-interview-redis-1',
    [string]$KafkaContainer = 'stellaris-interview-kafka-1',
    [string]$MySqlPassword = 'mysql123',
    [string]$RedisPassword = 'redis123',
    [string]$KafkaTopic = 'stellaris-create_order',
    [string]$KafkaGroup = 'create_order_data',
    [ValidateRange(1, 10000000)][int]$ExpectedSeats = 10000,
    [ValidateRange(5, 300)][int]$KafkaTimeoutSeconds = 30,
    [switch]$SkipKafka
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'StellarisV5Benchmark.Common.ps1')

$programId = $script:StellarisBenchmarkProgramId
$categoryId = $script:StellarisBenchmarkCategoryId
$saleShard = $programId % 16
$prefix = "stellaris:{sale:$saleShard}:program:$programId`:seat:"
$stream = "stellaris:{sale:$saleShard}:reservation:event:stream"
$deadStream = "stellaris:{sale:$saleShard}:reservation:event:dead-stream"
$streamGroup = 'stellaris-order-relay'
$checks = [System.Collections.Generic.List[object]]::new()

function Add-Check {
    param([string]$Name, [object]$Actual, [object]$Expected, [bool]$Passed, [string]$Reason)
    $checks.Add([pscustomobject]@{
        check = $Name
        actual = "$Actual"
        expected = "$Expected"
        result = if ($Passed) { 'PASS' } else { 'FAIL' }
        reason = if ($Passed) { '' } else { $Reason }
    })
}

function First-OrZero {
    param([object[]]$Value)
    if ($null -eq $Value -or $Value.Count -eq 0 -or [string]::IsNullOrWhiteSpace("$($Value[0])")) { return '0' }
    return "$($Value[0])"
}

function Redis-Scalar {
    param([string[]]$Command)
    return First-OrZero @(Invoke-VerificationRedisRaw -Command $Command)
}

function Invoke-VerificationRedisRaw {
    param([string[]]$Command)

    # redis-cli runs inside the container. Pass REDISCLI_AUTH into that process so
    # the password is not supplied through -a and no password warning is emitted.
    $stdoutFile = [System.IO.Path]::GetTempFileName()
    $stderrFile = [System.IO.Path]::GetTempFileName()
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $arguments = @(
            'exec',
            '-e', "REDISCLI_AUTH=$RedisPassword",
            $RedisContainer,
            'redis-cli',
            '--raw'
        ) + $Command
        & docker @arguments 1> $stdoutFile 2> $stderrFile
        $redisExitCode = $LASTEXITCODE
        $stdout = @(Get-Content -LiteralPath $stdoutFile)
        $stderr = @(Get-Content -LiteralPath $stderrFile)
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
        Remove-Item -LiteralPath $stdoutFile, $stderrFile -Force -ErrorAction SilentlyContinue
    }

    if ($redisExitCode -ne 0) {
        throw "Redis verification command failed (exit=$redisExitCode, command=$($Command[0])): $($stderr -join ' ')"
    }
    if ($stderr.Count -gt 0) {
        Write-Warning "Redis verification warning: $($stderr -join ' ')"
    }
    return $stdout
}

function Invoke-VerificationMySqlQuery {
    param([string]$Query)

    # mysql writes the "password on command line" notice to stderr even when the
    # query succeeds. Some PowerShell hosts promote native stderr to a terminating
    # error when ErrorActionPreference is Stop, so keep data and diagnostics apart.
    $stdoutFile = [System.IO.Path]::GetTempFileName()
    $stderrFile = [System.IO.Path]::GetTempFileName()
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & docker exec $MySqlContainer mysql -uroot "-p$MySqlPassword" -N -e $Query `
            1> $stdoutFile 2> $stderrFile
        $mysqlExitCode = $LASTEXITCODE
        $stdout = @(Get-Content -LiteralPath $stdoutFile)
        $stderr = @(Get-Content -LiteralPath $stderrFile)
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
        Remove-Item -LiteralPath $stdoutFile, $stderrFile -Force -ErrorAction SilentlyContinue
    }

    if ($mysqlExitCode -ne 0) {
        throw "MySQL verification query failed (exit=$mysqlExitCode): $($stderr -join ' ')"
    }

    # Windows PowerShell 5.1 wraps native stderr in a multi-line
    # NativeCommandError record, so classify the complete stderr block.
    $stderrText = $stderr -join ' '
    $isPasswordNotice = $stderrText -match 'mysql:\s+\[Warning\]\s+Using a password on the command line interface can be insecure\.?' 
    if (-not [string]::IsNullOrWhiteSpace($stderrText) -and -not $isPasswordNotice) {
        Write-Warning "MySQL verification warning: $stderrText"
    }
    return $stdout
}

function Get-KafkaLagSnapshot {
    $output = @(& docker exec $KafkaContainer /opt/kafka/bin/kafka-consumer-groups.sh `
        --bootstrap-server localhost:9092 --group $KafkaGroup --describe 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Kafka group describe failed: $($output -join ' ')"
    }
    $rows = @($output | Where-Object { $_ -match "^\s*$([regex]::Escape($KafkaGroup))\s+$([regex]::Escape($KafkaTopic))\s+" })
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
    return [pscustomobject]@{
        total = [long](($lags | Measure-Object -Sum).Sum)
        partitions = ($lags -join ',')
        raw = ($output -join [Environment]::NewLine)
    }
}

try {
    Assert-StellarisContainerRunning -Name $MySqlContainer
    Assert-StellarisContainerRunning -Name $RedisContainer
    if (-not $SkipKafka) { Assert-StellarisContainerRunning -Name $KafkaContainer }

    $mysqlQuery = @"
SELECT COUNT(*) FROM stellaris_program_0.d_seat_0
WHERE program_id=$programId AND sell_status=1 AND reservation_id IS NULL;
SELECT COALESCE(remain_number,-1) FROM stellaris_program_0.d_ticket_category_0
WHERE program_id=$programId AND id=$categoryId;
SELECT SUM(order_status=1),SUM(order_status=3) FROM (
  SELECT order_status FROM stellaris_order_0.d_order_0 WHERE program_id=$programId
  UNION ALL SELECT order_status FROM stellaris_order_0.d_order_1 WHERE program_id=$programId
  UNION ALL SELECT order_status FROM stellaris_order_0.d_order_2 WHERE program_id=$programId
  UNION ALL SELECT order_status FROM stellaris_order_0.d_order_3 WHERE program_id=$programId
  UNION ALL SELECT order_status FROM stellaris_order_1.d_order_0 WHERE program_id=$programId
  UNION ALL SELECT order_status FROM stellaris_order_1.d_order_1 WHERE program_id=$programId
  UNION ALL SELECT order_status FROM stellaris_order_1.d_order_2 WHERE program_id=$programId
  UNION ALL SELECT order_status FROM stellaris_order_1.d_order_3 WHERE program_id=$programId
) benchmark_orders;
"@
    $mysql = @(Invoke-VerificationMySqlQuery -Query $mysqlQuery)
    if ($mysql.Count -lt 3) { throw "Unexpected MySQL verification output: $($mysql -join '; ')" }
    $availableSeats = [int]$mysql[0]
    $categoryRemain = [int]$mysql[1]
    $orderCounts = @($mysql[2] -split "`t")
    $openOrders = if ($orderCounts.Count -gt 0 -and $orderCounts[0] -match '^\d+$') { [int]$orderCounts[0] } else { 0 }
    $paidOrders = if ($orderCounts.Count -gt 1 -and $orderCounts[1] -match '^\d+$') { [int]$orderCounts[1] } else { 0 }

    Add-Check 'MySQL available seats' $availableSeats $ExpectedSeats ($availableSeats -eq $ExpectedSeats) 'Seat rows are still locked/sold or fixture size is wrong.'
    Add-Check 'MySQL category remain' $categoryRemain $ExpectedSeats ($categoryRemain -eq $ExpectedSeats) 'Ticket category remain_number is not fully restored.'
    Add-Check 'Open benchmark orders' $openOrders 0 ($openOrders -eq 0) 'Previous round still has unpaid orders; run normal reset.'
    Add-Check 'Paid benchmark orders' $paidOrders 0 ($paidOrders -eq 0) 'Paid test orders exist; automatic hard reset is forbidden.'

    $redisAvailable = [int](Redis-Scalar @('ZCARD', "${prefix}available:$categoryId"))
    $owner = [int](Redis-Scalar @('HLEN', "${prefix}owner"))
    $reservation = [int](Redis-Scalar @('HLEN', "${prefix}reservation"))
    $ready = [int](Redis-Scalar @('EXISTS', "${prefix}ready"))
    $streamLength = [long](Redis-Scalar @('XLEN', $stream))
    $deadLength = [long](Redis-Scalar @('XLEN', $deadStream))
    $pendingRaw = @(Invoke-VerificationRedisRaw -Command @('XPENDING', $stream, $streamGroup))
    $pending = [long](First-OrZero $pendingRaw)

    Add-Check 'Redis available ZCARD' $redisAvailable $ExpectedSeats ($redisAvailable -eq $ExpectedSeats) 'Redis inventory snapshot is not fully restored.'
    Add-Check 'Redis owner HLEN' $owner 0 ($owner -eq 0) 'Seat owners remain.'
    Add-Check 'Redis reservation HLEN' $reservation 0 ($reservation -eq 0) 'Reservations remain.'
    Add-Check 'Redis ready key' $ready 1 ($ready -eq 1) 'Program snapshot is not ready; preheat is required.'
    Add-Check 'Redis Stream XLEN' $streamLength 0 ($streamLength -eq 0) 'Relay stream is not drained. It is shard-shared; do not delete blindly.'
    Add-Check 'Redis Stream PEL' $pending 0 ($pending -eq 0) 'Pending entries remain in stellaris-order-relay.'
    Add-Check 'Redis dead Stream XLEN' $deadLength 0 ($deadLength -eq 0) 'Dead-letter records require investigation/replay.'
    $delayCancelTasks = @(Get-StellarisDelayCancelTaskIdsForProgram -ProgramId $programId `
        -Container $RedisContainer -Password $RedisPassword).Count
    Add-Check 'Delayed-cancel tasks' $delayCancelTasks 0 ($delayCancelTasks -eq 0) 'Benchmark delayed-cancel tasks remain in the shared Redis lease queue.'

    if (-not $SkipKafka) {
        $deadline = (Get-Date).AddSeconds($KafkaTimeoutSeconds)
        $zeroSamples = 0
        $lastKafka = $null
        do {
            $lastKafka = Get-KafkaLagSnapshot
            if ($lastKafka.total -eq 0) { $zeroSamples++ } else { $zeroSamples = 0 }
            if ($zeroSamples -lt 2) { Start-Sleep -Seconds 2 }
        } while ($zeroSamples -lt 2 -and (Get-Date) -lt $deadline)
        Add-Check 'Kafka consumer LAG' $lastKafka.partitions '0 per partition' ($zeroSamples -ge 2) `
            "LAG did not remain zero before timeout; total=$($lastKafka.total)."
    } else {
        Add-Check 'Kafka consumer LAG' 'SKIPPED' '0 per partition' $true ''
    }
} catch {
    Add-Check 'Verification execution' $_.Exception.Message 'no exception' $false 'A dependency/query/parse step failed.'
}

$checks | Format-Table -AutoSize
$failures = @($checks | Where-Object result -eq 'FAIL')
if ($failures.Count -eq 0) {
    Write-Host "PASS: benchmark fixture is ready for the next round." -ForegroundColor Green
    return
}

Write-Host "FAIL: benchmark fixture is NOT ready." -ForegroundColor Red
$failures | ForEach-Object { Write-Host "- $($_.check): $($_.reason) actual=$($_.actual)" -ForegroundColor Red }
throw "Benchmark reset verification failed with $($failures.Count) failed check(s)."
