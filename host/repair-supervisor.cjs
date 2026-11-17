// Opt-in, bounded Codex repair worker for private technical diagnostics.
// Runs in a fresh public-source clone, never in the user's active working tree.
const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const { DiagnosticStore } = require('./diagnostic-store.cjs');
const { allowedSource, eligible, promptFor } = require('./repair-policy.cjs');
const root = path.resolve(__dirname, '..');
const runtime = process.env.BAND_AGENT_RUNTIME || path.join(process.env.LOCALAPPDATA || path.join(root, '.local'), 'BandAgentDev', 'runtime');
const configFile = path.join(runtime, 'repair-settings.json');
const lockFile = path.join(runtime, 'repair-worker.lock');
const store = new DiagnosticStore(path.join(runtime, 'diagnostics.sqlite'));
const repo = 'LumishadeVoyager/xiaomi-band-agent-companion';
const resultSchema = { type: 'object', properties: { outcome: { type: 'string', enum: ['fixed', 'blocked'] }, summary: { type: 'string' }, evidence: { type: 'string' } }, required: ['outcome', 'summary', 'evidence'], additionalProperties: false };
function run(command, args, options = {}) {
  const result = cp.spawnSync(command, args, { windowsHide: true, encoding: 'utf8', maxBuffer: 8 * 1024 * 1024, timeout: 10 * 60 * 1000, ...options });
  if (result.status !== 0) throw new Error(path.basename(command) + '_failed_' + result.status);
  return result.stdout.trim();
}
function git(cwd, args, config) { const flags = config.gitProxy ? ['-c', 'http.proxy=' + config.gitProxy] : []; return run('git', [...flags, '-c', 'gc.auto=0', ...args], { cwd }); }
async function repair(config, row) {
  const folder = path.join(runtime, 'repairs', row.fingerprint); fs.mkdirSync(folder, { recursive: true });
  const checkout = path.join(folder, 'source');
  if (fs.existsSync(checkout)) throw new Error('existing_repair_checkout_requires_review');
  git(folder, ['clone', '--depth', '1', 'https://github.com/' + repo + '.git', checkout], config);
  const currentPackage = JSON.parse(fs.readFileSync(path.join(checkout, 'package.json')));
  if (row.version_code < currentPackage.buildNumber) { store.db.prepare("UPDATE repairs SET status='awaiting_current_version' WHERE fingerprint=?").run(row.fingerprint); return; }
  const issueFile = path.join(folder, 'issue.md');
  fs.writeFileSync(issueFile, `Observed automatic diagnostic in ${row.entry}, build ${row.version_code}: ${row.code}.\n\nTechnical metadata only:\n\n\`\`\`json\n${row.evidence}\n\`\`\`\n\nThe repair worker will validate a source fix or record a capability blocker; device success requires subsequent real-device evidence. Relates to #15.\n`);
  const issueUrl = run('gh', ['issue', 'create', '--repo', repo, '--title', '[Auto repair] ' + row.entry + ' ' + row.code + ' build ' + row.version_code, '--body-file', issueFile]);
  const issue = Number(issueUrl.split('/').pop()); if (!Number.isSafeInteger(issue)) throw new Error('invalid_issue_receipt');
  store.db.prepare("UPDATE repairs SET attempts=attempts+1,status='running' WHERE fingerprint=?").run(row.fingerprint);
  const schema = path.join(folder, 'result-schema.json'), reportFile = path.join(folder, 'result.json'); fs.writeFileSync(schema, JSON.stringify(resultSchema));
  const log = fs.openSync(path.join(folder, 'codex-events.jsonl'), 'w');
  let status;
  try {
    status = cp.spawnSync(config.codexExecutable, ['-a', 'never', 'exec', '--sandbox', 'workspace-write', '--cd', checkout, '--ephemeral', '--json', '--output-schema', schema, '--output-last-message', reportFile, '-'], { input: promptFor(row, issue), windowsHide: true, encoding: 'utf8', timeout: 20 * 60 * 1000, stdio: ['pipe', log, log] }).status;
  } finally { fs.closeSync(log); }
  if (status !== 0 || !fs.existsSync(reportFile)) throw new Error('codex_repair_did_not_complete');
  const report = JSON.parse(fs.readFileSync(reportFile, 'utf8'));
  if (!['fixed', 'blocked'].includes(report.outcome) || typeof report.summary !== 'string') throw new Error('invalid_repair_report');
  const reportBody = path.join(folder, 'report.md'); fs.writeFileSync(reportBody, report.summary + '\n\n' + report.evidence + '\n');
  if (report.outcome === 'blocked') { run('gh', ['issue', 'comment', String(issue), '--repo', repo, '--body-file', reportBody]); store.db.prepare("UPDATE repairs SET status='capability_or_external_blocker' WHERE fingerprint=?").run(row.fingerprint); return; }
  const changed = [...new Set([...git(checkout, ['diff', '--name-only'], config).split('\n'), ...git(checkout, ['ls-files', '--others', '--exclude-standard'], config).split('\n')].filter(Boolean))];
  if (!changed.length || changed.some(name => !allowedSource(name))) throw new Error('repair_scope_rejected');
  for (const name of changed) { const file = path.join(checkout, name); if (fs.existsSync(file) && fs.lstatSync(file).isSymbolicLink()) throw new Error('repair_symlink_rejected'); }
  // Only test-passing source can enter a preview. Keys/build/publish stay in CI.
  run(process.execPath, ['--test', ...fs.readdirSync(path.join(checkout, 'test')).filter(f => f.endsWith('.test.cjs')).map(f => path.join(checkout, 'test', f))], { cwd: checkout });
  const match = /^(\d+\.\d+\.\d+-preview\.)(\d+)$/.exec(currentPackage.version); if (!match) throw new Error('preview_version_required');
  currentPackage.version = match[1] + (Number(match[2]) + 1); currentPackage.buildNumber++;
  fs.writeFileSync(path.join(checkout, 'package.json'), JSON.stringify(currentPackage, null, 2) + '\n');
  const branch = 'fix/auto-' + row.fingerprint.slice(0, 12);
  git(checkout, ['switch', '-c', branch], config);
  git(checkout, ['config', 'user.name', 'Band Agent Repair'], config); git(checkout, ['config', 'user.email', '288021395+LumishadeVoyager@users.noreply.github.com'], config);
  git(checkout, ['add', '--', ...changed, 'package.json'], config); git(checkout, ['commit', '-m', 'Repair ' + row.code + ' from device diagnostics'], config);
  git(checkout, ['push', 'origin', branch], config);
  fs.appendFileSync(reportBody, `\nPreview ${currentPackage.version}; device acceptance remains pending. Relates to #${issue}, #15.\n`);
  const pr = run('gh', ['pr', 'create', '--repo', repo, '--base', 'main', '--head', branch, '--title', 'Repair ' + row.code + ' from device diagnostics', '--body-file', reportBody], { cwd: checkout });
  run('gh', ['pr', 'checks', pr, '--watch', '--fail-fast', '--interval', '15'], { cwd: checkout });
  run('gh', ['pr', 'merge', pr, '--merge'], { cwd: checkout });
  run('gh', ['workflow', 'run', 'client-preview.yml', '--repo', repo, '--ref', 'main']);
  store.db.prepare("UPDATE repairs SET status='release_workflow_started' WHERE fingerprint=?").run(row.fingerprint);
}
async function tick() {
  if (!fs.existsSync(configFile)) return;
  const config = JSON.parse(fs.readFileSync(configFile, 'utf8')); if (config.enabled !== true || !config.codexExecutable) return;
  const devLock = path.join(root, '.local/development-active.json');
  if (fs.existsSync(devLock)) { const active = JSON.parse(fs.readFileSync(devLock, 'utf8')); if (active.expiresAt > Date.now()) return; }
  const attempts = store.db.prepare('SELECT COALESCE(SUM(attempts),0) AS n FROM repairs WHERE created>?').get(Date.now() - 86400000).n;
  const row = store.pendingRepairs().find(value => eligible(value, attempts)); if (!row) return;
  let fd;
  try { fd = fs.openSync(lockFile, 'wx'); fs.writeFileSync(fd, String(process.pid)); await repair(config, row); }
  catch (error) { if (fd !== undefined) store.db.prepare("UPDATE repairs SET status='needs_review' WHERE fingerprint=?").run(row.fingerprint); console.log(JSON.stringify({ event: 'repair_worker_attention', code: error.code === 'EEXIST' ? 'another_worker_active' : error.message })); }
  finally { if (fd !== undefined) { fs.closeSync(fd); fs.unlinkSync(lockFile); } }
}
async function main() { do { await tick(); if (!process.argv.includes('--watch')) break; await new Promise(resolve => setTimeout(resolve, 15000)); } while (true); store.close(); }
main().catch(error => { console.error(error.message); process.exitCode = 1; });
