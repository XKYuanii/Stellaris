param(
    [Parameter(Mandatory = $true)][string]$BodyTemplate,
    [string]$Endpoint = 'http://127.0.0.1:6085/stellaris/program/program/order/create/v5',
    [string]$Token = '',
    [ValidateRange(1, 100000)][int]$Requests = 100,
    [ValidateRange(1, 2000)][int]$Concurrency = 50,
    [string]$OutputPath = (Join-Path $PSScriptRoot '..\output\v5-load-result.csv')
)

$template = Get-Content -Raw -LiteralPath $BodyTemplate
$results = 1..$Requests | ForEach-Object -Parallel {
    $requestId = [guid]::NewGuid().ToString('N')
    $body = $using:template.Replace('{{REQUEST_ID}}', $requestId)
    $headers = @{ no_verify = 'true' }
    if (-not [string]::IsNullOrWhiteSpace($using:Token)) { $headers.token = $using:Token }
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = Invoke-WebRequest -Uri $using:Endpoint -Method Post -ContentType 'application/json;charset=utf-8' `
            -Headers $headers -Body $body -SkipHttpErrorCheck -TimeoutSec 30
        $watch.Stop()
        [pscustomobject]@{
            requestId = $requestId
            httpStatus = [int]$response.StatusCode
            elapsedMs = $watch.ElapsedMilliseconds
            response = $response.Content
        }
    } catch {
        $watch.Stop()
        [pscustomobject]@{
            requestId = $requestId
            httpStatus = 0
            elapsedMs = $watch.ElapsedMilliseconds
            response = $_.Exception.Message
        }
    }
} -ThrottleLimit $Concurrency

$directory = Split-Path -Parent $OutputPath
if (-not (Test-Path -LiteralPath $directory)) { New-Item -ItemType Directory -Path $directory | Out-Null }
$results | Export-Csv -NoTypeInformation -Encoding UTF8 -LiteralPath $OutputPath
$ordered = $results.elapsedMs | Sort-Object
$percentile = {
    param([double]$p)
    if ($ordered.Count -eq 0) { return 0 }
    $index = [Math]::Min($ordered.Count - 1, [Math]::Ceiling($ordered.Count * $p) - 1)
    return $ordered[$index]
}
[pscustomobject]@{
    requests = $results.Count
    http2xx = @($results | Where-Object { $_.httpStatus -ge 200 -and $_.httpStatus -lt 300 }).Count
    http429 = @($results | Where-Object httpStatus -eq 429).Count
    http503 = @($results | Where-Object httpStatus -eq 503).Count
    p50Ms = & $percentile 0.50
    p95Ms = & $percentile 0.95
    p99Ms = & $percentile 0.99
    evidence = (Resolve-Path -LiteralPath $OutputPath).Path
} | Format-List
