// Separate opt-in service: never restarts PowerProfile or the voice companion.
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const https = require('node:https');
const crypto = require('node:crypto');
const { snapshot } = require('./powerprofile-snapshot.cjs');
const { readRateLimits } = require('./codex-rate-limits.cjs');
const { Completions } = require('./codex-completions.cjs');
const runtime = process.env.POWERPROFILE_BAND_RUNTIME || path.join(process.env.LOCALAPPDATA || os.homedir(), 'BandAgentDev/runtime');
const root = process.env.POWERPROFILE_ROOT || 'E:/PowerProfile';
// Use the existing pinned pairing identity. No new trust root or credentials.
const expected = Buffer.from('Bearer ' + fs.readFileSync(path.join(runtime, 'token'), 'utf8').trim());
const completions = new Completions(path.join(runtime, 'powerprofile-completions.sqlite'));
let quota = null, quotaBusy = false;
async function refreshQuota() {
  if (quotaBusy) return; quotaBusy = true;
  try { quota = await readRateLimits(process.env.CODEX_EXECUTABLE || path.join(process.env.LOCALAPPDATA || '', 'Programs/OpenAI/Codex/bin/codex.exe')); }
  catch { /* Keep the last sample with its original timestamp. */ }
  finally { quotaBusy = false; }
}
function scan() { completions.scan(process.env.CODEX_SESSIONS_ROOT || path.join(os.homedir(), '.codex/sessions')); }
scan(); refreshQuota();
const quotaTimer = setInterval(refreshQuota, 60000), scanTimer = setInterval(scan, 5000);
const server = https.createServer({ key: fs.readFileSync(path.join(runtime, 'tls-key.pem')), cert: fs.readFileSync(path.join(runtime, 'tls-cert.pem')), minVersion: 'TLSv1.2' }, async (req, res) => {
  const reply = (code, data) => { res.writeHead(code, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); res.end(JSON.stringify(data)); };
  const auth = Buffer.from(req.headers.authorization || '');
  if (auth.length !== expected.length || !crypto.timingSafeEqual(auth, expected)) return reply(401, { error: 'unauthorized' });
  if (req.method !== 'POST' || !['/powerprofile/poll', '/powerprofile/ack', '/powerprofile/device-report'].includes(req.url)) return reply(404, { error: 'not_found' });
  try {
    let size = 0; const chunks = [];
    for await (const chunk of req) { size += chunk.length; if (size > 8192) throw new Error('body_too_large'); chunks.push(chunk); }
    const body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    if (body.entryId !== 'powerprofile' || body.protocol !== 1) throw new Error('invalid_identity');
    if (req.url.endsWith('/device-report')) {
      if (!Number.isSafeInteger(body.versionCode) || body.versionCode <= 0 || typeof body.snapshotAt !== 'string' || body.snapshotAt.length > 32 || !Number.isFinite(Date.parse(body.snapshotAt))) throw new Error('invalid_device_report');
      const report = { event: 'powerprofile_snapshot_applied', snapshotAt: body.snapshotAt, versionCode: body.versionCode, receivedAt: new Date().toISOString() };
      fs.writeFileSync(path.join(runtime, 'powerprofile-device-latest.json'), JSON.stringify(report));
      console.log(JSON.stringify(report)); return reply(200, { ok: true });
    }
    if (req.url.endsWith('/ack')) {
      if (!Array.isArray(body.ids) || body.ids.length > 20) throw new Error('invalid_ack');
      completions.ack(body.ids); return reply(200, { ok: true });
    }
    reply(200, { snapshot: snapshot(root, quota), events: completions.pending() });
  } catch { reply(400, { error: 'invalid_request' }); }
});
server.requestTimeout = 20000;
server.listen(Number(process.env.POWERPROFILE_BAND_PORT || 8791), '0.0.0.0', () => console.log('PowerProfile band monitor HTTPS ready; device acceptance pending.'));
for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => { clearInterval(quotaTimer); clearInterval(scanTimer); server.close(() => { completions.close(); process.exit(0); }); });
