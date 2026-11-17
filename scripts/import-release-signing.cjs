const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '..');
const privateKey = process.env.BAND_AGENT_APP_KEY, certificate = process.env.BAND_AGENT_APP_CERT, updateKey = process.env.BAND_AGENT_UPDATE_KEY;
if (!privateKey || !certificate || !updateKey) throw new Error('Three protected repository signing secrets required');
const cert = new crypto.X509Certificate(certificate);
if (!crypto.createPublicKey(privateKey).export({ type: 'spki', format: 'der' }).equals(cert.publicKey.export({ type: 'spki', format: 'der' }))) throw new Error('Application key and certificate mismatch');
if (!crypto.createPublicKey(updateKey).export({ type: 'spki', format: 'der' }).equals(crypto.createPublicKey(fs.readFileSync(path.join(root, 'updates/trust-root.pem'))).export({ type: 'spki', format: 'der' }))) throw new Error('Protected signing key does not match pinned update root');
for (const [file, contents] of [['.local/sign/debug/private.pem', privateKey], ['.local/sign/debug/certificate.pem', certificate], ['.local/sign/updates/root-private.pem', updateKey]]) {
  const target = path.join(root, file); fs.mkdirSync(path.dirname(target), { recursive: true }); fs.writeFileSync(target, contents, { mode: 0o600 });
}
console.log('Protected signing identities imported and matched; no keys printed.');
