const crypto = require('node:crypto');
const { DatabaseSync } = require('node:sqlite');
const ENTRY = /^(codex|harness)$/;
function validateBatch(batch) {
  if (batch.protocol !== 1 || !ENTRY.test(batch.entryId) || !Array.isArray(batch.events) || batch.events.length > 30) throw new Error('invalid_diagnostic_batch');
  for (const event of batch.events) {
    if (!event || event.entryId !== batch.entryId || !/^[a-zA-Z0-9_]{1,80}$/.test(event.id) || !/^[a-z0-9_]{1,64}$/.test(event.code) || !/^[a-zA-Z0-9.+_-]{1,64}$/.test(event.version) || !Number.isSafeInteger(event.versionCode) || event.versionCode < 1 || !['info', 'error'].includes(event.level) || !['wearable', 'phone'].includes(event.source) || !event.details || typeof event.details !== 'object' || Array.isArray(event.details)) throw new Error('invalid_diagnostic_event');
    const allowed = ['state', 'exportType', 'start', 'nestedStart', 'canIUseRecord', 'canIUseStart', 'errorCode', 'phase', 'bytes', 'sequence', 'queueLength'];
    if (Object.keys(event.details).some(key => !allowed.includes(key))) throw new Error('private_or_unknown_log_field');
    for (const value of Object.values(event.details)) if (!(value === null || typeof value === 'boolean' || (typeof value === 'number' && Number.isFinite(value)) || (typeof value === 'string' && value.length <= 100))) throw new Error('invalid_diagnostic_detail');
  }
  return batch;
}
class DiagnosticStore {
  constructor(file) {
    this.db = new DatabaseSync(file);
    this.db.exec('CREATE TABLE IF NOT EXISTS events (entry TEXT, id TEXT, body TEXT NOT NULL, received INTEGER NOT NULL, PRIMARY KEY(entry,id)); CREATE TABLE IF NOT EXISTS repairs (fingerprint TEXT PRIMARY KEY, entry TEXT, version_code INTEGER, code TEXT, evidence TEXT, status TEXT, created INTEGER, attempts INTEGER NOT NULL DEFAULT 0); CREATE TABLE IF NOT EXISTS commands (id TEXT PRIMARY KEY, entry TEXT, body TEXT NOT NULL, acked INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL);');
  }
  accept(batch) {
    validateBatch(batch); const accepted = []; this.db.exec('BEGIN');
    try {
      for (const event of batch.events) {
        const body = JSON.stringify(event);
        const existing = this.db.prepare('SELECT body FROM events WHERE entry=? AND id=?').get(event.entryId, event.id);
        if (existing && existing.body !== body) throw new Error('conflicting_diagnostic_retry');
        this.db.prepare('INSERT OR IGNORE INTO events VALUES (?,?,?,?)').run(event.entryId, event.id, body, Date.now()); accepted.push(event.id);
        if (event.level === 'error') {
          const fingerprint = crypto.createHash('sha256').update(JSON.stringify([event.entryId, event.versionCode, event.code, event.details])).digest('hex');
          this.db.prepare('INSERT OR IGNORE INTO repairs (fingerprint,entry,version_code,code,evidence,status,created) VALUES (?,?,?,?,?,?,?)').run(fingerprint, event.entryId, event.versionCode, event.code, body, 'queued', Date.now());
        }
      }
      this.db.exec('COMMIT'); return accepted;
    } catch (error) { this.db.exec('ROLLBACK'); throw error; }
  }
  pendingRepairs() { return this.db.prepare("SELECT fingerprint,entry,version_code,code,evidence,status,attempts FROM repairs WHERE status='queued' ORDER BY created").all(); }
  command(entry, id, value) { if (!ENTRY.test(entry) || !/^[a-zA-Z0-9_]{1,100}$/.test(id) || !['update_available', 'draft_ready'].includes(value.type)) throw new Error('invalid_command'); this.db.prepare('INSERT OR IGNORE INTO commands VALUES (?,?,?,0,?)').run(id, entry, JSON.stringify({ ...value, id }), Date.now()); }
  commands(entry) { if (!ENTRY.test(entry)) throw new Error('invalid_entry'); return this.db.prepare('SELECT body FROM commands WHERE entry=? AND acked=0 ORDER BY created LIMIT 5').all(entry).map(row => JSON.parse(row.body)); }
  ackCommands(entry, ids) { if (!ENTRY.test(entry) || !Array.isArray(ids) || ids.length > 5 || ids.some(id => typeof id !== 'string' || id.length > 100)) throw new Error('invalid_ack'); for (const id of ids) this.db.prepare('UPDATE commands SET acked=1 WHERE entry=? AND id=?').run(entry, id); }
  close() { this.db.close(); }
}
module.exports = { DiagnosticStore, validateBatch };
