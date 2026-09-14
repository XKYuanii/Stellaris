param(
    [string]$MySqlContainer = 'stellaris-local-mysql-1',
    [string]$RedisContainer = 'stellaris-local-redis-1',
    [string]$MySqlPassword = 'mysql123',
    [string]$RedisPassword = 'redis123',
    [string]$OrderServiceBaseUrl = 'http://127.0.0.1:8081',
    [string]$ProgramServiceBaseUrl = 'http://127.0.0.1:6086',
    [ValidateRange(10, 600)][int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'StellarisV5Benchmark.Common.ps1')

Assert-StellarisContainerRunning -Name $MySqlContainer
Assert-StellarisContainerRunning -Name $RedisContainer

$programId = $script:StellarisBenchmarkProgramId
$paid = Get-StellarisBenchmarkOrderCount -Container $MySqlContainer -Password $MySqlPassword -OrderStatus 3
if ($paid -gt 0) { throw "Benchmark fixture contains $paid paid order(s); refusing automatic cleanup." }

foreach ($order in @(Get-StellarisBenchmarkOpenOrderOwners -Container $MySqlContainer -Password $MySqlPassword)) {
    $body = @{ orderNumber = "$($order.orderNumber)" } | ConvertTo-Json -Compress
    $response = Invoke-RestMethod -Method Post -Uri "$OrderServiceBaseUrl/order/cancel" -ContentType 'application/json;charset=UTF-8' -Headers @{ userId = "$($order.userId)" } -Body $body
    if ("$($response.code)" -ne '0') { throw "Cancel failed for order $($order.orderNumber)" }
}

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
do {
    $open = Get-StellarisBenchmarkOrderCount -Container $MySqlContainer -Password $MySqlPassword -OrderStatus 1
    $saleShard = $programId % 16
    $ownerKey = "stellaris:{sale:$saleShard}:program:$programId" + ':seat:owner'
    $ownersRaw = Invoke-StellarisRedisRaw -Container $RedisContainer -Password $RedisPassword -Command @('HLEN', $ownerKey) | Select-Object -First 1
    $owners = if ([string]::IsNullOrWhiteSpace("$ownersRaw")) { 0 } else { [int]$ownersRaw }
    if ($open -eq 0 -and $owners -eq 0) { break }
    Start-Sleep -Seconds 1
} while ((Get-Date) -lt $deadline)
if ($open -ne 0 -or $owners -ne 0) { throw "Cleanup did not converge: open=$open owners=$owners" }

Remove-StellarisBenchmarkOrderHistory -Container $MySqlContainer -Password $MySqlPassword
Clear-StellarisV5BenchmarkRedis -Container $RedisContainer -Password $RedisPassword
Invoke-StellarisBenchmarkPreheat -ProgramServiceBaseUrl $ProgramServiceBaseUrl

$available = Invoke-StellarisMySqlQuery -Container $MySqlContainer -Password $MySqlPassword -Query "SELECT COUNT(*) FROM stellaris_trade.t_seat_inventory WHERE program_id=$programId AND sell_status=1;" | Select-Object -First 1
[pscustomobject]@{
    programId = $programId
    tradeAvailableSeats = [int]$available
    redisOwners = 0
    orderHistoryPurged = $true
} | Format-List
