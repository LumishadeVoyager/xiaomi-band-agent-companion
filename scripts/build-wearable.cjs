const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const zlib = require('node:zlib');
const root = path.resolve(__dirname, '..');
const tools = process.env.BAND_AGENT_TOOLS || path.join(process.env.LOCALAPPDATA || root, 'BandAgentDev');
const cli = path.join(tools, 'node_modules/aiot-toolkit/lib/bin.js');
if (!fs.existsSync(cli)) throw new Error('Install aiot-toolkit@2.0.5 and @aiot-toolkit/jsc@1.0.3 in BAND_AGENT_TOOLS first');
function crc32(data) { let crc = -1; for (const b of data) { crc ^= b; for (let j = 0; j < 8; j++) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1)); } return (crc ^ -1) >>> 0; }
function pngChunk(name, data) { const type = Buffer.from(name), size = Buffer.alloc(4), crc = Buffer.alloc(4); size.writeUInt32BE(data.length); crc.writeUInt32BE(crc32(Buffer.concat([type, data]))); return Buffer.concat([size, type, data, crc]); }
function icon(color) { const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(64); ihdr.writeUInt32BE(64, 4); ihdr[8] = 8; ihdr[9] = 2; const pixels = Buffer.alloc(64 * 193); for (let y = 0; y < 64; y++) for (let x = 0; x < 64; x++) { const p = y * 193 + 1 + x * 3; const white = (x > 15 && x < 22 && y > 13 && y < 51) || (y > 13 && y < 20 && x > 15 && x < 49) || (y > 44 && y < 51 && x > 15 && x < 49); pixels[p] = white ? 255 : color[0]; pixels[p + 1] = white ? 255 : color[1]; pixels[p + 2] = white ? 255 : color[2]; } return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]), pngChunk('IHDR', ihdr), pngChunk('IDAT', zlib.deflateSync(pixels)), pngChunk('IEND', Buffer.alloc(0))]); }
const powerprofile = process.argv.includes('--powerprofile');
for (const entry of powerprofile ? [{ id: 'powerprofile', name: 'PowerProfile', color: [35, 87, 154] }] : [{ id: 'codex', name: 'Codex', color: [35, 87, 154] }, { id: 'harness', name: 'DeepSeek Harness', color: [12, 114, 87] }]) {
  const work = path.join(tools, 'wearable-build', entry.id);
  fs.mkdirSync(work, { recursive: true });
  fs.cpSync(path.join(root, powerprofile ? 'powerprofile-wearable/src' : 'wearable/src'), path.join(work, 'src'), { recursive: true });
  const common = path.join(work, 'src/common'); fs.mkdirSync(common, { recursive: true });
  for (const file of powerprofile ? ['powerprofile-view.cjs'] : ['audio-wire.cjs', 'record-capability.cjs', 'diagnostics.cjs']) fs.copyFileSync(path.join(root, 'shared', file), path.join(common, file));
  entry.version = require('../package.json').version;
  entry.versionCode = require('../package.json').buildNumber;
  fs.writeFileSync(path.join(common, 'entry.json'), JSON.stringify(entry));
  if (powerprofile) {
    if (!fs.existsSync(path.join(common, 'logo.png'))) throw new Error('PowerProfile review icon assets missing');
  } else fs.writeFileSync(path.join(common, 'logo.png'), icon(entry.color));
  fs.writeFileSync(path.join(work, 'package.json'), JSON.stringify({ name: 'band-agent-' + entry.id, version: '0.1.0', dependencies: {} }));
  fs.writeFileSync(path.join(work, 'src/manifest.json'), JSON.stringify({ package: 'io.github.lumishadevoyager.bandagent.' + entry.id, name: entry.name, versionName: entry.version, versionCode: entry.versionCode, minPlatformVersion: 1200, icon: '/common/logo.png', deviceTypeList: ['watch'], features: [{ name: 'system.record' }, { name: 'system.interconnect' }, { name: 'system.app' }, { name: 'system.router' }, { name: 'system.storage' }], config: { designWidth: 336, logLevel: 'debug' }, router: { entry: 'pages/index', pages: { 'pages/index': { component: 'index' }, 'pages/update': { component: 'update' }, 'pages/diagnostics': { component: 'diagnostics' } } } }, null, 2));
  // Build outside synced storage; share the private development signing identity.
  if (powerprofile) {
    const manifestPath = path.join(work, 'src/manifest.json');
    const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
    manifest.features = [{ name: 'system.interconnect' }, { name: 'system.storage' }];
    manifest.router.pages = { 'pages/index': { component: 'index' } };
    fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2));
  }
  const sign = path.join(root, '.local/sign/debug');
  if (fs.existsSync(sign)) fs.cpSync(sign, path.join(work, 'sign/debug'), { recursive: true });
  const expectedName = 'io.github.lumishadevoyager.bandagent.' + entry.id + '.debug.' + entry.version + '.rpk';
  const artifact = path.join(work, 'dist', expectedName);
  // aiot-toolkit can print a fatal error and still exit zero. Never reuse an
  // old RPK or mistake that exit code for a successful current build.
  if (fs.existsSync(artifact)) fs.unlinkSync(artifact);
  const run = cp.spawnSync(process.execPath, [cli, 'build'], { cwd: work, encoding: 'utf8', env: { ...process.env, NODE_PATH: path.join(tools, 'node_modules') } });
  process.stdout.write(run.stdout || ''); process.stderr.write(run.stderr || '');
  if (run.status !== 0 || /build error|missing feature|❌/i.test((run.stdout || '') + (run.stderr || '')) || !fs.existsSync(artifact)) throw new Error('Wearable build failed; no current artifact accepted: ' + entry.id);
  const out = path.join(root, '.local/artifacts', entry.id); fs.mkdirSync(out, { recursive: true });
  fs.copyFileSync(artifact, path.join(out, expectedName));
  console.log('Built development app: ' + entry.id);
}
