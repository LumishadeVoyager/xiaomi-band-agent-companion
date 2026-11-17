const test = require('node:test');
const assert = require('node:assert/strict');
const { DiagnosticStore } = require('../host/diagnostic-store.cjs');
const event = () => ({ id: 'boot_01', entryId: 'codex', version: '0.1.0-preview.1', versionCode: 4, timestamp: 123, source: 'wearable', code: 'record_module_missing', level: 'error', details: { state: 'module_missing', canIUseRecord: false } });
const batch = events => ({ protocol: 1, entryId: 'codex', events });
test('durable ingestion deduplicates retries and creates one repair per failure version', () => {
  const store = new DiagnosticStore(':memory:');
  try {
    assert.deepEqual(store.accept(batch([event()])), ['boot_01']);
    store.accept(batch([event()])); store.accept(batch([{ ...event(), id: 'boot_02' }]));
    assert.equal(store.db.prepare('SELECT COUNT(*) AS n FROM events').get().n, 2);
    assert.equal(store.pendingRepairs().length, 1);
    store.accept(batch([{ ...event(), id: 'new_build', versionCode: 5 }]));
    assert.equal(store.pendingRepairs().length, 2);
  } finally { store.close(); }
});
test('conflicting retries roll back a whole batch and private fields are rejected', () => {
  const store = new DiagnosticStore(':memory:');
  try {
    store.accept(batch([event()]));
    assert.throws(() => store.accept(batch([{ ...event(), id: 'new_event' }, { ...event(), code: 'another_error' }])), /conflicting/);
    assert.equal(store.db.prepare('SELECT COUNT(*) AS n FROM events').get().n, 1);
    assert.throws(() => store.accept(batch([{ ...event(), details: { transcript: 'not allowed' } }])), /private/);
    assert.throws(() => store.accept(batch([{ ...event(), entryId: 'harness' }])), /identity|invalid/);
  } finally { store.close(); }
});
