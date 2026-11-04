# Token Monitor M

Token Monitor M is an unofficial Android port of Token Monitor. It displays AI tool usage, estimated costs, quotas, and session history collected on your computer. The last synced snapshot remains available offline.

## Installation

1. Install the APK on Android 8.0 or later. The APK uses a debug signature.
2. For a direct connection to a Windows PC, download and extract the Windows companion ZIP. Install Python 3.10 or later and the [Token Monitor v0.85.0 desktop app][desktop].
3. Run `Start-Token-Monitor.bat` from the extracted folder. It starts the companion and displays the computer address, port, and pairing key. If the desktop app hosts a Hub, it also displays the Hub URL and shared secret.

[Download the Android APK and Windows companion][releases]

## Connect

Open **Settings → Connect to Computer** for usage and session details. All four methods use the same computer pairing key.

| Method | Setup |
| --- | --- |
| Wi-Fi | Connect both devices to the same network. Enter the PC's local IPv4 address and port `47823`. |
| Bluetooth | Pair the devices in system settings, then select the PC in the app. |
| USB | Install [Android SDK Platform-Tools][platform-tools], enable USB debugging, authorize the PC, and run `desktop\Setup-USB.cmd`. Select **USB** in the app. |
| Remote Tunnel | Forward a trusted HTTPS endpoint to `http://127.0.0.1:47823` on the PC. Enter the public HTTPS address and port in the app. |

To sync usage and statistics through an existing Token Monitor Hub, open **Settings → Multi-device Sync** and enter its URL and shared secret. Use the direct connection to read conversation details.

### Codex notifications

Run `python notifications-desktop/configure.py --help` for relay configuration options. After configuration, run `notifications-desktop\start.cmd` and use `notifications-desktop\copy-pairing.cmd` to copy the pairing code. Paste it under **Settings → Codex Notifications** and allow Android notifications.

## Build from source

Open `android/` in Android Studio with JDK 17 or later, Android SDK 36, and Build Tools 36.0.0. You can also build with the Gradle wrapper in PowerShell.

```powershell
cd android
.\gradlew.bat assembleDebug
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk` relative to the repository root.

## Credits and license

This port is based on Javis603's Token Monitor v0.85.0 ([upstream commit `d7fd7db`][upstream]). The [MIT license](LICENSE) retains the upstream copyright notice. Bundled third-party components retain their own notices.

[releases]: https://github.com/GreentailTohru-arch/token-monitor-m/releases/latest
[desktop]: https://github.com/Javis603/token-monitor/releases/tag/v0.85.0
[platform-tools]: https://developer.android.com/tools/releases/platform-tools
[upstream]: https://github.com/Javis603/token-monitor/tree/d7fd7db7eb13c912acf0a94b07f5b2baa64149ba
