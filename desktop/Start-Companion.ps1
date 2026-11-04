param(
    [string]$StateDir = "$env:USERPROFILE\TokenMonitorMobile",
    [string]$Runtime = "$StateDir\runtime-0.85.0",
    [Alias('Profile')][string]$CompanionProfile = "$StateDir\profile-0.85.0",
    [switch]$NoBluetooth,
    [switch]$Background
)
$ErrorActionPreference = 'Stop'
$Python = (Get-Command python -ErrorAction Stop).Source
& $Python "$PSScriptRoot\engine\prepare_engine.py" --runtime $Runtime --profile $CompanionProfile
if ($LASTEXITCODE -ne 0) { throw 'Could not prepare the original Token Monitor engine.' }
$KeyFile = Join-Path $StateDir 'pairing.key'
if (-not (Test-Path -LiteralPath $KeyFile)) {
    & $Python -c "import sys,pathlib;sys.path.insert(0,sys.argv[1]);import bridge;bridge.pairing_key(pathlib.Path(sys.argv[2]))" $PSScriptRoot $KeyFile
}
$LogDir = Join-Path $StateDir 'logs'
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$env:TOKEN_MONITOR_MOBILE_PROFILE = [IO.Path]::GetFullPath($CompanionProfile)
$env:TOKEN_MONITOR_MOBILE_KEY_FILE = $KeyFile
Remove-Item Env:ELECTRON_RUN_AS_NODE -ErrorAction SilentlyContinue
$EngineReady = $false
try {
    $Auth = 'Bearer ' + (Get-Content -LiteralPath $KeyFile -Raw).Trim()
    $State = Invoke-RestMethod -Uri 'http://127.0.0.1:47822/api/rpc' -Method Post -Headers @{ Authorization = $Auth } -ContentType 'application/json' -Body '{"channel":"mobile:status","args":[]}' -TimeoutSec 2
    $EngineReady = $State.ok -and $State.result.ready -and $State.result.version -eq '0.85.0'
} catch {}
if (-not $EngineReady) {
    $Engine = Start-Process -FilePath (Join-Path $Runtime 'Token Monitor.exe') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $LogDir 'engine-out.log') -RedirectStandardError (Join-Path $LogDir 'engine-err.log')
    $Engine.Id | Set-Content -LiteralPath (Join-Path $LogDir 'engine.pid')
    for ($Attempt = 0; $Attempt -lt 30; $Attempt++) {
        Start-Sleep -Milliseconds 500
        try {
            $State = Invoke-RestMethod -Uri 'http://127.0.0.1:47822/api/rpc' -Method Post -Headers @{ Authorization = $Auth } -ContentType 'application/json' -Body '{"channel":"mobile:status","args":[]}' -TimeoutSec 2
            if ($State.ok -and $State.result.ready -and $State.result.version -eq '0.85.0') { $EngineReady = $true; break }
        } catch {}
    }
    if (-not $EngineReady) { throw "Companion engine did not become ready. See $LogDir\engine-err.log" }
}
Write-Host 'Token Monitor 0.85.0 companion engine ready.'
if (Test-Path -LiteralPath "$env:USERPROFILE\TokenMonitorNotifications\config.private.json") {
    & "$PSScriptRoot\..\notifications-desktop\manage.ps1" start
}
$BridgeReady = $false
try {
    $BridgeState = Invoke-RestMethod -Uri 'http://127.0.0.1:47823/api/rpc' -Method Post -Headers @{ Authorization = $Auth } -ContentType 'application/json' -Body '{"channel":"mobile:status","args":[]}' -TimeoutSec 2
    $BridgeReady = $BridgeState.ok
} catch {}
if ($BridgeReady) {
    Write-Host 'Mobile companion already running on port 47823.'
    Write-Host 'Run Copy-Pairing-Key.cmd to copy the computer pairing key.'
    Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -ne '127.0.0.1' } | ForEach-Object { Write-Host ('Computer IP: ' + $_.IPAddress) }
    return
}
$BridgeArgs = @("$PSScriptRoot\bridge.py", '--key-file', $KeyFile)
if ($NoBluetooth) { $BridgeArgs += '--no-bluetooth' }
if ($Background) {
    $LaunchArgs = @('-u', ('"'+$PSScriptRoot+'\bridge.py"'), '--key-file', ('"'+$KeyFile+'"'))
    if ($NoBluetooth) { $LaunchArgs += '--no-bluetooth' }
    $BridgeProcess = Start-Process -FilePath $Python -ArgumentList $LaunchArgs -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $LogDir 'bridge-out.log') -RedirectStandardError (Join-Path $LogDir 'bridge-err.log')
    $BridgeProcess.Id | Set-Content -LiteralPath (Join-Path $LogDir 'bridge.pid')
    for ($Attempt = 0; $Attempt -lt 15; $Attempt++) {
        Start-Sleep -Milliseconds 500
        try {
            $Health = Invoke-RestMethod -Uri 'http://127.0.0.1:47823/health' -TimeoutSec 2
            if ($Health.service -eq 'TokenMonitorBridge') { Write-Host 'Computer connection is running in the background. This window can be closed.'; return }
        } catch {}
    }
    throw "Computer connection did not start. See $LogDir\bridge-err.log"
} else { & $Python @BridgeArgs }
