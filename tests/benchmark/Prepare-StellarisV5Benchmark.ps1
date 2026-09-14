param(
    [string]$MySqlContainer = 'stellaris-local-mysql-1',
    [string]$RedisContainer = 'stellaris-local-redis-1',
    [string]$MySqlPassword = 'mysql123',
    [string]$RedisPassword = 'redis123',
    [string]$ProgramServiceBaseUrl = 'http://127.0.0.1:6086'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'StellarisV5Benchmark.Common.ps1')

Assert-StellarisContainerRunning -Name $MySqlContainer
Assert-StellarisContainerRunning -Name $RedisContainer

$programId = $script:StellarisBenchmarkProgramId
$open = Get-StellarisBenchmarkOrderCount -Container $MySqlContainer -Password $MySqlPassword -OrderStatus 1
$paid = Get-StellarisBenchmarkOrderCount -Container $MySqlContainer -Password $MySqlPassword -OrderStatus 3
if ($open -gt 0 -or $paid -gt 0) {
    throw "Benchmark fixture has active or paid orders (open=$open paid=$paid). Use an isolated disposable database."
}

Remove-StellarisBenchmarkOrderHistory -Container $MySqlContainer -Password $MySqlPassword
Invoke-StellarisMySqlScript -Container $MySqlContainer -Password $MySqlPassword -Path (Join-Path $PSScriptRoot 'prepare-v5-test.sql')
Clear-StellarisV5BenchmarkRedis -Container $RedisContainer -Password $RedisPassword

$csv = Join-Path (Resolve-Path (Join-Path $PSScriptRoot '..\jmeter\data')).Path 'v5-benchmark-users.csv'
Export-StellarisBenchmarkUsersCsv -Path $csv
Invoke-StellarisBenchmarkPreheat -ProgramServiceBaseUrl $ProgramServiceBaseUrl

$categoryId = $script:StellarisBenchmarkCategoryId
$tradeSeats = Invoke-StellarisMySqlQuery -Container $MySqlContainer -Password $MySqlPassword -Query "SELECT COUNT(*) FROM stellaris_trade.t_seat_inventory WHERE program_id=$programId AND ticket_category_id=$categoryId AND sell_status=1;" | Select-Object -First 1
$saleShard = $programId % 16
$readyKey = "stellaris:{sale:$saleShard}:program:$programId" + ':seat:ready'
$ready = Invoke-StellarisRedisRaw -Container $RedisContainer -Password $RedisPassword -Command @('EXISTS', $readyKey) | Select-Object -First 1

[pscustomobject]@{
    programId = $programId
    categoryId = $categoryId
    tradeAvailableSeats = [int]$tradeSeats
    users = $script:StellarisBenchmarkUserCount
    redisReady = "$ready" -eq '1'
    csv = $csv
} | Format-List
