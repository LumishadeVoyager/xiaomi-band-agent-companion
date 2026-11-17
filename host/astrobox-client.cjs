// AstroBox Local API v2. Credentials stay in a caller-selected private file.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
class AstroBoxClient {
  constructor(credentialsFile, base = 'http://127.0.0.1:10721') {
    if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(base)) throw new Error('loopback_required');
    this.base = base; this.file = credentialsFile;
    if (fs.existsSync(this.file)) this.identity = JSON.parse(fs.readFileSync(this.file, 'utf8'));
    else {
      const keys = crypto.generateKeyPairSync('ed25519');
      const publicKey = keys.publicKey.export({ format: 'jwk' }).x;
      this.identity = { publicKey, privateKey: keys.privateKey.export({ format: 'der', type: 'pkcs8' }).toString('base64'), fingerprint: crypto.createHash('sha256').update(Buffer.from(publicKey, 'base64url')).digest('hex') };
      this.save();
    }
  }
  save() { fs.mkdirSync(path.dirname(this.file), { recursive: true }); fs.writeFileSync(this.file, JSON.stringify(this.identity), { mode: 0o600 }); }
  async request(route, options = {}, authenticated = true) {
    const headers = new Headers(options.headers);
    if (authenticated) headers.set('Authorization', 'Bearer ' + await this.session());
    const response = await fetch(this.base + route, { ...options, headers, signal: AbortSignal.timeout(15000) });
    const text = await response.text(); const result = text ? JSON.parse(text) : null;
    if (!response.ok) { const error = new Error(result?.error?.code || 'astrobox_http_' + response.status); error.status = response.status; throw error; }
    return result;
  }
  json(route, body, authenticated = true) { return this.request(route, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }, authenticated); }
  async pair() {
    const result = await this.json('/v2/auth/pairing-requests', { clientName: 'Band Agent Development', publicKey: this.identity.publicKey, requestedScopes: ['device.read', 'device.control', 'resource.read', 'resource.install', 'queue.read', 'queue.control'], deviceAllowlist: [] }, false);
    this.identity.pairingId = result.pairingId; this.save(); return result;
  }
  pairingStatus() { return this.request('/v2/auth/pairing-requests/' + encodeURIComponent(this.identity.pairingId), {}, false); }
  async session() {
    if (this.token && Date.parse(this.expiresAt) > Date.now() + 5000) return this.token;
    const challenge = await this.json('/v2/auth/challenges', { fingerprint: this.identity.fingerprint }, false);
    const key = crypto.createPrivateKey({ key: Buffer.from(this.identity.privateKey, 'base64'), format: 'der', type: 'pkcs8' });
    const signature = crypto.sign(null, Buffer.from(challenge.signingPayload, 'base64url'), key).toString('base64url');
    const session = await this.json('/v2/auth/sessions', { challengeId: challenge.challengeId, signature }, false);
    this.token = session.token; this.expiresAt = session.expiresAt; return this.token;
  }
  devices() { return this.request('/v2/devices'); }
  apps(deviceId) { return this.request('/v2/devices/' + encodeURIComponent(deviceId) + '/quick-apps'); }
  async install(deviceId, file) {
    const bytes = fs.readFileSync(file); if (!bytes.length || bytes.length > 2 * 1024 * 1024) throw new Error('invalid_rpk_size');
    const form = new FormData(); form.append('file', new Blob([bytes]), path.basename(file));
    const upload = await this.request('/v2/uploads', { method: 'POST', body: form });
    if (upload.size !== bytes.length || upload.sha256 !== crypto.createHash('sha256').update(bytes).digest('hex')) throw new Error('upload_integrity_mismatch');
    return this.json('/v2/queue/install', { deviceId, uploadId: upload.uploadId, resourceType: 'quick_app' });
  }
  task(taskId) { return this.request('/v2/queue/tasks/' + encodeURIComponent(taskId)); }
}
module.exports = { AstroBoxClient };
