"""Create an isolated companion runtime from the user's installed MIT app.

No installed files are patched. Credentials are copied locally into an isolated
profile only; neither runtime nor profile belongs in the distributable archive.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import struct


def extract_asar(archive, target):
    with archive.open("rb") as source:
        header = struct.unpack("<4I", source.read(16))
        tree = json.loads(source.read(header[3]))
        data_start = 8 + header[1]

        def walk(files, relative=Path()):
            for name, entry in files.items():
                rel = relative / name
                dest = target / rel
                if not dest.resolve().is_relative_to(target.resolve()):
                    raise ValueError("Invalid archive path")
                if "files" in entry:
                    dest.mkdir(parents=True, exist_ok=True)
                    walk(entry["files"], rel)
                elif "link" in entry:
                    raise ValueError("Unsupported archive symlink")
                else:
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    if entry.get("unpacked"):
                        shutil.copy2(Path(str(archive) + ".unpacked") / rel, dest)
                    else:
                        source.seek(data_start + int(entry["offset"]))
                        dest.write_bytes(source.read(entry["size"]))
        walk(tree["files"])


def main():
    local = Path(os.environ["LOCALAPPDATA"])
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--installed", type=Path, default=local / "Programs/Token Monitor")
    parser.add_argument("--runtime", type=Path, default=local / "TokenMonitorMobile/runtime-0.85.0")
    parser.add_argument("--profile", type=Path, default=local / "TokenMonitorMobile/profile-0.85.0")
    args = parser.parse_args()
    if args.runtime.resolve() == args.installed.resolve():
        raise ValueError("Runtime must be isolated from the installed app")
    args.runtime.mkdir(parents=True, exist_ok=True)
    marker = args.runtime / "mobile-engine-version.json"
    prepared = marker.exists()
    metadata = json.loads(marker.read_text("utf-8")) if prepared else {}
    if not prepared:
        for item in args.installed.iterdir():
            if item.name.lower().startswith("uninstall"):
                continue
            dest = args.runtime / item.name
            if item.is_dir():
                shutil.copytree(item, dest, dirs_exist_ok=True,
                                ignore=shutil.ignore_patterns("app.asar", "app.asar.unpacked"))
            else:
                shutil.copy2(item, dest)
        extract_asar(args.installed / "resources/app.asar", args.runtime / "resources/app")
    app_dir = args.runtime / "resources/app"
    package = json.loads((app_dir / "package.json").read_text("utf-8"))
    if package.get("version") != "0.85.0":
        raise RuntimeError("This mobile renderer is pinned to Token Monitor 0.85.0. Update the mobile bundle before using a different engine version.")
    package["main"] = "mobile-main.js"
    (app_dir / "package.json").write_text(json.dumps(package, indent=2), "utf-8")
    shutil.copy2(Path(__file__).with_name("mobile-main.js"), app_dir / "mobile-main.js")
    args.profile.mkdir(parents=True, exist_ok=True)
    initial_settings = not (args.profile / "settings.json").exists()
    original_profile = Path(os.environ["APPDATA"]) / "Token Monitor"
    # Only initial bootstrap copies: subsequent phone settings stay independent.
    for name in ["settings.json", "credentials.json", "collector-anchor.json", "daily-history-archive.json", "exchange-rates.json"]:
        if (original_profile / name).exists() and not (args.profile / name).exists():
            shutil.copy2(original_profile / name, args.profile / name)
    db_path = original_profile / "session-usage-archive.sqlite"
    if db_path.exists() and not (args.profile / db_path.name).exists():
        with sqlite3.connect(db_path.as_uri() + "?mode=ro", uri=True) as source:
            with sqlite3.connect(args.profile / db_path.name) as dest:
                source.backup(dest)
    # Do not carry OS autostart/update/sync side effects into the companion.
    settings_path = args.profile / "settings.json"
    settings = json.loads(settings_path.read_text("utf-8")) if settings_path.exists() else {}
    if not prepared or initial_settings:
        settings.update({"hubMode": "local", "hubHostPort": 17322,
                         "autoLaunch": False, "launchAtLogin": False, "startAtLogin": False,
                         "discordRpcEnabled": False, "exportAutoEnabled": False,
                         "automaticAppUpdates": False, "windowToggleShortcut": "",
                         "alwaysOnTop": False, "windowBehavior": "normal"})
        settings_path.write_text(json.dumps(settings, ensure_ascii=False, indent=2), "utf-8")
    # A prepared runtime is pinned independently of desktop app upgrades.
    # Never overwrite its provenance with the hash of a newer installation.
    if not prepared:
        metadata["sourceAsarSha256"] = hashlib.sha256((args.installed / "resources/app.asar").read_bytes()).hexdigest()
    metadata.update({"sourceVersion": package["version"], "profile": str(args.profile.resolve()),
                     "source": "https://github.com/Javis603/token-monitor"})
    marker.write_text(json.dumps(metadata, indent=2), "utf-8")
    print(json.dumps({"runtime": str(args.runtime.resolve()), "profile": str(args.profile.resolve()), "version": package["version"]}))


if __name__ == "__main__":
    main()
