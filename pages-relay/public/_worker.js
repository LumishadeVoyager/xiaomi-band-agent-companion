// No static metrics or credentials. Authentication stays in the bound relay.
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
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
