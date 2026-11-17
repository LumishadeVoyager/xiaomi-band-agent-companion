"""Build signed Android preview companions using the official Xiaomi SDK."""
import argparse
from pathlib import Path
import os
import subprocess
import sys
import zipfile
import shutil

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--jdk', type=Path, required=True)
parser.add_argument('--platform', type=Path, required=True, help='Directory containing android.jar')
parser.add_argument('--build-tools', type=Path, required=True)
parser.add_argument('--wearable-aar', type=Path, required=True, help='Official SDK AAR, never committed')
parser.add_argument('--openssl', type=Path, default=Path(r'C:\Program Files\Git\usr\bin\openssl.exe'))
parser.add_argument('--development-usb', action='store_true', help='PRIVATE builds only: embed the local USB audio token; never publish these APKs')
args = parser.parse_args()
work_root = Path(os.environ.get('LOCALAPPDATA', ROOT / '.local')) / 'BandAgentDev' / 'android-build'


def run(command):
    build_env = dict(os.environ)
    build_env['JAVA_TOOL_OPTIONS'] = '-Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8'
    result = subprocess.run([str(item) for item in command], capture_output=True, text=True, encoding='utf-8', errors='replace', env=build_env)
    if result.returncode:
        print(result.stdout)
        print(result.stderr)
        raise SystemExit('Android development build failed')


sign = ROOT / '.local/sign/debug'
sign.mkdir(parents=True, exist_ok=True)
key, certificate = sign / 'private.pem', sign / 'certificate.pem'
if not key.exists() and not certificate.exists():
    run([args.openssl, 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '365', '-subj', '/CN=Band Agent Development/O=Development Only', '-keyout', key, '-out', certificate])
if not key.exists() or not certificate.exists():
    raise SystemExit('Incomplete development signing identity; refusing to overwrite it')
der_key = sign / 'private.pk8'
run([args.openssl, 'pkcs8', '-topk8', '-inform', 'PEM', '-outform', 'DER', '-in', key, '-out', der_key, '-nocrypt'])
token = ROOT / '.local/receiver/token'
if args.development_usb and not token.is_file():
    raise SystemExit('Start npm run dev:receiver first to initialize the private USB development token')

import json
version = json.loads((ROOT / 'package.json').read_text(encoding='utf-8'))['version']
version_code = json.loads((ROOT / 'package.json').read_text(encoding='utf-8'))['buildNumber']
update_public = ROOT / 'updates/trust-root.pem'
if not update_public.is_file():
    raise SystemExit('Run node scripts/init-update-signing.cjs first; update root must stay pinned')
for entry, label in [('codex', 'Codex 伴侣（预览）'), ('harness', 'Harness 伴侣（预览）')]:
    work = work_root / entry
    classes, dex, assets = work / 'classes', work / 'dex', work / 'assets'
    for directory in [classes, dex, assets]:
        directory.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.wearable_aar) as archive:
        (work / 'wearable.jar').write_bytes(archive.read('classes.jar'))
    (assets / 'development.json').write_text(json.dumps({'entryId': entry, 'development': args.development_usb, 'token': token.read_text().strip() if args.development_usb else ''}), encoding='utf-8')
    shutil.copyfile(update_public, assets / 'update-public.pem')
    rpk_candidates = list((ROOT / '.local/artifacts' / entry).glob(f'*.debug.{version}.rpk'))
    if len(rpk_candidates) != 1:
        raise SystemExit('Build the matching current RPK before the Android APK')
    shutil.copyfile(rpk_candidates[0], assets / 'wearable.rpk')
    package = 'io.github.lumishadevoyager.bandagent.' + entry
    manifest = work / 'AndroidManifest.xml'
    manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}" android:versionCode="{version_code}" android:versionName="{version}">
<uses-sdk android:minSdkVersion="33" android:targetSdkVersion="35"/>
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"/>
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/>
<queries><package android:name="com.mi.health"/><package android:name="com.xiaomi.wearable"/><package android:name="moe.astralsight.astrobox"/></queries>
<application android:label="{label}" android:allowBackup="false" android:usesCleartextTraffic="true" android:theme="@android:style/Theme.Material.Light.NoActionBar">
<service android:name="io.github.lumishadevoyager.bandagent.BridgeService" android:exported="false" android:foregroundServiceType="connectedDevice"/>
<receiver android:name="io.github.lumishadevoyager.bandagent.InstallResultReceiver" android:exported="false"/>
<receiver android:name="io.github.lumishadevoyager.bandagent.RestartReceiver" android:exported="false"><intent-filter><action android:name="android.intent.action.MY_PACKAGE_REPLACED"/><action android:name="android.intent.action.BOOT_COMPLETED"/></intent-filter></receiver>
<provider android:name="io.github.lumishadevoyager.bandagent.ProbeFileProvider" android:authorities="{package}.probe-files" android:exported="false" android:grantUriPermissions="true"/>
<activity android:name="io.github.lumishadevoyager.bandagent.BridgeActivity" android:exported="true" android:launchMode="singleTask"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity>
<activity android:name="io.github.lumishadevoyager.bandagent.RecordingIntakeActivity" android:label="发送到 {entry} 手环语音" android:exported="true"><intent-filter><action android:name="android.intent.action.SEND"/><category android:name="android.intent.category.DEFAULT"/><data android:mimeType="audio/*"/><data android:mimeType="application/octet-stream"/></intent-filter></activity>
</application></manifest>''', encoding='utf-8')
    android_jar = args.platform / 'android.jar'
    sources = list((ROOT / 'android-probe/src').rglob('*.java'))
    run([args.jdk / 'bin/javac.exe', '-source', '8', '-target', '8', '-encoding', 'UTF-8', '-classpath', str(android_jar) + os.pathsep + str(work / 'wearable.jar'), '-d', classes, *sources])
    class_jar = work / 'application.jar'
    with zipfile.ZipFile(class_jar, 'w') as archive:
        for file in classes.rglob('*.class'):
            archive.write(file, file.relative_to(classes).as_posix())
    run([args.jdk / 'bin/java.exe', '-cp', args.build_tools / 'lib/d8.jar', 'com.android.tools.r8.D8', '--min-api', '33', '--lib', android_jar, '--output', dex, class_jar, work / 'wearable.jar'])
    unsigned = work / 'unsigned.apk'
    run([args.build_tools / 'aapt2.exe', 'link', '--manifest', manifest, '-I', android_jar, '-A', assets, '-o', unsigned])
    with zipfile.ZipFile(unsigned, 'a') as archive:
        archive.write(dex / 'classes.dex', 'classes.dex')
    aligned = work / 'aligned.apk'
    run([args.build_tools / 'zipalign.exe', '-f', '4', unsigned, aligned])
    output = ROOT / '.local/artifacts' / f'band-agent-{entry}-probe.apk'
    output.parent.mkdir(parents=True, exist_ok=True)
    run([args.jdk / 'bin/java.exe', '-jar', args.build_tools / 'lib/apksigner.jar', 'sign', '--key', der_key, '--cert', certificate, '--out', output, aligned])
    run([args.jdk / 'bin/java.exe', '-jar', args.build_tools / 'lib/apksigner.jar', 'verify', output])
    print(f'Built and verified signed companion: {entry}')
