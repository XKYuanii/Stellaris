param(
    [int[]]$Stages = @(50, 100, 200, 300, 400, 500),
    [string]$JMeterBin = 'G:\study\Computer\java\apache-jmeter-5.6.3\apache-jmeter-5.6.3\bin',
    [string]$JdkHome = 'C:\Users\X\.jdks\ms-17.0.17',
    [switch]$Preconnect,
    [ValidateRange(1, 30)][int]$PreconnectRampSeconds = 5,
    [ValidateRange(30, 600)][int]$RecoveryVisibilityTimeoutSeconds = 180,
    [switch]$MechanicalRecovery,
    [switch]$SkipPrepare
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
foreach ($stage in $Stages) {
    if ($stage -lt 1 -or $stage -gt 5000) { throw "Stage must be between 1 and 5000: $stage" }
}
if (-not $SkipPrepare) {
    & (Join-Path $PSScriptRoot 'prepare-v4-v5-comparison.ps1') -JdkHome $JdkHome
}
& (Join-Path $PSScriptRoot 'prepare-v5-hot-cache.ps1')

$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
$ladderPrefix = if ($Preconnect) { 'preconnected-ladder' } else { 'ladder' }
$ladderDir = Join-Path $PSScriptRoot "results\http-capacity\$ladderPrefix-$runId"
New-Item -ItemType Directory -Path $ladderDir -Force | Out-Null
$aggregate = New-Object System.Collections.Generic.List[object]

foreach ($stage in $Stages) {
    Write-Host "`n===== V5 HTTP CAPACITY STAGE: $stage synchronized users =====" -ForegroundColor Cyan
    $stageDir = Join-Path $ladderDir ("{0:D4}-users" -f $stage)
    $stageParameters = @{
        Threads = $stage
        JMeterBin = $JMeterBin
        JdkHome = $JdkHome
        ResultDirectory = $stageDir
        RecoveryVisibilityTimeoutSeconds = $RecoveryVisibilityTimeoutSeconds
    }
    if ($Preconnect) {
        $stageParameters.Preconnect = $true
        $stageParameters.PreconnectRampSeconds = $PreconnectRampSeconds
    }
    if ($MechanicalRecovery) { $stageParameters.MechanicalRecovery = $true }
    & (Join-Path $PSScriptRoot 'run-v5-http-stage.ps1') @stageParameters
    $summary = Get-Content -Raw -LiteralPath (Join-Path $stageDir 'http-summary.json') | ConvertFrom-Json
    $topFailures = @($summary.failureBreakdown | Select-Object -First 3 | ForEach-Object {
        "$($_.failureType):$($_.code):$($_.message)=$($_.count)"
    }) -join ' | '
    $aggregate.Add([pscustomobject]@{
        threads = $summary.threads
        connectionMode = $summary.connectionMode
        requests = $summary.requests
        success = $summary.success
        successRatePercent = $summary.successRatePercent
        businessFailures = $summary.businessFailures
        technicalFailures = $summary.technicalFailures
        ticketUserRpcCalls = $summary.ticketUserRpcCallsDuringStage
        jmeterFailureRatePercent = $summary.jmeterFailureRatePercent
        throughputTps = $summary.throughputTps
        successfulThroughputTps = $summary.successfulThroughputTps
        averageMs = $summary.averageMs
        p95Ms = $summary.p95Ms
        p99Ms = $summary.p99Ms
        successfulAverageMs = $summary.successfulAverageMs
        successfulP95Ms = $summary.successfulP95Ms
        successfulP99Ms = $summary.successfulP99Ms
        maxMs = $summary.maxMs
        topFailures = $topFailures
        resultDirectory = $stageDir
    })
    $aggregate | Export-Csv -LiteralPath (Join-Path $ladderDir 'ladder-summary.csv') -NoTypeInformation -Encoding UTF8
    $aggregate | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $ladderDir 'ladder-summary.json') -Encoding UTF8
}

$aggregate | Format-Table threads,successRatePercent,businessFailures,technicalFailures,successfulThroughputTps,successfulAverageMs,successfulP95Ms,successfulP99Ms -AutoSize
Write-Host "PASS: ladder completed. Summary: $(Join-Path $ladderDir 'ladder-summary.csv')" -ForegroundColor Green
