@echo off
title Token Monitor - Start All
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0desktop\Start-All.ps1"
if errorlevel 1 echo Some components need attention. See the results above.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0desktop\Copy-Connection-Info.ps1"
pause
