param(
    [string]$MySqlContainer = 'stellaris-interview-mysql-1',
    [string]$RedisContainer = 'stellaris-interview-redis-1',
    [string]$KafkaContainer = 'stellaris-interview-kafka-1',
    [string]$MySqlPassword = 'mysql123',
    [string]$RedisPassword = 'redis123',
    [string]$ProgramServiceBaseUrl = 'http://127.0.0.1:6086',
    [string]$RedisHost = '127.0.0.1',
    [int]$RedisPort = 6380,
    [string]$JdkHome = 'C:\Users\X\.jdks\ms-17.0.17'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'V4V5Comparison.Common.ps1')

Assert-StellarisContainerRunning -Name $MySqlContainer
Assert-StellarisContainerRunning -Name $RedisContainer
Assert-StellarisContainerRunning -Name $KafkaContainer

if ((Get-ComparisonOrderCount -Container $MySqlContainer -Password $MySqlPassword -OrderStatus 1) -gt 0) {
    throw 'Comparison program still has open orders. Run reset-v4-v5-comparison.ps1 first.'
}
if ((Get-ComparisonOrderCount -Container $MySqlContainer -Password $MySqlPassword -OrderStatus 3) -gt 0) {
    throw 'Comparison program has paid orders. Refusing to rebuild fixture.'
}

Invoke-StellarisMySqlScript -Container $MySqlContainer -Password $MySqlPassword `
    -Path (Join-Path $PSScriptRoot 'sql\prepare-v4-v5-comparison.sql')
Invoke-StellarisMySqlScript -Container $MySqlContainer -Password $MySqlPassword `
    -Path (Join-Path $PSScriptRoot 'sql\reset-v4-v5-comparison.sql')
Add-ComparisonProgramToBloomFilter -RedisHost $RedisHost -RedisPort $RedisPort `
    -RedisPassword $RedisPassword -JdkHome $JdkHome
Remove-StellarisDelayCancelTasksForProgram -ProgramId $script:ComparisonProgramId `
    -Container $RedisContainer -Password $RedisPassword | Out-Null
Clear-ComparisonRedis -Container $RedisContainer -Password $RedisPassword
Invoke-ComparisonPreheat -ProgramServiceBaseUrl $ProgramServiceBaseUrl

& (Join-Path $PSScriptRoot 'verify-v4-v5-comparison.ps1') -Phase Ready `
    -MySqlContainer $MySqlContainer -RedisContainer $RedisContainer -KafkaContainer $KafkaContainer `
    -MySqlPassword $MySqlPassword -RedisPassword $RedisPassword

Write-Host 'Prepared isolated comparison fixture: program=900100 category=900101 seats=10000.' -ForegroundColor Green
