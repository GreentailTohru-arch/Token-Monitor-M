"""Notification-only Codex relay. Python 3.10+, standard library only.

Reads task and account events locally; never changes Codex config or Token Monitor.
The network API only exposes authenticated, short-lived Codex notification messages.
"""
import argparse
import base64
import collections
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import ssl
import threading
import time
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, parse_qs


def stamp(value):
    try:
        return datetime.fromisoformat(value.replace('Z', '+00:00')).timestamp()
    except (ValueError, AttributeError):
        return 0


def user_text(text):
    if not isinstance(text, str):
        return ''
    text = text.strip()
    # The desktop injects these as user-role context, not actual user speech.
    if text.startswith(('<environment_context>', '<send_user_message_question_reply>',
                        '<turn_aborted>', '<subagent_notification>', '# AGENTS.md instructions')):
        return ''
    if '## My request:' in text:
        text = text.split('## My request:', 1)[1].strip()
    if text.startswith('<image ') or text.startswith('<image>'):
        return ''
    return text


QUOTA_REASONS = {
    'rate_limit_reached': 'Codex 使用额度已达到上限。',
    'workspace_owner_credits_depleted': 'Codex 工作区积分已用完。',
    'workspace_member_credits_depleted': 'Codex 工作区积分已用完。',
    'workspace_owner_usage_limit_reached': 'Codex 工作区使用额度已达到上限。',
    'workspace_member_usage_limit_reached': 'Codex 工作区使用额度已达到上限。',
}


def input_question(payload):
    try:
        args = json.loads(payload.get('arguments', ''))
    except (TypeError, ValueError):
        return ''
    questions = args.get('questions') if isinstance(args, dict) else None
    if not isinstance(questions, list):
        return ''
    lines = [item.get('question') or item.get('title') for item in questions if isinstance(item, dict)]
    return '\n'.join(line.strip() for line in lines if isinstance(line, str) and line.strip())


def quota_notice(payload, now):
    if payload.get('type') == 'error':
        if payload.get('codex_error_info') in ('usage_limit_exceeded', 'usageLimitExceeded'):
            return payload.get('message') or QUOTA_REASONS['rate_limit_reached'], ''
        return None
    limits = payload.get('rate_limits')
    if payload.get('type') != 'token_count' or not isinstance(limits, dict):
        return None
    reason = limits.get('rate_limit_reached_type')
    if reason not in QUOTA_REASONS:
        return None
    resets = sorted({window['resets_at'] for name in ('primary', 'secondary')
                     if isinstance((window := limits.get(name)), dict)
                     and isinstance(window.get('resets_at'), int) and window['resets_at'] > now})
    identity = f"{limits.get('limit_id') or 'codex'}:{reason}:{resets[0]}" if resets else ''
    return QUOTA_REASONS[reason], identity


class TurnReader:
    def __init__(self):
        self.thread = ''
        self.turn = ''
        self.title = ''
        self.final = ''
        self.root = True
        self.source_seen = False
        self.completed = False
        self.quota_reported = False

    def accept(self, obj):
        if not isinstance(obj, dict):
            return None
        p = obj.get('payload', {})
        kind = obj.get('type')
        if not isinstance(p, dict):
            return None
        if kind == 'session_meta':
            # Fork rollouts can contain multiple metadata records. A subagent
            # origin remains a subagent, even if subsequent records say vscode.
            self.thread = self.thread or p.get('id', '')
            self.root = self.root and not isinstance(p.get('source'), dict)
            self.source_seen = True
        if kind == 'event_msg' and p.get('type') == 'task_started':
            self.turn = p.get('turn_id', '')
            self.title = self.final = ''
            self.completed = False
            self.quota_reported = False
        if kind == 'response_item' and p.get('type') == 'message':
            text = '\n'.join(c.get('text', '') for c in p.get('content', []) if isinstance(c, dict))
            if p.get('role') == 'user':
                text = user_text(text)
                if text and not self.title:
                    self.title = text
            elif p.get('role') == 'assistant' and p.get('phase') == 'final_answer':
                self.final = text
        if self.turn and self.root and self.source_seen and not self.completed:
            if kind == 'response_item' and p.get('type') == 'function_call':
                call = p.get('call_id')
                if call and p.get('name') in ('request_user_input', 'request_user_input_async'):
                    question = input_question(p)
                    if question:
                        return self.event(obj, 'input', f'{self.thread}:{self.turn}:input:{call}',
                                          self.title or 'Codex 需要输入', question)
            if kind == 'event_msg' and not self.quota_reported:
                quota = quota_notice(p, stamp(obj.get('timestamp', '')))
                if quota:
                    self.quota_reported = True
                    body, identity = quota
                    return self.event(obj, 'quota', f'quota:{identity or self.thread + ":" + self.turn}',
                                      'Codex 额度不足', body)
        if kind == 'event_msg':
            if p.get('type') == 'user_message' and not self.title:
                self.title = user_text(p.get('message', ''))
            if p.get('type') == 'turn_aborted':
                self.completed = True
            if p.get('type') == 'task_complete':
                body = p.get('last_agent_message') or self.final
                turn = p.get('turn_id') or self.turn
                if self.completed or not self.root or not self.source_seen or not self.title or not body or turn != self.turn:
                    return None
                self.completed = True
                return self.event(obj, 'complete', f'{self.thread}:{turn}', self.title, body)
        return None

    @staticmethod
    def event(obj, kind, identity, title, body):
        return dict(id=hashlib.sha256(identity.encode()).hexdigest(), kind=kind,
                    title=title[:2000], body=body[:32000], time=stamp(obj.get('timestamp', '')))


class Events:
    def __init__(self):
        self.epoch = secrets.token_hex(8)
        self.sequence = 0
        self.items = collections.deque(maxlen=100)
        self.seen = collections.deque(maxlen=2000)
        self.condition = threading.Condition()

    def publish(self, event):
        with self.condition:
            if event['id'] in self.seen:
                return
            self.seen.append(event['id'])
            self.sequence += 1
            self.items.append(dict(event, seq=self.sequence))
            self.condition.notify_all()

    def poll(self, cursor, wait=20):
        with self.condition:
            try:
                epoch, number = cursor.split(':')
                number = int(number)
                valid = epoch == self.epoch and 0 <= number <= self.sequence
            except (ValueError, AttributeError):
                valid = False
                number = 0
            # First pairing / server restart establishes a baseline, never replays history.
            if not valid:
                return dict(cursor=f'{self.epoch}:{self.sequence}', events=[])
            if number == self.sequence:
                self.condition.wait(wait)
            items = [x for x in self.items if x['seq'] > number and x['time'] >= time.time() - 86400]
            return dict(cursor=f'{self.epoch}:{self.sequence}', events=items)


class SessionNames:
    def __init__(self, index):
        self.index = index
        self.revision = None
        self.names = {}

    def get(self, thread):
        try:
            stat = self.index.stat()
            revision = (stat.st_mtime_ns, stat.st_size)
            if revision != self.revision:
                names = {}
                with self.index.open(encoding='utf-8-sig') as source:
                    for line in source:
                        try:
                            entry = json.loads(line)
                        except ValueError:
                            continue
                        if not isinstance(entry, dict):
                            continue
                        session_id = entry.get('id')
                        name = entry.get('thread_name')
                        if isinstance(session_id, str) and isinstance(name, str) and name.strip():
                            names[session_id] = ' '.join(name.split())[:160]
                self.names = names
                self.revision = revision
        except (OSError, UnicodeError):
            pass
        return self.names.get(thread, '')


class Watcher:
    def __init__(self, sessions, events):
        self.sessions = Path(sessions)
        self.names = SessionNames(self.sessions.parent / 'session_index.jsonl')
        self.events = events
        self.since = int(time.time())  # Codex event timestamps have millisecond precision.
        self.files = {}
        self.ready = False

    def scan(self):
        # Enumerate even old dated folders: users can resume an old conversation.
        for path in self.sessions.rglob('*.jsonl'):
            try:
                size = path.stat().st_size
                entry = self.files.get(path)
                if entry is None:
                    baseline = size if not self.ready else 0
                    # Do not begin a future read in the middle of a record that
                    # Codex was still appending at startup.
                    if baseline:
                        with path.open('rb') as f:
                            f.seek(baseline - 1)
                            if f.read(1) != b'\n':
                                f.seek(max(0, baseline - 2 * 1024 * 1024))
                                tail = f.read()
                                last = tail.rfind(b'\n')
                                baseline = baseline - len(tail) + last + 1 if last >= 0 else 0
                    entry = self.files[path] = [baseline, None]
                offset, reader = entry
                if size == offset:
                    continue
                if size < offset:
                    offset, reader = 0, None
                if reader is None:
                    reader = TurnReader()
                    # Recover only parsing state for in-flight turns; suppress old events.
                    with path.open('rb') as f:
                        while f.tell() < offset:
                            line = f.readline()
                            if not line:
                                break
                            self.consume(reader, line, False)
                with path.open('rb') as f:
                    f.seek(offset)
                    while f.tell() < size:
                        position = f.tell()
                        line = f.readline()
                        if not line.endswith(b'\n'):
                            f.seek(position)
                            break
                        self.consume(reader, line, True)
                    self.files[path] = [f.tell(), reader]
            except (OSError, ValueError):
                continue
        self.ready = True

    def consume(self, reader, line, live):
        # Tool outputs may be enormous. Required messages are bounded for notifications.
        if len(line) > 2 * 1024 * 1024:
            return
        try:
            event = reader.accept(json.loads(line))
        except (ValueError, TypeError, KeyError):
            return
        if live and event and event['time'] >= self.since:
            name = self.names.get(reader.thread)
            if name:
                event['sessionName'] = name
            self.events.publish(event)

    def run(self):
        while True:
            self.scan()
            time.sleep(2)


def make_handler(events, key):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def log_message(self, *args):
            pass  # Never put conversation text or authorization in logs.

        def do_GET(self):
            self.connection.settimeout(30)
            supplied = self.headers.get('Authorization', '')
            if not hmac.compare_digest(supplied.encode(), ('Bearer ' + key).encode()):
                self.respond(401, {'error': 'unauthorized'})
                return
            url = urlsplit(self.path)
            if url.path == '/v1/events':
                query = parse_qs(url.query)
                cursor = query.get('cursor', [''])[0][:100]
                result = events.poll(cursor, 0 if query.get('wait') == ['0'] else 20)
                if query.get('kinds') != ['1']:
                    result['events'] = [event for event in result['events'] if event.get('kind', 'complete') == 'complete']
                self.respond(200, result)
            elif url.path == '/v1/health':
                self.respond(200, {'ok': True, 'component': 'codex-notifications', 'version': '0.0.2'})
            else:
                self.respond(404, {'error': 'not_found'})

        def respond(self, code, data):
            encoded = json.dumps(data, ensure_ascii=False).encode('utf-8')
            try:
                self.send_response(code)
                self.send_header('Content-Type', 'application/json; charset=utf-8')
                self.send_header('Cache-Control', 'no-store')
                self.send_header('Content-Length', str(len(encoded)))
                self.send_header('Connection', 'close')
                self.end_headers()
                self.wfile.write(encoded)
            except OSError:
                pass
            self.close_connection = True
    return Handler


class RelayServer(ThreadingHTTPServer):
    """Bound network resources; perform TLS handshakes outside the accept loop."""
    daemon_threads = True
    def __init__(self, address, handler, tls=None):
        self.tls = tls
        self.slots = threading.BoundedSemaphore(16)
        super().__init__(address, handler)

    def process_request(self, request, client_address):
        request.settimeout(10)
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            if self.tls:
                request = self.tls.wrap_socket(request, server_side=True)
            super().process_request_thread(request, client_address)
        except (OSError, ssl.SSLError):
            self.shutdown_request(request)
        finally:
            self.slots.release()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', required=True)
    args = parser.parse_args()
    config = json.loads(Path(args.config).read_text('utf-8-sig'))
    events = Events()
    watcher = Watcher(config['sessions'], events)
    watcher.scan()
    context = None
    if config.get('cert'):
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(config['cert'], config['privateKey'])
    server = RelayServer((config.get('bind', '127.0.0.1'), config.get('port', 47824)), make_handler(events, config['key']), context)
    threading.Thread(target=watcher.run, daemon=True).start()
    Path(args.config).with_name('notifier.pid').write_text(str(os.getpid()))
    server.serve_forever(poll_interval=0.5)


if __name__ == '__main__':
    main()
