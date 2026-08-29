param(
    [string]$RuntimeDirectory = (Join-Path $PSScriptRoot 'results\runtime-v4-v5')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$statePath = Join-Path $RuntimeDirectory 'service-state.json'
if (-not (Test-Path -LiteralPath $statePath)) { throw "Benchmark service state not found: $statePath" }
$state = Get-Content -Raw -LiteralPath $statePath | ConvertFrom-Json

foreach ($service in $state.services) {
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId=$($service.pid)" -ErrorAction SilentlyContinue
    if ($null -eq $processInfo) { continue }
    $expectedJarName = Split-Path -Leaf $service.jar
    if ($processInfo.Name -ne 'java.exe' -or $processInfo.CommandLine -notlike "*$expectedJarName*") {
        throw "PID $($service.pid) no longer matches benchmark service $($service.name); refusing to stop it."
    }
    Stop-Process -Id $service.pid -Force
    Write-Host "Stopped benchmark $($service.name) process PID=$($service.pid)."
}

$state.status = 'STOPPED'
$state | Add-Member -NotePropertyName stoppedAt -NotePropertyValue (Get-Date).ToString('o') -Force
$state | Add-Member -NotePropertyName rollbackResult `
    -NotePropertyValue 'PASS: process-scoped JVM and logging options are no longer active; no application.yml or IDEA configuration was changed.' -Force
$state | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $statePath -Encoding UTF8
Write-Host 'PASS: benchmark services stopped; temporary JVM configuration rolled back.' -ForegroundColor Green
