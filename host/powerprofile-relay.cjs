const fs = require('node:fs');
function validateRelayConfig(config) {
  const url = new URL(config.endpoint);
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash || url.pathname !== '/' || (url.port && url.port !== '443') || !/^[a-z0-9.-]+\.[a-z]{2,}$/i.test(url.hostname) || url.hostname.endsWith('.local') || !/^[a-f0-9]{64}$/.test(config.pcToken)) throw new Error('invalid_relay_configuration');
  return { endpoint: url.origin, pcToken: config.pcToken };
}
async function publishRelay(config, snapshot, events, fetcher = fetch) {
  const valid = validateRelayConfig(config);
  const response = await fetcher(valid.endpoint + '/relay/publish', { method: 'POST', redirect: 'error', headers: { Authorization: 'Bearer ' + valid.pcToken, 'Content-Type': 'application/json' }, body: JSON.stringify({ protocol: 1, entryId: 'powerprofile', snapshot, events }), signal: AbortSignal.timeout(20000) });
  if (response.status !== 200) throw new Error('relay_http_' + response.status);
  const parts = []; let bytes = 0;
  for await (const part of response.body) { bytes += part.byteLength; if (bytes > 200000) throw new Error('relay_response_too_large'); parts.push(part); }
  const data = JSON.parse(Buffer.concat(parts).toString('utf8'));
  if (data.accepted !== true || !Array.isArray(data.ackedIds) || data.ackedIds.length > 2000 || data.ackedIds.some(id => typeof id !== 'string' || !/^[a-f0-9]{64}$/.test(id))) throw new Error('invalid_relay_response');
  let report = null;
  if (data.report && Number.isSafeInteger(data.report.versionCode) && data.report.versionCode > 0 && typeof data.report.snapshotAt === 'string' && Number.isFinite(Date.parse(data.report.snapshotAt)) && typeof data.report.receivedAt === 'string' && Number.isFinite(Date.parse(data.report.receivedAt)) && data.report.transport === 'internet') report = { event: 'powerprofile_snapshot_applied', versionCode: data.report.versionCode, snapshotAt: data.report.snapshotAt, receivedAt: data.report.receivedAt, transport: 'internet' };
  return { ackedIds: data.ackedIds, report };
}
function loadRelay(file) { return fs.existsSync(file) ? validateRelayConfig(JSON.parse(fs.readFileSync(file, 'utf8'))) : null; }
module.exports = { validateRelayConfig, publishRelay, loadRelay };
