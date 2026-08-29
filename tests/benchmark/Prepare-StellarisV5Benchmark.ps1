param(
    [string]$MySqlContainer = 'stellaris-interview-mysql-1',
    [string]$RedisContainer = 'stellaris-interview-redis-1',
    [string]$MySqlPassword = 'mysql123',
    [string]$RedisPassword = 'redis123',
    [string]$ProgramServiceBaseUrl = 'http://127.0.0.1:6086'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'StellarisV5Benchmark.Common.ps1')

Assert-StellarisContainerRunning -Name $MySqlContainer
Assert-StellarisContainerRunning -Name $RedisContainer

$programId = $script:StellarisBenchmarkProgramId
$saleShard = $programId % 16
$ownerKey = "stellaris:{sale:$saleShard}:program:$programId`:seat:owner"
$reservationKey = "stellaris:{sale:$saleShard}:program:$programId`:seat:reservation"

function Get-RedisIntegerOrZero {
    param([string[]]$Command)
    $values = @(Invoke-StellarisRedisRaw -Container $RedisContainer -Password $RedisPassword -Command $Command)
    if ($values.Count -eq 0 -or [string]::IsNullOrWhiteSpace("$($values[0])")) { return 0 }
    return [int]$values[0]
}

$ownerCount = Get-RedisIntegerOrZero -Command @('HLEN', $ownerKey)
$reservationCount = Get-RedisIntegerOrZero -Command @('HLEN', $reservationKey)
$openOrderCount = Get-StellarisBenchmarkOrderCount -Container $MySqlContainer -Password $MySqlPassword -OrderStatus 1
if ($ownerCount -gt 0 -or $reservationCount -gt 0 -or $openOrderCount -gt 0) {
    throw "Benchmark has active state (owners=$ownerCount reservations=$reservationCount openOrders=$openOrderCount). Run Cleanup-StellarisV5Benchmark.ps1 first."
}

Remove-StellarisDelayCancelTasksForProgram -ProgramId $programId `
    -Container $RedisContainer -Password $RedisPassword | Out-Null

$sqlPath = Join-Path $PSScriptRoot 'prepare-v5-test.sql'
Invoke-StellarisMySqlScript -Container $MySqlContainer -Password $MySqlPassword -Path $sqlPath
Clear-StellarisV5BenchmarkRedis -Container $RedisContainer -Password $RedisPassword

$csvPath = Join-Path (Resolve-Path (Join-Path $PSScriptRoot '..\jmeter\data')).Path 'v5-benchmark-users.csv'
Export-StellarisBenchmarkUsersCsv -Path $csvPath
Invoke-StellarisBenchmarkPreheat -ProgramServiceBaseUrl $ProgramServiceBaseUrl

$seatCount = Invoke-StellarisMySqlQuery -Container $MySqlContainer -Password $MySqlPassword `
    -Query "SELECT COUNT(*) FROM stellaris_program_0.d_seat_0 WHERE program_id=$programId AND sell_status=1;" |
    Select-Object -First 1
$readyKey = "stellaris:{sale:$saleShard}:program:$programId`:seat:ready"
$ready = Invoke-StellarisRedisRaw -Container $RedisContainer -Password $RedisPassword -Command @('EXISTS', $readyKey) |
    Select-Object -First 1

[pscustomobject]@{
    programId = $programId
    categoryId = $script:StellarisBenchmarkCategoryId
    availableSeats = [int]$seatCount
    users = $script:StellarisBenchmarkUserCount
    redisReady = "$ready" -eq '1'
    csv = $csvPath
} | Format-List
