"""Transfer an existing local Xiaomi session to the paired phone, sealed + signed.

Run with PowerProfile's energy-cloud Python (pycryptodome installed). No password,
refresh/pass token, device identity or decrypted session is printed or saved here.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import sys
import time
import urllib.request
import uuid
from Crypto.Cipher import AES
from Crypto.Hash import SHA256
from Crypto.PublicKey import RSA
from Crypto.Signature import pkcs1_15

ROOT = Path(__file__).resolve().parents[1]

def post(settings, route, body):
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs): return None
    request = urllib.request.Request(settings['endpoint'] + route, json.dumps(body).encode(), headers={
        'Authorization': 'Bearer ' + settings['pcToken'], 'Content-Type': 'application/json', 'User-Agent': 'PowerProfile/MijiaPairing'})
    with urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect()).open(request, timeout=20) as response:
        return json.loads(response.read(24000))

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--powerprofile-root', type=Path, default=Path('E:/PowerProfile'))
    parser.add_argument('--active', choices=['computer', 'purifier'], default='purifier')
    parser.add_argument('--test-purifier', action='store_true', help='Authorize one phone-side purifier toggle and restoration only')
    parser.add_argument('--publish', action='store_true')
    parser.add_argument('--status', action='store_true')
    parser.add_argument('--phone-status', action='store_true')
    args = parser.parse_args()
    settings = json.loads((ROOT / '.local/powerprofile-relay.json').read_text(encoding='utf-8-sig'))
    identity = {'protocol': 1, 'entryId': 'powerprofile'}
    if args.phone_status:
        print(json.dumps(post(settings, '/relay/phone-report', identity), ensure_ascii=True)); return
    if args.status:
        print(json.dumps(post(settings, '/relay/mijia-report', identity), ensure_ascii=True)); return
    if not args.publish: parser.error('--publish required; no implicit device command')
    if args.test_purifier and args.active != 'purifier': parser.error('test can only select purifier')
    sys.path.insert(0, str(args.powerprofile_root))
    from PowerProfileCloudHistory import client
    api = client()
    status = json.loads((args.powerprofile_root / '.codex/device-hub/status.json').read_text(encoding='utf-8-sig'))
    devices = status['devices']
    def target(role):
        matches = [d for d in devices if (d.get('computerPower') and d['model'] == 'cuco.plug.v3' if role == 'computer' else '.airpurifier.' in d['model']) and d.get('switch')]
        if len(matches) != 1: raise ValueError('target_not_unique')
        d = matches[0]
        return {'did': d['did'], 'model': d['model'], **d['switch']}
    targets = {role: target(role) for role in ('computer', 'purifier')}
    # Refresh the existing session using a READ of the purifier only.
    probe = {key: targets['purifier'][key] for key in ('did', 'siid', 'piid')}
    result = api.get_devices_prop(probe)
    if result.get('code') != 0 or type(result.get('value')) is not bool: raise ValueError('purifier_read_failed')
    auth = {key: str(api.auth_data[key]) for key in ('cUserId', 'serviceToken', 'ssecurity')}
    auth.update(userAgent=api.user_agent, deviceId=api.deviceId)
    now = int(time.time())
    payload = {'purpose': 'powerprofile_mijia_pairing', 'repository': 'LumishadeVoyager/xiaomi-band-agent-companion',
               'appId': 'io.github.lumishadevoyager.bandagent.powerprofile', 'issuedAt': now, 'expiresAt': now+30*86400,
               'revision': time.time_ns()//1000000, 'activeTarget': args.active, 'auth': auth, 'targets': targets}
    if args.test_purifier: payload['acceptanceTest'] = {'id': uuid.uuid4().hex, 'expiresAt': now+1800}
    raw = json.dumps(payload, separators=(',', ':')).encode()
    private = RSA.import_key((ROOT / '.local/sign/updates/root-private.pem').read_bytes())
    signature = pkcs1_15.new(private).sign(SHA256.new(raw))
    pkcs1_15.new(RSA.import_key((ROOT / 'updates/trust-root.pem').read_bytes())).verify(SHA256.new(raw), signature)
    b64 = lambda value: base64.b64encode(value).decode()
    signed = json.dumps({'schema': 1, 'algorithm': 'RS256', 'payload': b64(raw), 'signature': b64(signature)}).encode()
    secrets = json.loads((ROOT / '.local/relay-secrets.json').read_text(encoding='utf-8-sig'))
    key = hashlib.sha256(('powerprofile-mijia-v1\0'+secrets['PHONE_TOKEN']).encode()).digest()
    cipher = AES.new(key, AES.MODE_GCM, nonce=__import__('os').urandom(12)); cipher.update(b'powerprofile-mijia-v1')
    encrypted, tag = cipher.encrypt_and_digest(signed)
    response = post(settings, '/relay/mijia-config', {**identity, 'config': {'nonce': b64(cipher.nonce), 'ciphertext': b64(encrypted+tag)}})
    if response.get('ok') is not True: raise ValueError('pairing_not_accepted')
    print(json.dumps({'published': True, 'activeTarget': args.active, 'revision': payload['revision'], 'testId': payload.get('acceptanceTest', {}).get('id'), 'purifierOriginal': result['value']}))

if __name__ == '__main__':
    try: main()
    except Exception as error:
        # Library/network errors can contain sessions or device IDs. Redact them.
        print('Mijia pairing failed: ' + type(error).__name__, file=sys.stderr); sys.exit(1)
