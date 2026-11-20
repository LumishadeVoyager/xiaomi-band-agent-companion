const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { snapshot } = require('../host/powerprofile-snapshot.cjs');
const { normalize } = require('../host/codex-rate-limits.cjs');
const { present } = require('../shared/powerprofile-view.cjs');
const { Completions } = require('../host/codex-completions.cjs');
const vm = require('node:vm');
test('real power page emits fixed desired commands, suppresses double taps and never guesses unknown state', () => {
  const ux=fs.readFileSync(path.join(__dirname,'../powerprofile-wearable/src/pages/index/index.ux'),'utf8');
  const source=ux.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm,'').replace('export default','module.exports =');
  const vibrations=[];
  const sandbox={module:{exports:{}},entry:{versionCode:16},Date,Math,view:{},setInterval,clearInterval,setTimeout:()=>1,clearTimeout:()=>{},require:name=>{assert.equal(name,'@system.vibrator');return {vibrate:event=>vibrations.push(event.mode)};}};vm.runInNewContext(source,sandbox);
  const page=sandbox.module.exports, sent=[];
  const state={...page.private,...page,connection:{send:r=>sent.push(r.data)}};
  state.power={value:true,available:true,target:'purifier'};state.powerAt=Date.now();state.togglePower();state.togglePower();
  assert.equal(sent.filter(x=>x.type==='powerprofile_power_request').length,1);
  assert.equal(sent[0].desired,false);assert.equal(sent[0].target,'purifier');assert.match(sent[0].id,/^[a-f0-9]{32}$/);
  assert.equal(state.power.value,true); // Wait for real cloud readback.
  assert.equal(vibrations.length,1);assert.match(state.powerClass,/pending/);
  const commandId=sent[0].id;
  state.applyPower({value:true,available:true,target:'purifier',phase:'ready'});state.togglePower();
  assert.equal(state.pendingPowerId,commandId);assert.equal(sent.filter(x=>x.type==='powerprofile_power_request').length,1);
  const confirmed={value:false,available:true,target:'purifier',commandId,commandPhase:'confirmed'};
  state.applyPower(confirmed);assert.equal(state.pendingPowerId,'');assert.equal(state.power.value,false);assert.equal(vibrations.length,2);
  state.applyPower(confirmed);assert.equal(vibrations.length,2); // Periodic receipt is not a new haptic.
  sent.length=0;state.power={value:null,available:false,target:'computer'};state.togglePower();
  assert.equal(sent.some(x=>x.type==='powerprofile_power_request'),false);
  state.applyPower({value:true,available:true,target:'computer',computerStatus:'unknown'});assert.match(state.computerLabel,/待同步/);
});
test('compact layout keeps units, unknown values and stale quota distinct', () => {
  const { compact } = require('../shared/powerprofile-view.cjs');
  const now=Date.parse('2026-11-19T00:24:00Z');
  const metric=value=>({value,observedAt:new Date(now).toISOString()});
  const data={generatedAt:new Date(now).toISOString(),usageSeconds:metric(16320),gameSeconds:metric(4080),energyKWh:metric(.42),aiTokens:metric(842000),quota:{observedAt:new Date(now).toISOString(),fiveHour:{remainingPercent:0,resetsAt:now/1000+3600},weekly:{remainingPercent:46,resetsAt:now/1000+86400}}};
  let v=compact(data,now);assert.equal(v.usageValue,'04:32');assert.equal(v.energyValue,'0.42');assert.equal(v.gameValue,'01:08');assert.equal(v.tokenValue,'842.0K');assert.equal(v.fiveNumber,'0%');assert.equal(v.fiveLabel,'5 小时');assert.equal(v.fiveReset,'1小时0分后重置');
  v=compact({...data,energyKWh:metric(.004)},now);assert.equal(v.energyValue,'<0.01');assert.equal(v.snapshotStale,false);
  v=compact(data,now+120000);assert.equal(v.snapshotStale,true);assert.equal(v.snapshotAgeMinutes,2);
  v=compact(data,now+3600001);assert.match(v.fiveLabel,/上次/);assert.equal(v.fiveNumber,'0%');
  v=compact({generatedAt:'invalid'},now);assert.equal(v.usageValue,'—');assert.equal(v.fiveNumber,'—');assert.equal(v.fiveReset,'暂无数据');
});
test('Beijing date, overlapping ledgers, merged tokens and missing values', t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'powerprofile-')); t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const put = (file, data) => fs.writeFileSync(path.join(root, file), '\uFEFF' + JSON.stringify(data));
  const now = new Date('2026-11-17T17:00:00Z');
  put('energy.history.json', { Days: { '2026-11-18': { KWh: 1.2, Seconds: 9000, GameSeconds: 900 } } });
  put('usage.logs.history.json', { Days: { '2026-11-18': { Seconds: 10000, GameSeconds: null } } });
  put('ai.snapshot.json', { daily: [{ date: '2026-11-18', tokens: 999 }], combinedDaily: [{ date: '2026-11-18', tokens: 12 }, { date: '2026-11-18', tokens: 8 }, { date: '2026-11-17', tokens: 999 }] });
  let s = snapshot(root, null, now);
  assert.equal(s.day, '2026-11-18'); assert.equal(s.usageSeconds.value, 10000); assert.equal(s.gameSeconds.value, 900); assert.equal(s.energyKWh.value, 1.2); assert.equal(s.aiTokens.value, 20);
  fs.writeFileSync(path.join(root, 'energy.history.json'), '{');
  s = snapshot(root, null, now); assert.equal(s.energyKWh.value, null);
  assert.equal(s.gameSeconds.value, null);
  assert.equal(present(s, now.getTime()).energy, '未统计');
  assert.equal(snapshot(root, null, new Date('2026-11-19T00:00:00Z')).aiTokens.value, null);
});
test('quota checks window identity and never derives percentage from tokens', () => {
  const now = new Date('2026-11-18T00:00:00Z');
  const q = normalize({ rateLimits: { primary: { windowDurationMins: 10080, usedPercent: 35, resetsAt: now.getTime()/1000+500 }, secondary: { windowDurationMins: 300, usedPercent: 12.5, resetsAt: now.getTime()/1000+300 } } }, now);
  assert.equal(q.fiveHour.remainingPercent, 87.5); assert.equal(q.weekly.remainingPercent, 65);
  const s = { quota: q };
  assert.match(present(s, now.getTime()).five, /^剩余/);
  assert.match(present(s, now.getTime()+400000).five, /^上次/);
  assert.equal(normalize({ rateLimits: { primary: { windowDurationMins: 60, usedPercent: 1, resetsAt: 123 } } }).fiveHour, null);
});
test('completion baseline, partial lines, duplicate scan and durable ACK', t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'completions-')); t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const now = new Date(), dir = path.join(root, now.toISOString().slice(0,10).replaceAll('-', '/'));
  fs.mkdirSync(dir, { recursive: true }); const file = path.join(dir, 'rollout-test.jsonl');
  const event = () => JSON.stringify({ timestamp: new Date().toISOString(), type: 'event_msg', payload: { type: 'task_complete', last_agent_message: 'PRIVATE CONTENT MUST NOT BE FORWARDED' } });
  fs.writeFileSync(file, event()+'\n');
  const db = path.join(root,'events.sqlite'), c = new Completions(db); c.started = Date.now()+10;
  c.scan(root); assert.equal(c.pending().length,0);
  c.started = Date.now()-1;
  fs.appendFileSync(file,event()); c.scan(root); assert.equal(c.pending().length,0);
  fs.appendFileSync(file,'\n'); c.scan(root); assert.equal(c.pending().length,1);
  c.scan(root); assert.equal(c.pending().length,1);
  const pending=c.pending(); assert.deepEqual(Object.keys(pending[0]).sort(), ['at','id']); c.ack([pending[0].id]); c.close();
  fs.appendFileSync(file, event()+'\n');
  const reopened = new Completions(db); reopened.started = Date.now()+1000;
  assert.equal(reopened.pending().length,0); reopened.scan(root); assert.equal(reopened.pending().length,1); reopened.close();
});
test('wearable ACK requires a fresh matching snapshot, never cache or another entry', () => {
  const source = fs.readFileSync(path.join(__dirname, '../powerprofile-wearable/src/pages/index/index.ux'), 'utf8');
  const code = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '').replace('export default', 'module.exports =');
  const sent = [], connection = { send(message) { sent.push(message.data); } };
  let restore;
  const storage = { get(options) { restore = options.success; }, set() {} };
  const versionCode = require('../package.json').buildNumber;
  const context = { module: { exports: {} }, view: require('../shared/powerprofile-view.cjs'), entry: { versionCode }, setInterval() { return 1; }, clearInterval() {},
    require(name) { if (name === '@system.storage') return storage; if (name === '@system.interconnect') return { instance() { return connection; } }; throw Error('unexpected_module'); } };
  vm.runInNewContext(code, context); const page = context.module.exports; Object.assign(page, page.private); page.onInit();
  const data = { protocol: 1, entryId: 'powerprofile', type: 'powerprofile_snapshot', generatedAt: '2026-11-18T12:00:00.000Z', day: '2026-11-18' };
  connection.onmessage({ data: { ...data, entryId: 'codex' } });
  assert.equal(sent.filter(m => m.type === 'powerprofile_snapshot_applied').length, 0);
  connection.onmessage({ data });
  const ack = sent.filter(m => m.type === 'powerprofile_snapshot_applied'); assert.equal(ack.length, 1); assert.equal(ack[0].snapshotAt, data.generatedAt); assert.equal(ack[0].versionCode, versionCode);
  restore(JSON.stringify({ ...data, generatedAt: '2026-11-18T11:00:00.000Z' }));
  connection.onmessage({ data: { ...data, generatedAt: '2026-11-18T10:00:00.000Z' } });
  assert.equal(sent.filter(m => m.type === 'powerprofile_snapshot_applied').length, 1); assert.equal(page.last.generatedAt, data.generatedAt);
  page.onDestroy();
});
