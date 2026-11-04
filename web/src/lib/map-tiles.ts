import type {
  FilterSpecification,
  LayerSpecification,
  Map as MapLibreMap,
  RequestParameters,
  ResourceType,
  StyleSpecification,
} from 'maplibre-gl';

/** Shared OpenFreeMap basemap configuration for every MapLibre map in Vectra. */

const OPENFREEMAP_STYLE_BASE = 'https://tiles.openfreemap.org/styles';

export const OPENFREEMAP_ATTRIBUTION =
  '<a href="https://openfreemap.org/">OPF</a> &copy; <a href="https://openmaptiles.org/">OMT</a> &copy; <a href="https://www.openstreetmap.org/copyright">OSM</a>';

export function getOpenFreeMapStyleUrl(isLight = false): string {
  // The provider previously served a cached style with broken OpenSans glyph
  // and shield filters. A daily cache-buster defeats aggressive WebView/CDN
  // caches that ignore `cache: 'no-store'` on the fetch request.
  const day = Math.floor(Date.now() / 86_400_000);
  return `${OPENFREEMAP_STYLE_BASE}/${isLight ? 'positron' : 'dark'}?v=3&d=${day}`;
}

/** Comparison operators whose operands must be non-null at runtime. */
const CMP_OPS = new Set(['<=', '>=', '<', '>']);

/**
 * Walk a MapLibre filter/expression tree and make comparisons null-safe.
 *
 * The positron style uses filters like `["<=", ["get", "ref_length"], 6]`.
 * When a tile feature lacks `ref_length`, `["get", "ref_length"]` evaluates
 * to `null` at runtime, producing a MapLibre warning on every such feature.
 *
 * This wraps bare `["get", …]` operands inside comparison operators with
 * `["coalesce", ["get", …], 999]` so the comparison evaluates to `false`
 * silently (999 > any reasonable threshold) instead of warning.
 *
 * Also replaces literal `null` values with `0` for any stale style versions.
 */
function nullSafeExpr(value: unknown): unknown {
  if (value === null) return 0;
  if (!Array.isArray(value)) return value;

  const [op, ...rest] = value;
  const args = rest.map(nullSafeExpr);

  if (typeof op === 'string' && CMP_OPS.has(op)) {
    const safeArgs = args.map((arg) =>
      Array.isArray(arg) && arg[0] === 'get'
        ? ['coalesce', arg, 999]
        : arg
    );
    return [op, ...safeArgs];
  }

  return [op, ...args];
}

export function sanitizeOpenFreeMapStyle(style: StyleSpecification): StyleSpecification {
  const sanitized = JSON.parse(JSON.stringify(style)) as StyleSpecification;
  for (const layer of sanitized.layers) {
    const candidate = layer as LayerSpecification & {
      filter?: unknown;
      layout?: Record<string, unknown>;
      paint?: Record<string, unknown>;
    };
    if (candidate.filter !== undefined) {
      candidate.filter = nullSafeExpr(candidate.filter) as FilterSpecification;
    }
    // The OFM sprite is missing pattern images (e.g. "wood-pattern" in the
    // dark style's landcover_wood layer). Stripping the paint properties
    // prevents MapLibre from trying to resolve them — the layer falls back
    // to its solid fill-color instead.
    if (candidate.paint) {
      delete candidate.paint['fill-pattern'];
      delete candidate.paint['line-pattern'];
    }
    const textFont = candidate.layout?.['text-font'];
    if (Array.isArray(textFont)) {
      candidate.layout!['text-font'] = textFont.map((font) =>
        typeof font === 'string' ? font.replace(/OpenSans/gi, 'Noto Sans') : font
      );
    }
  }
  return sanitized;
}

export async function fetchOpenFreeMapStyle(isLight = false): Promise<StyleSpecification> {
  const response = await fetch(getOpenFreeMapStyleUrl(isLight), { cache: 'no-store' });
  if (!response.ok) throw new Error(`OpenFreeMap style request failed (${response.status})`);
  return sanitizeOpenFreeMapStyle((await response.json()) as StyleSpecification);
}

/**
 * Silences MapLibre's "Image … could not be loaded" warnings by providing a
 * 1×1 transparent pixel for any style-referenced image that is missing from
 * the sprite (e.g. "wood-pattern" in stale positron caches).
 *
 * Returns a cleanup function that removes the listener.
 */
export function suppressMissingStyleImages(map: MapLibreMap): () => void {
  const handled = new Set<string>();
  const onMissing = (e: { id: string }) => {
    if (handled.has(e.id)) return;
    handled.add(e.id);
    // 1×1 transparent RGBA image — satisfies the reference without rendering.
    map.addImage(e.id, { width: 1, height: 1, data: new Uint8Array(4) });
  };
  map.on('styleimagemissing', onMissing);
  return () => map.off('styleimagemissing', onMissing);
}

/**
 * Intercepts external resource requests from MapLibre.
 *
 * OpenFreeMap styles use OpenFreeMap's font endpoint template
 * (`https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf`).
 * However, OpenFreeMap only hosts Noto Sans glyphs; it does not host
 * OpenSansRegular. Vectra bundles self-hosted Latin & Latin Extended-A glyphs
 * (ranges 0-255 and 256-511) in `/fonts/OpenSansRegular/` which are precached
 * for offline transit stop rendering (supporting Hungarian characters like ő/ű).
 *
 * This transformRequest routes OpenSansRegular requests for ranges 0-255 and
 * 256-511 to the local bundled PBF assets. For any unbundled glyph ranges,
 * it redirects to OpenFreeMap's Noto Sans Regular so requests never 404.
 */
export function mapTransformRequest(
  url: string,
  resourceType?: ResourceType | string
): RequestParameters {
  if (resourceType === 'Glyphs' || url.includes('/fonts/')) {
    const match = url.match(
      /fonts\/(?:OpenSansRegular|Open%20Sans%20Regular|Open\s*Sans(?:\s*Regular)?)\/([0-9]+-[0-9]+\.pbf)/i
    );
    if (match) {
      const range = match[1];
      if (range === '0-255.pbf' || range === '256-511.pbf') {
        const base = (
          typeof import.meta !== 'undefined' && import.meta.env?.BASE_URL
            ? import.meta.env.BASE_URL
            : '/'
        ).replace(/\/$/, '');
        return {
          url: `${base}/fonts/OpenSansRegular/${range}`,
        };
      }
      return {
        url: `https://tiles.openfreemap.org/fonts/Noto%20Sans%20Regular/${range}`,
      };
    }
  }
  return { url };
}

/**
 * Add narrowly filtered overlays after the provider style is fully loaded.
 * They are inserted below transportation and label layers, so they cannot
 * cover roads, labels, or the transit overlays. Exact source filters prevent
 * rivers or unrelated land-use geometry from being painted as parks.
 */
const OVERLAY_LAYER_IDS = ['vectra-water-fill', 'vectra-park-landcover', 'vectra-park-landuse'] as const;

export function applyOpenFreeMapParkColors(map: MapLibreMap, isLight: boolean): void {
  const style = map.getStyle();
  const vectorSourceId = Object.entries(style.sources).find(([, source]) => source.type === 'vector')?.[0];
  if (!vectorSourceId) return;

  const waterColor = isLight ? '#c5dce8' : '#183b52';
  const parkColor = isLight ? '#b8d7b1' : '#315f3c';

  // Already applied: only recolour. Removing and re-adding the layers would
  // force MapLibre to reload every tile of the source, which fires `idle`
  // again and can loop.
  if (OVERLAY_LAYER_IDS.every((id) => map.getLayer(id))) {
    map.setPaintProperty('vectra-water-fill', 'fill-color', waterColor);
    map.setPaintProperty('vectra-park-landcover', 'fill-color', parkColor);
    map.setPaintProperty('vectra-park-landuse', 'fill-color', parkColor);
    return;
  }

  for (const id of OVERLAY_LAYER_IDS) {
    if (map.getLayer(id)) map.removeLayer(id);
  }

  const firstRoadLayer = map.getStyle().layers?.find(
    (layer) =>
      (layer as typeof layer & { 'source-layer'?: string })['source-layer'] === 'transportation'
  )?.id;
  const firstSymbolLayer = map.getStyle().layers?.find((layer) => layer.type === 'symbol')?.id;
  const beforeId = firstRoadLayer ?? firstSymbolLayer;

  const addOverlay = (layer: LayerSpecification) => {
    try {
      map.addLayer(layer, beforeId);
    } catch {
      // A style variant may not contain the calculated insertion layer. The
      // overlay is still safe at the top because its filters are exact.
      map.addLayer(layer);
    }
  };

  addOverlay({
    id: 'vectra-water-fill',
    type: 'fill',
    source: vectorSourceId,
    'source-layer': 'water',
    paint: {
      'fill-color': waterColor,
      'fill-opacity': 0.95,
      'fill-antialias': true,
    },
  });

  for (const [id, sourceLayer, property] of [
    ['vectra-park-landcover', 'landcover', 'subclass'],
    ['vectra-park-landuse', 'landuse', 'class'],
  ] as const) {
    addOverlay({
      id,
      type: 'fill',
      source: vectorSourceId,
      'source-layer': sourceLayer,
      filter: ['==', property, 'park'] as FilterSpecification,
      paint: {
        'fill-color': parkColor,
        'fill-opacity': 0.82,
        'fill-antialias': true,
      },
    });
  }
}

export const OPENFREEMAP_STYLE_URL = getOpenFreeMapStyleUrl(false);

/**
 * Map tile utilities for CARTO basemaps across Vectra.
 *
 * CARTO requires an API key on raster tile endpoints (basemaps.cartocdn.com).
 * When `VITE_CARTO_API_KEY` is configured in environment (.env.local), tile
 * requests automatically include `?key=...` to remove the "API KEY REQUIRED"
 * watermark.
 */

export const CARTO_ATTRIBUTION =
  '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> &copy; <a href="https://carto.com/attributions">CARTO</a>';

/**
 * Returns the CARTO API key if configured in the environment.
 */
export function getCartoApiKey(): string {
  try {
    return (typeof import.meta !== 'undefined' && import.meta.env?.VITE_CARTO_API_KEY) || '';
  } catch {
    return '';
  }
}

/**
 * Returns the query parameter string for CARTO URLs (e.g. `?key=abc` or `""`).
 */
export function getCartoKeyQuery(apiKey?: string): string {
  const key = apiKey !== undefined ? apiKey : getCartoApiKey();
  return key ? `?key=${encodeURIComponent(key)}` : '';
}

/**
 * MapLibre GL-compatible CARTO raster tile URLs (4 subdomains, @2x retina).
 * Returns an array suitable for a MapLibre `raster` source's `tiles` property.
 */
export function getCartoMapLibreTileUrls(isLight = false): string[] {
  const variant = isLight ? 'light_all' : 'dark_all';
  const query = getCartoKeyQuery();
  return ['a', 'b', 'c', 'd'].map(
    (s) => `https://${s}.basemaps.cartocdn.com/${variant}/{z}/{x}/{y}@2x.png${query}`
  );
}
