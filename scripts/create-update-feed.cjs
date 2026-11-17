// Generate from real artifacts; secrets never enter the feed or APK assets.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '..');
const pkg = require('../package.json');
const version = pkg.version, versionCode = pkg.buildNumber;
if (!/^\d+\.\d+\.\d+-preview\.\d+$/.test(version) || !Number.isSafeInteger(versionCode)) throw new Error('Preview version and numeric buildNumber required');
const output = path.join(root, '.local/release', version); fs.mkdirSync(output, { recursive: true });
const certificate = new crypto.X509Certificate(fs.readFileSync(path.join(root, '.local/sign/debug/certificate.pem')));
const signerSha256 = crypto.createHash('sha256').update(certificate.raw).digest('hex');
const components = [];
for (const entry of ['codex', 'harness']) {
  for (const kind of ['android', 'wearable']) {
    const filename = 'band-agent-' + entry + '-' + version + (kind === 'android' ? '.apk' : '.rpk');
    const source = kind === 'android' ? path.join(root, '.local/artifacts/band-agent-' + entry + '-probe.apk') : path.join(root, '.local/artifacts', entry, 'io.github.lumishadevoyager.bandagent.' + entry + '.debug.' + version + '.rpk');
    const bytes = fs.readFileSync(source); if (!bytes.length) throw new Error('empty_artifact'); fs.copyFileSync(source, path.join(output, filename));
    components.push({ entryId: entry, kind, appId: 'io.github.lumishadevoyager.bandagent.' + entry, version, versionCode, models: ['M2551B1'], regions: ['CN'], protocolMin: 1, protocolMax: 1, bridgeMinCode: 4, size: bytes.length, sha256: crypto.createHash('sha256').update(bytes).digest('hex'), signerSha256, url: 'https://github.com/LumishadeVoyager/xiaomi-band-agent-companion/releases/download/v' + version + '/' + filename });
  }
}
const now = Math.floor(Date.now() / 1000);
const payload = Buffer.from(JSON.stringify({ schema: 1, repository: 'LumishadeVoyager/xiaomi-band-agent-companion', channel: 'preview', issuedAt: now, expiresAt: now + 14 * 86400, release: version, components }));
const privateKey = fs.readFileSync(path.join(root, '.local/sign/updates/root-private.pem'));
const signature = crypto.sign('sha256', payload, privateKey);
if (!crypto.verify('sha256', payload, fs.readFileSync(path.join(root, 'updates/trust-root.pem')), signature)) throw new Error('update_signing_identity_mismatch');
const envelope = JSON.stringify({ schema: 1, algorithm: 'RS256', payload: payload.toString('base64'), signature: signature.toString('base64') }, null, 2) + '\n';
fs.writeFileSync(path.join(output, 'preview.json'), envelope);
fs.writeFileSync(path.join(output, 'SHA256SUMS.txt'), components.map(c => c.sha256 + '  ' + path.basename(c.url)).join('\n') + '\n');
console.log('Signed preview feed and four real artifacts prepared for ' + version + '; publish artifacts before promoting the feed.');
