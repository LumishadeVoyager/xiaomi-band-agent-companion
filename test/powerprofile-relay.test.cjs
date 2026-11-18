const { test } = require('node:test');
const assert = require('node:assert/strict');
const { validateRelayConfig, publishRelay } = require('../host/powerprofile-relay.cjs');
function sample(now = new Date()) {
  const metric = { value: 1, observedAt: now.toISOString(), source: 'PRIVATE FILE PATH', token: 'PRIVATE' };
  return { protocol: 1, entryId: 'powerprofile', type: 'powerprofile_snapshot', day: '2026-11-18', generatedAt: now.toISOString(), usageSeconds: metric, energyKWh: metric, gameSeconds: metric, aiTokens: metric, quota: null, transcript: 'PRIVATE' };
}
test('internet snapshot and event wire format drops private fields and checks units', async () => {
  const p = await import('../cloud-relay/src/protocol.ts');
  const normalized = p.normalizeSnapshot(sample());
  assert.equal(JSON.stringify(normalized).includes('PRIVATE'), false);
  assert.equal(normalized.usageSeconds.source, 'PowerProfile');
  assert.throws(() => p.normalizeSnapshot({ ...sample(), usageSeconds: { value: -1 } }));
  assert.throws(() => p.normalizeSnapshot({ ...sample(), generatedAt: new Date(Date.now()+600000).toISOString() }));
  const event = { id: 'a'.repeat(64), at: new Date().toISOString(), title: 'PRIVATE', text: 'PRIVATE' };
  assert.deepEqual(p.eventList([event]), [{ id: event.id, at: event.at }]);
  assert.throws(() => p.eventList([{ ...event, at: '2020-01-01T00:00:00Z' }]));
  assert.deepEqual(p.ids([event.id,event.id]), [event.id]);
});
test('relay rejects oversized streaming JSON before parsing', async () => {
  const { readBody } = await import('../cloud-relay/src/protocol.ts');
  const request = new Request('https://relay.example/relay/publish', { method:'POST', headers:{'Content-Type':'application/json'}, body:'x'.repeat(100) });
  await assert.rejects(readBody(request, 20), /body_too_large/);
  await assert.rejects(readBody(new Request('https://relay.example', {method:'POST',body:'{}'}), 20), /json_required/);
});
test('desktop publisher uses scoped credentials and only accepts explicit relay ACKs', async () => {
  const config = { endpoint:'https://relay.example', pcToken:'a'.repeat(64) };
  assert.equal(validateRelayConfig(config).endpoint, config.endpoint);
  for (const endpoint of ['http://relay.example','https://127.0.0.1','https://relay.local','https://relay.example/path','https://user:pass@relay.example']) assert.throws(() => validateRelayConfig({...config,endpoint}));
  let request;
  const result = await publishRelay(config, sample(), [], async (url,options) => { request={url,options}; return Response.json({accepted:true,ackedIds:['b'.repeat(64)],report:null}); });
  assert.equal(request.options.redirect,'error'); assert.equal(request.options.headers.Authorization,'Bearer '+config.pcToken); assert.equal(result.ackedIds.length,1);
  assert.equal(JSON.parse(request.options.body).pcToken,undefined);
  await assert.rejects(publishRelay(config,sample(),[],async()=>Response.json({accepted:true,ackedIds:['not-an-id']})),/invalid_relay_response/);
});
