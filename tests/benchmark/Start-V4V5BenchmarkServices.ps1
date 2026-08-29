param(
    [string]$JdkHome = 'C:\Users\X\.jdks\ms-17.0.17',
    [string]$InitialHeap = '256m',
    [string]$BaseDataMaxHeap = '512m',
    [string]$ProgramMaxHeap = '768m',
    [string]$OrderMaxHeap = '768m',
    [string]$MaxMetaspace = '384m',
    [string]$RuntimeDirectory = (Join-Path $PSScriptRoot 'results\runtime-v4-v5'),
    [ValidateRange(30, 300)][int]$StartupTimeoutSeconds = 180
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Wait-ServiceHealth {
    param([string]$Name, [string]$Uri, [int]$ProcessId, [int]$TimeoutSeconds)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if ($null -eq (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) {
            throw "$Name process $ProcessId exited before becoming healthy."
        }
        try {
            $health = Invoke-RestMethod -Method Get -Uri $Uri -TimeoutSec 3
            if ("$($health.status)" -eq 'UP') { return }
        } catch {
            Start-Sleep -Seconds 2
        }
    } while ((Get-Date) -lt $deadline)
    throw "$Name did not become healthy before ${TimeoutSeconds}s: $Uri"
}

function Get-EnvironmentSnapshot {
    $os = Get-CimInstance Win32_OperatingSystem
    $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
    return [pscustomobject]@{
        capturedAt = (Get-Date).ToString('o')
        os = $os.Caption
        osVersion = $os.Version
        totalRamGb = [Math]::Round($os.TotalVisibleMemorySize / 1MB, 2)
        freeRamGb = [Math]::Round($os.FreePhysicalMemory / 1MB, 2)
        cpu = $cpu.Name
        physicalCores = $cpu.NumberOfCores
        logicalProcessors = $cpu.NumberOfLogicalProcessors
        docker = @(& docker ps --format '{{.Names}}|{{.Status}}')
    }
}

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$java = Join-Path $JdkHome 'bin\java.exe'
$jcmd = Join-Path $JdkHome 'bin\jcmd.exe'
$baseDataJar = Join-Path $root 'stellaris-server\stellaris-base-data-service\target\stellaris-base-data-service-0.0.1-SNAPSHOT.jar'
$programJar = Join-Path $root 'stellaris-server\stellaris-program-service\target\stellaris-program-service-0.0.1-SNAPSHOT.jar'
$orderJar = Join-Path $root 'stellaris-server\stellaris-order-service\target\stellaris-order-service-0.0.1-SNAPSHOT.jar'
foreach ($path in @($java, $jcmd, $baseDataJar, $programJar, $orderJar)) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Required benchmark runtime file not found: $path" }
}
foreach ($port in @(6083, 6086, 8081)) {
    if (@(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue).Count -gt 0) {
        throw "Port $port is already in use. Refusing to replace an existing service."
    }
}

New-Item -ItemType Directory -Path $RuntimeDirectory -Force | Out-Null
$statePath = Join-Path $RuntimeDirectory 'service-state.json'
if (Test-Path -LiteralPath $statePath) {
    $previousState = Get-Content -Raw -LiteralPath $statePath | ConvertFrom-Json
    $live = @($previousState.services | Where-Object { $null -ne (Get-Process -Id $_.pid -ErrorAction SilentlyContinue) })
    if ($live.Count -gt 0) { throw "Benchmark service state already contains live process(es): $($live.pid -join ',')" }
}

$environmentBefore = Get-EnvironmentSnapshot
$environmentBefore | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $RuntimeDirectory 'environment-before-start.json') -Encoding UTF8
$started = New-Object System.Collections.Generic.List[object]

try {
    $serviceDefinitions = @(
        [pscustomobject]@{ name = 'base-data'; jar = $baseDataJar; port = 6083; maxHeap = $BaseDataMaxHeap },
        [pscustomobject]@{ name = 'order'; jar = $orderJar; port = 8081; maxHeap = $OrderMaxHeap },
        [pscustomobject]@{ name = 'program'; jar = $programJar; port = 6086; maxHeap = $ProgramMaxHeap }
    )
    foreach ($service in $serviceDefinitions) {
        $stdout = Join-Path $RuntimeDirectory "$($service.name)-stdout.log"
        $stderr = Join-Path $RuntimeDirectory "$($service.name)-stderr.log"
        $gcPath = (Join-Path $RuntimeDirectory "$($service.name)-gc.log").Replace('\', '/')
        $gcArgument = '-Xlog:gc*,safepoint:file={0}:time,uptime,level,tags:filecount=5,filesize=20M' -f $gcPath
        $arguments = @(
            "-Xms$InitialHeap",
            "-Xmx$($service.maxHeap)",
            "-XX:MaxMetaspaceSize=$MaxMetaspace",
            '-XX:+UseG1GC',
            '-XX:+HeapDumpOnOutOfMemoryError',
            "-XX:HeapDumpPath=$RuntimeDirectory",
            $gcArgument,
            '-Dfile.encoding=UTF-8',
            '-Dspring.output.ansi.enabled=never',
            '-jar',
            $service.jar,
            '--logging.level.root=WARN',
            '--logging.level.com.stellaris=WARN',
            '--spring.main.banner-mode=off'
        )
        $process = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $root `
            -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WindowStyle Hidden -PassThru
        $started.Add([pscustomobject]@{
            name = $service.name
            pid = $process.Id
            port = $service.port
            jar = $service.jar
            jarSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $service.jar).Hash
            java = $java
            arguments = $arguments
            stdout = $stdout
            stderr = $stderr
            gcLog = $gcPath
            startedAt = (Get-Date).ToString('o')
        })
        Wait-ServiceHealth -Name $service.name -Uri "http://127.0.0.1:$($service.port)/actuator/health" `
            -ProcessId $process.Id -TimeoutSeconds $StartupTimeoutSeconds
    }

    foreach ($service in $started) {
        @(& $jcmd $service.pid VM.command_line 2>&1) | Set-Content -LiteralPath (Join-Path $RuntimeDirectory "$($service.name)-jvm-command-line.txt") -Encoding UTF8
        @(& $jcmd $service.pid VM.flags 2>&1) | Set-Content -LiteralPath (Join-Path $RuntimeDirectory "$($service.name)-jvm-flags.txt") -Encoding UTF8
        @(& $jcmd $service.pid GC.heap_info 2>&1) | Set-Content -LiteralPath (Join-Path $RuntimeDirectory "$($service.name)-heap-after-start.txt") -Encoding UTF8
    }

    $state = [pscustomobject]@{
        schemaVersion = 1
        purpose = 'isolated reversible V4/V5 benchmark runtime'
        persistentApplicationConfigChanged = $false
        rollback = 'Run tests/benchmark/Stop-V4V5BenchmarkServices.ps1; all JVM/log-level options are process-scoped.'
        runtimeDirectory = $RuntimeDirectory
        services = @($started | ForEach-Object { $_ })
        status = 'RUNNING'
        createdAt = (Get-Date).ToString('o')
    }
    $state | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $statePath -Encoding UTF8
    $state | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $RuntimeDirectory 'configuration-manifest.json') -Encoding UTF8
    Get-EnvironmentSnapshot | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $RuntimeDirectory 'environment-after-start.json') -Encoding UTF8
    $started | Select-Object name,pid,port,jarSha256 | Format-Table -AutoSize
    Write-Host "PASS: BaseData, Program and Order benchmark services are healthy. State: $statePath" -ForegroundColor Green
} catch {
    foreach ($service in $started) {
        $process = Get-Process -Id $service.pid -ErrorAction SilentlyContinue
        if ($null -ne $process) { Stop-Process -Id $service.pid -Force -ErrorAction SilentlyContinue }
    }
    throw
}
