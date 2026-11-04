import { useCallback, useEffect, useRef } from 'react';
import * as maplibregl from '@/lib/maplibre';
import {
  angleDifference,
  bearingAlongPolyline,
  cumulativeHaversineDistances,
  pointAtDistance,
  snapToPolyline,
  type LatLon,
} from '../route-geometry';
import { isValidLatLon, samePoints } from './geo';
import { createVehicleMarkerElement, type VehicleVariant } from './markers';

export const VEHICLE_ANIM_MAX_MS = 10_000;
export const VEHICLE_ANIM_MIN_MS = 600;
export const VEHICLE_ANIM_SPEED_MPS = 10;

export interface AnimatedVehicleInput {
  id: string;
  polyline: LatLon[];
  location: LatLon;
  variant?: VehicleVariant;
  bearing?: number;
}

interface VehicleState {
  marker: maplibregl.Marker;
  rotor: HTMLElement | null;
  polyline: LatLon[];
  dists: number[];
  currentDist: number;
  fromDist: number;
  toDist: number;
  startMs: number;
  durationMs: number;
  done: boolean;
  variant: VehicleVariant;
  lastBearing?: number;
  fallbackBearing?: number;
}

export function easeInOutQuad(t: number): number {
  return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
}

export function transitionFor(
  previous: { currentDist: number } | undefined,
  targetDist: number,
  now: number
): Pick<VehicleState, 'fromDist' | 'toDist' | 'startMs' | 'durationMs' | 'done'> {
  if (!previous) {
    return { fromDist: targetDist, toDist: targetDist, startMs: now, durationMs: 0, done: true };
  }
  const delta = Math.abs(targetDist - previous.currentDist);
  if (delta < 1) {
    return { fromDist: targetDist, toDist: targetDist, startMs: now, durationMs: 0, done: true };
  }
  return {
    fromDist: previous.currentDist,
    toDist: targetDist,
    startMs: now,
    durationMs: Math.min(
      VEHICLE_ANIM_MAX_MS,
      Math.max(VEHICLE_ANIM_MIN_MS, delta / VEHICLE_ANIM_SPEED_MPS)
    ),
    done: false,
  };
}

const distCache = new WeakMap<LatLon[], number[]>();
function distsFor(polyline: LatLon[]): number[] {
  const cached = distCache.get(polyline);
  if (cached) return cached;
  const dists = cumulativeHaversineDistances(polyline);
  distCache.set(polyline, dists);
  return dists;
}

export function resolveBearing(
  polyline: LatLon[],
  dists: number[],
  dist: number,
  fallbackBearing?: number
): number {
  const hasFallback =
    fallbackBearing != null &&
    Number.isFinite(fallbackBearing) &&
    fallbackBearing >= 0;

  if (polyline.length >= 2 && dists.length >= 2) {
    const polyBearing = bearingAlongPolyline(polyline, dists, dist);
    if (!hasFallback) {
      return polyBearing;
    }
    // If telemetry bearing agrees with polyline direction (within 45°),
    // snap to polyline bearing so arrow aligns cleanly with road track.
    // Otherwise (oncoming lane, reverse polyline, sharp curve divergence),
    // telemetry bearing is authoritative.
    if (angleDifference(polyBearing, fallbackBearing) <= 45) {
      return polyBearing;
    }
    return fallbackBearing;
  }
  return hasFallback ? fallbackBearing : 0;
}

export interface VehicleAnimOptions {
  routeColor?: string;
  traveledSourceId?: string;
}

/**
 * Animates vehicle markers smoothly along their polylines at 60fps and points
 * their directional beacon arrows along the heading of travel.
 */
export function useVehicleAnimations(
  map: maplibregl.Map | null,
  loaded: boolean,
  vehicles: AnimatedVehicleInput[],
  _options?: VehicleAnimOptions
): void {
  const statesRef = useRef<Map<string, VehicleState>>(new Map());
  const frameRef = useRef<number | null>(null);

  const startLoop = useCallback(() => {
    if (frameRef.current != null) return;
    const step = () => {
      frameRef.current = null;
      const now = performance.now();
      let active = false;

      for (const st of statesRef.current.values()) {
        if (!st.done) {
          active = true;
          const t = Math.min(1, (now - st.startMs) / (st.durationMs || 1));
          st.currentDist = t >= 1 ? st.toDist : st.fromDist + (st.toDist - st.fromDist) * easeInOutQuad(t);
          if (t >= 1) st.done = true;
          const pt = pointAtDistance(st.polyline, st.dists, st.currentDist);
          st.marker.setLngLat([pt.lon, pt.lat]);
        }
        const bearing = resolveBearing(st.polyline, st.dists, st.currentDist, st.fallbackBearing);
        if (st.rotor && (st.lastBearing == null || Math.abs(bearing - st.lastBearing) >= 0.5)) {
          st.rotor.style.transform = `rotate(${Math.round(bearing)}deg)`;
          st.lastBearing = bearing;
        }
      }

      if (active) frameRef.current = requestAnimationFrame(step);
    };
    frameRef.current = requestAnimationFrame(step);
  }, []);

  useEffect(() => {
    if (!map || !loaded) return;
    const states = statesRef.current;
    const now = performance.now();
    const seen = new Set<string>();

    for (const v of vehicles) {
      if (!isValidLatLon(v.location) || v.polyline.length < 2) continue;
      seen.add(v.id);
      const dists = distsFor(v.polyline);
      const snapped = snapToPolyline(v.location, v.polyline, v.bearing);
      const targetDist = snapped ? snapped.distance : 0;
      const targetPt = snapped ? snapped.point : v.location;
      const variant = v.variant ?? 'primary';
      const initialBearing = resolveBearing(v.polyline, dists, targetDist, v.bearing);
      let existing = states.get(v.id);
      // The marker element is styled per variant at creation; rebuild it when
      // a vehicle switches between primary and secondary.
      if (existing && existing.variant !== variant) {
        existing.marker.remove();
        states.delete(v.id);
        existing = undefined;
      }

      if (existing && samePoints(existing.polyline, v.polyline)) {
        const next = transitionFor(existing, targetDist, now);
        Object.assign(existing, next);
        if (next.done) existing.currentDist = next.toDist;
        existing.fallbackBearing = v.bearing;
        const currentBearing = resolveBearing(existing.polyline, existing.dists, existing.currentDist, v.bearing);
        if (existing.rotor && (existing.lastBearing == null || Math.abs(currentBearing - existing.lastBearing) >= 0.5)) {
          existing.rotor.style.transform = `rotate(${Math.round(currentBearing)}deg)`;
          existing.lastBearing = currentBearing;
        }
      } else if (existing) {
        existing.polyline = v.polyline;
        existing.dists = dists;
        existing.currentDist = targetDist;
        Object.assign(existing, transitionFor(undefined, targetDist, now));
        existing.fallbackBearing = v.bearing;
        existing.marker.setLngLat([targetPt.lon, targetPt.lat]);
        if (existing.rotor) {
          existing.rotor.style.transform = `rotate(${Math.round(initialBearing)}deg)`;
          existing.lastBearing = initialBearing;
        }
      } else {
        const element = createVehicleMarkerElement(variant);
        element.title = v.id;
        const rotor = element.querySelector<HTMLElement>('[data-vehicle-rotor]');
        if (rotor) {
          rotor.style.transform = `rotate(${Math.round(initialBearing)}deg)`;
        }
        const marker = new maplibregl.Marker({
          element,
          subpixelPositioning: true,
        })
          .setLngLat([targetPt.lon, targetPt.lat])
          .addTo(map);
        states.set(v.id, {
          marker,
          rotor,
          polyline: v.polyline,
          dists,
          currentDist: targetDist,
          variant,
          lastBearing: initialBearing,
          fallbackBearing: v.bearing,
          ...transitionFor(undefined, targetDist, now),
        });
      }
    }

    for (const [id, st] of states) {
      if (!seen.has(id)) {
        st.marker.remove();
        states.delete(id);
      }
    }
    startLoop();
  }, [map, loaded, vehicles, startLoop]);

  // Remove markers and stop the loop when the map instance changes or on unmount;
  // the data effect recreates markers on the new map.
  useEffect(() => {
    if (!map) return;
    const states = statesRef.current;
    return () => {
      if (frameRef.current != null) cancelAnimationFrame(frameRef.current);
      frameRef.current = null;
      for (const st of states.values()) st.marker.remove();
      states.clear();
    };
  }, [map]);
}

