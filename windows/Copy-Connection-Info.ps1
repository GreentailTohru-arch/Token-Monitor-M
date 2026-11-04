$ErrorActionPreference = 'Stop'
$stateDir = Join-Path $env:USERPROFILE 'TokenMonitorMobile'
$hubDir = Join-Path $env:APPDATA 'Token Monitor'

function Get-LanAddress {
    $networks = Get-NetIPConfiguration |
        Sort-Object { if ($_.IPv4DefaultGateway) { 0 } else { 1 } }
    $address = $networks | ForEach-Object { $_.IPv4Address.IPAddress } |
        Where-Object { $_ -and $_ -notmatch '^(127\.|169\.254\.|0\.)' } |
        Select-Object -First 1
    if (!$address) { throw '找不到电脑的局域网 IPv4 地址。请先连接 Wi-Fi 或以太网。' }
    return $address
}

function Get-ComputerPairingKey {
    $path = Join-Path $stateDir 'pairing.key'
    if (!(Test-Path -LiteralPath $path)) { throw '找不到电脑配对密钥。请先启动电脑连接服务。' }
    $key = (Get-Content -LiteralPath $path -Raw).Trim()
    if (!$key) { throw '电脑配对密钥为空。请检查电脑连接服务。' }
    return $key
}

function Get-HostedHubUrl {
    $path = Join-Path $hubDir 'settings.json'
    if (!(Test-Path -LiteralPath $path)) { throw '找不到 Token Monitor 的 Hub 设置。' }
    try {
        $settings = Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
    } catch {
        throw '无法读取 Token Monitor 的 Hub 设置。'
    }
    if ($settings.hubMode -ne 'host') {
        throw '请先在电脑 Token Monitor 的「设置 → 多设备同步」中选择托管 Hub。'
    }
    $port = if ($settings.hubHostPort) { [int]$settings.hubHostPort } else { 17321 }
    return ('http://{0}:{1}' -f (Get-LanAddress), $port)
}

function Get-HostedHubSecret {
    $path = Join-Path $hubDir 'credentials.json'
    if (!(Test-Path -LiteralPath $path)) { throw '找不到 Hub 共享密钥。请先在电脑 Token Monitor 中托管 Hub。' }
    try {
        $credentials = Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
    } catch {
        throw '无法读取 Hub 共享密钥。请在电脑 Token Monitor 的「多设备同步」中复制。'
    }
    $secret = $credentials.credentials.hub.hostSecret
    if ($secret -isnot [string] -or !$secret.Trim()) {
        throw '无法从本机读取 Hub 共享密钥。可在电脑 Token Monitor 的「多设备同步」中复制。'
    }
    return $secret
}

Write-Host ''
Write-Host '连接电脑' -ForegroundColor Cyan
try {
    Write-Host "Wi-Fi 电脑地址：$(Get-LanAddress)"
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Yellow
}
Write-Host 'Wi-Fi 端口：47823'
Write-Host 'Remote Tunnel：填写自己的 HTTPS 地址和端口。'
try {
    Write-Host "配对密钥（以上方式通用）：$(Get-ComputerPairingKey)"
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Yellow
}

Write-Host ''
Write-Host '连接到 Hub' -ForegroundColor Cyan
try {
    Write-Host "Hub URL：$(Get-HostedHubUrl)"
    Write-Host "Hub 共享密钥：$(Get-HostedHubSecret)"
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Yellow
}
Write-Host ''
Write-Host '选中上方所需字段后复制。'

$startupFolder = [Environment]::GetFolderPath('Startup')
$shortcutPath = Join-Path $startupFolder 'Token Monitor Mobile.lnk'
$current = if (Test-Path -LiteralPath $shortcutPath) { '已开启' } else { '已关闭' }
do {
    $answer = ([string](Read-Host "是否开机自启动？当前$current。Y 开启，N 关闭，回车保留")).Trim()
} while ($answer -notin 'Y','N','')

try {
    if ($answer -eq 'Y') {
        $launcher = Join-Path $PSScriptRoot 'Start-All.ps1'
        $shell = New-Object -ComObject WScript.Shell
        $shortcut = $shell.CreateShortcut($shortcutPath)
        $shortcut.TargetPath = Join-Path $PSHOME 'powershell.exe'
        $shortcut.Arguments = '-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "' + $launcher + '"'
        $shortcut.WorkingDirectory = $PSScriptRoot
        $shortcut.WindowStyle = 7
        $shortcut.Save()
        Write-Host '已开启开机自启动。' -ForegroundColor Green
    } elseif ($answer -eq 'N') {
        if (Test-Path -LiteralPath $shortcutPath) {
            Remove-Item -LiteralPath $shortcutPath
        }
        Write-Host '已关闭开机自启动。' -ForegroundColor Green
    }
} catch {
    Write-Host '无法保存开机自启动设置。请检查 Windows 启动文件夹是否可写。' -ForegroundColor Yellow
    exit 1
}
