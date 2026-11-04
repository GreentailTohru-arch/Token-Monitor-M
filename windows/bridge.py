"""Token Monitor mobile bridge. Python 3.10+, standard library only.

Reads usage counters, never credentials or chat content into responses.
The installed Token Monitor remains the collector and pricing authority.
"""
from __future__ import annotations

import argparse
import collections
import datetime as dt
import hmac
import json
import math
import os
from pathlib import Path
import secrets
import socket
import subprocess
import threading
import time
import urllib.request
import urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 47823
SERVICE_UUID = "7f8b8b62-1de4-4b4b-94ce-8ab1d65342a1"
ENGINE_PORT = 47822
TAIPEI = dt.timezone(dt.timedelta(hours=8))


def number(value):
    try:
        result = float(value)
        return max(0.0, result) if math.isfinite(result) else 0.0
    except (TypeError, ValueError):
        return 0.0


def utcstamp(epoch=None):
    return dt.datetime.fromtimestamp(epoch or time.time(), dt.timezone.utc).isoformat().replace("+00:00", "Z")


def engine_rpc(channel, args, key):
    """Forward the original preload API to the isolated Electron collector."""
    if not isinstance(channel, str) or not isinstance(args, list):
        return {"ok": False, "error": "invalid_request"}
    data = json.dumps({"channel": channel, "args": args}, ensure_ascii=False).encode("utf-8")
    if len(data) > 2 * 1024 * 1024:
        return {"ok": False, "error": "request_too_large"}
    request = urllib.request.Request(f"http://127.0.0.1:{ENGINE_PORT}/api/rpc", data=data,
                                     headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=55) as response:
            raw = response.read(16 * 1024 * 1024 + 1)
            if len(raw) > 16 * 1024 * 1024:
                return {"ok": False, "error": "response_too_large"}
            return json.loads(raw)
    except (OSError, ValueError, urllib.error.URLError):
        return {"ok": False, "error": "电脑原版引擎未启动，请运行 Start-Companion.ps1"}


def read_json(path):
    # A collector may be replacing a file while we poll; retry without writing it.
    for attempt in range(3):
        try:
            return json.loads(path.read_text(encoding="utf-8-sig"))
        except (OSError, ValueError):
            if attempt == 2:
                raise
            time.sleep(0.05)


def observations(day):
    value = day.get("observations", {}) if isinstance(day, dict) else {}
    return [x for x in value.values() if isinstance(x, dict)] if isinstance(value, dict) else []


def day_tokens(day):
    return sum(int(number(x.get("tokens"))) for x in observations(day))


def day_cost(day):
    return sum(number(x.get("cost")) for x in observations(day))


def component_quality(day):
    summary = day.get("componentSummary")
    if isinstance(summary, dict):
        return 2 if summary.get("tokenComponentsAvailable") is True else 1
    usage = [x for x in observations(day) if number(x.get("tokens")) > 0]
    if all(x.get("tokenComponentsAvailable") is True for x in usage):
        return 2
    return 1 if any(number(x.get(k)) > 0 for x in usage for k in ("cacheReadTokens", "outputTokens", "cacheWriteTokens")) else 0


def merge_days(archive, today):
    """Select whole snapshots; never add overlapping graph + live counters."""
    days = {k: v for k, v in archive.get("days", {}).items()
            if isinstance(v, dict) and len(k) == 10 and k <= today}
    for date, incoming in archive.get("liveDays", {}).items():
        if not isinstance(incoming, dict) or len(date) != 10 or date > today:
            continue
        previous = days.get(date)
        if previous is None:
            days[date] = incoming
            continue
        left, right = day_tokens(incoming), day_tokens(previous)
        quality = component_quality(incoming) - component_quality(previous)
        if left > right or (left == right and (quality > 0 or (quality == 0 and day_cost(incoming) != day_cost(previous)))):
            days[date] = incoming
    return days


def summarize(days):
    models = collections.defaultdict(int)
    costs = 0.0
    for day in days:
        for item in observations(day):
            models[str(item.get("modelId") or "unknown")] += int(number(item.get("tokens")))
            costs += number(item.get("cost"))
    return {"totalTokens": sum(models.values()), "costUsd": round(costs, 6),
            "models": [{"name": k, "tokens": v} for k, v in sorted(models.items(), key=lambda x: -x[1])]}


def anchor_summary(period):
    return {"totalTokens": int(number(period.get("totalTokens"))),
            "costUsd": round(number(period.get("costUsd")), 6),
            "models": [{"name": str(k), "tokens": int(number(v))}
                       for k, v in sorted(period.get("models", {}).items(), key=lambda x: -number(x[1]))]}


class LiveCodexCounter:
    """Incrementally read token counters after the desktop's last scan.

    Totals are monotonic within a session. Duplicate token_count notifications
    therefore add nothing. Date boundaries use Taipei, matching this project.
    File content is never copied, persisted or included in the network schema.
    """
    def __init__(self, root):
        self.root = root
        self.files = {}
        self.discovered = []
        self.discovery_at = 0.0

    def update(self, anchor_mtime, today, anchor):
        if time.monotonic() - self.discovery_at > 5:
            self.discovered = list(self.root.rglob("*.jsonl"))
            self.discovery_at = time.monotonic()
        delta_models = collections.defaultdict(int)
        latest = anchor_mtime
        available = False
        for path in self.discovered:
            try:
                stat = path.stat()
                if stat.st_mtime < anchor_mtime - 2:
                    continue
                available = True
                state = self.files.get(path)
                if not state or stat.st_size < state["offset"]:
                    state = {"offset": 0, "previous": 0, "model": "unknown", "days": {}, "latest": 0}
                    self.files[path] = state
                if stat.st_size != state["offset"]:
                    with path.open("rb") as stream:
                        stream.seek(state["offset"])
                        while True:
                            line = stream.readline()
                            if not line or not line.endswith(b"\n"):
                                break  # incomplete final line will be reread
                            state["offset"] = stream.tell()
                            if b'"token_count"' not in line and b'"turn_context"' not in line:
                                continue
                            try:
                                event = json.loads(line)
                                payload = event.get("payload", {})
                                if event.get("type") == "turn_context":
                                    state["model"] = str(payload.get("model") or state["model"])
                                    continue
                                if event.get("type") != "event_msg" or payload.get("type") != "token_count":
                                    continue
                                info = payload.get("info") or {}
                                usage = info.get("total_token_usage")
                                if not isinstance(usage, dict) or "total_tokens" not in usage:
                                    continue
                                total = int(number(usage["total_tokens"]))
                                # A rare reset/compaction starts a new counter.
                                # Its first request is available as last usage.
                                amount = total - state["previous"] if total >= state["previous"] else int(number(info.get("last_token_usage", {}).get("total_tokens")))
                                state["previous"] = total
                                when = dt.datetime.fromisoformat(event["timestamp"].replace("Z", "+00:00"))
                                date = when.astimezone(TAIPEI).date().isoformat()
                                models = state["days"].setdefault(date, collections.defaultdict(int))
                                models[state["model"]] += max(0, amount)
                                state["latest"] = max(state["latest"], when.timestamp())
                            except (ValueError, KeyError, TypeError):
                                continue
                latest = max(latest, state["latest"])
                direct = state["days"].get(today, {})
                saved = anchor.get("today", {}).get("sessions", {}).get("codex:" + path.stem, {}) if anchor.get("dateKey") == today else {}
                baseline = saved.get("models", {})
                # Positive excess only: the desktop's merged/deleted archives
                # remain authoritative. No duplicated baseline is added.
                excess = max(0, sum(direct.values()) - int(number(saved.get("totalTokens"))))
                differences = {k: max(0, v - int(number(baseline.get(k)))) for k, v in direct.items()}
                for model, count in sorted(differences.items(), key=lambda x: -x[1]):
                    accepted = min(count, excess)
                    delta_models[model] += accepted
                    excess -= accepted
            except OSError:
                continue
        return dict(delta_models), latest, available


def add_live_delta(period, delta):
    models = {x["name"]: x["tokens"] for x in period["models"]}
    for name, count in delta.items():
        models[name] = models.get(name, 0) + count
    period["models"] = [{"name": name, "tokens": count} for name, count in sorted(models.items(), key=lambda x: -x[1])]
    period["totalTokens"] += sum(delta.values())


def codex_quotas(root, today):
    """Only return rate_limit counters from recent local token_count events.

    Bounded tail reads avoid loading old conversation histories. Content never
    leaves this function; auth files and provider API credentials are not read.
    """
    found = []
    for day_offset in range(3):
        day = today - dt.timedelta(days=day_offset)
        folder = root / day.strftime("%Y/%m/%d")
        files = sorted(folder.glob("*.jsonl"), key=lambda x: x.stat().st_mtime, reverse=True)[:8]
        for path in files:
            try:
                with path.open("rb") as f:
                    f.seek(max(0, path.stat().st_size - 1024 * 1024))
                    lines = f.read().splitlines()
                for line in reversed(lines):
                    if b'"token_count"' not in line or b'"rate_limits"' not in line:
                        continue
                    try:
                        event = json.loads(line)
                    except ValueError:
                        continue
                    payload = event.get("payload", {})
                    limits = payload.get("rate_limits")
                    if payload.get("type") != "token_count" or not isinstance(limits, dict):
                        continue
                    if limits.get("limit_id", "codex") != "codex":
                        continue
                    found.append((str(event.get("timestamp", "")), limits))
                    break
            except OSError:
                continue
        if found:
            break
    if not found:
        return []
    stamp, limits = max(found, key=lambda x: x[0])
    result = []
    for key in ("primary", "secondary"):
        meter = limits.get(key)
        if not isinstance(meter, dict) or "used_percent" not in meter:
            continue
        minutes = int(number(meter.get("window_minutes")))
        label = "Weekly" if minutes == 10080 else ("5 hours" if minutes == 300 else f"{minutes} min")
        reset = number(meter.get("resets_at"))
        result.append({"provider": "Codex", "plan": str(limits.get("plan_type") or ""),
                       "label": label, "remainingPercent": round(max(0, min(100, 100 - number(meter["used_percent"]))), 1),
                       "resetAt": utcstamp(reset) if reset else "", "updatedAt": stamp,
                       "expired": bool(reset and reset < time.time())})
    return result


class SnapshotSource:
    def __init__(self, data_dir, codex_dir=None):
        self.data_dir = Path(data_dir)
        self.codex_dir = Path(codex_dir) if codex_dir else Path.home() / ".codex" / "sessions"
        self.lock = threading.Lock()
        self.last = None
        self.checked = 0.0
        self.quota_checked = 0.0
        self.quotas = []
        self.live = LiveCodexCounter(self.codex_dir)

    def snapshot(self):
        with self.lock:
            if self.last and time.monotonic() - self.checked < 1:
                return self.last
            archive_path = self.data_dir / "daily-history-archive.json"
            anchor_path = self.data_dir / "collector-anchor.json"
            try:
                archive = read_json(archive_path)
                if archive.get("version") != 1 or not isinstance(archive.get("days"), dict):
                    raise ValueError("unsupported usage archive")
                anchor = read_json(anchor_path) if anchor_path.exists() else {}
                today = dt.datetime.now(TAIPEI).date()
                date = today.isoformat()
                days = merge_days(archive, date)
                daily = summarize([days.get(date, {})])
                # The anchor is the desktop's live summary; graph files can lag it.
                if anchor.get("dateKey") == date and number(anchor.get("today", {}).get("totalTokens")) >= daily["totalTokens"]:
                    daily = anchor_summary(anchor["today"])
                    days[date] = {"observations": {x["name"]: {"modelId": x["name"], "tokens": x["tokens"],
                                  "cost": number(anchor["today"].get("modelCosts", {}).get(x["name"]))}
                                  for x in daily["models"]}}
                week_start = (today - dt.timedelta(days=6)).isoformat()
                weekly = summarize([v for k, v in days.items() if week_start <= k <= date])
                # Prefer desktop's active-source ALL TIME counters, matching its
                # selected collectors. Heatmap retains historical archived days.
                total = anchor_summary(anchor["allTime"]) if isinstance(anchor.get("allTime"), dict) else summarize(days.values())
                archive_mtime = max(p.stat().st_mtime for p in [archive_path, anchor_path] if p.exists())
                delta, live_timestamp, live_available = self.live.update(anchor_path.stat().st_mtime if anchor_path.exists() else archive_mtime, date, anchor)
                # Overlay is safe only when the archive Day has not already
                # advanced ahead of the anchor baseline.
                live_delta = sum(delta.values())
                ahead = max(0, daily["totalTokens"] - int(number(anchor.get("today", {}).get("totalTokens")))) if anchor.get("dateKey") == date else 0
                if ahead:
                    for name in list(delta):
                        remove = min(ahead, delta[name])
                        delta[name] -= remove
                        ahead -= remove
                live_delta = sum(delta.values())
                for period in (daily, weekly, total):
                    add_live_delta(period, delta)
                if live_delta:
                    observation_map = dict(days.get(date, {}).get("observations", {}))
                    for name, count in delta.items():
                        observation_map["live:" + name] = {"modelId": name, "tokens": count, "cost": 0}
                    days[date] = {"observations": observation_map}
                activity = [{"date": k, "tokens": day_tokens(v)} for k, v in sorted(days.items())]
                # The source persists daily buckets, so Day is a one-day sample.
                daily["trend"] = [{"date": date, "tokens": daily["totalTokens"]}]
                weekly["trend"] = [{"date": (today - dt.timedelta(days=i)).isoformat(),
                                     "tokens": day_tokens(days.get((today - dt.timedelta(days=i)).isoformat(), {}))}
                                    for i in reversed(range(7))]
                total["trend"] = activity
                if time.monotonic() - self.quota_checked >= 10:
                    self.quotas = codex_quotas(self.codex_dir, today)
                    self.quota_checked = time.monotonic()
                latest = max(archive_mtime, live_timestamp)
                self.last = {"schemaVersion": 1, "updatedAt": utcstamp(latest),
                             "source": "Token Monitor", "periods": {"day": daily, "week": weekly, "total": total},
                             "activity": activity, "quotas": self.quotas,
                             "sourceStatus": "fresh" if live_available or time.time() - latest < 120 else "stale",
                             "sourceNote": "Desktop archive + live Codex usage counters; cost waits for desktop scan; unavailable provider quotas omitted",
                             "costUpdatedAt": utcstamp(archive_mtime), "costPending": live_delta > 0,
                             "liveDeltaTokens": live_delta,
                             "refreshIntervalSeconds": 2, "sourceTimezone": "Asia/Taipei"}
                self.checked = time.monotonic()
                return self.last
            except (OSError, ValueError, KeyError, TypeError) as exc:
                if self.last:
                    result = dict(self.last)
                    result["sourceStatus"] = "stale"
                    result["sourceNote"] = "Desktop archive temporarily unavailable; showing last successful snapshot"
                    return result
                raise RuntimeError("Token Monitor usage archive unavailable. Open Token Monitor and wait for its scan.") from exc


class BridgeHandler(BaseHTTPRequestHandler):
    server_version = "TokenMonitorBridge/1.0"

    def setup(self):
        super().setup()
        self.connection.settimeout(10)

    def log_message(self, *args):
        pass  # Do not log pairing keys or private counters.

    def send_json(self, code, value):
        body = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.split('?',1)[0] in ('/v1/events','/v1/health') and len(self.path)<512:
            # Notification-only component has its own key and reads no usage data.
            request=urllib.request.Request('http://127.0.0.1:47827'+self.path,
                headers={'Authorization':self.headers.get('Authorization','')})
            try:
                with urllib.request.urlopen(request,timeout=25) as response:
                    data=response.read(16*1024*1024+1)
                    if len(data)>16*1024*1024:return self.send_json(502,{'error':'response_too_large'})
                    return self.send_json(200,json.loads(data))
            except urllib.error.HTTPError as error:
                return self.send_json(error.code,{'error':'unauthorized' if error.code in (401,403) else 'notification_unavailable'})
            except (OSError,ValueError):return self.send_json(503,{'error':'notification_component_offline'})
        if self.path == "/health":
            return self.send_json(200, {"service": "TokenMonitorBridge", "schemaVersion": 1})
        if self.path != "/api/snapshot":
            return self.send_json(404, {"error": "not_found"})
        supplied = self.headers.get("Authorization", "")
        if not hmac.compare_digest(supplied.encode("utf-8"), ("Bearer " + self.server.pairing_key).encode("utf-8")):
            return self.send_json(401, {"error": "unauthorized"})
        try:
            self.send_json(200, self.server.source.snapshot())
        except RuntimeError:
            self.send_json(503, {"error": "source_unavailable"})

    def do_POST(self):
        if self.path != "/api/rpc":
            return self.send_json(404, {"ok": False, "error": "not_found"})
        supplied = self.headers.get("Authorization", "")
        if not hmac.compare_digest(supplied.encode("utf-8"), ("Bearer " + self.server.pairing_key).encode("utf-8")):
            return self.send_json(401, {"ok": False, "error": "unauthorized"})
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 2 * 1024 * 1024:
                return self.send_json(413, {"ok": False, "error": "request_too_large"})
            data = json.loads(self.rfile.read(length))
            self.send_json(200, engine_rpc(data.get("channel"), data.get("args", []), self.server.pairing_key))
        except (ValueError, TypeError, AttributeError):
            self.send_json(400, {"ok": False, "error": "invalid_request"})


def bluetooth_client(client, source, pairing_key):
    client.settimeout(15)
    try:
        with client, client.makefile("rwb") as stream:
            def read():
                line = stream.readline(2 * 1024 * 1024 + 1)
                if not line or len(line) > 2 * 1024 * 1024 or not line.endswith(b"\n"):
                    raise ValueError("invalid message")
                value = json.loads(line)
                if not isinstance(value, dict):
                    raise ValueError("invalid message")
                return value

            def send(value):
                stream.write(json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8") + b"\n")
                stream.flush()

            auth = read()
            key = auth.get("key", "")
            if auth.get("type") != "auth" or not isinstance(key, str) or not hmac.compare_digest(key.encode(), pairing_key.encode()):
                send({"error": "unauthorized"})
                return
            send({"type": "authenticated"})
            while True:
                request = read()
                if request.get("type") == "rpc":
                    send(engine_rpc(request.get("channel"), request.get("args", []), pairing_key))
                    continue
                if request.get("type") != "snapshot":
                    send({"error": "unknown_request"})
                    continue
                try:
                    send(source.snapshot())
                except RuntimeError:
                    send({"error": "source_unavailable"})
    except (OSError, ValueError):
        pass


def make_http_server(host, port, source, pairing_key):
    server = ThreadingHTTPServer((host, port), BridgeHandler)
    server.daemon_threads = True
    server.source = source
    server.pairing_key = pairing_key
    return server


def pairing_key(path, rotate=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and not rotate:
        value = path.read_text("ascii").strip()
        if len(value) >= 16:
            return value
    value = secrets.token_hex(16)
    path.write_text(value + "\n", encoding="ascii")
    return value


def find_adb():
    import shutil
    path = shutil.which("adb")
    if path:
        return path
    local = Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk/platform-tools/adb.exe"
    if local.exists():
        return str(local)
    raise RuntimeError("ADB not found. Install Android SDK platform-tools or pass --adb.")


def setup_usb(adb, serial, port):
    command = [adb or find_adb()]
    command.extend(["-s", serial] if serial else ["-d"])
    # -d explicitly selects USB hardware, never a Wi-Fi ADB target.
    result = subprocess.run(command + ["reverse", f"tcp:{port}", f"tcp:{port}"],
                            capture_output=True, text=True, timeout=15)
    if result.returncode:
        raise RuntimeError("USB ADB setup failed: " + result.stderr.strip())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", default=str(Path(os.environ.get("APPDATA", "")) / "Token Monitor"))
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=PORT)
    parser.add_argument("--no-bluetooth", action="store_true")
    parser.add_argument("--tls-cert")
    parser.add_argument("--tls-key")
    parser.add_argument("--usb", action="store_true", help="Set up ADB reverse for one USB-connected Android phone")
    parser.add_argument("--serial", help="ADB serial (needed if multiple devices)")
    parser.add_argument("--adb")
    parser.add_argument("--rotate-key", action="store_true")
    parser.add_argument("--key-file", default=str(Path(os.environ.get("LOCALAPPDATA", ".")) / "TokenMonitorMobile/pairing.key"))
    parser.add_argument("--check", action="store_true", help="Print safe summary then exit")
    args = parser.parse_args()
    source = SnapshotSource(args.data_dir)
    if args.check:
        snap = source.snapshot()
        print(json.dumps({"source": snap["source"], "status": snap["sourceStatus"],
                          "updatedAt": snap["updatedAt"], "dayTokens": snap["periods"]["day"]["totalTokens"],
                          "modelCount": len(snap["periods"]["day"]["models"]),
                          "activityDays": len(snap["activity"]), "quotaRows": len(snap["quotas"])}, indent=2))
        return
    key = pairing_key(Path(args.key_file), args.rotate_key)
    server = make_http_server(args.host, args.port, source, key)
    if bool(args.tls_cert) != bool(args.tls_key):
        parser.error("TLS requires both certificate and key")
    if args.tls_cert:
        import ssl
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.minimum_version = ssl.TLSVersion.TLSv1_2
        tls.load_cert_chain(args.tls_cert, args.tls_key)
        # Perform the handshake in the request worker after its 10s timeout is
        # installed, so an idle public client cannot block the accept loop.
        server.socket = tls.wrap_socket(server.socket, server_side=True, do_handshake_on_connect=False)
    print("Token Monitor Mobile Bridge", flush=True)
    print(f"HTTP port: {args.port}; source: {args.data_dir}", flush=True)
    ips = sorted({item[4][0] for item in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)})
    print("Wi-Fi computer IP: " + ", ".join(ips), flush=True)
    if not args.tls_cert:
        print("Pairing key is stored locally. Run Copy-Pairing-Key.cmd to copy it.", flush=True)
    print("USB phone address: 127.0.0.1 (run Setup-USB.ps1 after plugging in)", flush=True)
    if args.usb:
        try:
            setup_usb(args.adb, args.serial, args.port)
            print("USB ADB reverse ready", flush=True)
        except (RuntimeError, OSError, subprocess.TimeoutExpired) as exc:
            print(str(exc), flush=True)
    bluetooth = None
    if not args.no_bluetooth:
        try:
            from windows_bluetooth import BluetoothServer
            bluetooth = BluetoothServer(SERVICE_UUID, lambda client: bluetooth_client(client, source, key))
            bluetooth.start()
            print(f"Bluetooth ready: {bluetooth.address}, RFCOMM channel {bluetooth.channel}", flush=True)
        except (OSError, RuntimeError) as exc:
            print(f"Bluetooth unavailable: {exc}. Wi-Fi/USB remain available.", flush=True)
    try:
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        if bluetooth:
            bluetooth.close()


if __name__ == "__main__":
    main()
