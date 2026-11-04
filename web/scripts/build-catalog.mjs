/**
 * Full BKK transit catalog generator.
 *
 * The Futár API key in use is scoped: routes-for-agency / stops-for-route /
 * stop-search all return "Full authentication is required". The endpoints it
 * DOES authorize (stops-for-location, route-details) are enough to rebuild the
 * whole catalog offline:
 *
 *   1. Tile Budapest (+ HÉV corridors) with stops-for-location circles and
 *      collect every stop — each stop carries its `routeIds`.
 *   2. For every unique route id, call route-details to get the route's
 *      metadata (shortName, type, color, ...).
 *   3. Emit public/catalog/routes.json + stops.json + route-variants.json,
 *      fetched by the app at runtime (never bundled — the daily refresh must
 *      not change the service-worker precache manifest).
 *
 * Usage:  FUTAR_API_KEY=<key> node scripts/build-catalog.mjs
 */
import { writeFileSync, readFileSync, mkdirSync, renameSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const KEY = process.env.FUTAR_API_KEY;
if (!KEY) {
  console.error('Set FUTAR_API_KEY=<your futar api key>');
  process.exit(1);
}

const API = 'https://futar.bkk.hu/api/query/v1/ws/otp/api/where';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function get(endpoint, params, retries = 2) {
  const qs = new URLSearchParams({ ...params, key: KEY });
  const url = `${API}/${endpoint}.json?${qs}`;
  for (let attempt = 0; ; attempt++) {
    try {
      // A hung upstream must not hold the job (and its runner) for hours.
      const res = await fetch(url, { signal: AbortSignal.timeout(30_000) });
      const j = await res.json();
      if (j.status !== 'OK') throw new Error(`${endpoint} -> ${j.status}: ${j.text}`);
      return j;
    } catch (e) {
      if (attempt >= retries) throw e;
      await sleep(600 * (attempt + 1));
    }
  }
}

// Circles covering Budapest + the suburban HÉV corridors (Szentendre, Gödöllő,
// Csepel, Ráckeve) where BKK routes actually run.
const CIRCLES = [
  // City core
  { lat: 47.497, lon: 19.040, r: 5000 },
  { lat: 47.470, lon: 19.080, r: 5000 },
  { lat: 47.520, lon: 19.070, r: 5000 },
  { lat: 47.540, lon: 19.050, r: 5000 },
  { lat: 47.450, lon: 19.030, r: 5000 },
  { lat: 47.480, lon: 18.990, r: 5000 },
  { lat: 47.510, lon: 19.130, r: 5000 },
  { lat: 47.470, lon: 19.160, r: 5000 },
  { lat: 47.540, lon: 19.150, r: 5000 },
  { lat: 47.420, lon: 19.120, r: 5000 },
  { lat: 47.440, lon: 19.220, r: 5000 },
  { lat: 47.490, lon: 19.250, r: 5000 },
  { lat: 47.570, lon: 19.060, r: 5000 },
  // Buda hills / west
  { lat: 47.510, lon: 18.930, r: 4500 },
  { lat: 47.460, lon: 18.960, r: 4500 },
  // HÉV: Szentendre
  { lat: 47.600, lon: 19.050, r: 5000 },
  { lat: 47.640, lon: 19.080, r: 4500 },
  { lat: 47.670, lon: 19.080, r: 3500 },
  // HÉV: Gödöllő
  { lat: 47.580, lon: 19.250, r: 5000 },
  { lat: 47.600, lon: 19.350, r: 4000 },
  // HÉV: Csepel
  { lat: 47.420, lon: 19.060, r: 4500 },
  // HÉV: Ráckeve
  { lat: 47.350, lon: 19.000, r: 5000 },
  { lat: 47.280, lon: 18.980, r: 4500 },
  { lat: 47.210, lon: 18.950, r: 4000 },
  { lat: 47.130, lon: 18.940, r: 3500 },
  { lat: 47.080, lon: 18.960, r: 3000 },
];

const stops = new Map(); // id -> { id, lat, lon, name, locationType }
const routeIds = new Set();

let failedCircles = 0;
for (const c of CIRCLES) {
  try {
    const j = await get('stops-for-location', { lat: c.lat, lon: c.lon, radius: c.r });
    const list = j.data?.list || [];
    let added = 0;
    for (const s of list) {
      if (!s.id) continue;
      if (!stops.has(s.id)) {
        stops.set(s.id, {
          id: s.id,
          lat: s.lat,
          lon: s.lon,
          name: s.name,
          locationType: s.locationType ?? 0,
        });
        added++;
      }
      for (const rid of s.routeIds || []) routeIds.add(rid);
    }
    console.log(`circle (${c.lat},${c.lon}) r=${c.r} -> ${list.length} stops (${added} new)`);
  } catch (e) {
    failedCircles++;
    console.warn('circle failed:', c, e.message);
  }
  await sleep(200);
}
console.log(`\nunique stops: ${stops.size}, unique route ids: ${routeIds.size}`);

// ---- 2. Route metadata via route-details -------------------------------
const routes = new Map(); // id -> route meta
const routeVariants = new Map(); // id -> normalized variant stop-name lists
const ids = [...routeIds].sort();
let done = 0;
let failed = 0;
const failedIds = [];

/** Normalized stop name for offline direction matching — must stay in sync
 *  with normalizeStopName in src/features/trips/route-suggest.ts (runtime). */
const normalizeStopName = (s) =>
  String(s || '')
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/\([^)]*\)/g, '')
    .replace(/\b(m|h|pu|vasutallomas|allomas)\b/g, '')
    .replace(/\s+/g, ' ')
    .trim();

async function worker(batch) {
  for (const id of batch) {
    try {
      const j = await get('route-details', { routeId: id, includeReferences: 'stops', includePolylines: 'false', includeTrips: 'false' });
      const e = j.data?.entry || {};
      const refs = j.data?.references?.stops ?? {};
      routes.set(id, {
        id,
        shortName: e.shortName || id,
        description: e.description || e.longName || '',
        type: e.type || 'UNKNOWN',
        color: e.color || 'FFFFFF',
        textColor: e.textColor || '000000',
      });
      routeVariants.set(
        id,
        (j.data?.entry?.variants ?? []).map((v) => ({
          h: v.headsign || v.name || '',
          s: [...new Set((v.stopIds ?? []).map((sid) => normalizeStopName(refs[sid]?.name ?? '')).filter(Boolean))],
        }))
      );
    } catch (e) {
      failed++;
      failedIds.push(id);
      console.warn(`route-details failed: ${id} -> ${e.message}`);
    }
    done++;
    if (done % 50 === 0) console.log(`  ... ${done}/${ids.length} routes (${failed} failed)`);
    await sleep(150);
  }
}

const CONCURRENCY = 5;
for (let i = 0; i < ids.length; i += CONCURRENCY) {
  await Promise.all(ids.slice(i, i + CONCURRENCY).map((id) => worker([id])));
}
console.log(`routes fetched: ${routes.size}, failed: ${failed}`);

// ---- 3. Emit runtime JSON catalogs ---------------------------------------
const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const outDir = join(root, 'public/catalog');
mkdirSync(outDir, { recursive: true });

const readJson = (path, fallback) => {
  try {
    return JSON.parse(readFileSync(path, 'utf8'));
  } catch {
    return fallback;
  }
};

// Carry forward the previous catalog's entries for whatever failed this run.
// Dropping them would let small daily losses compound past the 5% gate below,
// which only compares against the previous (already shrunk) file.
if (failedIds.length > 0) {
  const prevRouteList = readJson(join(outDir, 'routes.json'), []);
  const prevVariants = readJson(join(outDir, 'route-variants.json'), {});
  const prevById = new Map(prevRouteList.map((r) => [r.id, r]));
  let carried = 0;
  for (const id of failedIds) {
    const prev = prevById.get(id);
    if (!prev) continue;
    routes.set(id, prev);
    if (prevVariants[id]) routeVariants.set(id, prevVariants[id]);
    carried++;
  }
  console.log(`carried over ${carried}/${failedIds.length} failed routes from the previous catalog`);
}
if (failedCircles > 0) {
  // A failed circle loses its stops; keep the previous catalog's stop areas.
  let carried = 0;
  for (const prev of readJson(join(outDir, 'stops.json'), [])) {
    if (!stops.has(prev.id)) {
      stops.set(prev.id, { ...prev, locationType: 1 });
      carried++;
    }
  }
  console.log(`${failedCircles} circle(s) failed; carried over ${carried} stop areas from the previous catalog`);
}

// Stop areas only (station-level, like the existing curated list) — this is
// what trackers store; platforms fold into their area.
const isArea = (s) => s.id.startsWith('BKK_CS') || s.locationType === 1;
const areaStops = [...stops.values()]
  .filter(isArea)
  .map((s) => ({ id: s.id, name: s.name, lat: s.lat, lon: s.lon }))
  .sort((a, b) => a.name.localeCompare(b.name, 'hu'));

// Sanity gate: a transient feed failure (rate limit, partial auth outage) must
// never produce a truncated catalog that then ships to the app. Fail loudly so
// the caller (local run or the daily refresh workflow) keeps the last good one.
if (routes.size < 100) {
  console.error(`ABORT: only ${routes.size} routes resolved (expected 600+) — refusing to overwrite the catalog.`);
  process.exit(1);
}
if (areaStops.length < 500) {
  console.error(`ABORT: only ${areaStops.length} stop areas resolved (expected 2000+) — refusing to overwrite the catalog.`);
  process.exit(1);
}

// Relative gate: a half-degraded feed (e.g. 300 of 650 routes) passes the
// absolute floor above but would still replace a healthy catalog. Refuse any
// regeneration that loses more than 5% versus the files currently on disk.
const countEntries = (path) => {
  try {
    const parsed = JSON.parse(readFileSync(path, 'utf8'));
    return Array.isArray(parsed) ? parsed.length : Object.keys(parsed).length;
  } catch {
    return 0;
  }
};
const prevRoutes = countEntries(join(outDir, 'routes.json'));
const prevStops = countEntries(join(outDir, 'stops.json'));
const prevVariantCount = countEntries(join(outDir, 'route-variants.json'));
const MIN_RETENTION = 0.95;
if (prevRoutes > 0 && routes.size < prevRoutes * MIN_RETENTION) {
  console.error(
    `ABORT: regenerated routes (${routes.size}) are >5% below the current catalog (${prevRoutes}) — refusing to overwrite.`
  );
  process.exit(1);
}
if (prevStops > 0 && areaStops.length < prevStops * MIN_RETENTION) {
  console.error(
    `ABORT: regenerated stop areas (${areaStops.length}) are >5% below the current catalog (${prevStops}) — refusing to overwrite.`
  );
  process.exit(1);
}

const routeList = [...routes.values()].sort((a, b) => {
  const pa = a.shortName.localeCompare(b.shortName, 'hu', { numeric: true });
  return pa;
});

const variantsObj = {};
for (const id of [...routeVariants.keys()].sort()) {
  variantsObj[id] = routeVariants.get(id);
}
if (prevVariantCount > 0 && Object.keys(variantsObj).length < prevVariantCount * MIN_RETENTION) {
  console.error(
    `ABORT: regenerated route variants (${Object.keys(variantsObj).length}) are >5% below the current catalog (${prevVariantCount}) — refusing to overwrite.`
  );
  process.exit(1);
}

// Write all three to temp files first, then rename, so a crash mid-write can
// never leave a truncated or mismatched catalog behind.
const outputs = [
  ['routes.json', JSON.stringify(routeList, null, 2) + '\n'],
  ['stops.json', JSON.stringify(areaStops, null, 2) + '\n'],
  ['route-variants.json', JSON.stringify(variantsObj) + '\n'],
];
for (const [name, body] of outputs) writeFileSync(join(outDir, `${name}.tmp`), body);
for (const [name] of outputs) renameSync(join(outDir, `${name}.tmp`), join(outDir, name));
console.log(`\nwrote ${routeList.length} routes -> public/catalog/routes.json`);
console.log(`wrote ${areaStops.length} stop areas -> public/catalog/stops.json`);
console.log(
  `wrote ${Object.keys(variantsObj).length} route variant lists -> public/catalog/route-variants.json`
);
