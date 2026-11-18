const { test } = require('node:test');
const assert = require('node:assert/strict');

test('Pages gateway preserves scoped authentication and streams to its binding only', async () => {
  const { default: gateway } = await import('../pages-relay/public/_worker.js');
  let forwarded;
  const request = new Request('https://candidate.pages.dev/powerprofile/poll', { method: 'POST', headers: { Authorization: 'Bearer fixture', 'Content-Type': 'application/json' }, body: '{"protocol":1,"entryId":"powerprofile"}' });
  const result = await gateway.fetch(request, { RELAY: { fetch: async value => { forwarded = value; return Response.json({ error: 'unauthorized' }, { status: 401, headers: { 'Cache-Control': 'no-store' } }); } } });
  assert.equal(forwarded, request);
  assert.equal(forwarded.headers.get('Authorization'), 'Bearer fixture');
  assert.equal(result.status, 401);
  assert.equal(result.headers.get('Cache-Control'), 'no-store');
  assert.equal((await forwarded.json()).entryId, 'powerprofile');
});

test('Pages gateway cannot expose arbitrary forwarding or static data', async () => {
  const { default: gateway } = await import('../pages-relay/public/_worker.js');
  const env = { RELAY: { fetch: async () => assert.fail('must not forward denied route') } };
  for (const [method, path] of [['POST','/arbitrary'],['GET','/relay/publish'],['GET','/powerprofile/poll'],['POST','/powerprofile/poll?token=secret'],['GET','/'],['GET','/snapshot.json']]) {
    const result = await gateway.fetch(new Request('https://candidate.pages.dev'+path, {method}), env);
    assert.equal(result.status, 404);
  }
  const failed = await gateway.fetch(new Request('https://candidate.pages.dev/health'), { RELAY: { fetch: async () => { throw Error('private backend diagnostic'); } } });
  assert.equal(failed.status, 503);
  assert.equal(JSON.stringify(await failed.json()).includes('private'), false);
});
