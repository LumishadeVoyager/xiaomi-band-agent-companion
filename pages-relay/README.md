# Optional Pages gateway

This is a Cloudflare Pages **Function**, not GitHub Pages static hosting. It provides a candidate `pages.dev` hostname for networks that cannot reach the relay's `workers.dev` hostname. Reachability of the root `pages.dev` site does not prove a project's hostname will work; mobile-data device acceptance is required. This project's phone v11/wrist v8 mobile-data and physical USB-unplug refresh checks passed on 2026-11-18; see the [version-specific acceptance record](../docs/POWERPROFILE_DEVICE_ACCEPTANCE.md).

The advanced-mode `_worker.js` exposes only `/health`, desktop publish and the three phone POST routes. A service binding calls the existing `powerprofile-band-relay` Worker internally, preserving role-scoped token authentication, Durable Object storage, validation and expiry. No tokens or private metrics are static assets, and the gateway has no separate data store. Both clients may use the Pages endpoint; desktop publishing can alternatively keep its original Worker endpoint. There is no generic external forwarding.

Use the same pinned Wrangler installation as `cloud-relay`:

```powershell
# Source is copied to an isolated build folder outside Google Drive.
node scripts/relay-tools.cjs --gateway pages dev public
# OAuth requires Pages write in addition to existing Worker/account scopes.
node scripts/relay-tools.cjs --gateway pages project create powerprofile-band-gateway --production-branch main
node scripts/relay-tools.cjs --gateway pages deploy public --project-name powerprofile-band-gateway --branch main
```

Wrangler 4.164.0 may delegate creation of a new Pages project to Workers. If that explicit delegation fails, its CLI instructs using `--force` **on project creation only** to create the previous Pages project type with a `pages.dev` hostname. Existing-project deployments do not need that flag. Pages configuration uses the private `CLOUDFLARE_ACCOUNT_ID` environment variable, not an `account_id` config field.

Once reachable, create a new signed phone invitation with the Pages origin while retaining the PC's Worker endpoint:

```powershell
node scripts/configure-powerprofile-relay.cjs https://PROJECT.pages.dev --phone-only
```

Pages Functions use the Workers platform and its usage limits; this does not bypass plan limits or establish a delivery guarantee. No paid upgrade is performed.

`cloud-relay/test/integration.mjs` starts both local Workers runtimes and exercises phone poll, authentication, ACK and device-report through the Pages service binding. When using the external Windows tool directory, run any `relay-tools.cjs --gateway` command first to refresh its copied source.

GitHub remains the source/CI/artifact platform. GitHub Pages can host public design previews or setup documentation, but it cannot run this stateful API, and publishing plaintext personal statistics there is not part of this design. GitHub Actions scheduled jobs are not a persistent notification service.

References: [Pages service bindings](https://developers.cloudflare.com/pages/functions/bindings/#service-bindings), [advanced mode](https://developers.cloudflare.com/pages/functions/advanced-mode/), [GitHub Pages](https://docs.github.com/en/pages/getting-started-with-github-pages/what-is-github-pages).
