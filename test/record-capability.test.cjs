const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const capability = require('../shared/record-capability.cjs');

test('native module absent, null, empty or throwing yields a report without dereferencing it', () => {
  for (const value of [undefined, null]) {
    const result = capability.inspectRecord(() => value, () => false);
    assert.equal(result.recorder, null);
    assert.equal(result.report.state, 'module_missing');
    assert.equal(result.report.canIUseStart, false);
  }
  assert.equal(capability.inspectRecord(() => ({})).report.state, 'start_missing');
  const result = capability.inspectRecord(() => { throw new Error('feature unavailable'); });
  assert.equal(result.report.state, 'module_load_error');
  assert.match(result.report.error, /feature unavailable/);
});

test('direct, default-wrapped and prototype native methods keep their owning object', () => {
  const direct = { start() {}, stop() {} };
  const prototype = Object.create(direct);
  for (const raw of [direct, { default: direct }, prototype]) {
    const result = capability.inspectRecord(() => raw);
    assert.equal(result.report.state, 'method_available');
    assert.equal(result.recorder, raw === prototype ? prototype : direct);
  }
  assert.equal(capability.inspectRecord(() => ({ default: null })).recorder, null);
});

test('capability query failures do not hide an available native method', () => {
  const result = capability.inspectRecord(() => ({ start() {} }), () => { throw new Error('old runtime'); });
  assert.equal(result.report.state, 'method_available');
  assert.match(result.report.capabilityQueryError, /old runtime/);
});

// Exercise the actual .ux script and event wiring, rather than a second copy
// of the diagnostic logic. This is a regression check, not device support proof.
function pageWith(recordValue) {
  const sent = [];
  const connection = { getReadyState(options) { options.success({ status: 1 }); }, send(options) { sent.push(options.data); options.success(); } };
  const source = fs.readFileSync(require.resolve('../wearable/src/pages/index/index.ux'), 'utf8').match(/<script>([\s\S]*?)<\/script>/)[1];
  const imports = { entry: { id: 'codex', name: 'Codex', version: 'test-build', versionCode: 4 }, wire: require('../shared/audio-wire.cjs'), capability, diagnostics: require('../shared/diagnostics.cjs') };
  const script = source.replace(/^import (\w+) from .+$/gm, 'const $1 = imports.$1;').replace('export default', 'module.exports =');
  const sandbox = { imports, module: { exports: {} }, setTimeout, setInterval: () => 0, clearInterval() {}, require(name) {
    if (name === '@system.record') return recordValue;
    if (name === '@system.app') return { canIUse: () => false };
    if (name === '@system.interconnect') return { instance: () => connection };
    if (name === '@system.storage') return { get(o) { o.success('[]'); }, set(o) { o.success(); } };
    throw new Error('unexpected module');
  } };
  vm.runInNewContext(script, sandbox);
  const page = sandbox.module.exports;
  Object.assign(page, page.private);
  return { page, sent, connection };
}

test('real page auto-diagnoses a missing module and never starts or queues audio', () => {
  const { page, sent } = pageWith(undefined);
  assert.doesNotThrow(() => page.onInit());
  assert.match(page.statusText, /未提供录音模块/);
  const report = sent.find(message => message.type === 'probe_capabilities');
  assert.equal(report.protocol, 1);
  assert.equal(report.record.state, 'module_missing');
  assert.equal(report.version, 'test-build');
  assert.doesNotThrow(() => page.startRecording());
  assert.equal(page.recording, false);
  assert.equal(page.queue.length, 0);
  assert.ok(sent.every(message => ['probe_capabilities', 'ota_hello', 'diag_batch'].includes(message.type)));
});

test('real page responds to phone diagnostics and only accepts its entry transcript', () => {
  const { page, sent, connection } = pageWith(undefined);
  page.onInit();
  connection.onmessage({ data: { type: 'probe_capabilities_request', entryId: 'codex' } });
  assert.equal(sent.filter(message => message.type === 'probe_capabilities').length, 2);
  connection.onmessage({ data: { type: 'probe_text', entryId: 'harness', text: 'wrong entry' } });
  assert.doesNotMatch(page.detailText, /wrong entry/);
  connection.onmessage({ data: { type: 'probe_text', entryId: 'codex', text: 'unsubmitted transcript' } });
  assert.equal(page.detailText, 'unsubmitted transcript');
  assert.match(page.statusText, /未发送/);
});
