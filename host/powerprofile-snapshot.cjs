const fs = require('node:fs');
const path = require('node:path');
function read(file) {
  try { return { data: JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, '')), at: fs.statSync(file).mtime.toISOString() }; }
  catch { return { data: null, at: null }; }
}
function metric(value, source, at) {
  return { value: typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : null, source, observedAt: at };
}
function snapshot(root, quota = null, now = new Date()) {
  const day = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(now);
  const energy = read(path.join(root, 'energy.history.json'));
  const usage = read(path.join(root, 'usage.logs.history.json'));
  const ai = read(path.join(root, 'ai.snapshot.json'));
  const e = energy.data?.Days?.[day], u = usage.data?.Days?.[day];
  // Match ProfileUsageLogReader.Merge: overlapping coverage uses the maximum.
  // Do not add the energy and log ledgers or prefer a smaller log value.
  function coverage(field) {
    const candidates = [{ value: e?.[field], at: energy.at }, { value: u?.[field], at: usage.at }]
      .filter(m => typeof m.value === 'number' && Number.isFinite(m.value) && m.value >= 0);
    const best = candidates.sort((a, b) => b.value - a.value)[0];
    return metric(best?.value, 'PowerProfile max(logs, sampled coverage)', best?.at ?? null);
  }
  const rows = ai.data?.combinedDaily;
  const today = Array.isArray(rows) ? rows.filter(r => r.date === day) : null;
  const tokens = today?.length && today.every(r => typeof r.tokens === 'number' && r.tokens >= 0) ? today.reduce((sum, r) => sum + r.tokens, 0) : null;
  return { protocol: 1, type: 'powerprofile_snapshot', entryId: 'powerprofile', day, generatedAt: now.toISOString(),
    usageSeconds: coverage('Seconds'),
    energyKWh: metric(e?.KWh, 'PowerProfile power integration estimate', energy.at),
    gameSeconds: coverage('GameSeconds'),
    aiTokens: metric(tokens, 'PowerProfile combinedDaily (day-client-max)', ai.data?.updatedAt ?? ai.at), quota };
}
module.exports = { snapshot, metric };
