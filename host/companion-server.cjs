// Phone is the runtime hub. AstroBox is not involved in this server or uploads.
const fs = require('node:fs');
const path = require('node:path');
const https = require('node:https');
const crypto = require('node:crypto');
const { spawnSync, spawn, execFile } = require('node:child_process');
const { DiagnosticStore } = require('./diagnostic-store.cjs');
const { AudioSession } = require('./audio-session.cjs');
const root = path.resolve(__dirname, '..');
const directory = process.env.BAND_AGENT_RUNTIME || path.join(process.env.LOCALAPPDATA || path.join(root, '.local'), 'BandAgentDev', 'runtime');
fs.mkdirSync(directory, { recursive: true });
const keyPath = path.join(directory, 'tls-key.pem'), certPath = path.join(directory, 'tls-cert.pem');
if (fs.existsSync(keyPath) !== fs.existsSync(certPath)) throw new Error('incomplete_computer_tls_identity');
if (!fs.existsSync(keyPath)) {
  const openssl = process.env.BAND_AGENT_OPENSSL || 'C:\\Program Files\\Git\\usr\\bin\\openssl.exe';
  const result = spawnSync(openssl, ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '365', '-subj', '/CN=Band Agent Computer', '-keyout', keyPath, '-out', certPath], { windowsHide: true, encoding: 'utf8' });
  if (result.status !== 0) throw new Error('computer_tls_generation_failed');
}
const tokenPath = path.join(directory, 'token');
if (!fs.existsSync(tokenPath)) fs.writeFileSync(tokenPath, crypto.randomBytes(32).toString('hex'), { mode: 0o600 });
const token = fs.readFileSync(tokenPath, 'utf8').trim(); const expected = Buffer.from('Bearer ' + token);
const store = new DiagnosticStore(path.join(directory, 'diagnostics.sqlite'));
const recordings = new Map();
const port = Number(process.env.BAND_AGENT_PORT || 8790);
function transcribe(file, entryId, audioId) {
  const python = process.env.BAND_AGENT_PYTHON || path.join(process.env.LOCALAPPDATA || '', 'BandAgentDev/stt-venv/Scripts/python.exe');
  if (!fs.existsSync(python)) return;
  const output = path.join(directory, entryId + '-' + audioId + '-draft.json');
  const child = spawn(python, [path.join(root, 'host/transcribe.py'), file, '--model', 'small', '--output', output], { windowsHide: true, stdio: ['ignore', 'ignore', 'pipe'] });
  child.stderr.resume(); child.on('error', () => console.log(JSON.stringify({ event: 'transcription_failed', entryId })));
  child.on('exit', code => {
    if (code === 0) { try { const draft = JSON.parse(fs.readFileSync(output, 'utf8')); store.command(entryId, 'draft_' + audioId, { type: 'draft_ready', audioId, text: draft.text, status: 'unsubmitted' }); } catch (error) { code = 1; } }
    console.log(JSON.stringify({ event: code === 0 ? 'draft_created_unsubmitted' : 'transcription_failed', entryId }));
  });
}
const server = https.createServer({ key: fs.readFileSync(keyPath), cert: fs.readFileSync(certPath), minVersion: 'TLSv1.2' }, async (req, res) => {
  const reply = (status, data) => { res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); res.end(JSON.stringify(data)); };
  const received = Buffer.from(req.headers.authorization || '');
  if (received.length !== expected.length || !crypto.timingSafeEqual(received, expected)) return reply(401, { error: 'unauthorized' });
  if (req.method !== 'POST' || !['/audio', '/diagnostics', '/poll', '/ack'].includes(req.url)) return reply(404, { error: 'not_found' });
  try {
    const parts = []; let size = 0; const limit = req.url === '/audio' ? 8192 : 65536;
    for await (const part of req) { size += part.length; if (size > limit) throw new Error('body_too_large'); parts.push(part); }
    const data = JSON.parse(Buffer.concat(parts).toString('utf8'));
    if (req.url === '/poll') return reply(200, { commands: store.commands(data.entryId) });
    if (req.url === '/ack') { store.ackCommands(data.entryId, data.ids); return reply(200, { ok: true }); }
    if (req.url === '/diagnostics') {
      const accepted = store.accept(data); reply(200, { accepted });
      const errors = data.events.filter(e => e.level === 'error');
      if (errors.length) console.log(JSON.stringify({ event: 'diagnostics_received', entryId: data.entryId, codes: errors.map(e => e.code), repairQueued: true }));
      return;
    }
    const { validateIdentity } = require('../shared/audio-wire.cjs'); validateIdentity(data.entryId, data.audioId);
    if (data.protocol !== 1) throw new Error('unsupported_protocol');
    const id = data.entryId + ':' + data.audioId;
    for (const [key, value] of recordings) if (Date.now() - value.touched > 120000) recordings.delete(key);
    if (data.type === 'audio_cancel') { recordings.delete(id); return reply(200, { cancelled: true }); }
    if (data.type === 'audio_begin') {
      if (!recordings.has(id)) { if (recordings.size >= 4) throw new Error('too_many_recordings'); recordings.set(id, { session: new AudioSession(data), touched: Date.now(), saved: false }); }
      return reply(200, { nextSequence: recordings.get(id).session.chunks.length });
    }
    const item = recordings.get(id); if (!item) throw new Error('unknown_recording'); item.touched = Date.now();
    const result = item.session.accept(data);
    if (result.complete && !item.saved) {
      const file = path.join(directory, data.entryId + '-' + data.audioId + '.wav'); fs.writeFileSync(file, item.session.wav(), { flag: 'wx' }); item.saved = true;
      transcribe(file, data.entryId, data.audioId);
    }
    reply(200, result);
  } catch (error) { reply(400, { error: error.message }); }
});
server.requestTimeout = 20000;
server.listen(port, '0.0.0.0', () => console.log('Band Agent HTTPS hub ready on port ' + port + '; credentials and logs remain private.'));
let checkingFeed = false;
function checkPublishedFeed() {
  if (checkingFeed || !fs.existsSync(path.join(root, 'updates/trust-root.pem'))) return;
  checkingFeed = true;
  execFile('gh', ['api', 'repos/LumishadeVoyager/xiaomi-band-agent-companion/contents/updates/preview.json', '--jq', '.content'], { windowsHide: true, timeout: 20000, maxBuffer: 200000 }, (error, output) => {
    checkingFeed = false; if (error) return;
    try {
      const envelope = JSON.parse(Buffer.from(output.replace(/\s/g, ''), 'base64').toString('utf8'));
      if (envelope.schema !== 1 || envelope.algorithm !== 'RS256') throw new Error('invalid_feed_algorithm');
      const bytes = Buffer.from(envelope.payload, 'base64'); if (bytes.length > 65536 || !crypto.verify('sha256', bytes, fs.readFileSync(path.join(root, 'updates/trust-root.pem')), Buffer.from(envelope.signature, 'base64'))) throw new Error('invalid_feed_signature');
      const feed = JSON.parse(bytes); const now = Math.floor(Date.now() / 1000);
      if (feed.repository !== 'LumishadeVoyager/xiaomi-band-agent-companion' || feed.channel !== 'preview' || feed.expiresAt <= now || feed.issuedAt > now + 300) throw new Error('invalid_feed_identity');
      for (const entry of ['codex', 'harness']) {
        const versions = feed.components.filter(c => c.entryId === entry).map(c => c.versionCode);
        const code = Math.max(...versions); if (Number.isSafeInteger(code) && code > 0) store.command(entry, 'ota_' + entry + '_' + code, { type: 'update_available', versionCode: code });
      }
    } catch (error) { console.log(JSON.stringify({ event: 'update_feed_rejected', code: error.message })); }
  });
}
checkPublishedFeed(); setInterval(checkPublishedFeed, 60000).unref();
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close(() => { store.close(); process.exit(0); }));
