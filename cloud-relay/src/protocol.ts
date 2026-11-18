export const DAY = 86400000;
export type Metric = { value: number | null; observedAt: string | null; source: string };
export type Window = { windowMinutes: number; usedPercent: number; remainingPercent: number; resetsAt: number };
export type Snapshot = { protocol: 1; entryId: 'powerprofile'; type: 'powerprofile_snapshot'; day: string; generatedAt: string;
  usageSeconds: Metric; energyKWh: Metric; gameSeconds: Metric; aiTokens: Metric;
  quota: null | { observedAt: string; fiveHour: Window | null; weekly: Window | null } };
export type Event = { id: string; at: string };
export type Report = { snapshotAt: string; versionCode: number; receivedAt: string; transport: 'internet' };
function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('invalid_object');
  return value as Record<string, unknown>;
}
export function stamp(value: unknown): string {
  if (typeof value !== 'string' || value.length > 32 || !Number.isFinite(Date.parse(value))) throw new Error('invalid_timestamp');
  return new Date(value).toISOString();
}
function metric(value: unknown): Metric {
  const m = object(value);
  if (m.value !== null && (typeof m.value !== 'number' || !Number.isFinite(m.value) || m.value < 0)) throw new Error('invalid_metric');
  return { value: m.value as number | null, observedAt: m.observedAt === null ? null : stamp(m.observedAt), source: 'PowerProfile' };
}
function window(value: unknown, minutes: number): Window | null {
  if (value === null) return null;
  const w = object(value);
  if (w.windowMinutes !== minutes || typeof w.usedPercent !== 'number' || !Number.isFinite(w.usedPercent) || w.usedPercent < 0 || w.usedPercent > 100 || !Number.isSafeInteger(w.resetsAt) || (w.resetsAt as number) <= 0) throw new Error('invalid_quota_window');
  return { windowMinutes: minutes, usedPercent: w.usedPercent, remainingPercent: 100 - w.usedPercent, resetsAt: w.resetsAt as number };
}
export function identity(value: unknown): Record<string, unknown> {
  const v = object(value);
  if (v.protocol !== 1 || v.entryId !== 'powerprofile') throw new Error('invalid_identity');
  return v;
}
export function normalizeSnapshot(value: unknown, now = Date.now()): Snapshot {
  const s = identity(value);
  const generatedAt = stamp(s.generatedAt);
  if (s.type !== 'powerprofile_snapshot' || typeof s.day !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(s.day) || Date.parse(generatedAt) > now + 300000 || Date.parse(generatedAt) < now - DAY) throw new Error('invalid_snapshot');
  let quota: Snapshot['quota'] = null;
  if (s.quota !== null) { const q = object(s.quota); quota = { observedAt: stamp(q.observedAt), fiveHour: window(q.fiveHour, 300), weekly: window(q.weekly, 10080) }; }
  return { protocol: 1, entryId: 'powerprofile', type: 'powerprofile_snapshot', day: s.day, generatedAt,
    usageSeconds: metric(s.usageSeconds), energyKWh: metric(s.energyKWh), gameSeconds: metric(s.gameSeconds), aiTokens: metric(s.aiTokens), quota };
}
export function eventList(value: unknown, now = Date.now()): Event[] {
  if (!Array.isArray(value) || value.length > 20) throw new Error('invalid_events');
  return value.map(item => { const e = object(item), at = stamp(e.at);
    if (typeof e.id !== 'string' || !/^[a-f0-9]{64}$/.test(e.id) || Date.parse(at) > now + 300000 || Date.parse(at) < now - DAY) throw new Error('invalid_event');
    return { id: e.id, at };
  });
}
export function ids(value: unknown): string[] {
  if (!Array.isArray(value) || value.length > 20 || value.some(id => typeof id !== 'string' || !/^[a-f0-9]{64}$/.test(id))) throw new Error('invalid_ids');
  return [...new Set(value)] as string[];
}
export function deviceReport(value: unknown, now = Date.now()): Report {
  const v = identity(value);
  if (!Number.isSafeInteger(v.versionCode) || (v.versionCode as number) <= 0) throw new Error('invalid_version');
  return { snapshotAt: stamp(v.snapshotAt), versionCode: v.versionCode as number, receivedAt: new Date(now).toISOString(), transport: 'internet' };
}
export function phoneReport(value: unknown, now = Date.now()): Record<string, unknown> {
  const v=identity(value), out: Record<string, unknown>={};
  if(typeof v.session !== 'string' || !/^[a-f0-9]{32}$/.test(v.session)) throw new Error('invalid_session');
  out.session=v.session;
  for(const key of ['phoneVersion','startedAt','uptimeSeconds','lastSyncAt','lastWearAt']) {
    if(typeof v[key] !== 'number' || !Number.isSafeInteger(v[key]) || (v[key] as number)<0 || ((key.endsWith('At')) && (v[key] as number)>now+300000)) throw new Error('invalid_runtime_number');
    out[key]=v[key];
  }
  if((v.phoneVersion as number)<1 || (v.startedAt as number)<1) throw new Error('invalid_runtime_identity');
  for(const key of ['wearConnected','batteryUnrestricted','notificationsAllowed']) {if(typeof v[key] !== 'boolean')throw new Error('invalid_runtime_boolean');out[key]=v[key];}
  return out;
}
export async function readBody(request: Request, limit: number): Promise<unknown> {
  if (!/^application\/json(?:\s*;|$)/i.test(request.headers.get('Content-Type') || '')) throw new Error('json_required');
  if (Number(request.headers.get('Content-Length')) > limit) throw new Error('body_too_large');
  if (!request.body) throw new Error('empty_body');
  const reader = request.body.getReader(), parts: Uint8Array[] = []; let size = 0;
  try { for (;;) { const { value, done } = await reader.read(); if (done) break; size += value.byteLength; if (size > limit) { await reader.cancel(); throw new Error('body_too_large'); } parts.push(value); } }
  finally { reader.releaseLock(); }
  const bytes = new Uint8Array(size); let offset = 0; for (const part of parts) { bytes.set(part, offset); offset += part.byteLength; }
  return JSON.parse(new TextDecoder('utf-8', { fatal: true, ignoreBOM: false }).decode(bytes));
}
