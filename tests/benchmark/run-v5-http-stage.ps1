param(
    [Parameter(Mandatory = $true)][ValidateRange(1, 5000)][int]$Threads,
    [string]$JMeterBin = 'G:\study\Computer\java\apache-jmeter-5.6.3\apache-jmeter-5.6.3\bin',
    [string]$JdkHome = 'C:\Users\X\.jdks\ms-17.0.17',
    [string]$JMeterHeap = '-Xms1g -Xmx4g -XX:MaxMetaspaceSize=512m',
    [string]$ResultDirectory,
    [string]$UserServiceBaseUrl = 'http://127.0.0.1:6082',
    [switch]$Preconnect,
    [ValidateRange(1, 30)][int]$PreconnectRampSeconds = 5,
    [ValidateRange(30, 600)][int]$RecoveryVisibilityTimeoutSeconds = 180,
    [switch]$MechanicalRecovery,
    [switch]$KeepState
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'V4V5Comparison.Common.ps1')

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$jmeter = Join-Path $JMeterBin 'jmeter.bat'
$jmx = Join-Path $PSScriptRoot 'v5-http-capacity.jmx'
$csv = Join-Path $PSScriptRoot 'users.csv'
if (-not (Test-Path -LiteralPath $jmeter)) { throw "JMeter not found: $jmeter" }
if (@(Import-Csv -LiteralPath $csv).Count -lt $Threads) { throw "users.csv needs at least $Threads distinct users." }
if ([string]::IsNullOrWhiteSpace($ResultDirectory)) {
    $runId = Get-Date -Format 'yyyyMMdd-HHmmss'
    $ResultDirectory = Join-Path $PSScriptRoot "results\http-capacity\$runId-$Threads-users"
}
if (Test-Path -LiteralPath $ResultDirectory) { throw "Result directory already exists: $ResultDirectory" }

& (Join-Path $PSScriptRoot 'verify-v4-v5-comparison.ps1') -Phase Ready
Assert-ComparisonTicketUserHotCache -CsvPath $csv

$report = Join-Path $ResultDirectory 'report'
$jtl = Join-Path $ResultDirectory 'result.jtl'
$log = Join-Path $ResultDirectory 'jmeter.log'
$summaryPath = Join-Path $ResultDirectory 'http-summary.json'
$breakdownPath = Join-Path $ResultDirectory 'failure-breakdown.csv'
$recoveryPath = Join-Path $ResultDirectory 'recovery.json'
New-Item -ItemType Directory -Path $ResultDirectory -Force | Out-Null

$oldJavaHome = $env:JAVA_HOME
$oldPath = $env:Path
$oldJmLaunch = $env:JM_LAUNCH
$oldHeap = $env:HEAP
$env:JAVA_HOME = $JdkHome
$env:Path = "$JdkHome\bin;$oldPath"
$env:JM_LAUNCH = Join-Path $JdkHome 'bin\java.exe'
$env:HEAP = $JMeterHeap
$locationPushed = $false
$stageError = $null
$httpSummary = $null
$recoveryVisibilityMs = $null
$testStarted = $null
$testEnded = $null
$ticketUserRpcBefore = $null
$ticketUserRpcAfter = $null

try {
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $javaVersion = @(& $env:JM_LAUNCH -version 2>&1)
        $javaExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($javaExitCode -ne 0 -or ($javaVersion -join ' ') -notmatch '17\.') { throw 'JDK 17 is required.' }

    Push-Location $root
    $locationPushed = $true
    $ticketUserRpcBefore = Get-ComparisonTicketUserRpcCount -UserServiceBaseUrl $UserServiceBaseUrl
    $testStarted = Get-Date
    try {
        $ErrorActionPreference = 'Continue'
        $preconnectValue = if ($Preconnect) { 'true' } else { 'false' }
        $threadRampSeconds = if ($Preconnect) { $PreconnectRampSeconds } else { 1 }
        & $jmeter -n -t $jmx "-Jthreads=$Threads" "-JdataFile=$csv" `
            "-Jpreconnect=$preconnectValue" "-JthreadRampSeconds=$threadRampSeconds" `
            '-Jsample_variables=requestId,userId,orderNumber,failureType,businessCode,businessMessage' `
            -l $jtl -j $log -e -o $report
        $jmeterExitCode = $LASTEXITCODE
    } finally {
        $testEnded = Get-Date
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($jmeterExitCode -ne 0) { throw "JMeter exited with code $jmeterExitCode. See $log" }
    $ticketUserRpcAfter = Get-ComparisonTicketUserRpcCount -UserServiceBaseUrl $UserServiceBaseUrl
    $ticketUserRpcDelta = if ($null -ne $ticketUserRpcBefore -and $null -ne $ticketUserRpcAfter) {
        [int][Math]::Round($ticketUserRpcAfter - $ticketUserRpcBefore)
    } else { 'UNAVAILABLE' }

    $stats = Get-Content -Raw -LiteralPath (Join-Path $report 'statistics.json') | ConvertFrom-Json
    $sampleProperty = $stats.PSObject.Properties['POST V5 Auto Seat HTTP']
    if ($null -eq $sampleProperty) { throw 'HTTP sampler statistics are missing.' }
    $sample = $sampleProperty.Value
    $rows = @(Import-Csv -LiteralPath $jtl | Where-Object { $_.label -eq 'POST V5 Auto Seat HTTP' })
    $successRows = @($rows | Where-Object { $_.failureType -eq 'SUCCESS' -and $_.success -eq 'true' })
    $businessRows = @($rows | Where-Object { $_.failureType -eq 'BUSINESS' })
    $technicalRows = @($rows | Where-Object { $_.failureType -eq 'TECHNICAL' })
    $failedRows = @($rows | Where-Object { $_.success -ne 'true' })
    $firstRequestEpochMs = [long](($rows | ForEach-Object { [long]$_.timeStamp } | Measure-Object -Minimum).Minimum)
    $lastResponseEpochMs = [long](($rows | ForEach-Object { [long]$_.timeStamp + [long]$_.elapsed } | Measure-Object -Maximum).Maximum)
    $sampleWindowSeconds = ($lastResponseEpochMs - $firstRequestEpochMs) / 1000.0
    $successElapsed = @($successRows | ForEach-Object { [double]$_.elapsed } | Sort-Object)
    $successAverageMs = if ($successElapsed.Count -eq 0) { 0 } else {
        [Math]::Round([double](($successElapsed | Measure-Object -Average).Average), 2)
    }
    $successP95Ms = if ($successElapsed.Count -eq 0) { 0 } else {
        $successElapsed[[Math]::Min($successElapsed.Count - 1, [Math]::Ceiling($successElapsed.Count * 0.95) - 1)]
    }
    $successP99Ms = if ($successElapsed.Count -eq 0) { 0 } else {
        $successElapsed[[Math]::Min($successElapsed.Count - 1, [Math]::Ceiling($successElapsed.Count * 0.99) - 1)]
    }
    $failureBreakdown = @($failedRows | Group-Object -Property failureType,businessCode,businessMessage,responseCode | ForEach-Object {
        $first = $_.Group[0]
        [pscustomobject]@{
            failureType = $first.failureType
            code = $first.businessCode
            message = $first.businessMessage
            httpCode = $first.responseCode
            count = $_.Count
            percent = [Math]::Round(100.0 * $_.Count / $rows.Count, 2)
        }
    } | Sort-Object count -Descending)
    if ($failureBreakdown.Count -eq 0) {
        '"failureType","code","message","httpCode","count","percent"' | Set-Content -LiteralPath $breakdownPath -Encoding UTF8
    } else {
        $failureBreakdown | Export-Csv -LiteralPath $breakdownPath -NoTypeInformation -Encoding UTF8
    }

    $orderNumbers = @($successRows | ForEach-Object { $_.orderNumber } | Where-Object { $_ -match '^\d+$' } | Select-Object -Unique)
    $requestIds = @($rows | ForEach-Object { $_.requestId } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique)
    $httpSummary = [pscustomobject]@{
        testScope = 'request entry through HTTP response only'
        connectionMode = if ($Preconnect) { 'preconnected keep-alive synchronized V5 request' } else { 'new-connection synchronized burst' }
        threadRampSeconds = $threadRampSeconds
        threads = $Threads
        requests = $rows.Count
        success = $successRows.Count
        businessFailures = $businessRows.Count
        technicalFailures = $technicalRows.Count
        jmeterFailures = $failedRows.Count
        successRatePercent = [Math]::Round(100.0 * $successRows.Count / $rows.Count, 2)
        jmeterFailureRatePercent = [Math]::Round([double]$sample.errorPct, 2)
        throughputTps = [Math]::Round([double]$sample.throughput, 2)
        successfulThroughputTps = if ($sampleWindowSeconds -le 0) { 0 } else {
            [Math]::Round($successRows.Count / $sampleWindowSeconds, 2)
        }
        averageMs = [Math]::Round([double]$sample.meanResTime, 2)
        p50Ms = [Math]::Round([double]$sample.medianResTime, 2)
        p90Ms = [Math]::Round([double]$sample.pct1ResTime, 2)
        p95Ms = [Math]::Round([double]$sample.pct2ResTime, 2)
        p99Ms = [Math]::Round([double]$sample.pct3ResTime, 2)
        maxMs = [Math]::Round([double]$sample.maxResTime, 2)
        successfulAverageMs = $successAverageMs
        successfulP95Ms = $successP95Ms
        successfulP99Ms = $successP99Ms
        uniqueRequestIds = $requestIds.Count
        uniqueSuccessfulOrderNumbers = $orderNumbers.Count
        hotTicketUserCacheVerified = $true
        ticketUserRpcCallsDuringStage = $ticketUserRpcDelta
        firstHttpRequestStarted = [DateTimeOffset]::FromUnixTimeMilliseconds($firstRequestEpochMs).ToLocalTime().ToString('o')
        lastHttpResponseEnded = [DateTimeOffset]::FromUnixTimeMilliseconds($lastResponseEpochMs).ToLocalTime().ToString('o')
        failureBreakdown = $failureBreakdown
        resultDirectory = $ResultDirectory
        report = (Join-Path $report 'index.html')
    }
    $httpSummary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $summaryPath -Encoding UTF8
    $httpSummary | Format-List

    if ($rows.Count -ne $Threads) { throw "Expected $Threads JMeter samples but found $($rows.Count)." }
    if ($requestIds.Count -ne $Threads) { throw 'requestId uniqueness check failed.' }
    if ($orderNumbers.Count -ne $successRows.Count) { throw 'Successful order-number uniqueness check failed.' }
    if ($ticketUserRpcDelta -is [int] -and $ticketUserRpcDelta -ne 0) {
        throw "Hot-path invariant failed: User Service /ticket/user/list increased by $ticketUserRpcDelta call(s)."
    }

    # Recovery starts only after synchronous HTTP statistics are finalized above.
    # This wait is not included in TPS or response-time metrics.
    if ($orderNumbers.Count -gt 0) {
        $recoveryVisibilityMs = Wait-ComparisonOrdersVisible -OrderNumbers $orderNumbers `
            -TimeoutSeconds $RecoveryVisibilityTimeoutSeconds
    }
} catch {
    $stageError = $_
} finally {
    try {
        if ($locationPushed) { Pop-Location; $locationPushed = $false }
        if (-not $KeepState) {
            if ($MechanicalRecovery) {
                & (Join-Path $PSScriptRoot 'reset-v4-v5-comparison.ps1') -Mode Force -ConfirmForce
            } else {
                & (Join-Path $PSScriptRoot 'reset-v4-v5-comparison.ps1') -Mode Normal
            }
            [pscustomobject]@{
                status = 'PASS'
                knownSuccessfulOrders = if ($null -eq $httpSummary) { 0 } else { $httpSummary.uniqueSuccessfulOrderNumbers }
                asyncVisibilityWaitMs = $recoveryVisibilityMs
                note = 'Recovery timing is excluded from HTTP capacity metrics.'
            } | ConvertTo-Json | Set-Content -LiteralPath $recoveryPath -Encoding UTF8
        } else {
            Write-Warning 'State was kept. Run reset-v4-v5-comparison.ps1 -Mode Normal before another stage.'
        }
    } catch {
        if ($null -eq $stageError) { $stageError = $_ } else { Write-Error "Recovery also failed: $($_.Exception.Message)" -ErrorAction Continue }
    } finally {
        $env:JAVA_HOME = $oldJavaHome
        $env:Path = $oldPath
        if ($null -eq $oldJmLaunch) { Remove-Item Env:JM_LAUNCH -ErrorAction SilentlyContinue } else { $env:JM_LAUNCH = $oldJmLaunch }
        if ($null -eq $oldHeap) { Remove-Item Env:HEAP -ErrorAction SilentlyContinue } else { $env:HEAP = $oldHeap }
    }
}

if ($null -ne $stageError) { throw $stageError }
Write-Host "PASS: V5 HTTP stage $Threads completed; request failures (if any) were retained as capacity results; fixture restored." -ForegroundColor Green
