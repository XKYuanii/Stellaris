Set-StrictMode -Version Latest

$script:StellarisBenchmarkProgramId = 900000L
$script:StellarisBenchmarkCategoryId = 900001L
$script:StellarisBenchmarkUserBase = 910000000000000000L
$script:StellarisBenchmarkTicketUserBase = 920000000000000000L
$script:StellarisBenchmarkUserCount = 5000

function Assert-StellarisContainerRunning {
    param([Parameter(Mandatory = $true)][string]$Name)
    $status = (& docker inspect -f '{{.State.Running}}' $Name 2>$null)
    if ($LASTEXITCODE -ne 0 -or $status -ne 'true') {
        throw "Container is not running: $Name"
    }
}

function Invoke-StellarisMySqlQuery {
    param(
        [Parameter(Mandatory = $true)][string]$Container,
        [Parameter(Mandatory = $true)][string]$Password,
        [Parameter(Mandatory = $true)][string]$Query
    )
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = & docker exec $Container mysql -uroot "-p$Password" -N -e $Query 2>$null
        $mysqlExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($mysqlExitCode -ne 0) {
        throw "MySQL query failed"
    }
    return @($output)
}

function Invoke-StellarisMySqlQueryStdin {
    param(
        [Parameter(Mandatory = $true)][string]$Container,
        [Parameter(Mandatory = $true)][string]$Password,
        [Parameter(Mandatory = $true)][string]$Query
    )
    $stdoutFile = [System.IO.Path]::GetTempFileName()
    $stderrFile = [System.IO.Path]::GetTempFileName()
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $Query | & docker exec -i $Container mysql -uroot "-p$Password" -N 1> $stdoutFile 2> $stderrFile
        $mysqlExitCode = $LASTEXITCODE
        $stdout = @(Get-Content -LiteralPath $stdoutFile)
        $stderr = @(Get-Content -LiteralPath $stderrFile)
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
        Remove-Item -LiteralPath $stdoutFile, $stderrFile -Force -ErrorAction SilentlyContinue
    }
    if ($mysqlExitCode -ne 0) {
        throw "MySQL stdin query failed: $($stderr -join ' ')"
    }
    return $stdout
}

function Invoke-StellarisMySqlScript {
    param(
        [Parameter(Mandatory = $true)][string]$Container,
        [Parameter(Mandatory = $true)][string]$Password,
        [Parameter(Mandatory = $true)][string]$Path
    )
    $sql = Get-Content -Raw -LiteralPath $Path
    $stdoutFile = [System.IO.Path]::GetTempFileName()
    $stderrFile = [System.IO.Path]::GetTempFileName()
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $sql | & docker exec -i $Container mysql -uroot "-p$Password" 1> $stdoutFile 2> $stderrFile
        $mysqlExitCode = $LASTEXITCODE
        $stdout = @(Get-Content -LiteralPath $stdoutFile)
        $stderr = @(Get-Content -LiteralPath $stderrFile)
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
        Remove-Item -LiteralPath $stdoutFile, $stderrFile -Force -ErrorAction SilentlyContinue
    }
    if ($mysqlExitCode -ne 0) {
        throw "MySQL script failed: $Path; exit=$mysqlExitCode; stderr=$($stderr -join ' ')"
    }
    $stderrText = $stderr -join ' '
    $onlyPasswordNotice = $stderrText -match 'mysql:\s+\[Warning\]\s+Using a password on the command line interface can be insecure\.?' 
    if (-not [string]::IsNullOrWhiteSpace($stderrText) -and -not $onlyPasswordNotice) {
        Write-Warning "MySQL script warning: $stderrText"
    }
    return @($stdout)
}

function Invoke-StellarisRedisRaw {
    param(
        [Parameter(Mandatory = $true)][string]$Container,
        [Parameter(Mandatory = $true)][string]$Password,
        [Parameter(Mandatory = $true)][string[]]$Command
    )
    $arguments = @('exec', '-e', "REDISCLI_AUTH=$Password", $Container, 'redis-cli', '--raw') + $Command
    $output = & docker @arguments 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "Redis command failed: $($Command -join ' ')"
    }
    return @($output)
}

function Remove-StellarisRedisKeys {
    param(
        [Parameter(Mandatory = $true)][string]$Container,
        [Parameter(Mandatory = $true)][string]$Password,
        [Parameter(Mandatory = $true)][string[]]$Keys
    )
    $existing = @($Keys | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique)
    for ($offset = 0; $offset -lt $existing.Count; $offset += 100) {
        $end = [Math]::Min($existing.Count - 1, $offset + 99)
        $batch = @($existing[$offset..$end])
        $arguments = @('exec', '-e', "REDISCLI_AUTH=$Password", $Container, 'redis-cli', 'DEL') + $batch
        & docker @arguments 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to delete scoped benchmark Redis keys"
        }
    }
}

function Get-StellarisDelayCancelTaskIdsForProgram {
    param(
        [Parameter(Mandatory = $true)][long]$ProgramId,
        [string]$Container = 'stellaris-interview-redis-1',
        [string]$Password = 'redis123'
    )
    $payloadKey = 'stellaris:{delay:cancel}:payload'
    $raw = @(Invoke-StellarisRedisRaw -Container $Container -Password $Password -Command @('HGETALL', $payloadKey))
    if ($raw.Count -eq 1 -and [string]::IsNullOrWhiteSpace("$($raw[0])")) { return @() }
    if (($raw.Count % 2) -ne 0) { throw 'Delay-cancel payload HGETALL returned an odd number of values.' }
    $ids = New-Object System.Collections.Generic.List[string]
    for ($index = 0; $index -lt $raw.Count; $index += 2) {
        $taskId = "$($raw[$index])"
        $payload = "$($raw[$index + 1])"
        try {
            $task = $payload | ConvertFrom-Json
            if ("$($task.programId)" -eq "$ProgramId") { $ids.Add($taskId) }
        } catch {
            Write-Warning "Ignoring malformed shared delay-cancel payload taskId=$taskId; it was not deleted."
        }
    }
    return @($ids)
}

function Remove-StellarisDelayCancelTasksForProgram {
    param(
        [Parameter(Mandatory = $true)][long]$ProgramId,
        [string]$Container = 'stellaris-interview-redis-1',
        [string]$Password = 'redis123',
        [ValidateRange(1, 10)][int]$ConvergenceAttempts = 3
    )
    $keys = @(
        'stellaris:{delay:cancel}:pending',
        'stellaris:{delay:cancel}:processing',
        'stellaris:{delay:cancel}:payload',
        'stellaris:{delay:cancel}:attempts',
        'stellaris:{delay:cancel}:dead'
    )
    $lua = "for i=1,#ARGV do redis.call('ZREM',KEYS[1],ARGV[i]); redis.call('ZREM',KEYS[2],ARGV[i]); redis.call('HDEL',KEYS[3],ARGV[i]); redis.call('HDEL',KEYS[4],ARGV[i]); redis.call('ZREM',KEYS[5],ARGV[i]); end; return #ARGV"
    $removed = 0
    for ($attempt = 1; $attempt -le $ConvergenceAttempts; $attempt++) {
        $ids = @(Get-StellarisDelayCancelTaskIdsForProgram -ProgramId $ProgramId -Container $Container -Password $Password)
        if ($ids.Count -eq 0) { return $removed }
        for ($offset = 0; $offset -lt $ids.Count; $offset += 100) {
            $last = [Math]::Min($offset + 99, $ids.Count - 1)
            $batch = @($ids[$offset..$last])
            $command = @('EVAL', $lua, '5') + $keys + $batch
            Invoke-StellarisRedisRaw -Container $Container -Password $Password -Command $command | Out-Null
            $removed += $batch.Count
        }
        if ($attempt -lt $ConvergenceAttempts) { Start-Sleep -Milliseconds 500 }
    }
    $remaining = @(Get-StellarisDelayCancelTaskIdsForProgram -ProgramId $ProgramId -Container $Container -Password $Password).Count
    if ($remaining -ne 0) { throw "Delay-cancel cleanup did not converge for program=$ProgramId remaining=$remaining." }
    return $removed
}

function Clear-StellarisV5BenchmarkRedis {
    param(
        [Parameter(Mandatory = $true)][string]$Container,
        [Parameter(Mandatory = $true)][string]$Password
    )
    $programId = $script:StellarisBenchmarkProgramId
    $categoryId = $script:StellarisBenchmarkCategoryId
    $saleShard = $programId % 16
    $pattern = "stellaris:{sale:$saleShard}:program:$programId`:seat:*"
    $scanArgs = @('exec', '-e', "REDISCLI_AUTH=$Password", $Container, 'redis-cli', '--raw', '--scan', '--pattern', $pattern)
    $keys = @(& docker @scanArgs 2>$null)
    $keys += @(
        "program_$programId",
        "program_group_$programId",
        "program_show_time_$programId",
        "program_ticket_category_list_$programId",
        "program_record_$programId",
        "program_record_finish_$programId",
        "discard_order_$programId",
        "program_seat_no_sold_resolution_hash_${programId}_${categoryId}",
        "program_seat_lock_resolution_hash_${programId}_${categoryId}",
        "program_seat_sold_resolution_hash_${programId}_${categoryId}",
        "program_ticket_remain_number_hash_resolution_${programId}_${categoryId}"
    )
    for ($index = 0; $index -lt $script:StellarisBenchmarkUserCount; $index++) {
        $userId = $script:StellarisBenchmarkUserBase + $index
        $keys += "ticket_user_list_$userId"
        $keys += "account_order_count_${userId}_${programId}"
    }
    Remove-StellarisRedisKeys -Container $Container -Password $Password -Keys $keys
}

function Get-StellarisBenchmarkOrderNumbers {
    param(
        [Parameter(Mandatory = $true)][string]$Container,
        [Parameter(Mandatory = $true)][string]$Password,
        [int]$OrderStatus = 1
    )
    $programId = $script:StellarisBenchmarkProgramId
    $selects = [System.Collections.Generic.List[string]]::new()
    foreach ($database in 0..1) {
        foreach ($table in 0..3) {
            $selects.Add("SELECT order_number,order_status FROM stellaris_order_${database}.d_order_${table} WHERE program_id=$programId")
        }
    }
    $query = "SELECT order_number FROM ($($selects -join ' UNION ALL ')) benchmark_orders WHERE order_status=$OrderStatus;"
    return @(Invoke-StellarisMySqlQuery -Container $Container -Password $Password -Query $query |
            Where-Object { $_ -match '^\d+$' })
}

function Get-StellarisBenchmarkOpenOrderOwners {
    param(
        [Parameter(Mandatory = $true)][string]$Container,
        [Parameter(Mandatory = $true)][string]$Password
    )
    $programId = $script:StellarisBenchmarkProgramId
    $selects = [System.Collections.Generic.List[string]]::new()
    foreach ($database in 0..1) {
        foreach ($table in 0..3) {
            $selects.Add("SELECT order_number,user_id,order_status FROM stellaris_order_${database}.d_order_${table} WHERE program_id=$programId")
        }
    }
    $query = "SELECT order_number,user_id FROM ($($selects -join ' UNION ALL ')) benchmark_orders WHERE order_status=1;"
    $rows = @(Invoke-StellarisMySqlQuery -Container $Container -Password $Password -Query $query)
    return @($rows | ForEach-Object {
        $columns = "$_" -split "`t"
        if ($columns.Count -eq 2 -and $columns[0] -match '^\d+$' -and $columns[1] -match '^\d+$') {
            [pscustomobject]@{ orderNumber = $columns[0]; userId = $columns[1] }
        }
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
    $response = Invoke-RestMethod -Method Post -Uri "$ProgramServiceBaseUrl/program/data/preheat" `
        -ContentType 'application/json;charset=UTF-8' -Headers @{ no_verify = 'true' } -Body $body
    if ("$($response.code)" -ne '0') {
        throw "Benchmark preheat failed: $($response | ConvertTo-Json -Depth 10 -Compress)"
    }
}

function Export-StellarisBenchmarkUsersCsv {
    param([Parameter(Mandatory = $true)][string]$Path)
    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add('userId,ticketUserId')
    for ($index = 0; $index -lt $script:StellarisBenchmarkUserCount; $index++) {
        $lines.Add("$($script:StellarisBenchmarkUserBase + $index),$($script:StellarisBenchmarkTicketUserBase + $index)")
    }
    $directory = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $directory)) {
        New-Item -ItemType Directory -Path $directory | Out-Null
    }
    [System.IO.File]::WriteAllLines($Path, $lines, [System.Text.UTF8Encoding]::new($false))
}

function Remove-StellarisBenchmarkOrderHistory {
    param(
        [Parameter(Mandatory = $true)][string]$Container,
        [Parameter(Mandatory = $true)][string]$Password
    )
    $programId = $script:StellarisBenchmarkProgramId
    $query = "SELECT table_schema,table_name FROM information_schema.columns " +
             "WHERE table_schema IN ('stellaris_order_0','stellaris_order_1') AND column_name='program_id' " +
             "AND (table_name LIKE 'd_order%' OR table_name LIKE 'd_reservation_transition_event%') " +
             "ORDER BY table_schema,table_name;"
    $tables = @(Invoke-StellarisMySqlQuery -Container $Container -Password $Password -Query $query)
    foreach ($row in $tables) {
        $parts = $row -split "`t"
        if ($parts.Count -ne 2 -or $parts[0] -notmatch '^stellaris_order_[01]$' -or
                $parts[1] -notmatch '^(d_order|d_reservation_transition_event)') {
            throw "Unexpected order table returned while purging benchmark data: $row"
        }
        Invoke-StellarisMySqlQuery -Container $Container -Password $Password `
            -Query "DELETE FROM $($parts[0]).$($parts[1]) WHERE program_id=$programId;" | Out-Null
    }
}
