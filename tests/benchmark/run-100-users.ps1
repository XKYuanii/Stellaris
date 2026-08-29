param(
    [string]$JMeterBin = 'G:\study\Computer\java\apache-jmeter-5.6.3\apache-jmeter-5.6.3\bin',
    [string]$JdkHome = 'C:\Users\X\.jdks\ms-17.0.17',
    [switch]$KeepOrders
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$jmeter = Join-Path $JMeterBin 'jmeter.bat'
$jmx = Join-Path $PSScriptRoot 'v5-100-users.jmx'
$csv = Join-Path $PSScriptRoot 'users.csv'
if (-not (Test-Path $jmeter)) { throw "JMeter not found: $jmeter" }
if (@(Import-Csv $csv).Count -lt 100) { throw 'users.csv needs at least 100 users. Run prepare-benchmark.ps1 first.' }

& (Join-Path $PSScriptRoot 'verify-reset.ps1')

$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
$runDir = Join-Path $PSScriptRoot "results\simple-100-users-$runId"
$report = Join-Path $runDir 'report'
$jtl = Join-Path $runDir 'result.jtl'
$log = Join-Path $runDir 'jmeter.log'
New-Item -ItemType Directory -Path $runDir | Out-Null

$oldJavaHome = $env:JAVA_HOME
$oldPath = $env:Path
$oldJmLaunch = $env:JM_LAUNCH
$env:JAVA_HOME = $JdkHome
$env:Path = "$JdkHome\bin;$oldPath"
$env:JM_LAUNCH = Join-Path $JdkHome 'bin\java.exe'

$locationPushed = $false
try {
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $javaVersionOutput = @(& $env:JM_LAUNCH -version 2>&1)
        $javaExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($javaExitCode -ne 0 -or ($javaVersionOutput -join ' ') -notmatch '17\.') { throw 'This JMeter test requires JDK 17.' }
    Push-Location $root
    $locationPushed = $true
    try {
        $ErrorActionPreference = 'Continue'
        & $jmeter -n -t $jmx "-JdataFile=$csv" '-Jsample_variables=requestId,userId,orderNumber,businessCode,businessMessage' `
            -l $jtl -j $log -e -o $report
        $jmeterExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($jmeterExitCode -ne 0) { throw "JMeter exited with code $jmeterExitCode" }

    $stats = Get-Content -Raw -LiteralPath (Join-Path $report 'statistics.json') | ConvertFrom-Json
    $sample = $stats.PSObject.Properties['POST V5 Auto Seat'].Value
    $rows = @(Import-Csv -LiteralPath $jtl | Where-Object { $_.label -eq 'POST V5 Auto Seat' })
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
    [pscustomobject]@{
        users = $sample.sampleCount
        success = $sample.sampleCount - $sample.errorCount
        failed = $sample.errorCount
        jmeterRequestFailureRatePercent = [Math]::Round($sample.errorPct, 3)
        actualQps = [Math]::Round($sample.throughput, 2)
        averageMs = [Math]::Round($sample.meanResTime, 2)
        p95Ms = $sample.pct2ResTime
        p99Ms = $sample.pct3ResTime
        maxMs = $sample.maxResTime
        failureBreakdown = $failureBreakdown
        report = Join-Path $report 'index.html'
    } | Format-List
} finally {
    try {
        if ($locationPushed) { Pop-Location }
        if (-not $KeepOrders) {
            & (Join-Path $PSScriptRoot 'reset-benchmark.ps1') -Mode Normal
        } else {
            Write-Warning 'Orders were kept. Run reset-benchmark.ps1 -Mode Normal before the next test.'
        }
    } finally {
        $env:JAVA_HOME = $oldJavaHome
        $env:Path = $oldPath
        if ($null -eq $oldJmLaunch) {
            Remove-Item Env:JM_LAUNCH -ErrorAction SilentlyContinue
        } else {
            $env:JM_LAUNCH = $oldJmLaunch
        }
    }
}
