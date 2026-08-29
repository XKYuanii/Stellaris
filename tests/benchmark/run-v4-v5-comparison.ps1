param(
    [Parameter(Mandatory = $true)][ValidateSet('V4', 'V5')][string]$Version,
    [ValidateRange(1, 1000)][int]$Threads = 10,
    [string]$JMeterBin = 'G:\study\Computer\java\apache-jmeter-5.6.3\apache-jmeter-5.6.3\bin',
    [string]$JdkHome = 'C:\Users\X\.jdks\ms-17.0.17',
    [ValidateRange(10, 300)][int]$OrderVisibilityTimeoutSeconds = 90,
    [switch]$KeepState
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'V4V5Comparison.Common.ps1')

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$jmeter = Join-Path $JMeterBin 'jmeter.bat'
$jmx = Join-Path $PSScriptRoot 'v4-v5-comparison.jmx'
$csv = Join-Path $PSScriptRoot 'users.csv'
if (-not (Test-Path -LiteralPath $jmeter)) { throw "JMeter not found: $jmeter" }
if (-not (Test-Path -LiteralPath $jmx)) { throw "JMX not found: $jmx" }
if (@(Import-Csv -LiteralPath $csv).Count -lt $Threads) { throw "users.csv needs at least $Threads users." }

& (Join-Path $PSScriptRoot 'verify-v4-v5-comparison.ps1') -Phase Ready

$versionLower = $Version.ToLowerInvariant()
$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
$runDir = Join-Path $PSScriptRoot "results\comparison\$runId-$versionLower-$Threads-users"
$report = Join-Path $runDir 'report'
$jtl = Join-Path $runDir 'result.jtl'
$log = Join-Path $runDir 'jmeter.log'
$summaryPath = Join-Path $runDir 'summary.json'
New-Item -ItemType Directory -Path $runDir -Force | Out-Null

$oldJavaHome = $env:JAVA_HOME
$oldPath = $env:Path
$oldJmLaunch = $env:JM_LAUNCH
$env:JAVA_HOME = $JdkHome
$env:Path = "$JdkHome\bin;$oldPath"
$env:JM_LAUNCH = Join-Path $JdkHome 'bin\java.exe'
$locationPushed = $false
$runError = $null
$summary = $null

try {
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $javaVersionOutput = @(& $env:JM_LAUNCH -version 2>&1)
        $javaExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($javaExitCode -ne 0 -or ($javaVersionOutput -join ' ') -notmatch '17\.') { throw 'This JMeter comparison requires JDK 17.' }

    Push-Location $root
    $locationPushed = $true
    try {
        $ErrorActionPreference = 'Continue'
        & $jmeter -n -t $jmx "-Jversion=$versionLower" "-Jthreads=$Threads" "-JdataFile=$csv" `
            '-Jsample_variables=requestId,userId,orderNumber,businessCode,businessMessage' -l $jtl -j $log -e -o $report
        $jmeterExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($jmeterExitCode -ne 0) { throw "JMeter exited with code $jmeterExitCode. See $log" }

    $stats = Get-Content -Raw -LiteralPath (Join-Path $report 'statistics.json') | ConvertFrom-Json
    $sampleProperty = $stats.PSObject.Properties['POST Auto Seat Comparison']
    if ($null -eq $sampleProperty) { throw 'JMeter report does not contain the comparison sampler.' }
    $sample = $sampleProperty.Value
    $rows = @(Import-Csv -LiteralPath $jtl | Where-Object { $_.label -eq 'POST Auto Seat Comparison' })
    $successfulRows = @($rows | Where-Object { $_.success -eq 'true' })
    $orderNumbers = @($successfulRows | ForEach-Object { $_.orderNumber } | Where-Object { $_ -match '^\d+$' } | Select-Object -Unique)
    $requestIds = @($successfulRows | ForEach-Object { $_.requestId } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique)
    $failedRows = @($rows | Where-Object { $_.success -ne 'true' })
    $failureBreakdown = @($failedRows | Group-Object -Property businessCode,businessMessage | ForEach-Object {
        $first = $_.Group[0]
        [pscustomobject]@{
            businessCode = $first.businessCode
            businessMessage = $first.businessMessage
            count = $_.Count
            percentOfAllRequests = [Math]::Round(100.0 * $_.Count / $rows.Count, 2)
        }
    } | Sort-Object count -Descending)
    $failureBreakdown | Export-Csv -LiteralPath (Join-Path $runDir 'failure-breakdown.csv') -NoTypeInformation -Encoding UTF8
    $visibilityMs = $null
    if ($orderNumbers.Count -gt 0) {
        $visibilityMs = Wait-ComparisonOrdersVisible -OrderNumbers $orderNumbers `
            -TimeoutSeconds $OrderVisibilityTimeoutSeconds
    }

    $summary = [pscustomobject]@{
        version = $Version
        users = [int]$sample.sampleCount
        success = [int]$sample.sampleCount - [int]$sample.errorCount
        failed = [int]$sample.errorCount
        jmeterRequestFailureRatePercent = [Math]::Round([double]$sample.errorPct, 3)
        actualQps = [Math]::Round([double]$sample.throughput, 2)
        averageMs = [Math]::Round([double]$sample.meanResTime, 2)
        p50Ms = [Math]::Round([double]$sample.medianResTime, 2)
        p90Ms = [Math]::Round([double]$sample.pct1ResTime, 2)
        p95Ms = [Math]::Round([double]$sample.pct2ResTime, 2)
        p99Ms = [Math]::Round([double]$sample.pct3ResTime, 2)
        maxMs = [Math]::Round([double]$sample.maxResTime, 2)
        uniqueRequestIds = $requestIds.Count
        uniqueOrderNumbers = $orderNumbers.Count
        mysqlVisibilityAfterJMeterMs = $visibilityMs
        failureBreakdown = $failureBreakdown
        resultDirectory = $runDir
        report = (Join-Path $report 'index.html')
    }
    $summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $summaryPath -Encoding UTF8
    $summary | Format-List

    if ($summary.failed -ne 0) { throw "$Version comparison had $($summary.failed) failed request(s)." }
    if ($summary.uniqueRequestIds -ne $Threads) { throw "$Version requestId uniqueness check failed." }
    if ($summary.uniqueOrderNumbers -ne $Threads) { throw "$Version order number uniqueness check failed." }
} catch {
    $runError = $_
} finally {
    try {
        if ($locationPushed) { Pop-Location; $locationPushed = $false }
        if (-not $KeepState) {
            & (Join-Path $PSScriptRoot 'reset-v4-v5-comparison.ps1') -Mode Normal
        } else {
            Write-Warning 'Comparison state was kept. Reset it before another run.'
        }
    } catch {
        if ($null -eq $runError) { $runError = $_ } else { Write-Error "Reset also failed: $($_.Exception.Message)" -ErrorAction Continue }
    } finally {
        $env:JAVA_HOME = $oldJavaHome
        $env:Path = $oldPath
        if ($null -eq $oldJmLaunch) { Remove-Item Env:JM_LAUNCH -ErrorAction SilentlyContinue } else { $env:JM_LAUNCH = $oldJmLaunch }
    }
}

if ($null -ne $runError) { throw $runError }
Write-Host "PASS: $Version $Threads-user comparison completed and fixture was restored." -ForegroundColor Green
