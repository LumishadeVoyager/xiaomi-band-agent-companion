import { DurableObject } from 'cloudflare:workers';
import { DAY, identity, normalizeSnapshot, eventList, ids, deviceReport, readBody, type Snapshot, type Event, type Report } from './protocol.ts';

function response(data: unknown, status = 200): Response {
  return Response.json(data, { status, headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' } });
}
async function matches(given: string, expected: string): Promise<boolean> {
  const enc = new TextEncoder();
  const [a, b] = await Promise.all([crypto.subtle.digest('SHA-256', enc.encode(given)), crypto.subtle.digest('SHA-256', enc.encode(expected))]);
  return crypto.subtle.timingSafeEqual(a, b);
}

export class PairRelay extends DurableObject<Env> {
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.ctx.storage.sql.exec('CREATE TABLE IF NOT EXISTS state (key TEXT PRIMARY KEY, value TEXT NOT NULL, at INTEGER NOT NULL)');
    this.ctx.storage.sql.exec('CREATE TABLE IF NOT EXISTS events (id TEXT PRIMARY KEY, at INTEGER NOT NULL, ack INTEGER NOT NULL DEFAULT 0)');
  }
  private prune(now: number): void { this.ctx.storage.sql.exec('DELETE FROM events WHERE at < ?', now - DAY); this.ctx.storage.sql.exec('DELETE FROM state WHERE at < ?', now - DAY); }
  async publish(snapshot: Snapshot, events: Event[]): Promise<{ accepted: true; ackedIds: string[]; report: Report | null }> {
    const now = Date.now();
    this.ctx.storage.transactionSync(() => {
      this.prune(now);
      for (const event of events) this.ctx.storage.sql.exec('INSERT OR IGNORE INTO events(id,at) VALUES (?,?)', event.id, Date.parse(event.at));
      if (this.ctx.storage.sql.exec<{ count: number }>('SELECT count(*) AS count FROM events').one().count > 2000) throw new Error('event_queue_full');
      const previous = this.ctx.storage.sql.exec<{ value: string }>("SELECT value FROM state WHERE key='snapshot'").toArray()[0];
      if (!previous || Date.parse(JSON.parse(previous.value).generatedAt) <= Date.parse(snapshot.generatedAt)) this.ctx.storage.sql.exec("INSERT OR REPLACE INTO state(key,value,at) VALUES ('snapshot',?,?)", JSON.stringify(snapshot), now);
      this.ctx.storage.sql.exec("INSERT OR REPLACE INTO state(key,value,at) VALUES ('heartbeat','1',?)", now);
    });
    if (!(await this.ctx.storage.getAlarm())) await this.ctx.storage.setAlarm(now + DAY);
    // The PC retransmits its unacknowledged batch. Return only that batch's
    // receipts rather than reading all historical tombstones every ten seconds.
    const ackedIds = events.filter(event => this.ctx.storage.sql.exec<{ ack: number }>('SELECT ack FROM events WHERE id=?', event.id).one().ack === 1).map(event => event.id);
    return { accepted: true, ackedIds, report: this.latestReport() };
  }
  private latestReport(): Report | null {
    const row = this.ctx.storage.sql.exec<{ value: string }>("SELECT value FROM state WHERE key='report'").toArray()[0]; return row ? JSON.parse(row.value) : null;
  }
  async poll(): Promise<{ snapshot: Snapshot; events: Event[]; relay: { sourceOnline: boolean; lastUploadAt: string } } | null> {
    const now = Date.now(); this.prune(now);
    const row = this.ctx.storage.sql.exec<{ value: string; at: number }>("SELECT value,at FROM state WHERE key='snapshot'").toArray()[0];
    if (!row) return null;
    const heartbeat = this.ctx.storage.sql.exec<{ at: number }>("SELECT at FROM state WHERE key='heartbeat'").toArray()[0];
    return { snapshot: JSON.parse(row.value), events: this.ctx.storage.sql.exec<{ id: string; at: number }>('SELECT id,at FROM events WHERE ack=0 ORDER BY at LIMIT 20').toArray().map(e => ({ id: e.id, at: new Date(e.at).toISOString() })), relay: { sourceOnline: !!heartbeat && now - heartbeat.at < 90000, lastUploadAt: new Date(heartbeat?.at ?? row.at).toISOString() } };
  }
  async ack(acked: string[]): Promise<{ ok: true }> {
    this.ctx.storage.transactionSync(() => { this.prune(Date.now()); for (const id of acked) this.ctx.storage.sql.exec('UPDATE events SET ack=1 WHERE id=?', id); });
    return { ok: true };
  }
  async report(report: Report): Promise<{ ok: true }> {
    const row = this.ctx.storage.sql.exec<{ value: string }>("SELECT value FROM state WHERE key='snapshot'").toArray()[0];
    if (!row || Math.abs(Date.parse(JSON.parse(row.value).generatedAt) - Date.parse(report.snapshotAt)) > 300000) throw new Error('unknown_snapshot');
    this.ctx.storage.sql.exec("INSERT OR REPLACE INTO state(key,value,at) VALUES ('report',?,?)", JSON.stringify(report), Date.now()); return { ok: true };
  }
  async alarm(): Promise<void> { this.prune(Date.now()); if (this.ctx.storage.sql.exec<{ count: number }>('SELECT count(*) AS count FROM state').one().count || this.ctx.storage.sql.exec<{ count: number }>('SELECT count(*) AS count FROM events').one().count) await this.ctx.storage.setAlarm(Date.now() + DAY); }
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const route = new URL(request.url).pathname;
    if (route === '/health' && request.method === 'GET') return response({ service: 'powerprofile-relay', protocol: 1 });
    if (request.method !== 'POST' || !['/relay/publish', '/powerprofile/poll', '/powerprofile/ack', '/powerprofile/device-report'].includes(route)) return response({ error: 'not_found' }, 404);
    if (!/^[a-f0-9]{64}$/.test(env.PC_TOKEN || '') || !/^[a-f0-9]{64}$/.test(env.PHONE_TOKEN || '') || !/^[a-f0-9]{32}$/.test(env.CHANNEL_ID || '')) return response({ error: 'relay_not_configured' }, 503);
    const supplied = request.headers.get('Authorization') || '';
    if (!(await matches(supplied, 'Bearer ' + (route === '/relay/publish' ? env.PC_TOKEN : env.PHONE_TOKEN)))) return response({ error: 'unauthorized' }, 401);
    try {
      const body = identity(await readBody(request, route === '/relay/publish' ? 32768 : 8192));
      const relay = env.PAIRS.getByName(env.CHANNEL_ID);
      if (route === '/relay/publish') return response(await relay.publish(normalizeSnapshot(body.snapshot), eventList(body.events)));
      if (route === '/powerprofile/ack') return response(await relay.ack(ids(body.ids)));
      if (route === '/powerprofile/device-report') return response(await relay.report(deviceReport(body)));
      const data = await relay.poll(); return data ? response(data) : response({ error: 'snapshot_not_available' }, 503);
    } catch { return response({ error: 'invalid_request' }, 400); }
  }
} satisfies ExportedHandler<Env>;
