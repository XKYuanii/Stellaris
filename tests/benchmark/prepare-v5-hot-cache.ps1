param(
    [string]$RedisContainer = 'stellaris-interview-redis-1',
    [string]$RedisPassword = 'redis123',
    [string]$CsvPath = (Join-Path $PSScriptRoot 'users.csv')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'V4V5Comparison.Common.ps1')

Assert-StellarisContainerRunning -Name $RedisContainer
Initialize-ComparisonTicketUserHotCache -CsvPath $CsvPath `
    -Container $RedisContainer -Password $RedisPassword

Write-Host 'Prepared V5 hot-path ticket-user cache without changing normal business data.' -ForegroundColor Green
