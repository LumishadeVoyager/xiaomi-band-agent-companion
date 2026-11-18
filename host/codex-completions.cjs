const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { DatabaseSync } = require('node:sqlite');
class Completions {
  constructor(file) {
    this.db = new DatabaseSync(file);
    this.db.exec('CREATE TABLE IF NOT EXISTS cursors (file TEXT PRIMARY KEY, offset INTEGER); CREATE TABLE IF NOT EXISTS completions (id TEXT PRIMARY KEY, at TEXT, ack INTEGER DEFAULT 0)');
    this.started = Date.now();
  }
  scan(root, now = new Date()) {
    // Only recent local rollouts; baseline existing files on first observation.
    for (const delta of [1, 0]) {
      const date = new Date(now.getTime() - delta * 86400000).toISOString().slice(0, 10).replaceAll('-', '/');
      const dir = path.join(root, date);
      let files; try { files = fs.readdirSync(dir).filter(f => f.endsWith('.jsonl')); } catch { continue; }
      for (const name of files) {
        const file = path.join(dir, name);
        try {
          const stat = fs.statSync(file), previous = this.db.prepare('SELECT offset FROM cursors WHERE file=?').get(file);
          let offset = previous?.offset ?? (stat.birthtimeMs < this.started ? stat.size : 0);
          if (offset > stat.size) offset = 0;
          const bytes = Buffer.alloc(Math.min(stat.size - offset, 2 * 1024 * 1024));
          const fd = fs.openSync(file, 'r'); try { fs.readSync(fd, bytes, 0, bytes.length, offset); } finally { fs.closeSync(fd); }
          const end = bytes.lastIndexOf(10);
          if (end >= 0) {
            let position = offset;
            for (const line of bytes.subarray(0, end).toString('utf8').split('\n')) {
              try {
                const event = JSON.parse(line);
                const time = Date.parse(event.timestamp);
                const cutoff = previous ? now.getTime() - 86400000 : this.started;
                if (event.type === 'event_msg' && event.payload?.type === 'task_complete' && Number.isFinite(time) && time >= cutoff && time <= now.getTime() + 300000) {
                  const id = crypto.createHash('sha256').update(file + ':' + position).digest('hex');
                  this.db.prepare('INSERT OR IGNORE INTO completions(id,at) VALUES (?,?)').run(id, new Date(time).toISOString());
                }
              } catch { /* Do not store prompts, replies, titles or raw lines. */ }
              position += Buffer.byteLength(line) + 1;
            }
            offset += end + 1;
          } else if (bytes.length === 2 * 1024 * 1024) offset += bytes.length;
          this.db.prepare('INSERT OR REPLACE INTO cursors(file,offset) VALUES (?,?)').run(file, offset);
        } catch { /* Writer rotation and transient sync locks retry next poll. */ }
      }
    }
    this.db.prepare('DELETE FROM completions WHERE at < ?').run(new Date(now.getTime() - 86400000).toISOString());
  }
  pending() { return this.db.prepare('SELECT id,at FROM completions WHERE ack=0 ORDER BY at LIMIT 20').all(); }
  ack(ids) { for (const id of ids) if (typeof id === 'string' && /^[a-f0-9]{64}$/.test(id)) this.db.prepare('UPDATE completions SET ack=1 WHERE id=?').run(id); }
  close() { this.db.close(); }
}
module.exports = { Completions };
