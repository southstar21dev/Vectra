export function radarProxyPlugin() {
  return {
    name: 'radar-proxy-plugin',
    configureServer(server: any) {
      // Same-origin only: the app calls this from the dev server's own origin.
      // No CORS header — the dev server listens on the LAN (host: true), and a
      // wildcard would let any page proxy through it.
      server.middlewares.use('/api/proxy-radar', async (_req: any, res: any) => {
        try {
          const response = await fetch('https://api.rainviewer.com/public/weather-maps.json', {
            headers: { Accept: 'application/json' },
            signal: AbortSignal.timeout(15_000),
          });
          const text = await response.text();
          res.setHeader('Content-Type', 'application/json; charset=utf-8');
          res.statusCode = response.ok ? 200 : 502;
          res.end(text);
        } catch (err: any) {
          res.statusCode = 500;
          res.setHeader('Content-Type', 'application/json; charset=utf-8');
          res.end(JSON.stringify({ error: err?.message || 'Radar proxy error' }));
        }
      });
    },
  };
}
