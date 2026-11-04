import { describe, expect, it } from 'vitest';
import {
  OPENFREEMAP_ATTRIBUTION,
  OPENFREEMAP_STYLE_URL,
  getOpenFreeMapStyleUrl,
  mapTransformRequest,
  sanitizeOpenFreeMapStyle,
  getCartoMapLibreTileUrls,
  CARTO_ATTRIBUTION,
} from './map-tiles';

describe('map-tiles', () => {
  it('uses the OpenFreeMap style for dark mode', () => {
    const url = getOpenFreeMapStyleUrl(false);
    expect(url).toContain('https://tiles.openfreemap.org/styles/dark?v=3&d=');
    expect(OPENFREEMAP_STYLE_URL).toBe(getOpenFreeMapStyleUrl(false));
  });

  it('uses the OpenFreeMap Positron style for light mode', () => {
    expect(getOpenFreeMapStyleUrl(true)).toContain(
      'https://tiles.openfreemap.org/styles/positron?v=3&d='
    );
  });

  it('wraps get-expressions in comparisons with coalesce and fixes stale font names', () => {
    const sanitized = sanitizeOpenFreeMapStyle({
      version: 8,
      sources: {},
      layers: [
        {
          id: 'shield',
          type: 'symbol',
          filter: ['all', ['<=', ['get', 'ref_length'], 6]],
          layout: { 'text-font': ['OpenSans Regular'] },
        },
      ],
    } as never);
    const layer = sanitized.layers[0] as typeof sanitized.layers[number] & {
      filter?: unknown;
      layout?: { 'text-font'?: unknown[] };
    };
    // ["get", "ref_length"] wrapped with coalesce to prevent runtime null warnings
    expect(layer.filter).toEqual(['all', ['<=', ['coalesce', ['get', 'ref_length'], 999], 6]]);
    expect(layer.layout?.['text-font']).toEqual(['Noto Sans Regular']);
  });

  it('strips fill-pattern and line-pattern references missing from the sprite', () => {
    const sanitized = sanitizeOpenFreeMapStyle({
      version: 8,
      sources: {},
      layers: [
        {
          id: 'landcover_wood',
          type: 'fill',
          paint: { 'fill-pattern': 'wood-pattern', 'fill-color': '#2d3a2e', 'fill-opacity': 1 },
        },
      ],
    } as never);
    const paint = (sanitized.layers[0] as typeof sanitized.layers[number] & { paint?: Record<string, unknown> }).paint;
    expect(paint?.['fill-pattern']).toBeUndefined();
    // Other paint properties are preserved
    expect(paint?.['fill-color']).toBe('#2d3a2e');
  });

  it('provides OpenFreeMap, OpenMapTiles, and OpenStreetMap attribution', () => {
    expect(OPENFREEMAP_ATTRIBUTION).toContain('OPF');
    expect(OPENFREEMAP_ATTRIBUTION).toContain('OMT');
    expect(OPENFREEMAP_ATTRIBUTION).toContain('OSM');
  });

  describe('mapTransformRequest', () => {
    it('redirects bundled OpenSansRegular glyph ranges (0-255, 256-511) to local static assets', () => {
      expect(
        mapTransformRequest('https://tiles.openfreemap.org/fonts/OpenSansRegular/0-255.pbf', 'Glyphs')
      ).toEqual({ url: '/fonts/OpenSansRegular/0-255.pbf' });

      expect(
        mapTransformRequest('https://tiles.openfreemap.org/fonts/OpenSansRegular/256-511.pbf')
      ).toEqual({ url: '/fonts/OpenSansRegular/256-511.pbf' });

      expect(
        mapTransformRequest('https://tiles.openfreemap.org/fonts/Open%20Sans%20Regular/0-255.pbf')
      ).toEqual({ url: '/fonts/OpenSansRegular/0-255.pbf' });
    });

    it('falls back unbundled OpenSans glyph ranges to OpenFreeMap Noto Sans Regular', () => {
      expect(
        mapTransformRequest('https://tiles.openfreemap.org/fonts/OpenSansRegular/512-767.pbf', 'Glyphs')
      ).toEqual({
        url: 'https://tiles.openfreemap.org/fonts/Noto%20Sans%20Regular/512-767.pbf',
      });
    });

    it('leaves standard requests and other fonts untouched', () => {
      const notoUrl = 'https://tiles.openfreemap.org/fonts/Noto%20Sans%20Regular/0-255.pbf';
      expect(mapTransformRequest(notoUrl, 'Glyphs')).toEqual({ url: notoUrl });

      const tileUrl = 'https://tiles.openfreemap.org/planet/14/8734/5821.pbf';
      expect(mapTransformRequest(tileUrl, 'Source')).toEqual({ url: tileUrl });
    });
  });

  describe('CARTO tile exports', () => {
    it('generates 4 subdomain tile urls for light and dark modes', () => {
      const darkUrls = getCartoMapLibreTileUrls(false);
      expect(darkUrls).toHaveLength(4);
      expect(darkUrls[0]).toContain('basemaps.cartocdn.com/dark_all/{z}/{x}/{y}@2x.png');

      const lightUrls = getCartoMapLibreTileUrls(true);
      expect(lightUrls).toHaveLength(4);
      expect(lightUrls[0]).toContain('basemaps.cartocdn.com/light_all/{z}/{x}/{y}@2x.png');
    });

    it('exports CARTO_ATTRIBUTION', () => {
      expect(CARTO_ATTRIBUTION).toContain('CARTO');
    });
  });
});
