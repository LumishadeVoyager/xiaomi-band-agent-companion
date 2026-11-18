// No static metrics or credentials. Authentication stays in the bound relay.
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    // Public install packages contain no pairing credentials or personal data.
    // The client still verifies the signed feed, hashes and application identity.
    const ota = url.pathname === '/ota/powerprofile-preview.json' || url.pathname === '/ota/powerprofile-stable.json'
      || /^\/ota\/releases\/download\/v[0-9A-Za-z._-]+\/band-agent-powerprofile-[0-9A-Za-z._-]+\.(apk|rpk)$/.test(url.pathname);
    if (ota && !url.search && ['GET', 'HEAD'].includes(request.method)) {
      const asset = await env.ASSETS.fetch(request);
      const response = new Response(asset.body, asset);
      response.headers.set('Cache-Control', 'no-store');
      response.headers.set('X-Content-Type-Options', 'nosniff');
      return response;
    }
    const allowed = request.method === 'GET' && url.pathname === '/health'
      || request.method === 'POST' && ['/relay/publish', '/powerprofile/poll', '/powerprofile/ack', '/powerprofile/device-report'].includes(url.pathname);
    if (!allowed || url.search) return Response.json({ error: 'not_found' }, { status: 404, headers: { 'Cache-Control': 'no-store' } });
    try {
      // Service binding calls the existing Worker without a public DNS hop.
      return await env.RELAY.fetch(request);
    } catch {
      return Response.json({ error: 'relay_unavailable' }, { status: 503, headers: { 'Cache-Control': 'no-store' } });
    }
  }
};
