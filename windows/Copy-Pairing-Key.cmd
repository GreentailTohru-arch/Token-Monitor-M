@echo off
powershell.exe -NoProfile -Command "Set-Clipboard -Value ((Get-Content -LiteralPath (Join-Path $env:USERPROFILE 'TokenMonitorMobile\pairing.key') -Raw).Trim()); Write-Host 'Computer pairing key copied.'"
pause
