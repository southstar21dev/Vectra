// Measures the REAL downloaded size of the "detailed" world pack (z0–6) by
// fetching every tile and summing actual bytes — the 11 KB/tile estimate is
// wrong because most of the world is ocean (tiny tiles).
const CONCURRENCY = 32;
const tiles = [];
for (let z = 0; z <= 6; z++) {
  const n = Math.pow(2, z);
  for (let x = 0; x < n; x++) for (let y = 0; y < n; y++) tiles.push({ z, x, y });
}

const perZoom = new Map();
let total = 0;
let ok = 0;
let fail = 0;
let next = 0;

async function worker() {
  while (true) {
    const i = next++;
    const t = tiles[i];
    if (!t) return;
    try {
      const res = await fetch(`https://a.basemaps.cartocdn.com/dark_all/${t.z}/${t.x}/${t.y}.png`, { cache: 'no-store' });
      if (!res.ok) {
        fail++;
        return;
      }
      const b = await res.arrayBuffer();
      total += b.byteLength;
      ok++;
      perZoom.set(t.z, (perZoom.get(t.z) ?? 0) + b.byteLength);
    } catch {
      fail++;
    }
  }
}

const t0 = Date.now();
await Promise.all(Array.from({ length: CONCURRENCY }, () => worker()));
const secs = ((Date.now() - t0) / 1000).toFixed(0);

const zoomTotals = [...perZoom.entries()]
  .sort((a, b) => a[0] - b[0])
  .map(([z, bytes]) => `z${z}: ${(bytes / 1e6).toFixed(2)} MB`);
console.log(`tiles: ${ok} ok / ${fail} fail in ${secs}s (${(ok / Number(secs)).toFixed(1)}/s)`);
console.log(zoomTotals.join('\n'));
console.log(`TOTAL: ${(total / 1e6).toFixed(2)} MB (${Math.round(total / Math.max(1, ok))} bytes/tile avg)`);
