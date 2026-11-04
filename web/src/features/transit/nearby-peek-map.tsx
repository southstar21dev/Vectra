import { useEffect, useRef, useState } from 'react';
import * as maplibregl from '@/lib/maplibre';
import { TransitMap } from './map/TransitMap';
import { useMapLoaded } from './map/use-map-loaded';
import type { LatLon } from './route-geometry';

export interface PeekStop {
  id: string;
  lat: number;
  lon: number;
}

/** A pinned line's stop, drawn as a small badge in the line's colour. */
export interface PeekPin extends PeekStop {
  label: string;
  /** Hex without '#', as the catalog stores it. */
  color?: string | null;
  textColor?: string | null;
}

interface NearbyPeekMapProps {
  center: LatLon;
  stops: PeekStop[];
  pins?: PeekPin[];
  /** The stop whose departure leads the card; it pulses. */
  highlightStopId: string | null;
  onReady?: () => void;
}

/** How far the user must move before the camera follows. */
const FOLLOW_DISTANCE_METERS = 40;

function pinElement(pin: PeekPin): HTMLElement {
  const el = document.createElement('span');
  el.className = 'flex h-5 min-w-5 items-center justify-center rounded-md px-1 font-mono text-[9px] font-extrabold leading-none shadow-md ring-1 ring-black/20';
  el.style.backgroundColor = pin.color ? `#${pin.color}` : '#64748b';
  el.style.color = pin.textColor ? `#${pin.textColor}` : '#ffffff';
  el.textContent = pin.label;
  return el;
}

function dotElement(html: string): HTMLElement {
  const el = document.createElement('div');
  el.innerHTML = html;
  return el;
}

const USER_DOT =
  '<div class="relative flex h-5 w-5 items-center justify-center">' +
  '<span class="absolute inline-flex h-full w-full animate-ping rounded-full bg-blue-400/70 motion-reduce:hidden"></span>' +
  '<span class="relative h-3.5 w-3.5 rounded-full bg-blue-500 ring-2 ring-white shadow-lg shadow-blue-500/50"></span>' +
  '</div>';
const STOP_DOT = '<span class="block h-2.5 w-2.5 rounded-full border-2 border-slate-700 bg-white shadow-sm dark:border-slate-300 dark:bg-slate-900"></span>';
const PULSE_DOT =
  '<span class="relative flex h-4 w-4 items-center justify-center">' +
  '<span class="absolute inline-flex h-full w-full animate-ping rounded-full bg-amber-400/80 motion-reduce:hidden"></span>' +
  '<span class="relative h-2.5 w-2.5 rounded-full bg-amber-400 ring-2 ring-white shadow-lg"></span>' +
  '</span>';

/**
 * The map slice at the top of the nearby card: the basemap around you, your
 * pulsing position, the nearby stops as dots and the stop with the next
 * departure pulsing amber. Not interactive; the card around it is the button.
 */
export function NearbyPeekMap({ center, stops, pins = [], highlightStopId, onReady }: NearbyPeekMapProps) {
  const [map, setMap] = useState<maplibregl.Map | null>(null);
  const userMarkerRef = useRef<maplibregl.Marker | null>(null);
  const pulseMarkerRef = useRef<maplibregl.Marker | null>(null);
  const stopMarkersRef = useRef<Map<string, maplibregl.Marker>>(new Map());
  const centeredRef = useRef(false);
  const loaded = useMapLoaded(map);
  const onReadyRef = useRef(onReady);
  useEffect(() => {
    onReadyRef.current = onReady;
  }, [onReady]);

  // One-shot: report once the style is loaded and a frame has painted, the
  // same signal the route maps use (map.loaded() can stay false while tiles
  // are pending, which would leave the cover up for good).
  useEffect(() => {
    if (!loaded) return;
    const frame = requestAnimationFrame(() => onReadyRef.current?.());
    return () => cancelAnimationFrame(frame);
  }, [loaded]);

  // Your position: a marker that follows each fix, with the camera easing
  // after it only once you have moved far enough to matter.
  useEffect(() => {
    if (!map) return;
    const target: [number, number] = [center.lon, center.lat];
    if (userMarkerRef.current) userMarkerRef.current.setLngLat(target);
    else userMarkerRef.current = new maplibregl.Marker({ element: dotElement(USER_DOT) }).setLngLat(target).addTo(map);
    if (!centeredRef.current) {
      centeredRef.current = true;
      map.jumpTo({ center: target, zoom: 15.5 });
      return;
    }
    if (map.getCenter().distanceTo(maplibregl.LngLat.convert(target)) > FOLLOW_DISTANCE_METERS) {
      map.easeTo({ center: target, duration: 900, essential: true });
    }
  }, [map, center.lat, center.lon]);

  // Stop dots, keyed so a re-render only adds and removes what changed.
  useEffect(() => {
    if (!map) return;
    const markers = stopMarkersRef.current;
    const keep = new Set<string>();
    for (const stop of stops) {
      keep.add(stop.id);
      const existing = markers.get(stop.id);
      if (existing) existing.setLngLat([stop.lon, stop.lat]);
      else markers.set(stop.id, new maplibregl.Marker({ element: dotElement(STOP_DOT) }).setLngLat([stop.lon, stop.lat]).addTo(map));
    }
    for (const [id, marker] of markers) {
      if (!keep.has(id)) {
        marker.remove();
        markers.delete(id);
      }
    }
  }, [map, stops]);

  // Pinned lines' stops, rebuilt whenever the pin set changes (a handful at most).
  useEffect(() => {
    if (!map) return;
    const markers = pins.map((pin) =>
      new maplibregl.Marker({ element: pinElement(pin) }).setLngLat([pin.lon, pin.lat]).addTo(map)
    );
    return () => {
      for (const marker of markers) marker.remove();
    };
  }, [map, pins]);

  // The leading stop pulses amber on top of its dot.
  useEffect(() => {
    if (!map) return;
    const stop = highlightStopId ? stops.find((s) => s.id === highlightStopId) : undefined;
    if (!stop) {
      pulseMarkerRef.current?.remove();
      pulseMarkerRef.current = null;
      return;
    }
    if (pulseMarkerRef.current) pulseMarkerRef.current.setLngLat([stop.lon, stop.lat]);
    else pulseMarkerRef.current = new maplibregl.Marker({ element: dotElement(PULSE_DOT) }).setLngLat([stop.lon, stop.lat]).addTo(map);
  }, [map, highlightStopId, stops]);

  useEffect(
    () => () => {
      userMarkerRef.current?.remove();
      pulseMarkerRef.current?.remove();
      for (const marker of stopMarkersRef.current.values()) marker.remove();
      stopMarkersRef.current.clear();
    },
    []
  );

  return (
    <TransitMap
      initialCenter={center}
      initialZoom={15.5}
      minZoom={10}
      dragRotate={false}
      attributionControl={false}
      ariaLabel="Map of transit stops near you"
      className="pointer-events-none h-full w-full"
      onReady={setMap}
    />
  );
}
