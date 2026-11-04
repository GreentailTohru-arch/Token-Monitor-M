param([ValidateSet('start','stop','copy')][string]$Action='start')
$ErrorActionPreference='Stop'
$stateDir=Join-Path $env:USERPROFILE 'TokenMonitorNotifications'
$configPath=Join-Path $stateDir 'config.private.json'
$relayPath=Join-Path $PSScriptRoot 'notifier.py'
try {
    if (-not (Test-Path -LiteralPath $configPath)) { throw 'Pairing configuration is missing. See NOTIFICATIONS.md.' }
    if ($Action -eq 'copy') {
        Set-Clipboard -Value (Get-Content -LiteralPath (Join-Path $stateDir 'pairing.txt') -Raw)
        Write-Host 'Pairing code copied. Paste in phone Settings > Codex notifications.'
        exit 0
    }
    $existing = @(Get-CimInstance Win32_Process -Filter "name='pythonw.exe' OR name='python.exe'" | Where-Object {
        $_.CommandLine -and $_.CommandLine.Replace('/','\').Contains($configPath) -and $_.CommandLine.Contains('notifier.py')
    })
    if ($Action -eq 'stop') {
        foreach ($item in $existing) { Stop-Process -Id $item.ProcessId -Force }
        Write-Host 'Codex notification component stopped.'
        exit 0
    }
    if ($existing.Count -gt 0) { Write-Host 'Codex notification component is already running.'; exit 0 }
    $pythonPath=(Get-Command python.exe -ErrorAction Stop).Source
    $pythonWindowless=Join-Path (Split-Path $pythonPath) 'pythonw.exe'
    if (Test-Path -LiteralPath $pythonWindowless) { $pythonPath=$pythonWindowless }
    $process=Start-Process -FilePath $pythonPath -ArgumentList @('"'+$relayPath+'"','--config','"'+$configPath+'"') -WindowStyle Hidden -PassThru
    Start-Sleep -Seconds 2
    $process.Refresh()
    if ($process.HasExited) { throw 'Component did not start. Check Python, port and certificate configuration.' }
    Write-Host 'Codex notification component started. Keep this computer and SakuraFrp online.'
} catch { Write-Host ('Error: '+$_.Exception.Message); exit 1 }
