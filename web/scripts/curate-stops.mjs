/**
 * One-off curation script for the Budapest Transit feature.
 *
 * Pulls stop-areas from the BKK Futar API along the M1–M4 metro corridors
 * plus the two fixed tram stops (Teleki László tér, Hidegkuti Nándor Stadion),
 * resolves their stop-area IDs, and generates:
 *   src/features/transit/curated-stops.ts
 *
 * Usage:  FUTAR_API_KEY=<key> node scripts/curate-stops.mjs
 */
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const KEY = process.env.FUTAR_API_KEY;
if (!KEY) {
  console.error('Set FUTAR_API_KEY=<your futar api key>');
  process.exit(1);
}

const API = 'https://futar.bkk.hu/api/query/v1/ws/otp/api/where';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function get(endpoint, params) {
  const qs = new URLSearchParams({ ...params, key: KEY });
  const res = await fetch(`${API}/${endpoint}.json?${qs}`, { signal: AbortSignal.timeout(30_000) });
  const j = await res.json();
  // Log the params without the key: these errors are printed.
  if (j.status !== 'OK') throw new Error(`${endpoint} ${new URLSearchParams(params)} -> ${j.status}: ${j.text}`);
  return j;
}

// Circles that cover every metro corridor of Budapest + the two special stops.
const CIRCLES = [
  { lat: 47.5088, lon: 19.0698, r: 5500 }, // M1 (Vörösmarty tér -> Mexikói út)
  { lat: 47.5016, lon: 19.0791, r: 6000 }, // M2 (Déli pu. -> Örs vezér tere)
  { lat: 47.4680, lon: 19.1250, r: 5000 }, // M3 south (Kőbánya-Kispest -> Nagyvárad)
  { lat: 47.5050, lon: 19.0620, r: 4500 }, // M3 mid (Semmelweis -> Lehel)
  { lat: 47.5480, lon: 19.0770, r: 4500 }, // M3 north (Dózsa György út -> Újpest-központ)
  { lat: 47.4823, lon: 19.0520, r: 5000 }, // M4 (Kelenföld -> Keleti pu.)
  { lat: 47.4975, lon: 19.0810, r: 3000 }, // Teleki László tér
  { lat: 47.4969, lon: 19.1160, r: 3000 }, // Hidegkuti Nándor Stadion
];

// Canonical station names per line, in station order (normalized later).
const LINES = {
  M1: ['Vörösmarty tér', 'Deák Ferenc tér', 'Bajcsy-Zsilinszky út', 'Opera', 'Oktogon', 'Vörösmarty utca', 'Kodály körönd', 'Bajza utca', 'Hősök tere', 'Széchenyi fürdő', 'Mexikói út'],
  M2: ['Déli pályaudvar', 'Széll Kálmán tér', 'Batthyány tér', 'Kossuth Lajos tér', 'Deák Ferenc tér', 'Astoria', 'Blaha Lujza tér', 'Keleti pályaudvar', 'Puskás Ferenc Stadion', 'Pillangó utca', 'Örs vezér tere'],
  M3: ['Kőbánya-Kispest', 'Határ út', 'Pöttyös utca', 'Ecseri út', 'Népliget', 'Nagyvárad tér', 'Semmelweis Klinikák', 'Corvin-negyed', 'Kálvin tér', 'Ferenciek tere', 'Deák Ferenc tér', 'Arany János utca', 'Nyugati pályaudvar', 'Lehel tér', 'Dózsa György út', 'Árpád híd', 'Forgách utca', 'Gyöngyösi utca', 'Újpest-Városkapu', 'Újpest-központ'],
  M4: ['Kelenföld vasútállomás', 'Bikás park', 'Újbuda-központ', 'Móricz Zsigmond körtér', 'Szent Gellért tér', 'Fővám tér', 'Kálvin tér', 'Rákóczi tér', 'II. János Pál pápa tér', 'Keleti pályaudvar'],
};

const SPECIAL = ['Teleki László tér', 'Hidegkuti Nándor Stadion'];

// Stations whose current official stop-area name differs from the familiar name.
// Keyed by line + canonical station name from LINES.
const OVERRIDES = {
  M3: { 'Árpád híd': { id: 'BKK_CS123405', name: 'Göncz Árpád városközpont M' } },
  M4: { 'Szent Gellért tér': { id: 'BKK_CS044117', name: 'Szent Gellért tér M' } },
};

const norm = (s) =>
  String(s || '')
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[^a-z0-9 ]/g, '')
    .replace(/\s+/g, ' ')
    .trim();

// ---- 1. Fetch all stop-areas in the corridors ---------------------------
const stops = new Map();
for (const c of CIRCLES) {
  try {
    const j = await get('stops-for-location', { lat: c.lat, lon: c.lon, radius: c.r });
    for (const s of j.data.list || []) if (!stops.has(s.id)) stops.set(s.id, s);
    console.log(`circle (${c.lat},${c.lon}) r=${c.r} -> ${(j.data.list || []).length} stops`);
  } catch (e) {
    console.warn('circle failed:', c, e.message);
  }
  await sleep(350);
}
console.log('total unique stops fetched:', stops.size);

const isStopArea = (s) => s.id.startsWith('BKK_CS') || s.locationType === 1;

// ---- 2. Discover the SUBWAY route ids (M1..M4) ---------------------------
const discoveryNames = ['Deák Ferenc tér', 'Kelenföld vasútállomás', 'Déli pályaudvar'];
const subway = {}; // id -> shortName
for (const name of discoveryNames) {
  const candidate = [...stops.values()].find((s) => isStopArea(s) && norm(s.name.replace(/\s*M$/i, '')) === norm(name));
  if (!candidate) {
    console.warn(`discovery stop not found in corridors: ${name}`);
    continue;
  }
  try {
    const j = await get('arrivals-and-departures-for-stop', { stopId: candidate.id, minutesAfter: 30 });
    const routes = j.data?.references?.routes || {};
    for (const r of Object.values(routes)) {
      if (r.type === 'SUBWAY') subway[r.id] = r.shortName;
    }
    console.log(`discovery ${name} (${candidate.id}) -> routes:`, Object.values(routes).map((r) => `${r.shortName}:${r.type}`).join(', '));
  } catch (e) {
    console.warn('discovery arrivals failed:', name, e.message);
  }
  await sleep(350);
}
console.log('SUBWAY routes found:', JSON.stringify(subway));

const subwayIds = new Set(Object.keys(subway));
if (subwayIds.size < 4) {
  console.warn('WARNING: fewer than 4 SUBWAY routes discovered — M4 might be missing.');
}

// ---- 3. Resolve metro stations per line (canonical order) ----------------
const groupByStopId = new Map(); // stopId -> group
const lineGroups = { M1: [], M2: [], M3: [], M4: [] };

for (const [line, stationNames] of Object.entries(LINES)) {
  const lineRouteId = Object.keys(subway).find((id) => subway[id] === line);
  if (!lineRouteId) {
    console.warn(`no route id known for ${line}`);
    continue;
  }
  for (const stationName of stationNames) {
    const override = OVERRIDES[line]?.[stationName];
    let stop;
    if (override) {
      stop = stops.get(override.id) || { id: override.id, name: override.name, routeIds: [lineRouteId] };
      stop = { ...stop, name: override.name };
      if (stops.has(override.id)) console.log(`override applied: ${line} / ${stationName} -> ${override.name} (${override.id})`);
    } else {
      const matches = [...stops.values()].filter(
        (s) => isStopArea(s) && norm(s.name.replace(/\s*M$/i, '')) === norm(stationName) && (s.routeIds || []).includes(lineRouteId)
      );
      if (matches.length === 0) {
        console.warn(`MISSING metro station: ${line} / ${stationName}`);
        continue;
      }
      stop = matches[0];
      if (matches.length > 1) console.warn(`duplicate match for ${stationName}:`, matches.map((m) => m.id).join(','));
    }
    if (!groupByStopId.has(stop.id)) {
      groupByStopId.set(stop.id, line);
      lineGroups[line].push(stop);
    }
  }
}

for (const [line, arr] of Object.entries(lineGroups)) {
  console.log(`${line}: ${arr.length} stations ->`, arr.map((s) => `${s.name} (${s.id})`).join(', '));
}

// ---- 4. Resolve the two special tram stops --------------------------------
const specialStops = [];
for (const specialName of SPECIAL) {
  const matches = [...stops.values()].filter((s) => isStopArea(s) && norm(s.name) === norm(specialName));
  if (matches.length === 0) {
    // Try a looser match (name containment) and print candidates for inspection
    const loose = [...stops.values()].filter((s) => isStopArea(s) && norm(s.name).includes(norm(specialName).split(' ')[0]));
    console.warn(`MISSING special stop: ${specialName}. Nearby candidates:`);
    loose.slice(0, 10).forEach((s) => console.warn(`  ${s.name} (${s.id})`));
    continue;
  }
  const stop = matches[0];
  if (matches.length > 1) console.warn(`duplicate special match ${specialName}:`, matches.map((m) => m.id).join(','));
  specialStops.push(stop);
  console.log(`special: ${stop.name} (${stop.id})`);
}

// ---- 5. Emit the generated TS module --------------------------------------
const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const outDir = join(root, 'src/features/transit');
mkdirSync(outDir, { recursive: true });

const sortedLines = ['M1', 'M2', 'M3', 'M4'];
const curated = [];
for (const line of sortedLines) {
  for (const s of lineGroups[line]) curated.push({ id: s.id, name: s.name, group: line });
}
for (const s of specialStops) curated.push({ id: s.id, name: s.name, group: 'STOPS' });

const metroIds = {};
for (const [id, shortName] of Object.entries(subway)) metroIds[shortName] = id;

const ts = `// AUTO-GENERATED by scripts/curate-stops.mjs — do not edit manually.
// Stop-area data sourced from the BKK Futár API (futar.bkk.hu).

export type TransitStopGroup = 'M1' | 'M2' | 'M3' | 'M4' | 'STOPS';

export interface CuratedStop {
  id: string;
  name: string;
  group: TransitStopGroup;
}

/** Route IDs of the four metro lines (used as a deterministic fallback picker option). */
export const METRO_ROUTE_IDS: Record<'M1' | 'M2' | 'M3' | 'M4', string> = ${JSON.stringify(metroIds, null, 2)};

export const CURATED_STOP_GROUPS: { id: TransitStopGroup; label: string }[] = [
  { id: 'M1', label: 'METRO LINE M1' },
  { id: 'M2', label: 'METRO LINE M2' },
  { id: 'M3', label: 'METRO LINE M3' },
  { id: 'M4', label: 'METRO LINE M4' },
  { id: 'STOPS', label: 'TRAM STOPS' },
];

export const CURATED_STOPS: CuratedStop[] = ${JSON.stringify(curated, null, 2)};
`;

writeFileSync(join(outDir, 'curated-stops.ts'), ts);
console.log(`\nwrote ${curated.length} stops -> src/features/transit/curated-stops.ts`);
console.log('metro route ids:', JSON.stringify(metroIds));
