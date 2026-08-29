param(
    [ValidateRange(1, 100000)][int]$TargetQps = 100,
    [ValidateRange(1, 3600)][int]$RampUpSeconds = 10,
    [ValidateRange(1, 3600)][int]$WarmupSeconds = 15,
    [ValidateRange(1, 7200)][int]$SampleSeconds = 20,
    [ValidateRange(1, 3600)][int]$RampDownSeconds = 5,
    [ValidateRange(1, 60000)][int]$ExpectedP99Ms = 1000,
    [ValidateRange(1, 10000)][int]$MaxThreads = 2000,
    [ValidateRange(1, 10000000)][int]$SeatBudget = 10000,
    [ValidateRange(1, 10000000)][int]$UserPurchaseBudget = 6000,
    [ValidateRange(0.1, 1.0)][double]$BudgetUtilization = 0.8,
    [ValidateRange(30, 900)][int]$RecoveryTimeoutSeconds = 600,
    [string]$JMeterBin = 'G:\study\Computer\java\apache-jmeter-5.6.3\apache-jmeter-5.6.3\bin',
    [string]$JdkHome = 'C:\Users\X\.jdks\ms-17.0.17',
    [string]$MySqlContainer = 'stellaris-interview-mysql-1',
    [string]$RedisContainer = 'stellaris-interview-redis-1',
    [string]$KafkaContainer = 'stellaris-interview-kafka-1',
    [string]$MySqlPassword = 'mysql123',
    [string]$RedisPassword = 'redis123',
    [string]$KafkaTopic = 'stellaris-create_order',
    [string]$KafkaGroup = 'create_order_data',
    [switch]$DirectProgramService,
    [switch]$RequireTokens,
    [switch]$AllowBudgetOverrun,
    [switch]$AllowThreadCap,
    [switch]$KeepState,
    [switch]$ValidateOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'V4V5Comparison.Common.ps1')

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$jmeter = Join-Path $JMeterBin 'jmeter.bat'
$jmx = Join-Path $PSScriptRoot 'benchmark.jmx'
$dataFile = Join-Path $PSScriptRoot 'users.csv'
$resultsRoot = Join-Path $PSScriptRoot 'results'
$programId = $script:StellarisBenchmarkProgramId
$categoryId = $script:StellarisBenchmarkCategoryId
$saleShard = $programId % 16
$stream = "stellaris:{sale:$saleShard}:reservation:event:stream"
$streamGroup = 'stellaris-order-relay'

function Get-RedisScalar {
    param([string[]]$Command)
    $value = @(Invoke-StellarisRedisRaw -Container $RedisContainer -Password $RedisPassword -Command $Command)
    if ($value.Count -eq 0 -or [string]::IsNullOrWhiteSpace("$($value[0])")) { return '0' }
    return "$($value[0])"
}

function Invoke-KafkaDescribe {
    return @(& docker exec $KafkaContainer /opt/kafka/bin/kafka-consumer-groups.sh `
        --bootstrap-server localhost:9092 --group $KafkaGroup --describe 2>&1)
}

function Get-KafkaLagTotal {
    $output = Invoke-KafkaDescribe
    if ($LASTEXITCODE -ne 0) { throw "Kafka describe failed: $($output -join ' ')" }
    $rows = @($output | Where-Object { $_ -match "^\s*$([regex]::Escape($KafkaGroup))\s+$([regex]::Escape($KafkaTopic))\s+" })
    if ($rows.Count -eq 0) { throw 'Kafka group/topic rows were not found.' }
    $sum = 0L
    foreach ($row in $rows) {
        $parts = @(-split $row)
        if ($parts.Count -lt 6 -or $parts[5] -notmatch '^\d+$') { throw "Cannot parse Kafka row: $row" }
        $sum += [long]$parts[5]
    }
    return $sum
}

function Wait-PipelineDrain {
    param([int]$TimeoutSeconds = 180)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $stable = 0
    do {
        $length = [long](Get-RedisScalar @('XLEN', $stream))
        $pendingRaw = @(Invoke-StellarisRedisRaw -Container $RedisContainer -Password $RedisPassword -Command @('XPENDING', $stream, $streamGroup))
        $pending = if ($pendingRaw.Count -gt 0 -and "$($pendingRaw[0])" -match '^\d+$') { [long]$pendingRaw[0] } else { 0L }
        $lag = Get-KafkaLagTotal
        if ($length -eq 0 -and $pending -eq 0 -and $lag -eq 0) { $stable++ } else { $stable = 0 }
        if ($stable -lt 2) { Start-Sleep -Seconds 2 }
    } while ($stable -lt 2 -and (Get-Date) -lt $deadline)
    if ($stable -lt 2) { throw "Pipeline did not drain: stream=$length pending=$pending kafkaLag=$lag" }
}

function Save-Snapshot {
    param([string]$Name, [string]$RunRoot)
    $snapshot = Join-Path $RunRoot $Name
    New-Item -ItemType Directory -Path $snapshot | Out-Null

    (& docker ps --format '{{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}' 2>&1) | Set-Content -LiteralPath (Join-Path $snapshot 'docker-ps.txt') -Encoding utf8
    (& docker stats --no-stream 2>&1) | Set-Content -LiteralPath (Join-Path $snapshot 'docker-stats.txt') -Encoding utf8
    (& (Join-Path $JdkHome 'bin\jps.exe') -lv 2>&1) | Set-Content -LiteralPath (Join-Path $snapshot 'jvm-command-lines.txt') -Encoding utf8
    foreach ($section in @('stats','memory','clients','persistence','commandstats','cpu')) {
        (& docker exec $RedisContainer redis-cli -a $RedisPassword --no-auth-warning INFO $section 2>&1) |
            Set-Content -LiteralPath (Join-Path $snapshot "redis-info-$section.txt") -Encoding utf8
    }
    (& docker exec $RedisContainer redis-cli -a $RedisPassword --no-auth-warning XLEN $stream 2>&1) |
        Set-Content -LiteralPath (Join-Path $snapshot 'redis-stream-length.txt') -Encoding utf8
    (& docker exec $RedisContainer redis-cli -a $RedisPassword --no-auth-warning XPENDING $stream $streamGroup 2>&1) |
        Set-Content -LiteralPath (Join-Path $snapshot 'redis-stream-pending.txt') -Encoding utf8
    (Invoke-KafkaDescribe) | Set-Content -LiteralPath (Join-Path $snapshot 'kafka-consumer-group.txt') -Encoding utf8

    $performanceSql = @"
SELECT DIGEST_TEXT,COUNT_STAR,ROUND(SUM_TIMER_WAIT/1e12,3) total_s,ROUND(AVG_TIMER_WAIT/1e9,3) avg_ms,SUM_ROWS_EXAMINED,SUM_ROWS_AFFECTED
FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME LIKE 'stellaris_%'
ORDER BY SUM_TIMER_WAIT DESC LIMIT 30;
SELECT OBJECT_SCHEMA,OBJECT_NAME,COUNT_READ,COUNT_WRITE,ROUND(SUM_TIMER_READ/1e12,3) read_s,ROUND(SUM_TIMER_WRITE/1e12,3) write_s
FROM performance_schema.table_io_waits_summary_by_table WHERE OBJECT_SCHEMA LIKE 'stellaris_%'
ORDER BY (SUM_TIMER_READ+SUM_TIMER_WRITE) DESC LIMIT 30;
SELECT * FROM performance_schema.data_lock_waits LIMIT 100;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected','Threads_running','Connections','Aborted_connects','Innodb_row_lock_current_waits','Innodb_row_lock_time','Innodb_row_lock_waits');
"@
    (& docker exec $MySqlContainer mysql -uroot "-p$MySqlPassword" -e $performanceSql 2>&1) |
        Set-Content -LiteralPath (Join-Path $snapshot 'mysql-performance-schema.txt') -Encoding utf8

    foreach ($endpoint in @(
        [pscustomobject]@{ name='gateway'; url='http://127.0.0.1:6085' },
        [pscustomobject]@{ name='program'; url='http://127.0.0.1:6086' },
        [pscustomobject]@{ name='order'; url='http://127.0.0.1:8081' }
    )) {
        & curl.exe -fsS "$($endpoint.url)/actuator/health" -o (Join-Path $snapshot "$($endpoint.name)-health.json") 2>$null
        & curl.exe -fsS "$($endpoint.url)/actuator/prometheus" -o (Join-Path $snapshot "$($endpoint.name)-prometheus.txt") 2>$null
    }

    $logTailDirectory = Join-Path $snapshot 'application-log-tails'
    New-Item -ItemType Directory -Path $logTailDirectory | Out-Null
    $logCandidates = @(
        Get-ChildItem -LiteralPath (Join-Path $repositoryRoot 'logs') -File -Filter '*.log' -ErrorAction SilentlyContinue
        Get-ChildItem -LiteralPath (Join-Path $repositoryRoot 'output\runtime') -File -Filter '*.log' -ErrorAction SilentlyContinue
    )
    foreach ($logFile in $logCandidates) {
        $safeName = ($logFile.FullName.Substring($repositoryRoot.Length).TrimStart('\') -replace '[\\/:*?"<>|]', '_')
        Get-Content -LiteralPath $logFile.FullName -Tail 2000 -ErrorAction SilentlyContinue |
            Set-Content -LiteralPath (Join-Path $logTailDirectory $safeName) -Encoding utf8
    }
}

function Start-RuntimeMonitor {
    param([string]$RunRoot, [int]$DurationSeconds)
    $redisCsv = Join-Path $RunRoot 'runtime-redis-kafka.csv'
    $dockerCsv = Join-Path $RunRoot 'runtime-docker-stats.csv'
    'timestamp,redisOpsPerSec,connectedClients,blockedClients,usedMemory,usedMemoryPeak,rejectedConnections,evictedKeys,kafkaLag' |
        Set-Content -LiteralPath $redisCsv -Encoding utf8
    'timestamp,container,cpuPercent,memoryUsage,netIO,blockIO,pids' |
        Set-Content -LiteralPath $dockerCsv -Encoding utf8
    return Start-Job -ScriptBlock {
        param($RedisContainer,$RedisPassword,$KafkaContainer,$KafkaGroup,$KafkaTopic,$RedisCsv,$DockerCsv,$DurationSeconds)
        function Info-Value([string[]]$Lines,[string]$Name) {
            $line = $Lines | Where-Object { $_ -like "$Name`:*" } | Select-Object -First 1
            if ($null -eq $line) { return 'NA' }
            return ($line -split ':',2)[1].Trim()
        }
        $deadline = (Get-Date).AddSeconds($DurationSeconds)
        while ((Get-Date) -lt $deadline) {
            $timestamp = (Get-Date).ToString('o')
            $info = @(& docker exec $RedisContainer redis-cli -a $RedisPassword --no-auth-warning INFO 2>$null)
            $kafka = @(& docker exec $KafkaContainer /opt/kafka/bin/kafka-consumer-groups.sh `
                --bootstrap-server localhost:9092 --group $KafkaGroup --describe 2>$null)
            $lag = 0L
            foreach ($row in @($kafka | Where-Object { $_ -match "^\s*$([regex]::Escape($KafkaGroup))\s+$([regex]::Escape($KafkaTopic))\s+" })) {
                $parts = @(-split $row)
                if ($parts.Count -ge 6 -and $parts[5] -match '^\d+$') { $lag += [long]$parts[5] }
            }
            $values = @(
                $timestamp,
                (Info-Value $info 'instantaneous_ops_per_sec'),
                (Info-Value $info 'connected_clients'),
                (Info-Value $info 'blocked_clients'),
                (Info-Value $info 'used_memory'),
                (Info-Value $info 'used_memory_peak'),
                (Info-Value $info 'rejected_connections'),
                (Info-Value $info 'evicted_keys'),
                $lag
            )
            ($values -join ',') | Add-Content -LiteralPath $RedisCsv -Encoding utf8
            $stats = @(& docker stats --no-stream --format '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}|{{.NetIO}}|{{.BlockIO}}|{{.PIDs}}' 2>$null)
            foreach ($line in $stats) {
                $parts = @($line -split '\|')
                if ($parts.Count -eq 6) {
                    $escaped = $parts | ForEach-Object { '"' + ($_.Replace('"','""')) + '"' }
                    ('"' + $timestamp + '",' + ($escaped -join ',')) | Add-Content -LiteralPath $DockerCsv -Encoding utf8
                }
            }
            Start-Sleep -Seconds 5
        }
    } -ArgumentList $RedisContainer,$RedisPassword,$KafkaContainer,$KafkaGroup,$KafkaTopic,$redisCsv,$dockerCsv,$DurationSeconds
}

if (-not (Test-Path -LiteralPath $jmeter)) { throw "JMeter not found: $jmeter" }
if (-not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin\java.exe'))) { throw "JDK not found: $JdkHome" }
if (-not (Test-Path -LiteralPath $dataFile)) { throw "CSV not found: $dataFile" }
foreach ($plugin in @('jmeter-plugins-casutg-*.jar')) {
    if (@(Get-ChildItem -LiteralPath (Join-Path $JMeterBin '..\lib\ext') -Filter $plugin -ErrorAction SilentlyContinue).Count -eq 0) {
        throw "Required JMeter plugin not installed: $plugin"
    }
}

$csvRows = @(Import-Csv -LiteralPath $dataFile)
if ($csvRows.Count -ne $script:StellarisBenchmarkUserCount) { throw "users.csv must contain $script:StellarisBenchmarkUserCount users; found $($csvRows.Count). Run prepare-benchmark.ps1." }
if (@($csvRows | Where-Object { $_.userId -notmatch '^\d{18}$' -or $_.ticketUserId -notmatch '^\d{18}$' }).Count -gt 0) {
    throw 'users.csv contains invalid 64-bit IDs.'
}
if ($RequireTokens -and @($csvRows | Where-Object { [string]::IsNullOrWhiteSpace($_.token) }).Count -gt 0) {
    throw 'RequireTokens was specified but users.csv has blank token values.'
}
Initialize-ComparisonTicketUserHotCache -CsvPath $dataFile -Container $RedisContainer -Password $RedisPassword

$estimatedRequests = [int][Math]::Ceiling($TargetQps * ($RampUpSeconds / 2.0 + $WarmupSeconds + $SampleSeconds + $RampDownSeconds / 2.0))
$hardBudget = [Math]::Min($SeatBudget, $UserPurchaseBudget)
$safeBudget = [int][Math]::Floor($hardBudget * $BudgetUtilization)
if ($estimatedRequests -gt $safeBudget -and -not $AllowBudgetOverrun) {
    throw "Data budget refused: estimated requests=$estimatedRequests, safe budget=$safeBudget (hard=$hardBudget). Enlarge the isolated fixture; do not turn inventory exhaustion into a fake capacity breakpoint."
}

$requiredThreads = [int][Math]::Ceiling($TargetQps * ($ExpectedP99Ms / 1000.0) * 2.0)
$threads = [Math]::Max(10, $requiredThreads)
if ($threads -gt $MaxThreads) {
    if (-not $AllowThreadCap) { throw "Load generator needs about $threads threads by Little's law, above MaxThreads=$MaxThreads. Split load generators or explicitly use -AllowThreadCap." }
    $threads = $MaxThreads
}

& (Join-Path $PSScriptRoot 'verify-reset.ps1') -MySqlContainer $MySqlContainer -RedisContainer $RedisContainer `
    -KafkaContainer $KafkaContainer -MySqlPassword $MySqlPassword -RedisPassword $RedisPassword `
    -KafkaTopic $KafkaTopic -KafkaGroup $KafkaGroup -ExpectedSeats $SeatBudget

if ($ValidateOnly) {
    [pscustomobject]@{
        targetQps = $TargetQps
        estimatedRequests = $estimatedRequests
        safeBudget = $safeBudget
        threads = $threads
        loadModel = 'free-form-arrivals-fixed-rate'
        endpointMode = if ($DirectProgramService) { 'direct-program-bypasses-gateway' } else { 'gateway-full-entry' }
        result = 'VALIDATION PASS - no load was started'
    } | Format-List
    return
}

$runId = '{0}-{1}qps' -f (Get-Date -Format 'yyyyMMdd-HHmmss'), $TargetQps
$runRoot = Join-Path $resultsRoot $runId
if (Test-Path -LiteralPath $runRoot) { $runRoot += '-' + [guid]::NewGuid().ToString('N').Substring(0,8) }
New-Item -ItemType Directory -Path $runRoot | Out-Null
$report = Join-Path $runRoot 'report'
$jtl = Join-Path $runRoot 'result.jtl'
$jmeterLog = Join-Path $runRoot 'jmeter.log'
$startTime = Get-Date
$endpointMode = if ($DirectProgramService) { 'direct-program-bypasses-gateway' } else { 'gateway-full-entry' }
$port = if ($DirectProgramService) { 6086 } else { 6085 }
$path = if ($DirectProgramService) { '/program/order/create/v5' } else { '/stellaris/program/program/order/create/v5' }

$gitCommit = (& git -C $repositoryRoot rev-parse HEAD 2>$null)
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace("$gitCommit")) { $gitCommit = 'UNAVAILABLE' }
$parameters = [ordered]@{
    runId=$runId; startTime=$startTime.ToString('o'); endpointMode=$endpointMode; host='127.0.0.1'; port=$port; path=$path
    targetQps=$TargetQps; rampUpSeconds=$RampUpSeconds; warmupSeconds=$WarmupSeconds; sampleSeconds=$SampleSeconds; rampDownSeconds=$RampDownSeconds
    expectedP99Ms=$ExpectedP99Ms; threads=$threads; loadModel='free-form-arrivals-fixed-rate'; estimatedRequests=$estimatedRequests; hardBudget=$hardBudget; safeBudget=$safeBudget
    programId=$programId; ticketCategoryId=$categoryId; users=$script:StellarisBenchmarkUserCount; ticketsPerRequest=1; jmeterVersion='5.6.3'; jdk='17'; gitCommit="$gitCommit"
}
$parameters | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $runRoot 'parameters.json') -Encoding utf8
$startTime.ToString('o') | Set-Content -LiteralPath (Join-Path $runRoot 'start-time.txt') -Encoding utf8
Copy-Item -LiteralPath (Join-Path $repositoryRoot 'stellaris-server\stellaris-gateway-service\src\main\resources\application.yml') -Destination (Join-Path $runRoot 'gateway-application.yml')
Copy-Item -LiteralPath (Join-Path $repositoryRoot 'stellaris-server\stellaris-gateway-service\src\main\resources\application-pro.yml') -Destination (Join-Path $runRoot 'gateway-application-pro.yml')
Copy-Item -LiteralPath (Join-Path $repositoryRoot 'stellaris-server\stellaris-program-service\src\main\resources\application.yml') -Destination (Join-Path $runRoot 'program-application.yml')
Copy-Item -LiteralPath (Join-Path $repositoryRoot 'stellaris-server\stellaris-order-service\src\main\resources\application.yml') -Destination (Join-Path $runRoot 'order-application.yml')
Copy-Item -LiteralPath $jmx -Destination (Join-Path $runRoot 'benchmark.jmx')
Copy-Item -LiteralPath (Join-Path $repositoryRoot 'ops\docker-compose.interview.yml') -Destination (Join-Path $runRoot 'docker-compose.interview.yml')
(& Get-ComputerInfo 2>&1) | Set-Content -LiteralPath (Join-Path $runRoot 'machine-info.txt') -Encoding utf8
(& Get-Process java -ErrorAction SilentlyContinue | Select-Object Id,Path,StartTime,CPU,WorkingSet64 2>&1) | Out-String |
    Set-Content -LiteralPath (Join-Path $runRoot 'java-processes.txt') -Encoding utf8

$env:JAVA_HOME = $JdkHome
$env:Path = "$JdkHome\bin;$env:Path"
$javaVersion = (& java -version 2>&1 | Select-Object -First 1)
if ($javaVersion -notmatch '17\.') { throw "JMeter requires JDK 17, actual: $javaVersion" }

$runFailure = $null
$jmeterStarted = $false
$monitorJob = $null
try {
    Save-Snapshot -Name 'before' -RunRoot $runRoot
    $ticketUserRpcBefore = Get-ComparisonTicketUserRpcCount
    $monitorDuration = $RampUpSeconds + $WarmupSeconds + $SampleSeconds + $RampDownSeconds + $RecoveryTimeoutSeconds + 60
    $monitorJob = Start-RuntimeMonitor -RunRoot $runRoot -DurationSeconds $monitorDuration
    $jmeterStarted = $true
    & $jmeter -n -t $jmx `
        '-Jprotocol=http' '-Jhost=127.0.0.1' "-Jport=$port" "-Jpath=$path" `
        "-JprogramId=$programId" "-JticketCategoryId=$categoryId" "-JdataFile=$dataFile" `
        "-Jthreads=$threads" "-JtargetQps=$TargetQps" "-JrampUpSeconds=$RampUpSeconds" `
        "-JwarmupSeconds=$WarmupSeconds" "-JsampleSeconds=$SampleSeconds" "-JrampDownSeconds=$RampDownSeconds" `
        "-JnoVerify=$((-not $RequireTokens).ToString().ToLowerInvariant())" `
        '-Jsample_variables=phase,requestId,userId,orderNumber,failureType,businessCode,businessMessage' `
        '-Jjmeter.save.saveservice.output_format=csv' '-Jjmeter.save.saveservice.print_field_names=true' `
        '-Jjmeter.save.saveservice.thread_counts=true' '-Jjmeter.save.saveservice.assertion_results_failure_message=true' `
        -l $jtl -j $jmeterLog -e -o $report
    if ($LASTEXITCODE -ne 0) { throw "JMeter exited with code $LASTEXITCODE" }
    $arrivalSummaryLine = Select-String -LiteralPath $jmeterLog -Pattern 'Done (\d+) arrivals, (\d+) completions, (\d+) abandonments' | Select-Object -Last 1
    if ($null -eq $arrivalSummaryLine) { throw 'JMeter arrivals summary was not found; fixed-rate generator validity is unknown.' }
    $arrivalMatch = [regex]::Match($arrivalSummaryLine.Line, 'Done (\d+) arrivals, (\d+) completions, (\d+) abandonments')
    $scheduledArrivals = [long]$arrivalMatch.Groups[1].Value
    $completedArrivals = [long]$arrivalMatch.Groups[2].Value
    $abandonedArrivals = [long]$arrivalMatch.Groups[3].Value
    $generatorValid = ($abandonedArrivals -eq 0 -and $completedArrivals -eq $scheduledArrivals)
    $ticketUserRpcAfter = Get-ComparisonTicketUserRpcCount
    $ticketUserRpcDelta = if ($null -ne $ticketUserRpcBefore -and $null -ne $ticketUserRpcAfter) {
        [int][Math]::Round($ticketUserRpcAfter - $ticketUserRpcBefore)
    } else { 'UNAVAILABLE' }
    if ($ticketUserRpcDelta -is [int] -and $ticketUserRpcDelta -ne 0) {
        throw "Hot-path invariant failed: User Service /ticket/user/list increased by $ticketUserRpcDelta call(s)."
    }

    $pipelineDrainWatch = [Diagnostics.Stopwatch]::StartNew()
    Wait-PipelineDrain -TimeoutSeconds $RecoveryTimeoutSeconds
    $pipelineDrainWatch.Stop()
    $pipelineDrainSeconds = [Math]::Round($pipelineDrainWatch.Elapsed.TotalSeconds, 2)
    Save-Snapshot -Name 'after' -RunRoot $runRoot

    $statisticsPath = Join-Path $report 'statistics.json'
    $statistics = Get-Content -Raw -LiteralPath $statisticsPath | ConvertFrom-Json
    $sampleLabel = 'V5 Create [sample]'
    $sampleProperty = $statistics.PSObject.Properties[$sampleLabel]
    if ($null -eq $sampleProperty) { throw "Formal sample label missing from statistics.json: $sampleLabel" }
    $sample = $sampleProperty.Value

    $formalRows = @(Import-Csv -LiteralPath $jtl | Where-Object { $_.label -eq $sampleLabel })
    $formalArrivalRate = [Math]::Round($formalRows.Count / [double]$SampleSeconds, 2)
    $targetAttainmentPercent = [Math]::Round($formalArrivalRate * 100.0 / $TargetQps, 2)
    $runtimePipelineRows = @(Import-Csv -LiteralPath (Join-Path $runRoot 'runtime-redis-kafka.csv'))
    $maxKafkaLag = if ($runtimePipelineRows.Count -gt 0) {
        [long](($runtimePipelineRows | Measure-Object -Property kafkaLag -Maximum).Maximum)
    } else { -1L }
    $businessFailures = @($formalRows | Where-Object { $_.failureType -eq 'BUSINESS' })
    $technicalFailures = @($formalRows | Where-Object { $_.failureType -eq 'TECHNICAL' })
    $failureBreakdown = @($formalRows | Where-Object { $_.success -ne 'true' } |
        Group-Object -Property failureType,businessCode,businessMessage,responseCode | ForEach-Object {
            $first = $_.Group[0]
            [pscustomobject]@{
                failureType = $first.failureType
                code = $first.businessCode
                message = $first.businessMessage
                httpCode = $first.responseCode
                count = $_.Count
            }
        } | Sort-Object count -Descending)
    if ($failureBreakdown.Count -eq 0) {
        '"failureType","code","message","httpCode","count"' | Set-Content -LiteralPath (Join-Path $runRoot 'failure-breakdown.csv') -Encoding utf8
    } else {
        $failureBreakdown | Export-Csv -LiteralPath (Join-Path $runRoot 'failure-breakdown.csv') -NoTypeInformation -Encoding utf8
    }

    $totalSuccess = 0L; $totalErrors = 0L; $uniqueOrders = [System.Collections.Generic.HashSet[string]]::new()
    $maxActiveThreads = 0
    foreach ($row in (Import-Csv -LiteralPath $jtl)) {
        if ($row.label -notlike 'V5 Create *') { continue }
        if ([int]$row.allThreads -gt $maxActiveThreads) { $maxActiveThreads = [int]$row.allThreads }
        if ($row.success -eq 'true') {
            $totalSuccess++
            if ($row.orderNumber -match '^\d+$') { [void]$uniqueOrders.Add($row.orderNumber) }
        } else { $totalErrors++ }
    }
    $mysqlIntegrity = @(Invoke-StellarisMySqlQuery -Container $MySqlContainer -Password $MySqlPassword -Query @"
SELECT COUNT(*) FROM (
  SELECT order_number,order_status FROM stellaris_order_0.d_order_0 WHERE program_id=$programId
  UNION ALL SELECT order_number,order_status FROM stellaris_order_0.d_order_1 WHERE program_id=$programId
  UNION ALL SELECT order_number,order_status FROM stellaris_order_0.d_order_2 WHERE program_id=$programId
  UNION ALL SELECT order_number,order_status FROM stellaris_order_0.d_order_3 WHERE program_id=$programId
  UNION ALL SELECT order_number,order_status FROM stellaris_order_1.d_order_0 WHERE program_id=$programId
  UNION ALL SELECT order_number,order_status FROM stellaris_order_1.d_order_1 WHERE program_id=$programId
  UNION ALL SELECT order_number,order_status FROM stellaris_order_1.d_order_2 WHERE program_id=$programId
  UNION ALL SELECT order_number,order_status FROM stellaris_order_1.d_order_3 WHERE program_id=$programId
) o WHERE order_status=1;
SELECT COUNT(*) FROM stellaris_program_0.d_seat_0 WHERE program_id=$programId AND reservation_id IS NOT NULL;
"@)
    $integrity = [ordered]@{
        allPhaseSuccess=$totalSuccess; allPhaseErrors=$totalErrors; uniqueExtractedOrderNumbers=$uniqueOrders.Count
        mysqlOpenOrders=[long]$mysqlIntegrity[0]; mysqlReservedSeats=[long]$mysqlIntegrity[1]
        redisOwners=[long](Get-RedisScalar @('HLEN', "stellaris:{sale:$saleShard}:program:$programId`:seat:owner"))
        redisReservations=[long](Get-RedisScalar @('HLEN', "stellaris:{sale:$saleShard}:program:$programId`:seat:reservation"))
    }
    $integrity.pass = ($integrity.uniqueExtractedOrderNumbers -eq $totalSuccess -and $integrity.mysqlOpenOrders -eq $totalSuccess -and
        $integrity.mysqlReservedSeats -eq $totalSuccess -and $integrity.redisOwners -eq $totalSuccess -and $integrity.redisReservations -eq $totalSuccess)
    $integrity | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runRoot 'integrity.json') -Encoding utf8

    $summary = @"
# V5 benchmark summary

- Run: $runId
- Endpoint mode: $endpointMode
- Target QPS: $TargetQps
- Load model: Free-Form Arrivals Thread Group (fixed request arrival rate)
- Formal phase: $SampleSeconds seconds
- Formal arrivals: $($formalRows.Count) ($formalArrivalRate req/s, $targetAttainmentPercent% of target)
- Generator: scheduled=$scheduledArrivals; completed=$completedArrivals; abandoned=$abandonedArrivals; valid=$generatorValid
- Actual throughput: $([Math]::Round($sample.throughput,2)) req/s
- Samples: $($sample.sampleCount); success: $($sample.sampleCount-$sample.errorCount); errors: $($sample.errorCount); error rate: $([Math]::Round($sample.errorPct,4))%
- Failure classification: business=$($businessFailures.Count); technical=$($technicalFailures.Count)
- Ticket-user RPC calls during load: $ticketUserRpcDelta
- Average/P50/P90/P95/P99/Max: $([Math]::Round($sample.meanResTime,2)) / $($sample.medianResTime) / $($sample.pct1ResTime) / $($sample.pct2ResTime) / $($sample.pct3ResTime) / $($sample.maxResTime) ms
- Max active JMeter threads: $maxActiveThreads
- Kafka max sampled LAG: $maxKafkaLag
- Pipeline drain: PASS in $pipelineDrainSeconds seconds after HTTP load
- Business integrity: $($integrity.pass)
- Git commit: $gitCommit

This is one measured point, not a capacity conclusion. Compare at least three repeated points and apply CAPACITY-TEST.md breakpoint rules.
"@
    $summary | Set-Content -LiteralPath (Join-Path $runRoot 'SUMMARY.md') -Encoding utf8
} catch {
    $runFailure = $_
    $_ | Out-String | Set-Content -LiteralPath (Join-Path $runRoot 'ERROR.txt') -Encoding utf8
} finally {
    if ($null -ne $monitorJob) {
        Stop-Job -Job $monitorJob -ErrorAction SilentlyContinue
        Receive-Job -Job $monitorJob -ErrorAction SilentlyContinue | Out-Null
        Remove-Job -Job $monitorJob -Force -ErrorAction SilentlyContinue
    }
    $endTime = Get-Date
    $endTime.ToString('o') | Set-Content -LiteralPath (Join-Path $runRoot 'end-time.txt') -Encoding utf8
    if ($jmeterStarted -and -not (Test-Path -LiteralPath (Join-Path $runRoot 'after'))) {
        try { Save-Snapshot -Name 'after' -RunRoot $runRoot } catch { $_ | Out-String | Set-Content -LiteralPath (Join-Path $runRoot 'after-snapshot-error.txt') }
    }
    if (-not $KeepState -and $jmeterStarted) {
        try {
            & (Join-Path $PSScriptRoot 'reset-benchmark.ps1') -Mode Normal -MySqlContainer $MySqlContainer `
                -RedisContainer $RedisContainer -KafkaContainer $KafkaContainer -MySqlPassword $MySqlPassword `
                -RedisPassword $RedisPassword -TimeoutSeconds $RecoveryTimeoutSeconds 2>&1 | Tee-Object -FilePath (Join-Path $runRoot 'reset.log')
            if ($LASTEXITCODE -ne 0) { throw 'Automatic normal reset returned a failure code.' }
        } catch {
            $_ | Out-String | Add-Content -LiteralPath (Join-Path $runRoot 'reset.log')
            if ($null -eq $runFailure) { $runFailure = $_ }
        }
    }
}

if ($null -ne $runFailure) { throw $runFailure }
Write-Host "Benchmark completed: $runRoot" -ForegroundColor Green
Write-Host "No next QPS stage was started automatically." -ForegroundColor Yellow
