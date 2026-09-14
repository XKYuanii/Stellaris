$ErrorActionPreference = 'Stop'

$dockerServer = docker version --format '{{json .Server}}' | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or $null -eq $dockerServer) {
    throw 'Docker is required for the trade database integration test.'
}

$dockerApiVersion = $dockerServer.MinAPIVersion
if ([string]::IsNullOrWhiteSpace($dockerApiVersion)) {
    $dockerApiVersion = $dockerServer.ApiVersion
}

$mavenArguments = @(
    '-pl', 'stellaris-server/stellaris-order-service',
    '-am',
    '-Dtest=TradeDatabaseConcurrencyIntegrationTest',
    '-Dsurefire.failIfNoSpecifiedTests=false',
    "-Dapi.version=$dockerApiVersion",
    'test'
)

& mvn @mavenArguments
if ($LASTEXITCODE -ne 0) {
    throw "Trade database integration test failed with exit code $LASTEXITCODE."
}
