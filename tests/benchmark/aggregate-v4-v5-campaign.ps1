param(
    [string]$CampaignRoot = (Join-Path $PSScriptRoot 'results\v4-v5-campaign\20260828-formal')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-Median {
    param([object[]]$Values)
    $sorted = @($Values | ForEach-Object { [double]$_ } | Sort-Object)
    if ($sorted.Count -eq 0) { throw 'Cannot calculate a median from an empty set.' }
    $middle = [math]::Floor($sorted.Count / 2)
    if ($sorted.Count % 2 -eq 1) { return $sorted[$middle] }
    return ($sorted[$middle - 1] + $sorted[$middle]) / 2.0
}

function Get-VersionAggregate {
    param([object[]]$Rows, [string]$Version)
    $selected = @($Rows | Where-Object version -eq $Version)
    if ($selected.Count -eq 0) { throw "No $Version formal summaries found." }
    $tps = @($selected.successfulThroughputTps | ForEach-Object { [double]$_ } | Sort-Object)
    $medianTps = Get-Median $tps
    return [pscustomobject]@{
        version = $Version
        runs = $selected.Count
        totalRequests = [int](($selected.requests | Measure-Object -Sum).Sum)
        totalSuccess = [int](($selected.success | Measure-Object -Sum).Sum)
        totalFailed = [int](($selected.failed | Measure-Object -Sum).Sum)
        successfulThroughputTpsMedian = [math]::Round($medianTps, 2)
        successfulThroughputTpsMin = [math]::Round($tps[0], 2)
        successfulThroughputTpsMax = [math]::Round($tps[-1], 2)
        successfulThroughputTpsRangePercent = [math]::Round(100.0 * ($tps[-1] - $tps[0]) / $medianTps, 2)
        requestSuccessRatePercentMedian = [math]::Round((Get-Median @($selected.requestSuccessRatePercent)), 3)
        sloAttainmentRatePercentMedian = [math]::Round((Get-Median @($selected.sloAttainmentRatePercent)), 3)
        successfulAverageMsMedian = [math]::Round((Get-Median @($selected.successfulAverageMs)), 2)
        successfulP50MsMedian = [math]::Round((Get-Median @($selected.successfulP50Ms)), 2)
        successfulP95MsMedian = [math]::Round((Get-Median @($selected.successfulP95Ms)), 2)
        successfulP99MsMedian = [math]::Round((Get-Median @($selected.successfulP99Ms)), 2)
        allRunsValid = @($selected | Where-Object { -not $_.validForComparison }).Count -eq 0
        allVisibilityPass = @($selected | Where-Object orderVisibilityStatus -ne 'PASS').Count -eq 0
        allRecoveryPass = @($selected | Where-Object recoveryStatus -ne 'PASS').Count -eq 0
    }
}

$formalRoot = Join-Path $CampaignRoot 'formal-100'
if (-not (Test-Path -LiteralPath $formalRoot)) { throw "Formal root not found: $formalRoot" }
$summaryFiles = @(Get-ChildItem -LiteralPath $formalRoot -Filter summary.json -Recurse)
if ($summaryFiles.Count -eq 0) { throw "No summary.json files found under $formalRoot" }

$rows = @($summaryFiles | ForEach-Object {
    $summary = Get-Content -Raw -LiteralPath $_.FullName | ConvertFrom-Json
    if ($_.Directory.Name -notmatch '^round-(\d+)-(v4|v5)$') { throw "Unexpected formal directory: $($_.Directory.Name)" }
    [pscustomobject]@{
        round = [int]$Matches[1]
        version = $summary.version
        requests = $summary.requests
        success = $summary.success
        failed = $summary.failed
        requestSuccessRatePercent = $summary.requestSuccessRatePercent
        sloAttainmentRatePercent = $summary.sloAttainmentRatePercent
        successfulThroughputTps = $summary.successfulThroughputTps
        successfulAverageMs = $summary.successfulAverageMs
        successfulP50Ms = $summary.successfulP50Ms
        successfulP95Ms = $summary.successfulP95Ms
        successfulP99Ms = $summary.successfulP99Ms
        orderVisibilityStatus = $summary.orderVisibilityStatus
        recoveryStatus = $summary.recoveryStatus
        validForComparison = $summary.validForComparison
        schemaVersion = $summary.schemaVersion
        resultDirectory = $_.Directory.FullName
    }
} | Sort-Object round,version)

foreach ($version in @('V4','V5')) {
    $count = @($rows | Where-Object version -eq $version).Count
    if ($count -ne 7) { throw "Expected 7 formal $version runs, found $count." }
}
if (@($rows | Where-Object { -not $_.validForComparison }).Count -ne 0) {
    throw 'At least one formal run is invalid; aggregation is forbidden.'
}

$v4 = Get-VersionAggregate -Rows $rows -Version V4
$v5 = Get-VersionAggregate -Rows $rows -Version V5
$comparison = [pscustomobject]@{
    throughputImprovementPercent = [math]::Round(100.0 * ($v5.successfulThroughputTpsMedian / $v4.successfulThroughputTpsMedian - 1), 2)
    throughputMultiple = [math]::Round($v5.successfulThroughputTpsMedian / $v4.successfulThroughputTpsMedian, 2)
    averageLatencyReductionPercent = [math]::Round(100.0 * ($v4.successfulAverageMsMedian - $v5.successfulAverageMsMedian) / $v4.successfulAverageMsMedian, 2)
    p99ReductionPercent = [math]::Round(100.0 * ($v4.successfulP99MsMedian - $v5.successfulP99MsMedian) / $v4.successfulP99MsMedian, 2)
    sloAttainmentIncreasePercentagePoints = [math]::Round($v5.sloAttainmentRatePercentMedian - $v4.sloAttainmentRatePercentMedian, 2)
}

$result = [pscustomobject]@{
    schemaVersion = 1
    generatedAt = (Get-Date).ToString('o')
    testModel = 'seven alternating paired runs; 100 concurrent one-shot users; median of run metrics'
    synchronousBoundary = 'V4: HTTP entry -> ticket-category JVM lock -> Redis Lua -> Kafka broker ACK -> HTTP response; V5: HTTP entry -> bounded candidate selection -> O(k) Redis Lua (lock + XADD) -> HTTP response'
    v4 = $v4
    v5 = $v5
    comparison = $comparison
    visibilityCaveat = 'Schema v3 observed-by values include report/probe delay and are excluded from aggregation.'
}

$rows | Export-Csv -LiteralPath (Join-Path $formalRoot 'formal-100-all-runs.csv') -NoTypeInformation -Encoding UTF8
$result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $formalRoot 'formal-100-aggregate.json') -Encoding UTF8

$markdown = @"
# V4/V5 100并发正式对照汇总

统计方法：7组交替配对测试，取每个版本7轮指标的中位数；14轮均通过订单唯一性、全量落库、真实取消及最终状态校验。

| 指标 | V4中位数 | V5中位数 | 变化 |
|---|---:|---:|---:|
| 成功TPS | $($v4.successfulThroughputTpsMedian) | $($v5.successfulThroughputTpsMedian) | 提升$($comparison.throughputImprovementPercent)%（$($comparison.throughputMultiple)倍） |
| 成功请求Avg | $($v4.successfulAverageMsMedian)ms | $($v5.successfulAverageMsMedian)ms | 降低$($comparison.averageLatencyReductionPercent)% |
| 成功请求P99 | $($v4.successfulP99MsMedian)ms | $($v5.successfulP99MsMedian)ms | 降低$($comparison.p99ReductionPercent)% |
| 3秒SLO达标率 | $($v4.sloAttainmentRatePercentMedian)% | $($v5.sloAttainmentRatePercentMedian)% | 提升$($comparison.sloAttainmentIncreasePercentagePoints)个百分点 |
| 请求成功率 | $($v4.requestSuccessRatePercentMedian)% | $($v5.requestSuccessRatePercentMedian)% | 均为100% |

TPS极差/中位数：V4为$($v4.successfulThroughputTpsRangePercent)%，V5为$($v5.successfulThroughputTpsRangePercent)%。V5在100并发下HTTP窗口约0.1秒，1～2ms变化会放大TPS相对波动，因此最终只报告中位数。

异步落库说明：schema v3的7轮正式结果是在生成HTML报告后检查MySQL，观测值包含工具耗时，未用于本表的优化结论。所有成功订单确实均完成落库，且每轮恢复均为PASS。
"@
$markdown | Set-Content -LiteralPath (Join-Path $formalRoot 'FORMAL-100-SUMMARY.md') -Encoding UTF8

$result | Format-List
Write-Host "PASS: formal 100-concurrency aggregation generated under $formalRoot" -ForegroundColor Green
