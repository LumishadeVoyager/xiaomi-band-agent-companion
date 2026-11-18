"""Refuse APKs carrying development credentials or mismatched package metadata."""
import json
from pathlib import Path
import zipfile
import sys

root = Path(__file__).resolve().parents[1]
package = json.loads((root / 'package.json').read_text(encoding='utf-8'))
entries = ['powerprofile'] if '--powerprofile' in sys.argv else ['codex', 'harness']
for entry in entries:
    with zipfile.ZipFile(root / f'.local/artifacts/band-agent-{entry}-probe.apk') as apk:
        config = json.loads(apk.read('assets/development.json'))
        assert config['entryId'] == entry and config['token'] == '' and config['development'] is False, 'Private development APK must never be published'
        assert apk.read('assets/update-public.pem') == (root / 'updates/trust-root.pem').read_bytes()
        with zipfile.ZipFile(__import__('io').BytesIO(apk.read('assets/wearable.rpk'))) as rpk:
            assert not any(name.endswith(('.pem', '.pk8', '.key', '.keystore', '.p12')) for name in rpk.namelist()), 'Signing material must never enter RPK contents'
            manifest = json.loads(rpk.read('manifest.json'))
            assert manifest['package'] == 'io.github.lumishadevoyager.bandagent.' + entry
            assert manifest['versionName'] == package['version'] and manifest['versionCode'] == package['buildNumber']
        assert not any(name.endswith(('.pem', '.pk8', '.keystore', '.p12')) and name != 'assets/update-public.pem' for name in apk.namelist())
print('Audited ' + ', '.join(entries) + ': no development token/private key; embedded RPK versions and update roots match (not device acceptance)')
