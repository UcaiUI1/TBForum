param(
    [Parameter(Mandatory = $true)][uri]$BaseUrl,
    [ValidateRange(1, 600)][int]$TimeoutSeconds = 120,
    [ValidateRange(1, 10)][int]$ConsecutiveSuccesses = 3
)
$ErrorActionPreference = 'Stop'
if ($BaseUrl.Scheme -notin 'http', 'https') { throw 'BaseUrl must use http or https' }
$deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
$url = $BaseUrl.AbsoluteUri.TrimEnd('/') + '/actuator/health/readiness'
$successes = 0
while ([DateTimeOffset]::UtcNow -lt $deadline) {
    try {
        $health = Invoke-RestMethod -Uri $url -Method Get -TimeoutSec 2
        if ($health.status -eq 'UP') { $successes++ } else { $successes = 0 }
    } catch { $successes = 0 }
    if ($successes -ge $ConsecutiveSuccesses) {
        Write-Output "服务已就绪：$url"
        exit 0
    }
    Start-Sleep -Milliseconds 500
}
Write-Error "等待服务就绪超时：$url"
exit 1
