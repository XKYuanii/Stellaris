param(
    [string]$JMeterBin = "$env:JMETER_HOME\bin",
    [string]$JdkHome = $env:JAVA_HOME,
    [int[]]$Stages = @(50, 100, 200, 400),
    [ValidateRange(1, 10)][int]$Repeats = 3,
    [ValidateSet('direct', 'gateway')][string[]]$Entries = @('direct', 'gateway'),
    [ValidateRange(30, 600)][int]$DrainTimeoutSeconds = 180,
    [switch]$Preconnect,
    [string]$OutputRoot = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'StellarisV5Benchmark.Common.ps1')

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$jmeter = Join-Path $JMeterBin 'jmeter.bat'
$jmx = Join-Path $PSScriptRoot 'v5-http-capacity.jmx'
$dataFile = Join-Path $repoRoot 'tests\jmeter\data\v5-benchmark-users.csv'
$prepare = Join-Path $PSScriptRoot 'Prepare-StellarisV5Benchmark.ps1'
$cleanup = Join-Path $PSScriptRoot 'Cleanup-StellarisV5Benchmark.ps1'
$mysqlContainer = 'stellaris-local-mysql-1'
$redisContainer = 'stellaris-local-redis-1'
$mysqlPassword = 'mysql123'
$redisPassword = 'redis123'
$programId = $script:StellarisBenchmarkProgramId
$categoryId = $script:StellarisBenchmarkCategoryId
$saleShard = $programId % 16
$stream = "stellaris:{sale:$saleShard}:reservation:event:stream"
$group = 'stellaris-order-service'
$seatKeyPrefix = "stellaris:{sale:$saleShard}:program:$programId" + ':seat:'

if (-not (Test-Path -LiteralPath $jmeter)) { throw "JMeter not found: $jmeter" }
if (-not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin\java.exe'))) { throw "JDK not found: $JdkHome" }
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $repoRoot ('output\capacity\v5-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null

function Get-Percentile {
    param([long[]]$Values, [double]$Percentile)
    if ($Values.Count -eq 0) { return 0 }
    $ordered = @($Values | Sort-Object)
    $index = [Math]::Max(0, [Math]::Min($ordered.Count - 1, [Math]::Ceiling($ordered.Count * $Percentile) - 1))
    return [long]$ordered[$index]
}

function Get-RedisLong {
    param([string[]]$Command)
    $value = Invoke-StellarisRedisRaw -Container $redisContainer -Password $redisPassword -Command $Command | Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace("$value")) { return 0L }
    return [long]$value
}

function Get-MySqlStatusValue {
    param([string]$Name)
    $row = Invoke-StellarisMySqlQuery -Container $mysqlContainer -Password $mysqlPassword -Query "SHOW GLOBAL STATUS LIKE '$Name';" | Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace("$row")) { return 0L }
    $columns = "$row" -split [char]9
    return [long]$columns[-1]
}

function Get-TradeSnapshot {
    $query = @"
SELECT CONCAT_WS(',',
 (SELECT COUNT(*) FROM stellaris_trade.t_order_request WHERE program_id=$programId),
 (SELECT COUNT(*) FROM stellaris_trade.t_order_request WHERE program_id=$programId AND result_status='CREATED'),
 (SELECT COUNT(*) FROM stellaris_trade.t_order_request WHERE program_id=$programId AND result_status='REJECTED'),
 (SELECT COUNT(*) FROM stellaris_trade.d_order WHERE program_id=$programId),
 (SELECT COUNT(DISTINCT order_number) FROM stellaris_trade.d_order WHERE program_id=$programId),
 (SELECT COUNT(*) FROM stellaris_trade.t_seat_inventory WHERE program_id=$programId AND sell_status=2),
 (SELECT COUNT(DISTINCT seat_id) FROM stellaris_trade.t_seat_inventory WHERE program_id=$programId AND sell_status=2),
 (SELECT COALESCE(SUM(purchase_count),0) FROM stellaris_trade.t_account_program_purchase WHERE program_id=$programId),
 (SELECT COUNT(*) FROM stellaris_trade.d_order_stream_failure WHERE program_id=$programId));
"@
    $values = ((Invoke-StellarisMySqlQuery -Container $mysqlContainer -Password $mysqlPassword -Query $query | Select-Object -First 1) -split ',')
    return [pscustomobject]@{
        requests = [long]$values[0]
        createdRequests = [long]$values[1]
        rejectedRequests = [long]$values[2]
        orders = [long]$values[3]
        distinctOrders = [long]$values[4]
        lockedSeats = [long]$values[5]
        distinctLockedSeats = [long]$values[6]
        purchaseCount = [long]$values[7]
        auditedFailures = [long]$values[8]
    }
}

function Wait-StreamDrained {
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    do {
        $length = Get-RedisLong -Command @('XLEN', $stream)
        $pending = Get-RedisLong -Command @('XPENDING', $stream, $group)
        if ($length -eq 0 -and $pending -eq 0) {
            $watch.Stop()
            return $watch.ElapsedMilliseconds
        }
        Start-Sleep -Milliseconds 100
    } while ($watch.Elapsed.TotalSeconds -lt $DrainTimeoutSeconds)
    throw "Stream did not drain: length=$length pending=$pending"
}

function Start-RedisStreamMonitor {
    param([string]$Path)
    return Start-Job -ArgumentList $redisContainer, $redisPassword, $stream, $group, $Path -ScriptBlock {
        param($Container, $Password, $Stream, $Group, $OutputPath)
        $stopPath = "$OutputPath.stop"
        [System.IO.File]::WriteAllText($OutputPath, "timestamp,length,pending`r`n")
        while (-not (Test-Path -LiteralPath $stopPath)) {
            $auth = "REDISCLI_AUTH=$Password"
            $length = & docker exec -e $auth $Container redis-cli --raw XLEN $Stream 2>$null | Select-Object -First 1
            $pending = & docker exec -e $auth $Container redis-cli --raw XPENDING $Stream $Group 2>$null | Select-Object -First 1
            [System.IO.File]::AppendAllText($OutputPath, "$(Get-Date -Format o),$length,$pending`r`n")
            Start-Sleep -Milliseconds 100
        }
    }
}

function Stop-RedisStreamMonitor {
    param($Job, [string]$Path)
    New-Item -ItemType File -Path "$Path.stop" -Force | Out-Null
    Wait-Job -Job $Job -Timeout 10 | Out-Null
    if ($Job.State -ne 'Completed') { Stop-Job -Job $Job }
    Receive-Job -Job $Job | Out-Null
    Remove-Job -Job $Job -Force
    Remove-Item -LiteralPath "$Path.stop" -Force -ErrorAction SilentlyContinue
}

$entrySettings = @{
    direct = [pscustomobject]@{ port = 6086; path = '/program/order/create/v5' }
    gateway = [pscustomobject]@{ port = 6085; path = '/stellaris/program/program/order/create/v5' }
}

$oldJavaHome = $env:JAVA_HOME
$oldPath = $env:Path
$oldJmLaunch = $env:JM_LAUNCH
$env:JAVA_HOME = $JdkHome
$env:Path = "$JdkHome\bin;$oldPath"
$env:JM_LAUNCH = Join-Path $JdkHome 'bin\java.exe'
$results = [System.Collections.Generic.List[object]]::new()

try {
    foreach ($entry in $Entries) {
        $setting = $entrySettings[$entry]
        foreach ($threads in $Stages) {
            for ($repeat = 1; $repeat -le $Repeats; $repeat++) {
                try {
                Write-Host "[$entry] threads=$threads repeat=$repeat preparing fixture..."
                & $prepare | Out-Host
                if ($LASTEXITCODE -ne 0) { throw 'Fixture preparation failed' }

                $runName = "$entry-$threads-r$repeat"
                $runDirectory = Join-Path $OutputRoot $runName
                New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
                $jtl = Join-Path $runDirectory 'result.jtl'
                $jmeterLog = Join-Path $runDirectory 'jmeter.log'
                $monitorPath = Join-Path $runDirectory 'stream.csv'
                $monitor = Start-RedisStreamMonitor -Path $monitorPath
                $lockWaitsBefore = Get-MySqlStatusValue -Name 'Innodb_row_lock_waits'
                $deadlocksBefore = Get-MySqlStatusValue -Name 'Innodb_deadlocks'

                try {
                    $arguments = @(
                        '-n', '-t', $jmx,
                        "-Jthreads=$threads", '-JthreadRampSeconds=0',
                        '-Jhost=127.0.0.1', "-Jport=$($setting.port)", "-Jpath=$($setting.path)",
                        "-JprogramId=$programId", "-JticketCategoryId=$categoryId", "-JdataFile=$dataFile",
                        "-Jpreconnect=$($Preconnect.IsPresent.ToString().ToLowerInvariant())",
                        '-Jsample_variables=requestId,userId,orderNumber,businessCode,businessMessage,failureType',
                        '-l', $jtl, '-j', $jmeterLog
                    )
                    $watch = [System.Diagnostics.Stopwatch]::StartNew()
                    & $jmeter @arguments | Tee-Object -FilePath (Join-Path $runDirectory 'jmeter-console.log') | Out-Host
                    $exitCode = $LASTEXITCODE
                    $watch.Stop()
                    if ($exitCode -ne 0) { throw "JMeter exited with code $exitCode" }
                    $drainAfterHttpMs = Wait-StreamDrained
                } finally {
                    Stop-RedisStreamMonitor -Job $monitor -Path $monitorPath
                }

                $rows = @(Import-Csv -LiteralPath $jtl | Where-Object { $_.label -eq 'POST V5 Auto Seat HTTP' })
                $successRows = @($rows | Where-Object { $_.success -eq 'true' })
                $elapsed = @($rows | ForEach-Object { [long]$_.elapsed })
                $windowMs = if ($rows.Count -eq 0) { 0L } else {
                    $firstStart = ($rows | ForEach-Object { [long]$_.timeStamp } | Measure-Object -Minimum).Minimum
                    $lastEnd = ($rows | ForEach-Object { [long]$_.timeStamp + [long]$_.elapsed } | Measure-Object -Maximum).Maximum
                    [long]$lastEnd - [long]$firstStart
                }
                $streamSamples = @(Import-Csv -LiteralPath $monitorPath)
                $peakStream = if ($streamSamples.Count) { [long](($streamSamples | Measure-Object -Property length -Maximum).Maximum) } else { 0L }
                $peakPending = if ($streamSamples.Count) { [long](($streamSamples | Measure-Object -Property pending -Maximum).Maximum) } else { 0L }
                $snapshot = Get-TradeSnapshot
                $redisOwners = Get-RedisLong -Command @('HLEN', ($seatKeyPrefix + 'owner'))
                $redisAvailable = Get-RedisLong -Command @('ZCARD', ($seatKeyPrefix + "available:$categoryId"))
                $lockWaits = (Get-MySqlStatusValue -Name 'Innodb_row_lock_waits') - $lockWaitsBefore
                $deadlocks = (Get-MySqlStatusValue -Name 'Innodb_deadlocks') - $deadlocksBefore
                $invariant = $snapshot.createdRequests -eq $snapshot.orders `
                    -and $snapshot.orders -eq $snapshot.distinctOrders `
                    -and $snapshot.orders -eq $snapshot.lockedSeats `
                    -and $snapshot.lockedSeats -eq $snapshot.distinctLockedSeats `
                    -and $snapshot.lockedSeats -eq $snapshot.purchaseCount `
                    -and $snapshot.lockedSeats -eq $redisOwners `
                    -and $redisAvailable -eq (10000 - $snapshot.lockedSeats) `
                    -and $snapshot.auditedFailures -eq 0
                $codes = @($rows | Group-Object businessCode | Sort-Object Count -Descending | ForEach-Object { "$($_.Name):$($_.Count)" }) -join ';'
                $record = [pscustomobject]@{
                    entry = $entry
                    threads = $threads
                    repeat = $repeat
                    requests = $rows.Count
                    accepted = $successRows.Count
                    failed = $rows.Count - $successRows.Count
                    completionWindowMs = $windowMs
                    completionThroughput = if ($windowMs -gt 0) { [Math]::Round(1000.0 * $rows.Count / $windowMs, 2) } else { 0 }
                    p50Ms = Get-Percentile -Values $elapsed -Percentile 0.50
                    p95Ms = Get-Percentile -Values $elapsed -Percentile 0.95
                    p99Ms = Get-Percentile -Values $elapsed -Percentile 0.99
                    maxMs = if ($elapsed.Count) { ($elapsed | Measure-Object -Maximum).Maximum } else { 0 }
                    drainAfterHttpMs = $drainAfterHttpMs
                    peakStream = $peakStream
                    peakPending = $peakPending
                    created = $snapshot.createdRequests
                    rejected = $snapshot.rejectedRequests
                    mysqlLockWaits = $lockWaits
                    mysqlDeadlocks = $deadlocks
                    invariant = if ($invariant) { 'PASS' } else { 'FAIL' }
                    resultCodes = $codes
                    runDirectory = $runDirectory
                }
                $results.Add($record)
                $record | Format-List | Out-Host
                if (-not $invariant) { throw "Business invariant failed for $runName" }
                & $cleanup | Out-Host
                if ($LASTEXITCODE -ne 0) { throw 'Fixture cleanup failed' }
                } catch {
                    Write-Warning "Run cleanup after failure: $($_.Exception.Message)"
                    & $cleanup | Out-Host
                    throw
                }
            }
        }
    }
} finally {
    $results | Export-Csv -LiteralPath (Join-Path $OutputRoot 'summary.csv') -NoTypeInformation -Encoding UTF8
    $env:JAVA_HOME = $oldJavaHome
    $env:Path = $oldPath
    if ($null -eq $oldJmLaunch) { Remove-Item Env:JM_LAUNCH -ErrorAction SilentlyContinue } else { $env:JM_LAUNCH = $oldJmLaunch }
}

Write-Host "Capacity evidence: $OutputRoot"
