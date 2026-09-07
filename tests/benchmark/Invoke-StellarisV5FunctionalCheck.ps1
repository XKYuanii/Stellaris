param(
    [string]$JMeterBin = 'G:\study\Computer\java\apache-jmeter-5.6.3\apache-jmeter-5.6.3\bin',
    [string]$Jdk17 = 'C:\Users\X\.jdks\ms-17.0.17',
    [switch]$KeepOrders
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'StellarisV5Benchmark.Common.ps1')

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$jmeter = Join-Path $JMeterBin 'jmeter.bat'
$jmx = Join-Path $repositoryRoot 'tests\jmeter\v5-create.jmx'
$dataFile = Join-Path $repositoryRoot 'tests\jmeter\data\v5-benchmark-users.csv'
if (-not (Test-Path -LiteralPath $jmeter)) { throw "JMeter not found: $jmeter" }
if (-not (Test-Path -LiteralPath $dataFile)) { throw "Run Prepare-StellarisV5Benchmark.ps1 first" }

$env:JAVA_HOME = $Jdk17
$env:Path = "$Jdk17\bin;$env:Path"
if ((& java -version 2>&1 | Select-Object -First 1) -notmatch '17\.') {
    throw "JMeter functional check requires JDK 17"
}

$scenarios = @(
    [pscustomobject]@{ name = 'single'; threads = 1 },
    [pscustomobject]@{ name = 'parallel-3'; threads = 3 },
    [pscustomobject]@{ name = 'parallel-10'; threads = 10 }
)
$results = [System.Collections.Generic.List[object]]::new()

Push-Location $repositoryRoot
try {
    foreach ($scenario in $scenarios) {
        $runId = Get-Date -Format 'yyyyMMdd-HHmmssfff'
        $jtl = Join-Path $repositoryRoot "output\jmeter\functional-$($scenario.name)-$runId.jtl"
        $log = Join-Path $repositoryRoot "output\jmeter\functional-$($scenario.name)-$runId.log"
        $report = Join-Path $repositoryRoot "output\jmeter\functional-$($scenario.name)-$runId-html"
        # The functional check exercises the public boundary. Start Gateway with
        # STELLARIS_ALLOW_NORMAL_ACCESS=true only in this isolated local environment.
        & $jmeter -n -t $jmx '-Jhost=127.0.0.1' '-Jport=6085' '-Jpath=/stellaris/program/program/order/create/v5' `
            '-JprogramId=900000' '-JticketCategoryId=900001' "-JdataFile=$dataFile" `
            "-Jthreads=$($scenario.threads)" '-Jloops=1' '-Jramp=1' -l $jtl -j $log -e -o $report
        if ($LASTEXITCODE -ne 0) { throw "JMeter scenario failed to execute: $($scenario.name)" }
        $rows = @(Import-Csv -LiteralPath $jtl)
        $success = @($rows | Where-Object success -eq 'true').Count
        $result = [pscustomobject]@{
            scenario = $scenario.name
            samples = $rows.Count
            success = $success
            errors = $rows.Count - $success
            report = Join-Path $report 'index.html'
        }
        $results.Add($result)
        if ($result.errors -ne 0) {
            $failures = $rows | Where-Object success -ne 'true' | Select-Object -ExpandProperty failureMessage
            throw "Functional scenario $($scenario.name) has business failures: $($failures -join '; ')"
        }
    }
} finally {
    Pop-Location
}

if (-not $KeepOrders) {
    & (Join-Path $PSScriptRoot 'Cleanup-StellarisV5Benchmark.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'Functional orders were created but cleanup failed' }
}

$results | Format-Table -AutoSize
