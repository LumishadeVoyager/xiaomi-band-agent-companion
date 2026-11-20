// Compile outside Google Drive's virtual filesystem, preserving other runtimes.
const fs = require('node:fs'), path = require('node:path'), cp = require('node:child_process');
const root = path.resolve(__dirname, '..');
const tools = process.env.POWERPROFILE_RELAY_TOOLS || path.join(process.env.LOCALAPPDATA || root, 'BandAgentDev/relay-tools');
const argv = process.argv.slice(2), gateway = argv[0] === '--gateway';
if (gateway && argv.includes('deploy') && fs.existsSync(path.join(root, '.local/powerprofile-ui-review.pending.json'))) throw new Error('PowerProfile UI review pending; Pages OTA deployment is paused');
if (gateway) argv.shift();
const source = path.join(root, gateway ? 'pages-relay' : 'cloud-relay');
const work = path.join(tools, gateway ? 'gateway-work' : 'work'); fs.mkdirSync(work, { recursive: true });
for (const file of gateway ? ['package.json', 'wrangler.jsonc'] : ['package.json', 'tsconfig.json', 'wrangler.jsonc']) fs.copyFileSync(path.join(source, file), path.join(work, file));
fs.cpSync(path.join(source, gateway ? 'public' : 'src'), path.join(work, gateway ? 'public' : 'src'), { recursive: true });
const generated = path.join(root, 'cloud-relay/worker-configuration.d.ts');
if (!gateway && fs.existsSync(generated)) fs.copyFileSync(generated, path.join(work, 'worker-configuration.d.ts'));
const deploy = path.join(root, '.local/relay-deployment.json');
const runEnv = { ...process.env };
if (fs.existsSync(deploy)) { const { accountId } = JSON.parse(fs.readFileSync(deploy, 'utf8')); if (accountId) { if (gateway) runEnv.CLOUDFLARE_ACCOUNT_ID = accountId; else { const file = path.join(work, 'wrangler.jsonc'); const config = JSON.parse(fs.readFileSync(file, 'utf8')); config.account_id = accountId; fs.writeFileSync(file, JSON.stringify(config, null, 2)); } } }
const [command, ...args] = argv;
const cli = command === 'check' ? path.join(tools, 'node_modules/typescript/bin/tsc') : path.join(tools, 'node_modules/wrangler/bin/wrangler.js');
if (!fs.existsSync(cli)) throw new Error('Install the pinned cloud-relay devDependencies into POWERPROFILE_RELAY_TOOLS first');
const run = cp.spawnSync(process.execPath, [cli, ...(command === 'check' ? ['--noEmit'] : [command, ...args])], { cwd: work, env: runEnv, stdio: 'inherit', windowsHide: true });
if (!gateway && run.status === 0 && command === 'types') fs.copyFileSync(path.join(work, 'worker-configuration.d.ts'), generated);
process.exitCode = run.status ?? 1;
