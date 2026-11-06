# Token Monitor M

An Android fork of Token Monitor that shows AI token usage, estimated costs, quotas, and session history from your computer.

## Get started

| Task | What to do |
| --- | --- |
| Install on Android | Install the debug-signed APK from the [latest release][releases]. Requires Android 8.0 or later. |
| Connect to a Windows PC | Install [Token Monitor v0.87.0][desktop] and Python 3.10 or later. Extract the Windows companion ZIP and run `Start-Token-Monitor.bat` to get the connection details and pairing key. |
| Sync through a Hub | Open **Settings → Multi-device Sync** and enter the Hub URL and shared secret. Hub sync provides usage statistics. Connect directly to the PC for conversation details. |

For a direct connection, open **Settings → Connect to Computer** and use the computer pairing key with one of these methods.

| Method | Setup |
| --- | --- |
| Wi-Fi | Connect both devices to the same network. Enter the PC's IPv4 address and port `47823`. |
| Bluetooth | Pair the phone and PC in system settings, then select the PC in the app. |
| USB | Install [Android SDK Platform-Tools][platform-tools] and authorize USB debugging. Run [`windows/Setup-USB.cmd`](windows/Setup-USB.cmd) from this repository or `desktop\Setup-USB.cmd` from the released ZIP, then select **USB** in the app. |
| Remote Tunnel | Forward a trusted HTTPS endpoint to `http://127.0.0.1:47823` on the PC. Enter the public address and port in the app. |

## Repository layout

| Path | Contents |
| --- | --- |
| [`android/`](android/) | Android app and Gradle build |
| [`windows/`](windows/) | Windows companion and connection scripts |
| [`windows/notifications/`](windows/notifications/) | Optional Codex notification relay. Run `python windows/notifications/configure.py --help` for setup options. |

## Build from source

Open `android/` in Android Studio with JDK 17, Android SDK 36, and Build Tools 36.0.0. On Windows, run these commands from the repository root.

```powershell
cd android
.\gradlew.bat assembleDebug
```

The build writes the APK to `android/app/build/outputs/apk/debug/app-debug.apk`.

## License

Based on [Javis603's Token Monitor v0.87.0][upstream]. See the [MIT license](LICENSE).

[releases]: https://github.com/GreentailTohru-arch/token-monitor-m/releases/latest
[desktop]: https://github.com/Javis603/token-monitor/releases/tag/v0.87.0
[platform-tools]: https://developer.android.com/tools/releases/platform-tools
[upstream]: https://github.com/Javis603/token-monitor/tree/b3dd5e25bd9ab89620eb6380c863e335d8b8751e
