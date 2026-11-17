const test = require('node:test');
const assert = require('node:assert/strict');
const { Diagnostics } = require('../shared/diagnostics.cjs');
const entry = { id: 'codex', version: 'test', versionCode: 4 };
test('logs created before asynchronous restore survive and remain bounded', () => {
  let restore; const writes = [];
  const d = new Diagnostics(entry, { get(o) { restore = o.success; }, set(o) { writes.push(o.value); o.success(); } }, () => 123);
  d.load(); const early = d.record('record_module_missing', { state: 'module_missing' }, 'error');
  assert.equal(d.batch().length, 0);
  restore(JSON.stringify([{ id: 'old', entryId: 'codex' }]));
  assert.deepEqual(d.batch().map(e => e.id), ['old', early]);
  for (let i = 0; i < 100; i++) d.record('sample', { bytes: i });
  assert.equal(d.events.length, 24);
  assert.equal(JSON.parse(writes.at(-1)).length, 24);
});
test('unacknowledged failures retry without losing events; ACK removes only matching ids', () => {
  let persisted = '[]';
  const storage = { get(o) { o.success(persisted); }, set(o) { persisted = o.value; o.success(); } };
  const d = new Diagnostics(entry, storage); d.load();
  const id = d.record('capture_failed', { errorCode: 202, prompt: 'secret', audio: 'secret', transcript: 'secret' }, 'error');
  assert.equal(d.batch().length, 1); assert.equal(d.batch().length, 1);
  d.ack(['not-this-event']); assert.equal(d.events.length, 1);
  assert.ok(!JSON.stringify(d.events).includes('secret'));
  const afterRestart = new Diagnostics(entry, storage); afterRestart.load(); assert.equal(afterRestart.batch()[0].id, id);
  afterRestart.ack([id]); assert.equal(JSON.parse(persisted).length, 0);
});
