param(
    [string]$MySqlContainer = 'stellaris-interview-mysql-1',
    [string]$RedisContainer = 'stellaris-interview-redis-1',
    [string]$MySqlPassword = 'mysql123',
    [string]$RedisPassword = 'redis123',
    [string]$OrderServiceBaseUrl = 'http://127.0.0.1:8081',
    [string]$ProgramServiceBaseUrl = 'http://127.0.0.1:6086',
    [ValidateRange(10, 600)][int]$TimeoutSeconds = 120,
    [ValidateRange(1, 50)][int]$CancelBatchSize = 20,
    [switch]$PurgeOrderHistory
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'StellarisV5Benchmark.Common.ps1')

Assert-StellarisContainerRunning -Name $MySqlContainer
Assert-StellarisContainerRunning -Name $RedisContainer

$programId = $script:StellarisBenchmarkProgramId
$saleShard = $programId % 16
$ownerKey = "stellaris:{sale:$saleShard}:program:$programId`:seat:owner"
$seen = [System.Collections.Generic.HashSet[string]]::new()
$cancelled = 0
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)

function Get-RedisIntegerOrZero {
    param([string[]]$Command)
    $values = @(Invoke-StellarisRedisRaw -Container $RedisContainer -Password $RedisPassword -Command $Command)
    if ($values.Count -eq 0 -or [string]::IsNullOrWhiteSpace("$($values[0])")) { return 0 }
    return [int]$values[0]
}

$previousConnectionLimit = [System.Net.ServicePointManager]::DefaultConnectionLimit
[System.Net.ServicePointManager]::DefaultConnectionLimit = [Math]::Max($previousConnectionLimit, $CancelBatchSize)
$httpClient = [System.Net.Http.HttpClient]::new()
$httpClient.Timeout = [TimeSpan]::FromSeconds(30)
[void]$httpClient.DefaultRequestHeaders.TryAddWithoutValidation('no_verify', 'true')
try {
    while ((Get-Date) -lt $deadline) {
        $openOrders = @(Get-StellarisBenchmarkOrderNumbers -Container $MySqlContainer -Password $MySqlPassword -OrderStatus 1)
        $newOpenOrders = @($openOrders | Where-Object { $seen.Add("$_") })
        $failedCancels = [System.Collections.Generic.List[object]]::new()
        for ($offset = 0; $offset -lt $newOpenOrders.Count; $offset += $CancelBatchSize) {
            $last = [Math]::Min($newOpenOrders.Count - 1, $offset + $CancelBatchSize - 1)
            $pending = [System.Collections.Generic.List[object]]::new()
            foreach ($orderNumber in @($newOpenOrders[$offset..$last])) {
                $body = "{`"orderNumber`":`"$orderNumber`"}"
                $content = [System.Net.Http.StringContent]::new(
                    $body, [System.Text.Encoding]::UTF8, 'application/json')
                $pending.Add([pscustomobject]@{
                    orderNumber = "$orderNumber"
                    content = $content
                    task = $httpClient.PostAsync("$OrderServiceBaseUrl/order/cancel", $content)
                })
            }
            try {
                [System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]@($pending.task))
            } catch {
                # Inspect each task below so only the affected open order is retried.
            }
            foreach ($request in $pending) {
                try {
                    if ($request.task.IsFaulted -or $request.task.IsCanceled) {
                        $failedCancels.Add([pscustomobject]@{
                            orderNumber = $request.orderNumber
                            kind = 'transport'
                            detail = if ($request.task.Exception) { $request.task.Exception.GetBaseException().Message } else { 'request cancelled' }
                        })
                        continue
                    }
                    $httpResponse = $request.task.Result
                    try {
                        $raw = $httpResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                        $response = $raw | ConvertFrom-Json
                        $responseCode = "$($response.code)"
                        if ($responseCode -eq '0') {
                            $cancelled++
                        } elseif ([string]::IsNullOrWhiteSpace($responseCode)) {
                            $failedCancels.Add([pscustomobject]@{
                                orderNumber = $request.orderNumber
                                kind = 'transient-empty-response'
                                detail = $raw
                            })
                        } else {
                            $failedCancels.Add([pscustomobject]@{
                                orderNumber = $request.orderNumber
                                kind = 'business'
                                detail = $raw
                            })
                        }
                    } finally {
                        $httpResponse.Dispose()
                    }
                } finally {
                    $request.content.Dispose()
                }
            }
        }

        if ($failedCancels.Count -gt 0) {
            # The delayed-cancel worker may win after the initial open-order query.
            # Check MySQL once per batch instead of once per raced order.
            $stillOpenSet = [System.Collections.Generic.HashSet[string]]::new(
                [string[]]@(Get-StellarisBenchmarkOrderNumbers -Container $MySqlContainer `
                    -Password $MySqlPassword -OrderStatus 1))
            foreach ($failure in $failedCancels) {
                if (-not $stillOpenSet.Contains($failure.orderNumber)) { continue }
                if ($failure.kind -in @('transport', 'transient-empty-response')) {
                    [void]$seen.Remove($failure.orderNumber)
                    Write-Warning "Transient cancel failure for $($failure.orderNumber); it will be retried: $($failure.detail)"
                    continue
                }
                throw "Cancel failed for still-open order $($failure.orderNumber): $($failure.detail)"
            }
        }

        $ownerCount = Get-RedisIntegerOrZero -Command @('HLEN', $ownerKey)
        $remainingOpen = Get-StellarisBenchmarkOrderCount -Container $MySqlContainer -Password $MySqlPassword -OrderStatus 1
        if ($ownerCount -eq 0 -and $remainingOpen -eq 0) { break }
        Start-Sleep -Seconds 1
    }
} finally {
    $httpClient.Dispose()
    [System.Net.ServicePointManager]::DefaultConnectionLimit = $previousConnectionLimit
}

$finalOwners = Get-RedisIntegerOrZero -Command @('HLEN', $ownerKey)
$finalOpenOrders = Get-StellarisBenchmarkOrderCount -Container $MySqlContainer -Password $MySqlPassword -OrderStatus 1
if ($finalOwners -ne 0 -or $finalOpenOrders -ne 0) {
    throw "Benchmark cleanup did not converge (owners=$finalOwners openOrders=$finalOpenOrders)."
}
$paidOrders = Get-StellarisBenchmarkOrderCount -Container $MySqlContainer -Password $MySqlPassword -OrderStatus 3
if ($paidOrders -gt 0) {
    throw "Benchmark contains $paidOrders paid orders. Refusing to reset sold seats or purge history."
}

$removedDelayTasks = Remove-StellarisDelayCancelTasksForProgram -ProgramId $programId `
    -Container $RedisContainer -Password $RedisPassword
Write-Host "Removed $removedDelayTasks delayed-cancel task(s) for benchmark program $programId."

Invoke-StellarisMySqlScript -Container $MySqlContainer -Password $MySqlPassword `
    -Path (Join-Path $PSScriptRoot 'reset-v5-test.sql')
if ($PurgeOrderHistory) {
    Remove-StellarisBenchmarkOrderHistory -Container $MySqlContainer -Password $MySqlPassword
}
Clear-StellarisV5BenchmarkRedis -Container $RedisContainer -Password $RedisPassword
Invoke-StellarisBenchmarkPreheat -ProgramServiceBaseUrl $ProgramServiceBaseUrl

$availableSeats = Invoke-StellarisMySqlQuery -Container $MySqlContainer -Password $MySqlPassword `
    -Query "SELECT COUNT(*) FROM stellaris_program_0.d_seat_0 WHERE program_id=$programId AND sell_status=1 AND reservation_id IS NULL;" |
    Select-Object -First 1

[pscustomobject]@{
    programId = $programId
    cancelledOrders = $cancelled
    purgedOrderHistory = [bool]$PurgeOrderHistory
    availableSeats = [int]$availableSeats
    redisOwners = 0
} | Format-List
