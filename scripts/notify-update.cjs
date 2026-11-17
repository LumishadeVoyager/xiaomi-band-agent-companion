const path = require('node:path');
const { DiagnosticStore } = require('../host/diagnostic-store.cjs');
const version = Number(process.argv[2]); if (!Number.isSafeInteger(version) || version < 1) throw new Error('Numeric published versionCode required');
const directory = process.env.BAND_AGENT_RUNTIME || path.join(process.env.LOCALAPPDATA || path.join(__dirname, '../.local'), 'BandAgentDev', 'runtime');
const store = new DiagnosticStore(path.join(directory, 'diagnostics.sqlite'));
try { for (const entry of ['codex', 'harness']) store.command(entry, 'ota_' + entry + '_' + version, { type: 'update_available', versionCode: version }); }
finally { store.close(); }
console.log('Durable update notifications queued for both phone bridges.');
