// Stage only an authenticated feed and its exact audited release artifacts.
const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto');
const root = path.resolve(__dirname, '..'), version = require('../package.json').version;
const source = path.join(root, '.local/release', version);
const feedFile = path.join(source, 'powerprofile-preview.json');
const envelope = JSON.parse(fs.readFileSync(feedFile));
const payload = Buffer.from(envelope.payload, 'base64');
if (envelope.schema !== 1 || envelope.algorithm !== 'RS256' || !crypto.verify('sha256', payload, fs.readFileSync(path.join(root, 'updates/trust-root.pem')), Buffer.from(envelope.signature, 'base64'))) throw Error('invalid_feed_signature');
const feed = JSON.parse(payload);
if (feed.repository !== 'LumishadeVoyager/xiaomi-band-agent-companion' || feed.channel !== 'preview' || feed.release !== version || feed.expiresAt <= Date.now()/1000 || feed.components.length !== 2) throw Error('invalid_feed_identity');
const destination = path.join(root, 'pages-relay/public/ota');
for (const item of feed.components) {
  const expected = `https://github.com/${feed.repository}/releases/download/v${version}/band-agent-powerprofile-${version}.${item.kind === 'android' ? 'apk' : 'rpk'}`;
  if (item.entryId !== 'powerprofile' || !['android','wearable'].includes(item.kind) || item.url !== expected) throw Error('invalid_artifact_identity');
  const filename = path.basename(new URL(item.url).pathname), bytes = fs.readFileSync(path.join(source, filename));
  if (bytes.length !== item.size || crypto.createHash('sha256').update(bytes).digest('hex') !== item.sha256) throw Error('artifact_hash_mismatch');
  const out = path.join(destination, 'releases/download', 'v'+version); fs.mkdirSync(out, {recursive:true}); fs.writeFileSync(path.join(out, filename), bytes);
}
// Promotion is the last local write; deploy atomically after GitHub publication.
fs.copyFileSync(feedFile, path.join(destination, 'powerprofile-preview.json'));
console.log('Staged verified PowerProfile OTA feed and two matching artifacts; publish GitHub artifacts before deploying Pages.');
