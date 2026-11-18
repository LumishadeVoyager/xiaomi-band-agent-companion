# Private PowerProfile Internet relay

Worker + SQLite Durable Object. One durable instance per configured private pairing channel. Desktop uploads outbound HTTPS; Android polls outbound HTTPS and forwards to the band using Mi Fitness/Xiaomi interconnect. No PC port forwarding, shared Wi-Fi, USB, or PC Bluetooth is required for this path.

## Local checks

Node 24 or later:

```powershell
cd cloud-relay
npm ci
npm run types -- --include-runtime false
npm run check
npm run build
node test/integration.mjs
```

On Google Drive for Windows, install the pinned devDependencies outside synced storage into `%LOCALAPPDATA%/BandAgentDev/relay-tools`, then use `node scripts/relay-tools.cjs types --include-runtime false`, `check`, and `deploy --dry-run` from the repository root. The wrapper copies source to a private build directory and does not touch other runtimes. For integration tests, set `POWERPROFILE_RELAY_TOOLS` to that external tools directory.

## Deployment

1. Use Wrangler login with account/user read and Workers scripts write. No Cloudflare credentials belong in this repository.
2. Run `node scripts/configure-powerprofile-relay.cjs` from the root. It creates private PC/phone tokens and a channel ID under `.local`, retaining existing values on rerun.
3. Run `node scripts/relay-tools.cjs secret bulk ABSOLUTE_PRIVATE_SECRETS_PATH`, then `node scripts/relay-tools.cjs deploy`. The public API is unusable without its role token. Do not add tokens to config, URLs or logs.
4. Run `node scripts/configure-powerprofile-relay.cjs https://YOUR_WORKER_HOST` to save the desktop configuration and a ten-minute signed Android pairing invitation. Signing uses the existing project trust root; it does not rotate or replace it.
5. Install the matching Android companion and deliver the signed invitation through the existing first-setup flow. ADB is a setup/debug method, not the daily connection.

The deployment uses SQLite Durable Objects, available on Workers Free. No paid-plan upgrade is performed by the scripts. Cloudflare usage limits still apply; outages and quota exhaustion retain local pending events for retry and never mark them delivered. See [official pricing](https://developers.cloudflare.com/durable-objects/platform/pricing/).

## Protocol and privacy

| Route | Role | Result |
| --- | --- | --- |
| GET /health | Public | Protocol/service identifier only |
| POST /relay/publish | PC | Upload normalized snapshot/events; read phone ACKs and latest band report |
| POST /powerprofile/poll | Phone | Last snapshot, pending completion IDs/times, PC heartbeat age |
| POST /powerprofile/ack | Phone | Persist acknowledgment of existing completion IDs |
| POST /powerprofile/device-report | Phone | Persist applied snapshot timestamp and wrist version |

Distinct role tokens are compared as fixed-size hashes in constant time. The phone cannot publish events or read the PC token. Body reads are bounded, redirects are rejected by clients, responses use no-store, and unknown/private snapshot fields are dropped. No audio, prompts, replies, session titles, account data, file paths or raw logs are sent. Cloudflare can process the normalized metrics as the relay operator; this is TLS transport protection, not end-to-end payload encryption.

Only the newest snapshot is retained; older uploads cannot replace it. Completion IDs deduplicate uploads; phone ACKs retire the PC's local event only after being returned to the PC. Events expire after 24 hours, and the total queue is bounded. Storage is pruned on requests and by an alarm; background cleanup can lag its nominal expiry. A phone notification ACK is not a wrist delivery receipt. The newest band report proves its data path, not physical pixels or vibration.

PC uploads every 10 seconds, phone polls every 15 seconds after each completed request. This is not a push transport or an instant-delivery guarantee. Android long Doze, OEM background restrictions, offline recovery, reboot and battery cost require separate acceptance. A PC heartbeat older than 90 seconds is marked offline; cached quota keeps its original observation/reset time.
