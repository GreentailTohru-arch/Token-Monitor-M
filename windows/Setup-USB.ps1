param([string]$Serial, [int]$Port = 47823, [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe")
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Adb)) { $Adb = (Get-Command adb -ErrorAction Stop).Source }
& $Adb devices -l
$DeviceArgs = if ($Serial) { @('-s', $Serial) } else { @('-d') }
& $Adb @DeviceArgs reverse "tcp:$Port" "tcp:$Port"
if ($LASTEXITCODE -ne 0) { throw 'Unlock the phone, enable USB debugging, authorize this computer, then retry. Use -Serial for multiple phones.' }
Write-Host "USB ready. In the phone app select USB; address is 127.0.0.1:$Port. Enter the pairing key printed by Start-Companion."
