param(
    [string]$MySqlContainer = 'stellaris-interview-mysql-1',
    [string]$RedisContainer = 'stellaris-interview-redis-1',
    [string]$KafkaContainer = 'stellaris-interview-kafka-1',
    [string]$MySqlPassword = 'mysql123',
    [string]$RedisPassword = 'redis123',
    [string]$ProgramServiceBaseUrl = 'http://127.0.0.1:6086',
    [switch]$DiscardExistingTokens
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$targetCsv = Join-Path $PSScriptRoot 'users.csv'
$tokenByUser = @{}
if (-not $DiscardExistingTokens -and (Test-Path -LiteralPath $targetCsv)) {
    foreach ($row in @(Import-Csv -LiteralPath $targetCsv)) {
        if ($null -ne $row.userId -and -not [string]::IsNullOrWhiteSpace("$($row.userId)") -and
                $null -ne $row.PSObject.Properties['token']) {
            $tokenByUser["$($row.userId)"] = "$($row.token)"
        }
    }
}

& (Join-Path $PSScriptRoot 'Prepare-StellarisV5Benchmark.ps1') `
    -MySqlContainer $MySqlContainer `
    -RedisContainer $RedisContainer `
    -MySqlPassword $MySqlPassword `
    -RedisPassword $RedisPassword `
    -ProgramServiceBaseUrl $ProgramServiceBaseUrl
if ($LASTEXITCODE -ne 0) { throw 'Legacy fixture preparation failed.' }

$sourceCsv = Join-Path (Resolve-Path (Join-Path $PSScriptRoot '..\jmeter\data')).Path 'v5-benchmark-users.csv'
$sourceRows = @(Import-Csv -LiteralPath $sourceCsv)
if ($sourceRows.Count -ne 5000) {
    throw "Expected 5000 benchmark users, found $($sourceRows.Count): $sourceCsv"
}

$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add('userId,ticketUserId,token')
foreach ($row in $sourceRows) {
    $userId = "$($row.userId)"
    $ticketUserId = "$($row.ticketUserId)"
    if ($userId -notmatch '^\d{18}$' -or $ticketUserId -notmatch '^\d{18}$') {
        throw "Invalid 64-bit benchmark ID in source CSV: user=$userId ticketUser=$ticketUserId"
    }
    $token = if ($tokenByUser.ContainsKey($userId)) { "$($tokenByUser[$userId])" } else { '' }
    if ($token.Contains(',') -or $token.Contains('"') -or $token.Contains("`r") -or $token.Contains("`n")) {
        throw "Token contains unsupported CSV characters for user $userId"
    }
    $lines.Add("$userId,$ticketUserId,$token")
}
[System.IO.File]::WriteAllLines($targetCsv, $lines, [System.Text.UTF8Encoding]::new($false))

& (Join-Path $PSScriptRoot 'verify-reset.ps1') `
    -MySqlContainer $MySqlContainer `
    -RedisContainer $RedisContainer `
    -KafkaContainer $KafkaContainer `
    -MySqlPassword $MySqlPassword `
    -RedisPassword $RedisPassword

Write-Host "Prepared benchmark fixture and 5000-row CSV: $targetCsv" -ForegroundColor Green
