param(
    [ValidateSet('Ready', 'AfterCancel')][string]$Phase = 'Ready',
    [string]$MySqlContainer = 'stellaris-interview-mysql-1',
    [string]$RedisContainer = 'stellaris-interview-redis-1',
    [string]$KafkaContainer = 'stellaris-interview-kafka-1',
    [string]$MySqlPassword = 'mysql123',
    [string]$RedisPassword = 'redis123',
    [string]$KafkaTopic = 'stellaris-create_order',
    [string]$KafkaGroup = 'create_order_data',
    [ValidateRange(5, 300)][int]$KafkaTimeoutSeconds = 45,
    [switch]$SkipKafka
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'V4V5Comparison.Common.ps1')

$programId = $script:ComparisonProgramId
$categoryId = $script:ComparisonCategoryId
$expectedSeats = $script:ComparisonExpectedSeats
$saleShard = $script:ComparisonSaleShard
$v5Prefix = "stellaris:{sale:$saleShard}:program:$programId`:seat:"
$stream = "stellaris:{sale:$saleShard}:reservation:event:stream"
$deadStream = "stellaris:{sale:$saleShard}:reservation:event:dead-stream"
$streamGroup = 'stellaris-order-relay'
$legacyAvailable = "stellaris-program_seat_no_sold_resolution_hash_${programId}_${categoryId}"
$legacyLock = "stellaris-program_seat_lock_resolution_hash_${programId}_${categoryId}"
$legacySold = "stellaris-program_seat_sold_resolution_hash_${programId}_${categoryId}"
$legacyRemain = "stellaris-program_ticket_remain_number_hash_resolution_${programId}_${categoryId}"
$checks = New-Object System.Collections.Generic.List[object]

function Add-Check {
    param([string]$Name, [object]$Actual, [object]$Expected, [bool]$Passed, [string]$Reason)
    $checks.Add([pscustomobject]@{
        check = $Name; actual = "$Actual"; expected = "$Expected"
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
    return First-OrZero @(Invoke-StellarisRedisRaw -Container $RedisContainer -Password $RedisPassword -Command $Command)
}

function Get-KafkaLagSnapshot {
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& docker exec $KafkaContainer /opt/kafka/bin/kafka-consumer-groups.sh `
            --bootstrap-server localhost:9092 --group $KafkaGroup --describe 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($exitCode -ne 0) { throw "Kafka group describe failed: $($output -join ' ')" }
    $rows = @($output | Where-Object { $_ -match "^\s*$([regex]::Escape($KafkaGroup))\s+$([regex]::Escape($KafkaTopic))\s+" })
    if ($rows.Count -eq 0) { throw "Kafka group/topic row not found: group=$KafkaGroup topic=$KafkaTopic" }
    $lags = foreach ($row in $rows) {
        $parts = @(-split $row)
        if ($parts.Count -lt 6 -or $parts[5] -notmatch '^\d+$') { throw "Cannot parse Kafka LAG row: $row" }
        [long]$parts[5]
    }
    return [pscustomobject]@{ total = [long](($lags | Measure-Object -Sum).Sum); partitions = ($lags -join ',') }
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
SELECT COALESCE(SUM(order_status=1),0),COALESCE(SUM(order_status=3),0) FROM (
  SELECT order_status FROM stellaris_order_0.d_order_0 WHERE program_id=$programId
  UNION ALL SELECT order_status FROM stellaris_order_0.d_order_1 WHERE program_id=$programId
  UNION ALL SELECT order_status FROM stellaris_order_0.d_order_2 WHERE program_id=$programId
  UNION ALL SELECT order_status FROM stellaris_order_0.d_order_3 WHERE program_id=$programId
  UNION ALL SELECT order_status FROM stellaris_order_1.d_order_0 WHERE program_id=$programId
  UNION ALL SELECT order_status FROM stellaris_order_1.d_order_1 WHERE program_id=$programId
  UNION ALL SELECT order_status FROM stellaris_order_1.d_order_2 WHERE program_id=$programId
  UNION ALL SELECT order_status FROM stellaris_order_1.d_order_3 WHERE program_id=$programId
) comparison_orders;
SELECT
 (SELECT COUNT(*) FROM stellaris_program_0.d_order_inventory_operation_0 WHERE program_id=$programId)+
 (SELECT COUNT(*) FROM stellaris_program_0.d_order_inventory_operation_1 WHERE program_id=$programId)+
 (SELECT COUNT(*) FROM stellaris_program_1.d_order_inventory_operation_0 WHERE program_id=$programId)+
 (SELECT COUNT(*) FROM stellaris_program_1.d_order_inventory_operation_1 WHERE program_id=$programId);
"@
    $mysql = @(Invoke-StellarisMySqlQuery -Container $MySqlContainer -Password $MySqlPassword -Query $mysqlQuery)
    if ($mysql.Count -lt 4) { throw "Unexpected MySQL output: $($mysql -join '; ')" }
    $availableSeats = [int]$mysql[0]
    $categoryRemain = [int]$mysql[1]
    $orderCounts = @($mysql[2] -split "`t")
    $openOrders = [int]$orderCounts[0]
    $paidOrders = [int]$orderCounts[1]
    $operations = [int]$mysql[3]

    Add-Check 'MySQL available seats' $availableSeats $expectedSeats ($availableSeats -eq $expectedSeats) 'Comparison seats are not fully released.'
    Add-Check 'MySQL category remain' $categoryRemain $expectedSeats ($categoryRemain -eq $expectedSeats) 'Ticket category inventory is not fully restored.'
    Add-Check 'Open comparison orders' $openOrders 0 ($openOrders -eq 0) 'Unpaid comparison orders remain.'
    Add-Check 'Paid comparison orders' $paidOrders 0 ($paidOrders -eq 0) 'Paid comparison orders exist; hard reset is forbidden.'
    if ($Phase -eq 'Ready') {
        Add-Check 'Inventory operation rows' $operations 0 ($operations -eq 0) 'Preparation metadata was not normalized.'
    }

    $legacyAvailableCount = [int](Redis-Scalar @('HLEN', $legacyAvailable))
    $legacyLockCount = [int](Redis-Scalar @('HLEN', $legacyLock))
    $legacySoldCount = [int](Redis-Scalar @('HLEN', $legacySold))
    $legacyRemainCount = [int](Redis-Scalar @('HGET', $legacyRemain, "$categoryId"))
    $v5AvailableCount = [int](Redis-Scalar @('ZCARD', "${v5Prefix}available:$categoryId"))
    $v5OwnerCount = [int](Redis-Scalar @('HLEN', "${v5Prefix}owner"))
    $v5ReservationCount = [int](Redis-Scalar @('HLEN', "${v5Prefix}reservation"))
    $v5Ready = [int](Redis-Scalar @('EXISTS', "${v5Prefix}ready"))
    $streamLength = [long](Redis-Scalar @('XLEN', $stream))
    $deadLength = [long](Redis-Scalar @('XLEN', $deadStream))
    $streamExists = [int](Redis-Scalar @('EXISTS', $stream))
    $pending = 0L
    if ($streamExists -eq 1) {
        $pending = [long](First-OrZero @(Invoke-StellarisRedisRaw -Container $RedisContainer -Password $RedisPassword `
            -Command @('XPENDING', $stream, $streamGroup)))
    }
    $delayCancelTasks = @(Get-StellarisDelayCancelTaskIdsForProgram -ProgramId $programId `
        -Container $RedisContainer -Password $RedisPassword).Count

    Add-Check 'V4 available HLEN' $legacyAvailableCount $expectedSeats ($legacyAvailableCount -eq $expectedSeats) 'Legacy V4 seat cache is not restored.'
    Add-Check 'V4 lock HLEN' $legacyLockCount 0 ($legacyLockCount -eq 0) 'Legacy V4 locked seats remain.'
    Add-Check 'V4 sold HLEN' $legacySoldCount 0 ($legacySoldCount -eq 0) 'Legacy V4 sold seats remain.'
    Add-Check 'V4 remain HGET' $legacyRemainCount $expectedSeats ($legacyRemainCount -eq $expectedSeats) 'Legacy V4 category remain is not restored.'
    Add-Check 'V5 available ZCARD' $v5AvailableCount $expectedSeats ($v5AvailableCount -eq $expectedSeats) 'V5 seat snapshot is not restored.'
    Add-Check 'V5 owner HLEN' $v5OwnerCount 0 ($v5OwnerCount -eq 0) 'V5 owners remain.'
    Add-Check 'V5 reservation HLEN' $v5ReservationCount 0 ($v5ReservationCount -eq 0) 'V5 reservations remain.'
    Add-Check 'V5 ready key' $v5Ready 1 ($v5Ready -eq 1) 'V5 snapshot was not preheated.'
    Add-Check 'Relay Stream XLEN' $streamLength 0 ($streamLength -eq 0) 'Relay stream has not drained.'
    Add-Check 'Relay Stream PEL' $pending 0 ($pending -eq 0) 'Relay pending entries remain.'
    Add-Check 'Dead Stream XLEN' $deadLength 0 ($deadLength -eq 0) 'Dead-letter stream contains failures.'
    Add-Check 'Delayed-cancel tasks' $delayCancelTasks 0 ($delayCancelTasks -eq 0) 'Benchmark delayed-cancel tasks were not cleaned from Redis.'

    if (-not $SkipKafka) {
        $deadline = (Get-Date).AddSeconds($KafkaTimeoutSeconds)
        $zeroSamples = 0
        $lastKafka = $null
        do {
            $lastKafka = Get-KafkaLagSnapshot
            if ($lastKafka.total -eq 0) { $zeroSamples++ } else { $zeroSamples = 0 }
            if ($zeroSamples -lt 2) { Start-Sleep -Seconds 2 }
        } while ($zeroSamples -lt 2 -and (Get-Date) -lt $deadline)
        Add-Check 'Kafka consumer LAG' $lastKafka.partitions '0 per partition' ($zeroSamples -ge 2) "LAG did not stay at zero; total=$($lastKafka.total)."
    }
} catch {
    Add-Check 'Verification execution' $_.Exception.Message 'no exception' $false 'A dependency/query/parse step failed.'
}

$checks | Format-Table -AutoSize
$failures = @($checks | Where-Object { $_.result -eq 'FAIL' })
if ($failures.Count -eq 0) {
    Write-Host "PASS: V4/V5 comparison fixture phase=$Phase." -ForegroundColor Green
    return
}
$failures | ForEach-Object { Write-Host "FAIL: $($_.check) - $($_.reason) actual=$($_.actual)" -ForegroundColor Red }
throw "V4/V5 comparison verification failed with $($failures.Count) failed check(s)."
