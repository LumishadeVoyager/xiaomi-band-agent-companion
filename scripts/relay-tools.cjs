// Compile outside Google Drive's virtual filesystem, preserving other runtimes.
const fs = require('node:fs'), path = require('node:path'), cp = require('node:child_process');
const root = path.resolve(__dirname, '..');
const tools = process.env.POWERPROFILE_RELAY_TOOLS || path.join(process.env.LOCALAPPDATA || root, 'BandAgentDev/relay-tools');
const work = path.join(tools, 'work'); fs.mkdirSync(work, { recursive: true });
for (const file of ['package.json', 'tsconfig.json', 'wrangler.jsonc']) fs.copyFileSync(path.join(root, 'cloud-relay', file), path.join(work, file));
fs.cpSync(path.join(root, 'cloud-relay/src'), path.join(work, 'src'), { recursive: true });
const generated = path.join(root, 'cloud-relay/worker-configuration.d.ts');
if (fs.existsSync(generated)) fs.copyFileSync(generated, path.join(work, 'worker-configuration.d.ts'));
const deploy = path.join(root, '.local/relay-deployment.json');
if (fs.existsSync(deploy)) { const { accountId } = JSON.parse(fs.readFileSync(deploy, 'utf8')); if (accountId) { const file = path.join(work, 'wrangler.jsonc'); const config = JSON.parse(fs.readFileSync(file, 'utf8')); config.account_id = accountId; fs.writeFileSync(file, JSON.stringify(config, null, 2)); } }
const [command, ...args] = process.argv.slice(2);
const cli = command === 'check' ? path.join(tools, 'node_modules/typescript/bin/tsc') : path.join(tools, 'node_modules/wrangler/bin/wrangler.js');
if (!fs.existsSync(cli)) throw new Error('Install the pinned cloud-relay devDependencies into POWERPROFILE_RELAY_TOOLS first');
const run = cp.spawnSync(process.execPath, [cli, ...(command === 'check' ? ['--noEmit'] : [command, ...args])], { cwd: work, env: process.env, stdio: 'inherit', windowsHide: true });
if (run.status === 0 && command === 'types') fs.copyFileSync(path.join(work, 'worker-configuration.d.ts'), generated);
process.exitCode = run.status ?? 1;
