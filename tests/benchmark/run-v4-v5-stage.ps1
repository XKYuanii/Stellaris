param(
    [Parameter(Mandatory = $true)][ValidateSet('V4', 'V5')][string]$Version,
    [Parameter(Mandatory = $true)][ValidateRange(1, 5000)][int]$Threads,
    [string]$JMeterBin = 'G:\study\Computer\java\apache-jmeter-5.6.3\apache-jmeter-5.6.3\bin',
    [string]$JdkHome = 'C:\Users\X\.jdks\ms-17.0.17',
    [string]$JMeterHeap = '-Xms512m -Xmx3g -XX:MaxMetaspaceSize=512m',
    [string]$ResultDirectory,
    [switch]$Preconnect,
    [ValidateRange(1, 60)][int]$PreconnectRampSeconds = 5,
    [ValidateRange(100, 90000)][int]$SloThresholdMs = 3000,
    [ValidateRange(30, 900)][int]$OrderVisibilityTimeoutSeconds = 300,
    [switch]$AllowRequestFailures,
    [switch]$MechanicalRecovery,
    [switch]$SkipHtmlReport,
    [switch]$KeepState
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'V4V5Comparison.Common.ps1')

function Get-NearestRankPercentile {
    param([double[]]$Values, [ValidateRange(0.01, 1.0)][double]$Percentile)
    if ($null -eq $Values -or $Values.Count -eq 0) { return 0 }
    $sorted = @($Values | Sort-Object)
    $index = [Math]::Min($sorted.Count - 1, [Math]::Ceiling($sorted.Count * $Percentile) - 1)
    return [Math]::Round([double]$sorted[$index], 2)
}

function Get-PortProcessSnapshot {
    param([int]$Port)
    $connection = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1)
    if ($connection.Count -ne 1) { return $null }
    $process = Get-Process -Id $connection[0].OwningProcess -ErrorAction SilentlyContinue
    if ($null -eq $process) { return $null }
    return [pscustomobject]@{
        pid = $process.Id
        cpuSeconds = [Math]::Round([double]$process.CPU, 3)
        workingSetMb = [Math]::Round($process.WorkingSet64 / 1MB, 2)
        privateMemoryMb = [Math]::Round($process.PrivateMemorySize64 / 1MB, 2)
        threads = $process.Threads.Count
        handles = $process.HandleCount
    }
}

function Get-ProcessSnapshotById {
    param([int]$ProcessId)
    $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if ($null -eq $process) { return $null }
    return [pscustomobject]@{
        pid = $process.Id
        cpuSeconds = [Math]::Round([double]$process.CPU, 3)
        workingSetMb = [Math]::Round($process.WorkingSet64 / 1MB, 2)
        privateMemoryMb = [Math]::Round($process.PrivateMemorySize64 / 1MB, 2)
        threads = $process.Threads.Count
        handles = $process.HandleCount
    }
}

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$jmeter = Join-Path $JMeterBin 'jmeter.bat'
$jmx = Join-Path $PSScriptRoot 'v4-v5-comparison.jmx'
$csv = Join-Path $PSScriptRoot 'users.csv'
if (-not (Test-Path -LiteralPath $jmeter)) { throw "JMeter not found: $jmeter" }
if (-not (Test-Path -LiteralPath $jmx)) { throw "JMX not found: $jmx" }
if (@(Import-Csv -LiteralPath $csv).Count -lt $Threads) { throw "users.csv needs at least $Threads distinct users." }
if ([string]::IsNullOrWhiteSpace($ResultDirectory)) {
    $runId = Get-Date -Format 'yyyyMMdd-HHmmss'
    $ResultDirectory = Join-Path $PSScriptRoot "results\comparison-formal\$runId-$($Version.ToLowerInvariant())-$Threads-users"
}
if (Test-Path -LiteralPath $ResultDirectory) { throw "Result directory already exists: $ResultDirectory" }

& (Join-Path $PSScriptRoot 'verify-v4-v5-comparison.ps1') -Phase Ready
Assert-ComparisonTicketUserHotCache -CsvPath $csv

$report = Join-Path $ResultDirectory 'report'
$jtl = Join-Path $ResultDirectory 'result.jtl'
$log = Join-Path $ResultDirectory 'jmeter.log'
$summaryPath = Join-Path $ResultDirectory 'summary.json'
$breakdownPath = Join-Path $ResultDirectory 'failure-breakdown.csv'
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
$runError = $null
$summary = $null
$recoveryStatus = 'NOT_RUN'
$programBefore = $null
$programAfter = $null
$orderBefore = $null
$orderAfter = $null

try {
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $javaVersionOutput = @(& $env:JM_LAUNCH -version 2>&1)
        $javaExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($javaExitCode -ne 0 -or ($javaVersionOutput -join ' ') -notmatch '17\.') { throw 'This comparison requires JDK 17.' }

    $programBefore = Get-PortProcessSnapshot -Port 6086
    $orderBefore = Get-PortProcessSnapshot -Port 8081
    if ($null -eq $programBefore) { throw 'Program Service is not listening on port 6086.' }
    if ($null -eq $orderBefore) { throw 'Order Service is not listening on port 8081.' }

    Push-Location $root
    $locationPushed = $true
    try {
        $ErrorActionPreference = 'Continue'
        $preconnectValue = if ($Preconnect) { 'true' } else { 'false' }
        $threadRampSeconds = if ($Preconnect) { $PreconnectRampSeconds } else { 1 }
        & $jmeter -n -t $jmx "-Jversion=$($Version.ToLowerInvariant())" "-Jthreads=$Threads" `
            "-JdataFile=$csv" "-Jpreconnect=$preconnectValue" "-JthreadRampSeconds=$threadRampSeconds" `
            '-Jsample_variables=requestId,userId,orderNumber,businessCode,businessMessage' `
            -l $jtl -j $log
        $jmeterExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($jmeterExitCode -ne 0) { throw "JMeter exited with code $jmeterExitCode. See $log" }

    $programAfter = Get-ProcessSnapshotById -ProcessId $programBefore.pid
    $orderAfter = Get-ProcessSnapshotById -ProcessId $orderBefore.pid
    $rows = @(Import-Csv -LiteralPath $jtl | Where-Object { $_.label -eq 'POST Auto Seat Comparison' })
    if ($rows.Count -eq 0) { throw 'No formal comparison samples were recorded.' }
    $preconnectRows = @(Import-Csv -LiteralPath $jtl | Where-Object { $_.label -eq 'Preconnect Program Service' })
    $successfulPreconnectRows = @($preconnectRows | Where-Object { $_.success -eq 'true' -and $_.responseCode -eq '200' })
    $connectionPreparationStatus = if (-not $Preconnect) {
        'NOT_APPLICABLE'
    } elseif ($preconnectRows.Count -eq $Threads -and $successfulPreconnectRows.Count -eq $Threads) {
        'PASS'
    } else {
        'FAIL'
    }

    $successfulRows = @($rows | Where-Object {
        $_.success -eq 'true' -and $_.responseCode -eq '200' -and $_.businessCode -eq '0' -and $_.orderNumber -match '^\d+$'
    })
    $failedRows = @($rows | Where-Object { $successfulRows -notcontains $_ })
    $technicalRows = @($failedRows | Where-Object {
        $_.responseCode -ne '200' -or $_.businessCode -eq 'INVALID_JSON' -or $_.businessCode -eq 'NOT_PARSED'
    })
    $businessRows = @($failedRows | Where-Object { $technicalRows -notcontains $_ })
    $requestIds = @($rows | ForEach-Object { $_.requestId } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique)
    $orderNumbers = @($successfulRows | ForEach-Object { $_.orderNumber } | Select-Object -Unique)
    $firstRequestEpochMs = [long](($rows | ForEach-Object { [long]$_.timeStamp } | Measure-Object -Minimum).Minimum)
    $lastResponseEpochMs = [long](($rows | ForEach-Object { [long]$_.timeStamp + [long]$_.elapsed } | Measure-Object -Maximum).Maximum)
    $sampleWindowSeconds = ($lastResponseEpochMs - $firstRequestEpochMs) / 1000.0
    $allElapsed = @($rows | ForEach-Object { [double]$_.elapsed })
    $successfulElapsed = @($successfulRows | ForEach-Object { [double]$_.elapsed })
    $sloPassRows = @($successfulRows | Where-Object { [double]$_.elapsed -le $SloThresholdMs })

    $failureBreakdown = @($failedRows | Group-Object -Property businessCode,businessMessage,responseCode | ForEach-Object {
        $first = $_.Group[0]
        $failureType = if ($first.responseCode -ne '200' -or $first.businessCode -in @('INVALID_JSON', 'NOT_PARSED')) { 'TECHNICAL' } else { 'BUSINESS' }
        [pscustomobject]@{
            failureType = $failureType
            businessCode = $first.businessCode
            businessMessage = $first.businessMessage
            httpCode = $first.responseCode
            count = $_.Count
            percentOfAllRequests = [Math]::Round(100.0 * $_.Count / $rows.Count, 3)
        }
    } | Sort-Object count -Descending)
    if ($failureBreakdown.Count -eq 0) {
        '"failureType","businessCode","businessMessage","httpCode","count","percentOfAllRequests"' | Set-Content -LiteralPath $breakdownPath -Encoding UTF8
    } else {
        $failureBreakdown | Export-Csv -LiteralPath $breakdownPath -NoTypeInformation -Encoding UTF8
    }

    $visibilityWaitMs = 0L
    $burstToVisibleMs = 0L
    $orderVisibilityStatus = 'PASS'
    $orderVisibilityError = $null
    $visibleSuccessfulOrders = $orderNumbers.Count
    if ($orderNumbers.Count -gt 0) {
        $visibilityStartedMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        try {
            $visibilityWaitMs = Wait-ComparisonOrdersVisible -OrderNumbers $orderNumbers -TimeoutSeconds $OrderVisibilityTimeoutSeconds
        } catch {
            $orderVisibilityStatus = 'FAIL'
            $orderVisibilityError = $_.Exception.Message
            $visibilityWaitMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() - $visibilityStartedMs
            if ($orderVisibilityError -match '^Only (\d+)/(\d+) comparison orders became visible') {
                $visibleSuccessfulOrders = [int]$Matches[1]
            } else {
                $visibleSuccessfulOrders = -1
            }
        }
        $burstToVisibleMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() - $firstRequestEpochMs
    }

    $summary = [pscustomobject]@{
        schemaVersion = 5
        version = $Version
        threads = $Threads
        requests = $rows.Count
        success = $successfulRows.Count
        failed = $failedRows.Count
        businessFailures = $businessRows.Count
        technicalFailures = $technicalRows.Count
        requestSuccessRatePercent = [Math]::Round(100.0 * $successfulRows.Count / $rows.Count, 3)
        requestFailureRatePercent = [Math]::Round(100.0 * $failedRows.Count / $rows.Count, 3)
        sloThresholdMs = $SloThresholdMs
        sloPassCount = $sloPassRows.Count
        sloMissCount = $rows.Count - $sloPassRows.Count
        sloAttainmentRatePercent = [Math]::Round(100.0 * $sloPassRows.Count / $rows.Count, 3)
        totalThroughputTps = if ($sampleWindowSeconds -le 0) { 0 } else { [Math]::Round($rows.Count / $sampleWindowSeconds, 2) }
        successfulThroughputTps = if ($sampleWindowSeconds -le 0) { 0 } else { [Math]::Round($successfulRows.Count / $sampleWindowSeconds, 2) }
        averageMs = if ($allElapsed.Count -eq 0) { 0 } else { [Math]::Round([double](($allElapsed | Measure-Object -Average).Average), 2) }
        p50Ms = Get-NearestRankPercentile -Values $allElapsed -Percentile 0.50
        p95Ms = Get-NearestRankPercentile -Values $allElapsed -Percentile 0.95
        p99Ms = Get-NearestRankPercentile -Values $allElapsed -Percentile 0.99
        maxMs = if ($allElapsed.Count -eq 0) { 0 } else { [Math]::Round([double](($allElapsed | Measure-Object -Maximum).Maximum), 2) }
        successfulAverageMs = if ($successfulElapsed.Count -eq 0) { 0 } else { [Math]::Round([double](($successfulElapsed | Measure-Object -Average).Average), 2) }
        successfulP50Ms = Get-NearestRankPercentile -Values $successfulElapsed -Percentile 0.50
        successfulP95Ms = Get-NearestRankPercentile -Values $successfulElapsed -Percentile 0.95
        successfulP99Ms = Get-NearestRankPercentile -Values $successfulElapsed -Percentile 0.99
        uniqueRequestIds = $requestIds.Count
        uniqueSuccessfulOrderNumbers = $orderNumbers.Count
        connectionMode = if ($Preconnect) { 'preconnected-keep-alive' } else { 'synchronized-new-connection' }
        threadRampSeconds = if ($Preconnect) { $PreconnectRampSeconds } else { 1 }
        preconnectRequests = $preconnectRows.Count
        preconnectSuccess = $successfulPreconnectRows.Count
        preconnectFailed = $preconnectRows.Count - $successfulPreconnectRows.Count
        connectionPreparationStatus = $connectionPreparationStatus
        asyncVisibilityWaitAfterHttpMs = $visibilityWaitMs
        allOrdersVisibleObservedByMsFromBurstStart = $burstToVisibleMs
        orderVisibilityMeasurement = 'post-http polling; conservative observed-by upper bound'
        orderVisibilityStatus = $orderVisibilityStatus
        visibleSuccessfulOrders = $visibleSuccessfulOrders
        orderVisibilityError = $orderVisibilityError
        htmlReportGeneratedAfterVisibility = -not $SkipHtmlReport
        firstRequestStarted = [DateTimeOffset]::FromUnixTimeMilliseconds($firstRequestEpochMs).ToLocalTime().ToString('o')
        lastResponseEnded = [DateTimeOffset]::FromUnixTimeMilliseconds($lastResponseEpochMs).ToLocalTime().ToString('o')
        programProcessBefore = $programBefore
        programProcessAfterHttp = $programAfter
        programCpuSecondsDuringHttp = if ($null -eq $programAfter) { $null } else { [Math]::Round($programAfter.cpuSeconds - $programBefore.cpuSeconds, 3) }
        orderProcessBefore = $orderBefore
        orderProcessAfterHttp = $orderAfter
        orderCpuSecondsDuringHttp = if ($null -eq $orderAfter) { $null } else { [Math]::Round($orderAfter.cpuSeconds - $orderBefore.cpuSeconds, 3) }
        failureBreakdown = $failureBreakdown
        recoveryMode = if ($MechanicalRecovery) { 'mechanical-isolated-fixture-reset' } else { 'real-order-cancel' }
        recoveryStatus = 'PENDING'
        validForComparison = $false
        resultDirectory = $ResultDirectory
        report = if ($SkipHtmlReport) { $null } else { (Join-Path $report 'index.html') }
    }

    if ($rows.Count -ne $Threads) { throw "Expected $Threads formal samples but found $($rows.Count)." }
    if ($requestIds.Count -ne $Threads) { throw 'requestId uniqueness check failed.' }
    if ($orderNumbers.Count -ne $successfulRows.Count) { throw 'Successful order-number uniqueness check failed.' }
    if ($connectionPreparationStatus -eq 'FAIL') { throw "Keep-Alive preconnect gate failed: $($successfulPreconnectRows.Count)/$Threads succeeded." }
    if ($orderVisibilityStatus -ne 'PASS') { throw $orderVisibilityError }
    if (-not $AllowRequestFailures -and $failedRows.Count -ne 0) { throw "$Version stage had $($failedRows.Count) failed request(s)." }

    if (-not $SkipHtmlReport) {
        try {
            $ErrorActionPreference = 'Continue'
            & $jmeter -g $jtl -o $report
            $reportExitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
        if ($reportExitCode -ne 0) { throw "JMeter offline report generation exited with code $reportExitCode." }
    }
} catch {
    $runError = $_
} finally {
    try {
        if ($locationPushed) { Pop-Location; $locationPushed = $false }
        if (-not $KeepState) {
            if ($MechanicalRecovery) {
                & (Join-Path $PSScriptRoot 'reset-v4-v5-comparison.ps1') -Mode Force -ConfirmForce
            } else {
                & (Join-Path $PSScriptRoot 'reset-v4-v5-comparison.ps1') -Mode Normal
            }
            $recoveryStatus = 'PASS'
        } else {
            $recoveryStatus = 'KEPT'
            Write-Warning 'Comparison state was kept. Reset it before another run.'
        }
    } catch {
        $recoveryStatus = 'FAIL'
        if ($null -eq $runError) { $runError = $_ } else { Write-Error "Recovery also failed: $($_.Exception.Message)" -ErrorAction Continue }
    } finally {
        $env:JAVA_HOME = $oldJavaHome
        $env:Path = $oldPath
        if ($null -eq $oldJmLaunch) { Remove-Item Env:JM_LAUNCH -ErrorAction SilentlyContinue } else { $env:JM_LAUNCH = $oldJmLaunch }
        if ($null -eq $oldHeap) { Remove-Item Env:HEAP -ErrorAction SilentlyContinue } else { $env:HEAP = $oldHeap }
    }
}

if ($null -ne $summary) {
    $summary.recoveryStatus = $recoveryStatus
    $summary.validForComparison = ($recoveryStatus -eq 'PASS' -and $summary.requests -eq $Threads -and
        $summary.connectionPreparationStatus -ne 'FAIL' -and $summary.orderVisibilityStatus -eq 'PASS' -and
        $summary.uniqueRequestIds -eq $Threads -and
        $summary.uniqueSuccessfulOrderNumbers -eq $summary.success)
    $summary | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $summaryPath -Encoding UTF8
    $summary | Format-List
}
if ($null -ne $runError) { throw $runError }
Write-Host "PASS: $Version $Threads-user stage completed; recovery=$recoveryStatus." -ForegroundColor Green
