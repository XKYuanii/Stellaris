param(
    [ValidateSet('Normal', 'Force')][string]$Mode = 'Normal',
    [string]$MySqlContainer = 'stellaris-interview-mysql-1',
    [string]$RedisContainer = 'stellaris-interview-redis-1',
    [string]$KafkaContainer = 'stellaris-interview-kafka-1',
    [string]$MySqlPassword = 'mysql123',
    [string]$RedisPassword = 'redis123',
    [string]$OrderServiceBaseUrl = 'http://127.0.0.1:8081',
    [string]$ProgramServiceBaseUrl = 'http://127.0.0.1:6086',
    [ValidateRange(10, 600)][int]$TimeoutSeconds = 180,
    [switch]$PurgeOrderHistory,
    [switch]$ConfirmForceReset
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'StellarisV5Benchmark.Common.ps1')

Write-Warning 'This reset is only for isolated program 900000/category 900001 and benchmark users. Never run it in production.'

if ($Mode -eq 'Normal') {
    $arguments = @{
        MySqlContainer = $MySqlContainer
        RedisContainer = $RedisContainer
        MySqlPassword = $MySqlPassword
        RedisPassword = $RedisPassword
        OrderServiceBaseUrl = $OrderServiceBaseUrl
        ProgramServiceBaseUrl = $ProgramServiceBaseUrl
        TimeoutSeconds = $TimeoutSeconds
    }
    if ($PurgeOrderHistory) { $arguments.PurgeOrderHistory = $true }
    & (Join-Path $PSScriptRoot 'Cleanup-StellarisV5Benchmark.ps1') @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Normal reset failed before verification.' }
} else {
    if (-not $ConfirmForceReset) {
        throw 'Force reset refused. Re-run with -Mode Force -ConfirmForceReset only in the isolated test environment.'
    }
    Assert-StellarisContainerRunning -Name $MySqlContainer
    Assert-StellarisContainerRunning -Name $RedisContainer

    $paidOrders = Get-StellarisBenchmarkOrderCount -Container $MySqlContainer -Password $MySqlPassword -OrderStatus 3
    if ($paidOrders -gt 0) {
        throw "Force reset refused: benchmark program contains $paidOrders paid orders. Investigate them manually."
    }

    # Force mode deliberately deletes only rows whose program_id is the reserved benchmark program.
    Remove-StellarisDelayCancelTasksForProgram -ProgramId $script:StellarisBenchmarkProgramId `
        -Container $RedisContainer -Password $RedisPassword | Out-Null
    Remove-StellarisBenchmarkOrderHistory -Container $MySqlContainer -Password $MySqlPassword
    Invoke-StellarisMySqlScript -Container $MySqlContainer -Password $MySqlPassword `
        -Path (Join-Path $PSScriptRoot 'sql\force-reset.sql')
    Clear-StellarisV5BenchmarkRedis -Container $RedisContainer -Password $RedisPassword
    Invoke-StellarisBenchmarkPreheat -ProgramServiceBaseUrl $ProgramServiceBaseUrl
    Write-Warning 'Force reset does not blindly delete the shard-shared Redis Stream. Verification will fail if Stream/PEL is not safely drained.'
}

& (Join-Path $PSScriptRoot 'verify-reset.ps1') `
    -MySqlContainer $MySqlContainer `
    -RedisContainer $RedisContainer `
    -KafkaContainer $KafkaContainer `
    -MySqlPassword $MySqlPassword `
    -RedisPassword $RedisPassword

Write-Host "$Mode reset completed and verified." -ForegroundColor Green
