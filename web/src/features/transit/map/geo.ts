import type * as GeoJSON from 'geojson';
import { cutPolylineAtDistance, type LatLon } from '../route-geometry';
import { labelPriority, type MapStop } from './types';

export function isValidLatLon(p: LatLon | null | undefined): p is LatLon {
  return !!p && Number.isFinite(p.lat) && Number.isFinite(p.lon) && (p.lat !== 0 || p.lon !== 0);
}

/** True when both polylines contain exactly the same points in the same order. */
export function samePoints(a: LatLon[], b: LatLon[]): boolean {
  if (a === b) return true;
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) {
    if (a[i].lat !== b[i].lat || a[i].lon !== b[i].lon) return false;
  }
  return true;
}

export function lineFeatureCollection(
  polylines: LatLon[][],
  propertiesFor?: (index: number) => GeoJSON.GeoJsonProperties
): GeoJSON.FeatureCollection<GeoJSON.LineString> {
  return {
    type: 'FeatureCollection',
    features: polylines
      .map((pts, index) => ({ pts, index }))
      .filter(({ pts }) => pts.length >= 2)
      .map(({ pts, index }) => ({
        type: 'Feature' as const,
        properties: propertiesFor?.(index) ?? {},
        geometry: {
          type: 'LineString' as const,
          coordinates: pts.map((p) => [p.lon, p.lat] as [number, number]),
        },
      })),
  };
}

export function stopFeatureCollection(
  stops: MapStop[]
): GeoJSON.FeatureCollection<GeoJSON.Point> {
  return {
    type: 'FeatureCollection',
    features: stops.filter((s) => isValidLatLon(s)).map((s) => ({
      type: 'Feature' as const,
      properties: {
        id: s.id ?? '',
        name: s.name,
        kind: s.kind ?? 'regular',
        labelPriority: labelPriority(s.kind),
      },
      geometry: { type: 'Point' as const, coordinates: [s.lon, s.lat] },
    })),
  };
}

export function accuracyCircleFeature(
  center: LatLon,
  radiusMeters: number
): GeoJSON.Feature<GeoJSON.Polygon> | null {
  if (!isValidLatLon(center) || !(radiusMeters > 0)) return null;
  const points = 64;
  const coords: [number, number][] = [];
  const km = radiusMeters / 1000;
  const latRad = (center.lat * Math.PI) / 180;
  for (let i = 0; i <= points; i++) {
    const angle = (i / points) * 2 * Math.PI;
    const dLat = (km / 111.32) * Math.cos(angle);
    const dLon = (km / (111.32 * Math.cos(latRad))) * Math.sin(angle);
    coords.push([center.lon + dLon, center.lat + dLat]);
  }
  return {
    type: 'Feature',
    properties: {},
    geometry: { type: 'Polygon', coordinates: [coords] },
  };
}

export function cutLineFeature(
  polyline: LatLon[],
  dists: number[],
  distance: number,
  properties: GeoJSON.GeoJsonProperties = {}
): GeoJSON.Feature<GeoJSON.LineString> | null {
  if (!(distance > 0)) return null;
  const pts = cutPolylineAtDistance(polyline, dists, distance);
  if (pts.length < 2) return null;
  return {
    type: 'Feature',
    properties,
    geometry: {
      type: 'LineString',
      coordinates: pts.map(([lat, lon]) => [lon, lat] as [number, number]),
    },
  };
}

export interface Bounds {
  minLat: number;
  minLon: number;
  maxLat: number;
  maxLon: number;
}

export function boundsOf(points: LatLon[]): Bounds | null {
  if (points.length === 0) return null;
  let minLat = points[0].lat;
  let maxLat = points[0].lat;
  let minLon = points[0].lon;
  let maxLon = points[0].lon;
  for (const p of points) {
    if (p.lat < minLat) minLat = p.lat;
    if (p.lat > maxLat) maxLat = p.lat;
    if (p.lon < minLon) minLon = p.lon;
    if (p.lon > maxLon) maxLon = p.lon;
  }
  return { minLat, minLon, maxLat, maxLon };
}
