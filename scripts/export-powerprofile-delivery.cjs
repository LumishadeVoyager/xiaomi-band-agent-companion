// Export the real existing app artifacts, never label an RPK as a watchface.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const cp = require('node:child_process');
const root = path.resolve(__dirname, '..');
const pkg = require('../package.json');
const audit = cp.spawnSync(process.env.PYTHON || 'python', [path.join(root, 'scripts/audit-release.py'), '--powerprofile'], { cwd: root, windowsHide: true, encoding: 'utf8' });
if (audit.status !== 0) throw new Error('Current app artifact audit failed; export refused');
const rpkName = 'io.github.lumishadevoyager.bandagent.powerprofile.debug.' + pkg.version + '.rpk';
const inputs = [
  { source: path.join(root, '.local/artifacts/powerprofile', rpkName), name: rpkName, type: 'wearable_application_rpk' },
  { source: path.join(root, '.local/artifacts/band-agent-powerprofile-probe.apk'), name: 'band-agent-powerprofile-probe.apk', type: 'android_companion_apk' }
];
const output = path.join(root, '.local/deliveries/powerprofile'); fs.mkdirSync(output, { recursive: true });
const files = inputs.map(item => {
  const bytes = fs.readFileSync(item.source); const sha256 = crypto.createHash('sha256').update(bytes).digest('hex');
  fs.writeFileSync(path.join(output, item.name), bytes);
  return { name: item.name, type: item.type, size: bytes.length, sha256 };
});
const git = cp.spawnSync('git', ['rev-parse', 'HEAD'], { cwd: root, windowsHide: true, encoding: 'utf8' });
const manifest = { schema: 1, exportedAt: new Date().toISOString(), sourceCheckout: git.status === 0 ? git.stdout.trim() : null, version: pkg.version, versionCode: pkg.buildNumber, stage: 'development_preview_not_device_accepted', nativeWatchfaceIncluded: false, files,
  pending: ['new app device install and signed pairing', 'physical layout and scrolling', 'locked-phone sync and reconnect', 'band notification receipt vibration and DND', 'native watchface implementation and validation'],
  note: 'Hashes identify these local binaries; sourceCheckout identifies the exporting checkout, not a reproducible binary provenance attestation. No release or OTA promotion.' };
fs.writeFileSync(path.join(output, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
fs.writeFileSync(path.join(output, 'SHA256SUMS.txt'), files.map(f => f.sha256 + '  ' + f.name).join('\n') + '\n');
fs.copyFileSync(path.join(root, 'docs/POWERPROFILE_WATCHFACE.md'), path.join(output, '路线与验收.md'));
fs.writeFileSync(path.join(output, '交付说明.txt'), 'PowerProfile 开发预览\n\nRPK 是手环应用，APK 是安卓伴侣，均不是表盘。\n本次不包含已实现或已验收的原生表盘 BIN/FACE。\n真实哈希见 SHA256SUMS.txt；新入口真机安装、配对、后台同步、系统通知与震动尚待验收。\n设计预览数据是示例，不是真机读数。\n开发包未发布到 OTA，不自动安装、不发送真实通知。\n');
console.log('Exported audited application artifacts, hashes and acceptance notes: ' + output);
