import * as maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url';

// In Vite production builds, MapLibre GL v6 worker must be explicitly registered
// via ?worker&url so Vite bundles the worker and its shared dependencies into dist/assets.
// Without this, browsers (specifically Safari which defaults to workerCount=3)
// request the missing relative worker script, receive index.html via SPA fallback,
// and throw "SyntaxError: Unexpected token '<' (x3)", which breaks all GeoJSON/vector sources.
maplibregl.setWorkerUrl(workerUrl);

export * from 'maplibre-gl';
export default maplibregl;
