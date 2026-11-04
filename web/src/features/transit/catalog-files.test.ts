import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import type { CatalogRoute, CatalogRouteVariant, CatalogStop } from './catalog-types';

function readCatalog<T>(name: string): T {
  return JSON.parse(
    readFileSync(new URL(`../../../public/catalog/${name}`, import.meta.url), 'utf8')
  ) as T;
}

describe('committed BKK catalog files', () => {
  it('routes.json is a healthy route catalog', () => {
    const routes = readCatalog<CatalogRoute[]>('routes.json');
    expect(routes.length).toBeGreaterThan(100);
    expect(routes.some((r) => r.id === 'BKK_5400' && r.shortName === 'M4')).toBe(true);
    for (const r of routes) {
      expect(r.id).toBeTruthy();
      expect(typeof r.shortName).toBe('string');
      expect(typeof r.description).toBe('string');
      expect(typeof r.type).toBe('string');
      expect(r.color).toMatch(/^[0-9A-Fa-f]{6}$/);
      expect(r.textColor).toMatch(/^[0-9A-Fa-f]{6}$/);
    }
  });

  it('stops.json is a healthy stop-area catalog', () => {
    const stops = readCatalog<CatalogStop[]>('stops.json');
    expect(stops.length).toBeGreaterThan(1000);
    for (const s of stops) {
      expect(s.id).toBeTruthy();
      expect(typeof s.name).toBe('string');
      expect(Number.isFinite(s.lat)).toBe(true);
      expect(Number.isFinite(s.lon)).toBe(true);
    }
  });

  it('route-variants.json maps route ids to normalized variants', () => {
    const variants = readCatalog<Record<string, CatalogRouteVariant[]>>('route-variants.json');
    const ids = Object.keys(variants);
    expect(ids.length).toBeGreaterThan(100);
    for (const id of ids) {
      expect(Array.isArray(variants[id])).toBe(true);
    }
    for (const id of ids.slice(0, 50)) {
      for (const v of variants[id]) {
        expect(typeof v.h).toBe('string');
        expect(Array.isArray(v.s)).toBe(true);
        for (const name of v.s) expect(typeof name).toBe('string');
      }
    }
  });
});
