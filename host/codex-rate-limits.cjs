const { spawn } = require('node:child_process');
const readline = require('node:readline');
function normalize(result, now = new Date()) {
  const limits = result?.rateLimits;
  const windows = [limits?.primary, limits?.secondary];
  function window(minutes) {
    const w = windows.find(w => w?.windowDurationMins === minutes);
    if (!w || typeof w.usedPercent !== 'number' || !Number.isFinite(w.usedPercent) || w.usedPercent < 0 || w.usedPercent > 100 || !Number.isSafeInteger(w.resetsAt)) return null;
    return { usedPercent: w.usedPercent, remainingPercent: 100 - w.usedPercent, resetsAt: w.resetsAt, windowMinutes: minutes };
  }
  return { observedAt: now.toISOString(), fiveHour: window(300), weekly: window(10080) };
}
function readRateLimits(executable) {
  return new Promise((resolve, reject) => {
    const child = spawn(executable, ['app-server', '--stdio'], { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
    const lines = readline.createInterface({ input: child.stdout }); let settled = false;
    const finish = (error, value) => { if (settled) return; settled = true; clearTimeout(timer); lines.close(); child.stdin.end(); child.kill(); error ? reject(error) : resolve(value); };
    const timer = setTimeout(() => finish(new Error('quota_timeout')), 20000);
    child.stderr.resume(); child.stdin.on('error', () => {});
    child.on('error', () => finish(new Error('quota_executable_unavailable')));
    child.on('exit', () => finish(new Error('quota_process_exited')));
    lines.on('line', line => {
      try {
        const message = JSON.parse(line);
        if (message.id === 1) {
          if (message.error) return finish(new Error('quota_initialize_failed'));
          child.stdin.write(JSON.stringify({ method: 'initialized', params: {} }) + '\n');
          child.stdin.write(JSON.stringify({ id: 2, method: 'account/rateLimits/read', params: null }) + '\n');
        }
        if (message.id === 2) message.error ? finish(new Error('quota_read_failed')) : finish(null, normalize(message.result));
      } catch { /* Other app-server output is not persisted or forwarded. */ }
    });
    child.stdin.write(JSON.stringify({ id: 1, method: 'initialize', params: { clientInfo: { name: 'powerprofile-band-monitor', version: '0.1.0' }, capabilities: { experimentalApi: true } } }) + '\n');
  });
}
module.exports = { normalize, readRateLimits };
