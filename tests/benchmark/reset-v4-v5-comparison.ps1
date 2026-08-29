param(
    [ValidateSet('Normal', 'Force')][string]$Mode = 'Normal',
    [string]$MySqlContainer = 'stellaris-interview-mysql-1',
    [string]$RedisContainer = 'stellaris-interview-redis-1',
    [string]$KafkaContainer = 'stellaris-interview-kafka-1',
    [string]$MySqlPassword = 'mysql123',
    [string]$RedisPassword = 'redis123',
    [string]$ProgramServiceBaseUrl = 'http://127.0.0.1:6086',
    [string]$OrderServiceBaseUrl = 'http://127.0.0.1:8081',
    [ValidateRange(10, 600)][int]$TimeoutSeconds = 180,
    [switch]$ConfirmForce
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'V4V5Comparison.Common.ps1')

Write-Warning 'This reset is only for isolated program 900100/category 900101 and benchmark users. Never run it in production.'
Assert-StellarisContainerRunning -Name $MySqlContainer
Assert-StellarisContainerRunning -Name $RedisContainer
Assert-StellarisContainerRunning -Name $KafkaContainer

if ($Mode -eq 'Normal') {
    $cancelled = Stop-ComparisonOpenOrders -MySqlContainer $MySqlContainer -MySqlPassword $MySqlPassword `
        -OrderServiceBaseUrl $OrderServiceBaseUrl -TimeoutSeconds $TimeoutSeconds
    Write-Host "Real cancellation completed for $cancelled comparison order(s)."
    $removedDelayTasks = Remove-StellarisDelayCancelTasksForProgram -ProgramId $script:ComparisonProgramId `
        -Container $RedisContainer -Password $RedisPassword
    Write-Host "Removed $removedDelayTasks delayed-cancel task(s) for comparison program."
    & (Join-Path $PSScriptRoot 'verify-v4-v5-comparison.ps1') -Phase AfterCancel `
        -MySqlContainer $MySqlContainer -RedisContainer $RedisContainer -KafkaContainer $KafkaContainer `
        -MySqlPassword $MySqlPassword -RedisPassword $RedisPassword
} else {
    if (-not $ConfirmForce) { throw 'Force reset requires -ConfirmForce.' }
    if ((Get-ComparisonOrderCount -Container $MySqlContainer -Password $MySqlPassword -OrderStatus 3) -gt 0) {
        throw 'Paid comparison orders exist. Refusing force reset.'
    }
    Remove-StellarisDelayCancelTasksForProgram -ProgramId $script:ComparisonProgramId `
        -Container $RedisContainer -Password $RedisPassword | Out-Null
    Remove-ComparisonOrderHistory -Container $MySqlContainer -Password $MySqlPassword
}

Invoke-StellarisMySqlScript -Container $MySqlContainer -Password $MySqlPassword `
    -Path (Join-Path $PSScriptRoot 'sql\reset-v4-v5-comparison.sql')
Clear-ComparisonRedis -Container $RedisContainer -Password $RedisPassword
Invoke-ComparisonPreheat -ProgramServiceBaseUrl $ProgramServiceBaseUrl

& (Join-Path $PSScriptRoot 'verify-v4-v5-comparison.ps1') -Phase Ready `
    -MySqlContainer $MySqlContainer -RedisContainer $RedisContainer -KafkaContainer $KafkaContainer `
    -MySqlPassword $MySqlPassword -RedisPassword $RedisPassword
Write-Host 'PASS: comparison fixture normalized and preheated.' -ForegroundColor Green
