"""Create a private pairing configuration. Does not print the secret."""
import argparse, base64, hashlib, json, os, secrets, ssl
from pathlib import Path

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--url', required=True)
    p.add_argument('--cert')
    p.add_argument('--private-key')
    p.add_argument('--behind-proxy', action='store_true', help='Serve HTTP on loopback; use --cert only for the public pairing fingerprint')
    p.add_argument('--port', type=int, default=47824)
    p.add_argument('--bind', default='127.0.0.1')
    p.add_argument('--state', default=str(Path.home()/'TokenMonitorNotifications'))
    p.add_argument('--sessions', default=str(Path(os.environ.get('CODEX_HOME', str(Path.home()/'.codex')))/'sessions'))
    a=p.parse_args()
    if a.url.startswith('https://') and not (a.cert and (a.private_key or a.behind_proxy)):
        p.error('HTTPS requires --cert and --private-key')
    state=Path(a.state);state.mkdir(parents=True,exist_ok=True)
    config_file=state/'config.private.json'
    old=json.loads(config_file.read_text('utf8')) if config_file.exists() else {}
    key=old.get('key') or secrets.token_urlsafe(32)
    if a.behind_proxy and a.bind!='127.0.0.1':p.error('Proxy backend must bind to loopback')
    config=dict(sessions=a.sessions,bind=a.bind,port=a.port,key=key,cert=None if a.behind_proxy else a.cert,privateKey=None if a.behind_proxy else a.private_key)
    config_file.write_text(json.dumps(config,indent=2),encoding='utf8')
    pin=hashlib.sha256(ssl.PEM_cert_to_DER_cert(Path(a.cert).read_text())).hexdigest() if a.cert else ''
    pairing=dict(url=a.url.rstrip('/'),key=key,pin=pin)
    code='TMN1.'+base64.urlsafe_b64encode(json.dumps(pairing,separators=(',',':')).encode()).decode().rstrip('=')
    (state/'pairing.txt').write_text(code,encoding='utf8')
    print('Private pairing saved in '+str(state/'pairing.txt'))

if __name__=='__main__':main()
