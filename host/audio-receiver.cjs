// USB-only development endpoint. Never starts an Agent or transcribes automatically.
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const { randomBytes, timingSafeEqual } = require('node:crypto');
const { AudioSession } = require('./audio-session.cjs');
const privateDir = path.resolve(__dirname, '../.local/receiver');
fs.mkdirSync(privateDir, { recursive: true });
const tokenFile = path.join(privateDir, 'token');
if (!fs.existsSync(tokenFile)) fs.writeFileSync(tokenFile, randomBytes(32).toString('hex'), { mode: 0o600 });
const expected = Buffer.from('Bearer ' + fs.readFileSync(tokenFile, 'utf8').trim());
const sessions = new Map();
http.createServer(async (req, res) => {
  const reply = (status, data) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(data)); };
  const received = Buffer.from(req.headers.authorization || '');
  if (received.length !== expected.length || !timingSafeEqual(received, expected)) return reply(401, { error: 'unauthorized' });
  if (req.method !== 'POST' || req.url !== '/audio') return reply(404, { error: 'not_found' });
  try {
    const parts = []; let size = 0;
    for await (const part of req) { size += part.length; if (size > 8192) throw new Error('body_too_large'); parts.push(part); }
    const message = JSON.parse(Buffer.concat(parts).toString('utf8'));
    const key = message.entryId + ':' + message.audioId;
    // Bound temporary in-memory recordings; expire abandoned/cancelled uploads.
    for (const [id, item] of sessions) if (Date.now() - item.touched > 120000) sessions.delete(id);
    if (message.type === 'audio_cancel') { sessions.delete(key); return reply(200, { cancelled: true }); }
    if (message.type === 'audio_begin') {
      if (sessions.has(key)) return reply(200, { nextSequence: sessions.get(key).session.chunks.length });
      if (sessions.size >= 4) throw new Error('too_many_recordings');
      sessions.set(key, { session: new AudioSession(message), touched: Date.now(), saved: false });
      return reply(200, { nextSequence: 0 });
    }
    const item = sessions.get(key);
    if (!item) throw new Error('unknown_recording');
    item.touched = Date.now();
    const result = item.session.accept(message);
    if (result.complete && !item.saved) {
      fs.writeFileSync(path.join(privateDir, item.session.entryId + '-' + item.session.audioId + '.wav'), item.session.wav(), { flag: 'wx' });
      item.saved = true;
      console.log(JSON.stringify({ event: 'audio_received', entryId: item.session.entryId, bytes: result.bytes, sha256: result.sha256 }));
    }
    reply(200, result);
  } catch (error) { reply(400, { error: error.message }); }
}).listen(8789, '127.0.0.1', () => console.log('USB audio receiver ready on 127.0.0.1:8789; no Agent execution enabled.'));
