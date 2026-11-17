const test = require('node:test');
const assert = require('node:assert/strict');
const { allowedSource, eligible, promptFor } = require('../host/repair-policy.cjs');
test('automatic repair cannot publish credentials, change trust roots or edit workflows', () => {
  for (const path of ['.local/sign/key.pem', 'updates/trust-root.pem', '.github/workflows/client-preview.yml', 'package.json', 'host/../updates/key.json', '/host/server.cjs', 'host/.git/config', 'wearable/src/../../key.json']) assert.equal(allowedSource(path), false, path);
  assert.equal(allowedSource('wearable/src/pages/index/index.ux'), true);
  assert.equal(allowedSource('android-probe/src/io/github/lumishadevoyager/bandagent/BridgeService.java'), true);
});
test('one attempt per failure and daily limit prevent recursive repair storms', () => {
  assert.equal(eligible({ status: 'queued', attempts: 0 }, 2), true);
  assert.equal(eligible({ status: 'queued', attempts: 1 }, 0), false);
  assert.equal(eligible({ status: 'queued', attempts: 0 }, 3), false);
  assert.match(promptFor({ evidence: JSON.stringify({ code: 'record_module_missing' }) }, 15), /Missing native modules cannot be created/);
});
