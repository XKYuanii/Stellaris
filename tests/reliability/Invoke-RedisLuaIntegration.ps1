$ErrorActionPreference = 'Stop'

$dockerServer = docker version --format '{{json .Server}}' | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or $null -eq $dockerServer) {
    throw 'Docker is required for the Redis Lua integration test.'
}

# Docker Engine 29 rejects the old API default used by Testcontainers 1.19.x.
# Selecting the server's advertised minimum stays compatible with both old and new engines.
$dockerApiVersion = $dockerServer.MinAPIVersion
if ([string]::IsNullOrWhiteSpace($dockerApiVersion)) {
    $dockerApiVersion = $dockerServer.ApiVersion
}

$mavenArguments = @(
    '-pl', 'stellaris-server/stellaris-order-service,stellaris-server/stellaris-program-service',
    '-am',
    '-Dtest=ReferenceReservationLuaIntegrationTest,OrderStreamDeliveryIntegrationTest',
    '-Dsurefire.failIfNoSpecifiedTests=false',
    "-Dapi.version=$dockerApiVersion",
    'test'
)

& mvn @mavenArguments
if ($LASTEXITCODE -ne 0) {
    throw "Redis Lua integration test failed with exit code $LASTEXITCODE."
}
