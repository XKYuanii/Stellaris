Set-StrictMode -Version Latest

$script:StellarisBenchmarkProgramId = 900000L
$script:StellarisBenchmarkCategoryId = 900001L
$script:StellarisBenchmarkUserBase = 910000000000000000L
$script:StellarisBenchmarkTicketUserBase = 920000000000000000L
$script:StellarisBenchmarkUserCount = 5000

function Assert-StellarisContainerRunning {
    param([Parameter(Mandatory = $true)][string]$Name)
    $status = & docker inspect -f '{{.State.Running}}' $Name 2>$null
    if ($LASTEXITCODE -ne 0 -or $status -ne 'true') { throw "Container is not running: $Name" }
}

function Invoke-StellarisMySqlQuery {
    param(
        [Parameter(Mandatory = $true)][string]$Container,
        [Parameter(Mandatory = $true)][string]$Password,
        [Parameter(Mandatory = $true)][string]$Query
    )
    $output = & docker exec $Container mysql -uroot "-p$Password" -N -e $Query 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'MySQL query failed' }
    return @($output)
}

function Invoke-StellarisMySqlScript {
    param(
        [Parameter(Mandatory = $true)][string]$Container,
        [Parameter(Mandatory = $true)][string]$Password,
        [Parameter(Mandatory = $true)][string]$Path
    )
    $sql = Get-Content -Raw -LiteralPath $Path
    $sql | & docker exec -i $Container mysql -uroot "-p$Password" 2>$null
    if ($LASTEXITCODE -ne 0) { throw "MySQL script failed: $Path" }
}

function Invoke-StellarisRedisRaw {
    param(
        [Parameter(Mandatory = $true)][string]$Container,
        [Parameter(Mandatory = $true)][string]$Password,
        [Parameter(Mandatory = $true)][string[]]$Command
    )
    $arguments = @('exec', '-e', "REDISCLI_AUTH=$Password", $Container, 'redis-cli', '--raw') + $Command
    $output = & docker @arguments 2>$null
    if ($LASTEXITCODE -ne 0) { throw "Redis command failed: $($Command -join ' ')" }
    return @($output)
}

function Remove-StellarisRedisKeys {
    param(
        [Parameter(Mandatory = $true)][string]$Container,
        [Parameter(Mandatory = $true)][string]$Password,
        [Parameter(Mandatory = $true)][string[]]$Keys
    )
    $items = @($Keys | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique)
    for ($offset = 0; $offset -lt $items.Count; $offset += 100) {
        $last = [Math]::Min($items.Count - 1, $offset + 99)
        $batch = @($items[$offset..$last])
        $arguments = @('exec', '-e', "REDISCLI_AUTH=$Password", $Container, 'redis-cli', 'DEL') + $batch
        & docker @arguments 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Failed to delete benchmark Redis keys' }
    }
}

function Clear-StellarisV5BenchmarkRedis {
    param(
        [Parameter(Mandatory = $true)][string]$Container,
        [Parameter(Mandatory = $true)][string]$Password
    )
    $programId = $script:StellarisBenchmarkProgramId
    $saleShard = $programId % 16
    $pattern = "stellaris:{sale:$saleShard}:program:$programId" + ':seat:*'
    $scanArguments = @('exec', '-e', "REDISCLI_AUTH=$Password", $Container, 'redis-cli', '--raw', '--scan', '--pattern', $pattern)
    $keys = @(& docker @scanArguments 2>$null)
    Remove-StellarisRedisKeys -Container $Container -Password $Password -Keys $keys
}

function Get-StellarisBenchmarkOrderNumbers {
    param(
        [Parameter(Mandatory = $true)][string]$Container,
        [Parameter(Mandatory = $true)][string]$Password,
        [int]$OrderStatus = 1
    )
    $programId = $script:StellarisBenchmarkProgramId
    return @(Invoke-StellarisMySqlQuery -Container $Container -Password $Password -Query "SELECT order_number FROM stellaris_trade.d_order WHERE program_id=$programId AND order_status=$OrderStatus;" | Where-Object { $_ -match '^\d+$' })
}

function Get-StellarisBenchmarkOpenOrderOwners {
    param(
        [Parameter(Mandatory = $true)][string]$Container,
        [Parameter(Mandatory = $true)][string]$Password
    )
    $programId = $script:StellarisBenchmarkProgramId
    $rows = @(Invoke-StellarisMySqlQuery -Container $Container -Password $Password -Query "SELECT order_number,user_id FROM stellaris_trade.d_order WHERE program_id=$programId AND order_status=1;")
    return @($rows | ForEach-Object {
        $columns = "$_" -split [char]9
        if ($columns.Count -eq 2) { [pscustomobject]@{ orderNumber = $columns[0]; userId = $columns[1] } }
    })
}

function Get-StellarisBenchmarkOrderCount {
    param(
        [Parameter(Mandatory = $true)][string]$Container,
        [Parameter(Mandatory = $true)][string]$Password,
        [int]$OrderStatus
    )
    return @(Get-StellarisBenchmarkOrderNumbers -Container $Container -Password $Password -OrderStatus $OrderStatus).Count
}

function Invoke-StellarisBenchmarkPreheat {
    param([string]$ProgramServiceBaseUrl = 'http://127.0.0.1:6086')
    $body = @{ programId = $script:StellarisBenchmarkProgramId } | ConvertTo-Json
    $response = Invoke-RestMethod -Method Post -Uri "$ProgramServiceBaseUrl/program/data/preheat" -ContentType 'application/json;charset=UTF-8' -Headers @{ no_verify = 'true' } -Body $body
    if ("$($response.code)" -ne '0') { throw "Benchmark preheat failed: $($response | ConvertTo-Json -Depth 10 -Compress)" }
}

function Export-StellarisBenchmarkUsersCsv {
    param([Parameter(Mandatory = $true)][string]$Path)
    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add('userId,ticketUserId')
    for ($index = 0; $index -lt $script:StellarisBenchmarkUserCount; $index++) {
        $lines.Add("$($script:StellarisBenchmarkUserBase + $index),$($script:StellarisBenchmarkTicketUserBase + $index)")
    }
    $directory = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $directory)) { New-Item -ItemType Directory -Path $directory | Out-Null }
    [System.IO.File]::WriteAllLines($Path, $lines, [System.Text.UTF8Encoding]::new($false))
}

function Remove-StellarisBenchmarkOrderHistory {
    param(
        [Parameter(Mandatory = $true)][string]$Container,
        [Parameter(Mandatory = $true)][string]$Password
    )
    $programId = $script:StellarisBenchmarkProgramId
    $sql = @"
USE stellaris_trade;
DELETE FROM d_reservation_transition_event WHERE program_id=$programId;
DELETE FROM d_order_stream_failure WHERE program_id=$programId;
DELETE FROM d_order_ticket_user WHERE program_id=$programId;
DELETE FROM d_order_program WHERE program_id=$programId;
DELETE FROM d_order WHERE program_id=$programId;
DELETE FROM t_order_request WHERE program_id=$programId;
DELETE FROM t_account_program_purchase WHERE program_id=$programId;
DELETE FROM t_seat_inventory WHERE program_id=$programId;
"@
    Invoke-StellarisMySqlQuery -Container $Container -Password $Password -Query $sql | Out-Null
}
