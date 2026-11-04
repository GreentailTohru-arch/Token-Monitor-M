$ErrorActionPreference='Stop'
$stateDir=Join-Path $env:USERPROFILE 'TokenMonitorMobile'
$logDir=Join-Path $stateDir 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$failed=$false
function Report([string]$name,[bool]$ready,[string]$note='') {
    if ($ready) { Write-Host ('[OK]   '+$name+' '+$note) -ForegroundColor Green }
    else { Write-Host ('[WAIT] '+$name+' '+$note) -ForegroundColor Yellow; $script:failed=$true }
}
function Running([string]$path) {
    return @(Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -eq $path }).Count -gt 0
}
Write-Host 'Starting Token Monitor services...'
$installed=Join-Path $env:USERPROFILE 'AppData\Local\Programs\Token Monitor\Token Monitor.exe'
try {
    if (-not (Running $installed)) { Start-Process -FilePath $installed -WindowStyle Hidden | Out-Null }
    $hubConfig=Get-Content -LiteralPath (Join-Path $env:USERPROFILE 'AppData\Roaming\Token Monitor\settings.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $hubReady=$false
    if ($hubConfig.hubMode -eq 'host') {
        $hubPort=if ($hubConfig.hubHostPort) { [int]$hubConfig.hubHostPort } else {17321}
        for ($attempt=0;$attempt -lt 12;$attempt++) {
            try { $health=Invoke-RestMethod -Uri "http://127.0.0.1:$hubPort/api/health" -TimeoutSec 2; if ($health.role -eq 'hub') {$hubReady=$true;break} } catch {}
            Start-Sleep -Milliseconds 500
        }
        Report 'Original Hub' $hubReady "(local port $hubPort)"
    } else { Report 'Original Hub' $false '(Enable Host Hub in desktop Token Monitor once.)' }
} catch { Report 'Original Hub' $false $_.Exception.Message }

try {
    & "$PSScriptRoot\Start-Companion.ps1" -Background *> (Join-Path $logDir 'start-all.log')
    $health=Invoke-RestMethod -Uri 'http://127.0.0.1:47823/health' -TimeoutSec 3
    Report 'Wi-Fi / USB / Bluetooth bridge' ($health.service -eq 'TokenMonitorBridge') '(port 47823; USB still requires device authorization)'
} catch { Report 'Computer bridge' $false ('See '+(Join-Path $logDir 'start-all.log')) }

Write-Host ''
Write-Host 'You can close this window. Services continue in the background.'
Write-Host 'For Remote Tunnel, configure your own HTTPS endpoint to forward to the computer bridge.'
if ($failed) { exit 1 }
exit 0
