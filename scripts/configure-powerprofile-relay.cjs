// Credentials stay private. The existing update trust root signs phone pairing.
const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto');
const { validateRelayConfig } = require('../host/powerprofile-relay.cjs');
const root = path.resolve(__dirname, '..'), secretsFile = path.join(root, '.local/relay-secrets.json');
fs.mkdirSync(path.dirname(secretsFile), { recursive: true });
if (!fs.existsSync(secretsFile)) fs.writeFileSync(secretsFile, JSON.stringify({ PC_TOKEN: crypto.randomBytes(32).toString('hex'), PHONE_TOKEN: crypto.randomBytes(32).toString('hex'), CHANNEL_ID: crypto.randomBytes(16).toString('hex') }), { mode: 0o600 });
const secrets = JSON.parse(fs.readFileSync(secretsFile, 'utf8'));
if (!/^[a-f0-9]{64}$/.test(secrets.PC_TOKEN) || !/^[a-f0-9]{64}$/.test(secrets.PHONE_TOKEN) || !/^[a-f0-9]{32}$/.test(secrets.CHANNEL_ID)) throw new Error('invalid_private_secrets');
const endpoint = process.argv[2];
if (endpoint) {
  const config = validateRelayConfig({ endpoint, pcToken: secrets.PC_TOKEN });
  fs.writeFileSync(path.join(root, '.local/powerprofile-relay.json'), JSON.stringify(config), { mode: 0o600 });
  const now = Math.floor(Date.now() / 1000);
  const payload = Buffer.from(JSON.stringify({ purpose: 'powerprofile_relay_pairing', repository: 'LumishadeVoyager/xiaomi-band-agent-companion', appId: 'io.github.lumishadevoyager.bandagent.powerprofile', issuedAt: now, expiresAt: now + 600, endpoint: config.endpoint, token: secrets.PHONE_TOKEN }));
  const signature = crypto.sign('sha256', payload, fs.readFileSync(path.join(root, '.local/sign/updates/root-private.pem')));
  fs.writeFileSync(path.join(root, '.local/computer-pairing-powerprofile-relay.json'), JSON.stringify({ schema: 1, algorithm: 'RS256', payload: payload.toString('base64'), signature: signature.toString('base64') }), { mode: 0o600 });
  console.log('Private relay config and ten-minute signed phone invitation saved; no credentials printed.');
} else console.log('Private relay secrets initialized; existing keys were retained.');
