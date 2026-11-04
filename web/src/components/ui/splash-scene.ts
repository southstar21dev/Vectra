/**
 * The boot splash scene: a large, faint dotted orb rising over a dotted
 * Budapest skyline — Gellért Hill with the Citadella and the Liberty Statue,
 * the Rock Chapel, Liberty Bridge with a tram crossing, and Corvinus E
 * building — reflected in the Danube.
 *
 * Everything is drawn in fixed scene units (a 260-wide strip with the
 * waterline at y = B) and scaled to the screen by the caller. The landmarks
 * are drawn once as solid shapes and sampled into dots; each frame only draws
 * dots, batched by alpha so a frame is a few dozen fills, not thousands.
 */

/** Scene width in scene units; the caller scales it to the screen width. */
export const SCENE_W = 260;
/** Waterline, in scene units. */
export const B = 424;

/** How long the sunrise exit runs before the splash unmounts. */
export const SUNRISE_EXIT_MS = 700;

const TAU = Math.PI * 2;
const ease = (x: number) => 1 - Math.pow(1 - Math.min(1, Math.max(0, x)), 3);
const easeIn = (x: number) => Math.pow(Math.min(1, Math.max(0, x)), 2);

/**
 * The sunrise exit at `ms` since it began: the orb keeps rising and leaves
 * through the top while the city sinks below the waterline and fades.
 */
export function sunriseExit(ms: number) {
  const orbT = ms / SUNRISE_EXIT_MS;
  return {
    /** Upward travel of the orb, in scene units. */
    orbLift: ease(orbT) * orbT * 442,
    /** Orb opacity multiplier. */
    orbFade: 1 - easeIn(orbT),
    /** Downward travel of the skyline, in scene units. */
    citySink: easeIn(ms / 560) * 110,
    /** Skyline opacity multiplier. */
    cityFade: 1 - ease(ms / 630),
  };
}

// ---- Landmarks ----------------------------------------------------------

// Bridge ends and piers, mirror-symmetric about x = 130
const BL = 58, BR = 202, P1 = 98, P2 = 162, DECK = B - 14;
// The Liberty Statue crowns Gellért Hill's summit
const SX = 86, SG = B - 106, SK = 0.5; // statue base, drawing scale

type Ctx = CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D;

/** Far layer: Gellért Hill and the Citadella, seen behind the bridge. */
function farShape(c: Ctx) {
  c.beginPath();
  // broad, rounded dome: a long gentle rise from the left, a steeper rocky fall to the river on the right
  c.moveTo(-2, B); c.lineTo(-2, B - 52); c.bezierCurveTo(26, B - 62, 46, B - 96, 74, B - 100);
  c.lineTo(98, B - 100); c.bezierCurveTo(120, B - 100, 132, B - 72, 150, B - 48);
  c.bezierCurveTo(164, B - 32, 180, B - 18, 198, B - 8); c.lineTo(198, B); c.closePath();
  // Citadella: long, low fortress across the summit, statue at its centre
  c.moveTo(68, B - 100); c.lineTo(70, B - 106); c.lineTo(102, B - 106); c.lineTo(104, B - 100); c.closePath();
  for (let x = 71; x < 101; x += 3.2) c.rect(x, B - 108, 1.6, 2.2);
  c.rect(66, B - 109, 5, 9); c.rect(101, B - 109, 5, 9);
  c.fill();
}

const quad = (x: number, x0: number, y0: number, cx: number, cy: number, x1: number, y1: number) => {
  const t = (x - x0) / (x1 - x0);
  return (1 - t) * (1 - t) * y0 + 2 * (1 - t) * t * cy + t * t * y1;
};
/** Liberty Bridge's upper truss chord: rises to each pylon, dips mid-river. */
const chord = (x: number) =>
  x < P1 ? quad(x, BL, DECK - 4, BL + 22, DECK - 8, P1 - 1, B - 38)
    : x < P2 ? quad(x, P1 - 1, B - 38, 130, B - 10, P2 + 1, B - 38)
      : quad(x, P2 + 1, B - 38, BR - 22, DECK - 8, BR, DECK - 4);

/** Near layer: quays, the Buda road, Liberty Bridge and Corvinus E building. */
function nearShape(c: Ctx) {
  c.beginPath();
  c.rect(-2, B - 7, BL + 2, 7); c.rect(BR, B - 7, 62, 7); // stone quays above the waterline
  c.rect(-2, DECK, BL + 2, B - 7 - DECK); c.rect(-2, DECK - 2, BL + 2, 2.5); // Buda riverside road on its retaining wall
  // Corvinus E building — Ybl's Customs House: long palace, raised end pavilions,
  // taller central block with a pediment and statues
  c.rect(BR, B - 35, 60, 28);
  c.rect(BR, B - 39, 10, 4); c.rect(BR + 50, B - 39, 10, 4);
  c.rect(BR + 20, B - 43, 20, 8);
  c.moveTo(BR + 20, B - 43); c.lineTo(BR + 30, B - 47); c.lineTo(BR + 40, B - 43); c.closePath();
  for (const x of [BR + 21, BR + 25, BR + 35, BR + 39]) {
    c.rect(x - 0.6, B - 46.5, 1.2, 3.5); c.moveTo(x + 0.9, B - 47.3); c.arc(x, B - 47.3, 0.9, 0, TAU);
  }
  // stone abutments and portal posts with lamps
  for (const [x, d] of [[BL, 1], [BR, -1]]) {
    c.rect(d > 0 ? x : x - 8, DECK, 8, B - DECK);
    const px = x + d * 3;
    c.rect(px - 1.5, B - 30, 3, 16); c.moveTo(px + 1.8, B - 32); c.arc(px, B - 32, 1.8, 0, TAU);
  }
  c.rect(BL, DECK - 2, BR - BL, 2.5); // deck
  for (const px of [P1, P2]) {
    c.rect(px - 3.5, DECK, 7, B - DECK); // river pier
    c.moveTo(px - 3.5, DECK - 2); c.lineTo(px - 1.5, B - 50); c.lineTo(px + 1.5, B - 50); c.lineTo(px + 3.5, DECK - 2); c.closePath(); // pylon
    c.rect(px - 0.6, B - 55, 1.2, 5); // mast
    c.moveTo(px + 2.3, B - 57); c.arc(px, B - 57, 2.3, 0, TAU); // golden orb
    c.moveTo(px - 6, B - 63); c.lineTo(px, B - 59.5); c.lineTo(px + 6, B - 63); c.lineTo(px, B - 60.6); c.closePath(); // Turul wings
  }
  c.fill();
  // truss: upper chord and the verticals between the chords
  c.lineWidth = 1.4; c.beginPath();
  c.moveTo(BL, DECK - 4); c.quadraticCurveTo(BL + 22, DECK - 8, P1 - 1, B - 38);
  c.quadraticCurveTo(130, B - 10, P2 + 1, B - 38); c.quadraticCurveTo(BR - 22, DECK - 8, BR, DECK - 4);
  c.stroke();
  c.lineWidth = 0.9; c.beginPath();
  for (let x = BL + 6; x <= BR - 6; x += 6) {
    if (Math.abs(x - P1) < 5 || Math.abs(x - P2) < 5) continue;
    c.moveTo(x, DECK - 2); c.lineTo(x, chord(x));
  }
  c.stroke();
}

/** Rock Chapel on the Buda road: crag, cross, Pauline monastery, street lamps. */
function chapelShape(c: Ctx) {
  c.beginPath();
  // jagged limestone crag, running off the left edge
  c.moveTo(-2, B - 16); c.lineTo(-2, B - 42); c.lineTo(3, B - 43); c.lineTo(7, B - 45); c.lineTo(11, B - 44);
  c.lineTo(15, B - 50); c.lineTo(20, B - 51); c.lineTo(26, B - 50); c.lineTo(29, B - 46); c.lineTo(33, B - 47);
  c.lineTo(37, B - 40); c.lineTo(40, B - 35); c.lineTo(41, B - 16); c.closePath();
  c.rect(19.4, B - 53, 6.2, 2); for (const x of [19.6, 22.2, 24.8]) c.rect(x, B - 55, 0.8, 2); // railed platform
  c.rect(21.8, B - 67, 1.6, 14); c.rect(18.6, B - 63.4, 8, 1.6); // cross
  c.rect(40, B - 29, 14, 13); // monastery
  c.moveTo(39.5, B - 29); c.lineTo(42.5, B - 42); c.lineTo(45.5, B - 29); c.closePath(); // pointed spire
  c.rect(48, B - 36, 7, 20); c.moveTo(47.4, B - 36); c.lineTo(51.5, B - 43); c.lineTo(55.6, B - 36); c.closePath(); // round tower
  for (const x of [5, 25, 35]) { c.rect(x, B - 26, 0.9, 10); c.rect(x - 1, B - 26.6, 2.9, 1); } // street lamps
  c.fill();
}

/** Liberty Statue: tall pedestal, slender robed figure, palm frond held level overhead. */
function statueShape(c: Ctx) {
  c.save(); c.translate(SX, SG); c.scale(SK, SK); c.translate(-SX, -SG);
  c.beginPath();
  c.rect(SX - 6, SG - 3, 12, 5); // base step
  c.moveTo(SX - 4, SG - 3); c.lineTo(SX - 3, SG - 30); c.lineTo(SX + 3, SG - 30); c.lineTo(SX + 4, SG - 3); c.closePath(); // pedestal
  c.rect(SX - 3.8, SG - 32, 7.6, 2); // top slab
  const F = SG - 32; // her feet
  c.moveTo(SX - 2.9, F); c.lineTo(SX - 2.3, F - 13); c.lineTo(SX - 3.3, F - 19.5); c.lineTo(SX + 3.3, F - 19.5);
  c.lineTo(SX + 2.3, F - 13); c.lineTo(SX + 2.9, F); c.closePath(); // robe
  c.moveTo(SX - 3.3, F - 19.5); c.lineTo(SX - 4.6, F - 14.5); c.lineTo(SX - 3.4, F - 15); c.lineTo(SX - 2.4, F - 18); c.closePath(); // sleeves
  c.moveTo(SX + 3.3, F - 19.5); c.lineTo(SX + 4.6, F - 14.5); c.lineTo(SX + 3.4, F - 15); c.lineTo(SX + 2.4, F - 18); c.closePath();
  c.moveTo(SX + 1.6, F - 21.4); c.arc(SX, F - 21.4, 1.6, 0, TAU); // head
  c.moveTo(SX - 3.3, F - 19); c.lineTo(SX - 4.9, F - 30); c.lineTo(SX - 3.5, F - 30.2); c.lineTo(SX - 2.1, F - 19.4); c.closePath(); // arms
  c.moveTo(SX + 3.3, F - 19); c.lineTo(SX + 4.9, F - 30); c.lineTo(SX + 3.5, F - 30.2); c.lineTo(SX + 2.1, F - 19.4); c.closePath();
  // palm frond: feathered tip on the left, stem on the right, gently arched
  c.moveTo(SX - 13, F - 31); c.quadraticCurveTo(SX - 2, F - 36.5, SX + 10, F - 33.2); c.lineTo(SX + 14, F - 33.4);
  c.lineTo(SX + 14, F - 32.6); c.lineTo(SX + 10, F - 31.6); c.quadraticCurveTo(SX - 2, F - 29.4, SX - 13, F - 31); c.closePath();
  for (let x = SX - 12; x < SX + 9; x += 1.7) {
    const t = (x - (SX - 13)) / 23, y = F - 31 - 4.6 * t * (1 - t) * 2.2;
    c.moveTo(x, y); c.lineTo(x - 2.4, y - 2.2); c.lineTo(x + 0.9, y + 0.2); c.closePath();
  }
  c.fill();
  c.restore();
}

/** Solid silhouette of the whole skyline, used to hide the orb behind the city. */
function skylineShape(c: Ctx) { farShape(c); nearShape(c); chapelShape(c); statueShape(c); }

// Rock Chapel cave mouths: round-headed arches cut into the crag
const arch = (x: number, y: number, cx: number, top: number, r: number, bottom: number) =>
  Math.abs(x - cx) < r && y < bottom && (y > top + r || Math.hypot(x - cx, y - (top + r)) < r);
const isOpening = (x: number, y: number) =>
  arch(x, y, 12, B - 36, 3.6, B - 16) || arch(x, y, 31, B - 31, 2.6, B - 16) || (x > 49.5 && x < 53.5 && y > B - 33 && y < B - 30);
// E building's window grid, left out of the dot fill
const isWindow = (x: number, y: number) =>
  isOpening(x, y) || (x > 205 && x < 259 && [B - 32, B - 25, B - 18].some((r) => y > r && y < r + 3.2) && (x - 205) % 5.4 < 2.6);

// ---- Sampling -------------------------------------------------------------

/** x, y, brightness, random phase, radius (scene units). */
type SkyDot = [number, number, number, number, number];

/** Rasterises `draw` over a scene-unit box at `scale` px per unit and returns an alpha lookup. */
function rasterise(draw: (c: Ctx) => void, x0: number, y0: number, w: number, h: number, scale: number) {
  const cw = Math.ceil(w * scale), ch = Math.ceil(h * scale);
  const canvas = new OffscreenCanvas(cw, ch);
  const c = canvas.getContext('2d')!;
  c.setTransform(scale, 0, 0, scale, -x0 * scale, -y0 * scale);
  c.fillStyle = c.strokeStyle = '#000';
  draw(c);
  const img = c.getImageData(0, 0, cw, ch).data;
  return (x: number, y: number) => {
    const xi = Math.round((x - x0) * scale), yi = Math.round((y - y0) * scale);
    return xi < 0 || yi < 0 || xi >= cw || yi >= ch ? 0 : img[(yi * cw + xi) * 4 + 3];
  };
}

function sampleSkyline(): SkyDot[] {
  const far = rasterise(farShape, -2, 300, SCENE_W + 4, B - 298, 1);
  const near = rasterise((c) => { nearShape(c); chapelShape(c); }, -2, 330, SCENE_W + 4, B - 328, 1);
  const chapel = rasterise(chapelShape, -2, B - 72, 62, 74, 4);
  const statue = rasterise(statueShape, SX - 10, SG - 40, 20, 44, 4);
  const out: SkyDot[] = [];
  const nearby = (x: number, y: number) =>
    near(x, y) > 40 || near(x - 2, y) > 40 || near(x + 2, y) > 40 || near(x, y - 2) > 40 || near(x, y + 2) > 40;

  // the bridge and Pest bank, crisp and bright
  for (let y = 340; y < B + 2; y += 2.1) for (let x = 0; x < SCENE_W; x += 2.1) {
    if (near(x, y) < 80 || chapel(x, y) > 0 || isWindow(x, y)) continue;
    const edge = near(x, y - 3) < 80 || near(x - 3, y) < 80 || near(x + 3, y) < 80;
    out.push([x, y, edge ? 1 : 0.45, Math.random(), edge ? 0.85 : 0.65]);
  }
  // the hill behind it: dimmer, with a clear gap wherever the bridge crosses in front
  for (let y = 300; y < B + 2; y += 2.4) for (let x = 0; x < SCENE_W; x += 2.4) {
    if (far(x, y) < 80 || nearby(x, y)) continue;
    const edge = far(x, y - 3) < 80 || far(x - 3, y) < 80 || far(x + 3, y) < 80;
    out.push([x, y, edge ? 0.6 : 0.18, Math.random(), 0.65]);
  }
  // the Rock Chapel on a finer grid, so the crag, cross, spire and tower stay legible
  for (let y = B - 70; y < B; y += 1.15) for (let x = 0.4; x < 58; x += 1.15) {
    if (chapel(x, y) < 100 || isOpening(x, y)) continue;
    const edge = chapel(x, y - 1.6) < 100 || chapel(x - 1.6, y) < 100 || chapel(x + 1.6, y) < 100
      || isOpening(x, y + 1.6) || isOpening(x - 1.6, y) || isOpening(x + 1.6, y);
    out.push([x, y, edge ? 1.1 : 0.4, Math.random(), 0.5]);
  }
  // the statue on a fine grid with small, bright dots so its shape survives at this size
  for (let y = SG - 38; y < SG + 2; y += 0.55) for (let x = SX - 9; x < SX + 9; x += 0.55) {
    if (statue(x, y) > 100) out.push([x, y, y > SG - 32 * SK ? 0.8 : 1.5, Math.random(), 0.42]); // pedestal dimmer than the figure
  }
  return out;
}

// Tram 47/49 crossing the bridge
const TRAM_W = 30, TRAM_H = 8, TRAM_Y = B - 16 - TRAM_H; // wheels on the deck
const TRAM_LOOP_MS = 7000;

function sampleTram(): Array<[number, number]> {
  const w = TRAM_W + 4, h = TRAM_H + 8;
  const c = new OffscreenCanvas(w, h).getContext('2d')!;
  c.fillStyle = c.strokeStyle = '#000';
  c.beginPath(); c.roundRect(1, 5, TRAM_W, TRAM_H, [3, 3, 1, 1]); c.fill();
  c.globalCompositeOperation = 'destination-out'; // window band
  for (let x = 5; x < TRAM_W - 3; x += 5) c.fillRect(x, 7, 3.4, 2.6);
  c.globalCompositeOperation = 'source-over'; c.lineWidth = 0.8; c.beginPath(); // pantograph
  c.moveTo(TRAM_W / 2 - 3, 5); c.lineTo(TRAM_W / 2, 1.5); c.lineTo(TRAM_W / 2 + 3, 5);
  c.moveTo(TRAM_W / 2 - 4, 1); c.lineTo(TRAM_W / 2 + 4, 1); c.stroke();
  const img = c.getImageData(0, 0, w, h).data, out: Array<[number, number]> = [];
  for (let y = 0; y < h; y += 1.5) for (let x = 0; x < w; x += 1.6) {
    if (img[(Math.round(y) * w + Math.round(x)) * 4 + 3] > 90) out.push([x, y]);
  }
  return out;
}

/** Fibonacci sphere of unit points. */
function sphere(n: number): Array<[number, number, number]> {
  return Array.from({ length: n }, (_, i) => {
    const y = 1 - (i / (n - 1)) * 2, r = Math.sqrt(1 - y * y), t = i * 2.39996;
    return [Math.cos(t) * r, y, Math.sin(t) * r];
  });
}
type Vec3 = [number, number, number];
const rotY = ([x, y, z]: Vec3, a: number): Vec3 => [x * Math.cos(a) + z * Math.sin(a), y, -x * Math.sin(a) + z * Math.cos(a)];
const rotX = ([x, y, z]: Vec3, a: number): Vec3 => [x, y * Math.cos(a) - z * Math.sin(a), y * Math.sin(a) + z * Math.cos(a)];

// ---- Drawing --------------------------------------------------------------

const BUCKETS = 32;

/** Collects dots into alpha buckets so each colour is a handful of fills per frame. */
class DotBatch {
  private paths: Path2D[] = [];
  add(x: number, y: number, r: number, a: number) {
    if (a <= 0.004) return;
    const i = Math.min(BUCKETS - 1, Math.round(a * BUCKETS));
    const p = (this.paths[i] ??= new Path2D());
    p.moveTo(x + r, y); p.arc(x, y, r, 0, TAU);
  }
  flush(c: CanvasRenderingContext2D, rgb: string, gain = 1) {
    this.paths.forEach((p, i) => { if (!p) return; c.fillStyle = `rgba(${rgb},${Math.min(1, (i / BUCKETS) * gain)})`; c.fill(p); });
    this.paths = [];
  }
}

export interface SplashFrame {
  /** ms since the splash mounted. */
  elapsed: number;
  /** ms since the exit began, or null while showing. */
  exitElapsed: number | null;
  /** Clock for slow drifts (orb spin, ripples, twinkle). */
  time: number;
  /** Visible area in scene units, from the caller's screen mapping. */
  view: { left: number; top: number; width: number; height: number };
  /** "r,g,b" of the dots and of the background. */
  fg: string;
  bg: string;
  /** Dot opacity multiplier: dark dots on a light background need more to read. */
  gain: number;
}

const TRAM_RGB = '255,205,0';
const ORB_R = 230;
const ORB_CY = B - 81;
const LIGHTS_START_MS = 100;

export function createSplashScene() {
  const skyDots = sampleSkyline();
  const tramDots = sampleTram();
  const orbPts = sphere(900);
  const fgBatch = new DotBatch(), tramBatch = new DotBatch();

  function drawOrb(c: CanvasRenderingContext2D, f: SplashFrame, lift: number, k: number) {
    const fade = ease(f.elapsed / 900) * k;
    const cx = SCENE_W / 2, cy = ORB_CY - lift;
    if (fade > 0) {
      for (const pt of orbPts) {
        const q = rotX(rotY(pt, f.time * 0.00009), -0.5), depth = (q[2] + 1) / 2;
        fgBatch.add(cx + q[0] * ORB_R, cy + q[1] * ORB_R, 0.6 + depth * 1.1, fade * (0.03 + depth * 0.2));
      }
      // one orbiting band of particles with a bright head and a fading trail
      for (let i = 0; i < 70; i++) {
        const a = f.time * 0.00035 + i * (TAU / 70), trail = i % 35 < 10 ? 1 - (i % 35) / 10 : 0.1;
        const q = rotY(rotX([Math.cos(a) * 1.02, 0, Math.sin(a) * 1.02], 0.42), 0.3), depth = (q[2] + 1) / 2;
        fgBatch.add(cx + q[0] * ORB_R, cy + q[1] * ORB_R, (0.7 + depth * 1.4) * (0.6 + trail * 0.6), fade * (0.05 + trail * 0.45) * (0.3 + depth * 0.7));
      }
      fgBatch.flush(c, f.fg, f.gain);
    }
    // vignette: the screen dims towards its edges
    const { left, top, width, height } = f.view;
    const g = c.createRadialGradient(cx, B - 190, 104, cx, B - 190, 390);
    g.addColorStop(0, `rgba(${f.bg},0)`);
    g.addColorStop(1, `rgba(${f.bg},${0.85 * Math.max(0, k)})`);
    c.fillStyle = g;
    c.fillRect(left, top, width, height);
  }

  function drawSkyline(c: CanvasRenderingContext2D, f: SplashFrame, sink: number, fade: number) {
    if (fade <= 0) return;
    const { left, width, top, height } = f.view;
    const bottom = top + height;
    c.save();
    c.translate(0, sink);
    // the orb sets behind the city and the river
    c.save();
    c.globalAlpha = fade;
    c.fillStyle = c.strokeStyle = `rgb(${f.bg})`;
    skylineShape(c);
    const water = c.createLinearGradient(0, B, 0, bottom);
    water.addColorStop(0, `rgba(${f.bg},0.95)`);
    water.addColorStop(1, `rgba(${f.bg},1)`);
    c.fillStyle = water;
    c.fillRect(left, B + 1, width, bottom - B);
    c.restore();

    // on a screen wider than the skyline, its ends fade out instead of stopping dead
    const wide = left < -4;
    const edge = (x: number) => (wide ? Math.min(1, Math.max(0, (x + 2) / 30), Math.max(0, (SCENE_W + 2 - x) / 30)) : 1);
    // fully lit ~1.1s in, before the exit can start
    const lights = (x: number, r: number) => ease((f.elapsed - LIGHTS_START_MS - (x / SCENE_W) * 600 - r * 80) / 350);

    for (const [x, y, e, r, rad] of skyDots) {
      const on = lights(x, r) * fade * edge(x);
      if (on <= 0) continue;
      const twinkle = e < 1 && r > 0.93 ? 0.35 * (0.5 + 0.5 * Math.sin(f.time * 0.003 + r * 40)) : 0;
      fgBatch.add(x, y, rad, on * (0.08 + e * 0.32 + twinkle));
      // Danube reflection: squashed, rippling, faint
      const ry = B + (B - y) * 0.55 + 3;
      fgBatch.add(x + Math.sin(f.time * 0.002 + y * 0.35) * 1.4, ry, 0.6, on * (0.03 + e * 0.07) * (1 - (ry - B) / 60));
    }
    for (let x = 1; x < SCENE_W; x += 3.2) fgBatch.add(x, B + 2, 0.5, lights(x, 0) * fade * edge(x) * 0.12); // waterline
    fgBatch.flush(c, f.fg, f.gain);

    // the tram starts crossing once the city is lit, slipping in and out at the bridge ends
    const tramT = f.elapsed - LIGHTS_START_MS - 500;
    if (tramT > 0) {
      const k = (tramT % TRAM_LOOP_MS) / TRAM_LOOP_MS, x0 = BL - TRAM_W + k * (BR - BL + TRAM_W);
      const arrive = ease(tramT / 400) * fade;
      for (const [dx, dy] of tramDots) {
        const x = x0 + dx;
        const a = arrive * Math.min(1, Math.max(0, (x - BL - 2) / 8), Math.max(0, (BR - 2 - x) / 8));
        if (a <= 0) continue;
        const y = TRAM_Y - 5 + dy;
        tramBatch.add(x, y, 0.7, a * 0.9);
        const ry = B + (B - y) * 0.55 + 3;
        tramBatch.add(x + Math.sin(f.time * 0.002 + y * 0.35) * 1.4, ry, 0.6, a * 0.16 * (1 - (ry - B) / 60));
      }
      tramBatch.flush(c, TRAM_RGB);
    }
    c.restore();
  }

  /** Draws one frame; the context must already map scene units to the screen. */
  return function draw(c: CanvasRenderingContext2D, f: SplashFrame) {
    const { left, top, width, height } = f.view;
    c.clearRect(left, top, width, height);
    const exit = f.exitElapsed == null ? null : sunriseExit(f.exitElapsed);
    drawOrb(c, f, exit?.orbLift ?? 0, exit?.orbFade ?? 1);
    drawSkyline(c, f, exit?.citySink ?? 0, exit?.cityFade ?? 1);
  };
}

/**
 * Maps the scene onto a screen: the skyline spans the width (up to a cap on
 * wide screens) with its waterline at 81.5% of the height.
 */
export function sceneTransform(screenW: number, screenH: number) {
  const scale = Math.min(screenW / SCENE_W, 2.4);
  const offsetX = screenW / 2 - (SCENE_W / 2) * scale;
  const offsetY = screenH * 0.815 - B * scale;
  return {
    scale, offsetX, offsetY,
    view: { left: -offsetX / scale, top: -offsetY / scale, width: screenW / scale, height: screenH / scale },
  };
}

/** A frame with the city fully lit and the tram mid-bridge, for reduced motion. */
export const STILL_FRAME_ELAPSED = LIGHTS_START_MS + 500 + TRAM_LOOP_MS / 2;
