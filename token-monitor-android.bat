@echo off
title Token Monitor - Android Bridge
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0windows\Start-All.ps1"
if errorlevel 1 echo Some components need attention. See the results above.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0windows\Copy-Connection-Info.ps1"
pause
