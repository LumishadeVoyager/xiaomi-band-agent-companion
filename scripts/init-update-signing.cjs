const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '..');
const directory = path.join(root, '.local/sign/updates');
const privatePath = path.join(directory, 'root-private.pem');
const publicPath = path.join(root, 'updates/trust-root.pem');
if (fs.existsSync(privatePath) !== fs.existsSync(publicPath)) throw new Error('Incomplete update signing identity; refusing to replace a trust root');
if (!fs.existsSync(privatePath)) {
  const keys = crypto.generateKeyPairSync('rsa', { modulusLength: 3072, publicKeyEncoding: { type: 'spki', format: 'pem' }, privateKeyEncoding: { type: 'pkcs8', format: 'pem' } });
  fs.mkdirSync(directory, { recursive: true }); fs.mkdirSync(path.dirname(publicPath), { recursive: true });
  fs.writeFileSync(privatePath, keys.privateKey, { mode: 0o600 }); fs.writeFileSync(publicPath, keys.publicKey);
}
const pinned = crypto.createPublicKey(fs.readFileSync(publicPath)).export({ type: 'spki', format: 'der' });
const actual = crypto.createPublicKey(fs.readFileSync(privatePath)).export({ type: 'spki', format: 'der' });
if (!pinned.equals(actual)) throw new Error('Update signing key does not match pinned public root');
console.log('Pinned update public key verified: ' + crypto.createHash('sha256').update(pinned).digest('hex'));
