<div align="center">
    <img src="assets/icon.png" alt="Token Monitor icon" width="120">
    <h1>Token Monitor</h1>
</div>

<p align="center">
    <em>Your computer's AI usage, quotas, and sessions on Android.</em>
</p>

<p align="center">
    <a href="https://github.com/GreentailTohru-arch/token-monitor-android/releases/latest"><img src="https://img.shields.io/github/v/release/GreentailTohru-arch/token-monitor-android?style=flat-square&amp;label=Release&amp;logo=github&amp;logoColor=white&amp;color=9a431c" alt="Latest release"></a>
    <a href="https://raw.githubusercontent.com/GreentailTohru-arch/token-monitor-android/main/assets/badges/windows-x64.svg"><img src="https://raw.githubusercontent.com/GreentailTohru-arch/token-monitor-android/main/assets/badges/windows-x64.svg" alt="Windows 10+"></a>
    <a href="https://raw.githubusercontent.com/GreentailTohru-arch/token-monitor-android/main/assets/badges/android.svg"><img src="https://raw.githubusercontent.com/GreentailTohru-arch/token-monitor-android/main/assets/badges/android.svg" alt="Android 8.0+"></a>
    <a href="LICENSE"><img src="https://raw.githubusercontent.com/GreentailTohru-arch/token-monitor-android/main/assets/badges/license.svg" alt="MIT license"></a>
</p>

## What is Token Monitor for Android?

An Android fork of Token Monitor. Connect to your Windows PC or an existing Hub to view token usage, estimated costs, account quotas, and session activity.

## Quick Install

1. Download the APK and Windows bridge ZIP from [GitHub Releases][releases].
2. Install the APK on Android 8.0 or later. On Windows, install [Token Monitor v0.87.0][desktop] and Python 3.10 or later.
3. Extract the bridge ZIP, run `token-monitor-android.bat`, and enter the displayed connection details in the app.

The APK uses a debug signature. The bridge runs a separate copy of the desktop engine with its own profile.

## Features

- Usage totals, estimated costs, and breakdowns by tool, model, device, and session.
- Account quotas, activity heatmaps, and historical trends supplied by the connected computer or Hub.
- Fullscreen portrait and landscape layouts with the latest snapshot available offline.
- Optional Codex notifications for completed tasks, input requests, and quota warnings.

## Connections

Open **Settings → Connect to Computer** for direct access to usage and conversation details. All four direct connection methods use the pairing key shown by the bridge.

| Method | Setup |
| --- | --- |
| Wi-Fi | Connect both devices to the same network. Enter the PC's IPv4 address and port `47823`. |
| Bluetooth | Pair the phone and PC in system settings, then select the PC in the app. |
| USB | Install [Android SDK Platform-Tools][platform-tools], authorize USB debugging, run `windows\Setup-USB.cmd`, and select **USB** in the app. |
| Remote Tunnel | Forward a trusted HTTPS endpoint to `http://127.0.0.1:47823` on the PC. Enter the public address and port in the app. |
| Hub | Open **Settings → Multi-device Sync** and enter the Hub URL and shared secret. Hub sync provides usage statistics. Conversation details require a direct connection. |

For Codex notifications, run `python windows/notifications/configure.py --help` for relay configuration options. After configuration, run `windows\notifications\start.cmd` and `windows\notifications\copy-pairing.cmd`, then paste the pairing code in the app's notification settings.

## Build from Source

Open `android/` in Android Studio with JDK 17, Android SDK 36, and Build Tools 36.0.0, or run these commands from the repository root.

```powershell
cd android
.\gradlew.bat assembleDebug
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

## Credits and License

Based on [Javis603/token-monitor v0.87.0][upstream]. Upstream copyright and third-party notices are retained. See the [MIT license](LICENSE).

[releases]: https://github.com/GreentailTohru-arch/token-monitor-android/releases/latest
[desktop]: https://github.com/Javis603/token-monitor/releases/tag/v0.87.0
[platform-tools]: https://developer.android.com/tools/releases/platform-tools
[upstream]: https://github.com/Javis603/token-monitor/tree/b3dd5e25bd9ab89620eb6380c863e335d8b8751e
