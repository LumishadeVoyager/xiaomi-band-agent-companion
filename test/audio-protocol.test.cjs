const test = require('node:test');
const assert = require('node:assert/strict');
const { encodeBase64, envelope } = require('../shared/audio-wire.cjs');
const { AudioSession } = require('../host/audio-session.cjs');
const msg = (type, extra = {}) => envelope('codex', 'audio_test_01', type, extra);
const begin = () => new AudioSession(msg('audio_begin', { sampleRate: 16000, channels: 1, encoding: 'pcm_s16le' }));
test('wearable base64 preserves PCM bytes for all tail lengths', () => {
  for (let n = 0; n < 4096; n++) {
    const bytes = Uint8Array.from({ length: n }, (_, i) => (i * 37) & 255);
    assert.equal(encodeBase64(bytes), Buffer.from(bytes).toString('base64'));
  }
});
test('complete PCM gets a valid WAV container without changing the payload', () => {
  const session = begin(), pcm = Buffer.from([0, 0, 255, 127]);
  session.accept(msg('audio_chunk', { sequence: 0, data: pcm.toString('base64') }));
  assert.equal(session.accept(msg('audio_end', { chunks: 1, bytes: 4 })).complete, true);
  assert.equal(session.wav().readUInt32LE(24), 16000);
  assert.deepEqual(session.wav().subarray(44), pcm);
});
test('wrong entry, missing chunks and premature end never count as completed audio', () => {
  const session = begin();
  assert.throws(() => session.accept({ ...msg('audio_chunk', { sequence: 0, data: 'AAA=' }), entryId: 'harness' }), /identity/);
  assert.throws(() => session.accept(msg('audio_chunk', { sequence: 1, data: 'AAA=' })), /missing/);
  assert.throws(() => session.accept(msg('audio_end', { chunks: 1, bytes: 2 })), /incomplete/);
});
test('retries are idempotent, conflicting duplicate content is rejected', () => {
  const session = begin(), chunk = msg('audio_chunk', { sequence: 0, data: 'AAA=' });
  session.accept(chunk);
  assert.equal(session.accept(chunk).duplicate, true);
  assert.throws(() => session.accept({ ...chunk, data: 'AQE=' }), /conflicting/);
  session.accept(msg('audio_end', { chunks: 1, bytes: 2 }));
  assert.equal(session.accept(chunk).duplicate, true);
  assert.throws(() => session.accept({ ...chunk, sequence: 1 }), /already_ended/);
});
test('malformed base64 and odd PCM samples are rejected', () => {
  for (const data of ['!', 'AA==', 'AAAA=', 'AB==']) assert.throws(() => begin().accept(msg('audio_chunk', { sequence: 0, data })));
});
