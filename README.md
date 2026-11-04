# Token Monitor M

Token Monitor M 是 [Token Monitor](https://github.com/Javis603/token-monitor) 的非官方 Android 移植版。它在手机上显示电脑的 AI 工具 Token 用量、费用、趋势、额度和会话，保留原版界面风格，并适配触屏与横竖屏。

## 连接电脑

| 方式 | 手机入口 | 需要填写 |
| --- | --- | --- |
| Wi-Fi | 设置 → 连接电脑 | 电脑的局域网地址、端口和配对密钥 |
| Bluetooth | 设置 → 连接电脑 | 已在系统中配对的电脑和电脑配对密钥 |
| USB | 设置 → 连接电脑 | 用 ADB 建立 USB 反向连接，再填写电脑配对密钥 |
| Remote Tunnel | 设置 → 连接电脑 | 自己的 HTTPS 穿透地址、端口和电脑配对密钥 |
| 原版 Hub | 设置 → 多设备同步 | Hub URL 和 Hub 共享密钥 |

Hub 用来同步用量和统计。展开会话以查看对话详情时，使用「连接电脑」。手机断开连接后可以显示最近缓存的数据。

### Windows 电脑

1. 安装 [Token Monitor v0.85.0](https://github.com/Javis603/token-monitor/releases/tag/v0.85.0) 和 Python 3.10 或以上版本。
2. 下载或克隆本仓库后，双击 `Start-Token-Monitor.bat`。脚本会启动电脑连接组件，并显示本机地址、端口和配对密钥。若已在桌面版中启用托管 Hub，也会显示 Hub URL 和共享密钥。
3. 使用 USB 时，安装 [Android SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools)，连接手机并授权 USB 调试，再运行 `desktop\Setup-USB.cmd`。
4. 使用 Remote Tunnel 时，自行配置 HTTPS 入口，用 Android 信任的证书，将流量转发到电脑的 `127.0.0.1:47823`。让电脑端组件和穿透客户端保持运行。

配对密钥、Hub 共享密钥和会话记录属于本机数据。请勿将其提交到 GitHub。

### Codex 通知

可选的 Windows 组件位于 `notifications-desktop/`。运行 `python notifications-desktop/configure.py --help` 查看配置参数。配置完成后，启动脚本会尝试启动通知组件。在手机设置中打开 Codex 通知，并允许 Android 通知权限。公开版使用 Android 系统默认通知音。

## 构建 Android

在 Android Studio 中打开 `android/`，配置 Android SDK 和 JDK，或在 PowerShell 中运行：

```powershell
cd android
.\gradlew.bat assembleDebug
```

调试 APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`。Android 版本号为 0.0.2。

## 上游与许可证

本项目基于 [Javis603/token-monitor](https://github.com/Javis603/token-monitor) [v0.85.0 源码（d7fd7db）](https://github.com/Javis603/token-monitor/tree/d7fd7db7eb13c912acf0a94b07f5b2baa64149ba)。感谢 Javis 和上游贡献者。上游版权声明和 MIT 许可证见 [LICENSE](LICENSE)，随源码保留的第三方声明适用于各自组件。

Token Monitor M 独立维护。Android 适配、Windows 配套启动脚本和 Codex 通知组件属于本移植版，相关问题请在本仓库反馈。
