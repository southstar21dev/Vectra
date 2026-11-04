import { useEffect, useState } from 'react';
import type * as maplibregl from '@/lib/maplibre';

/** True once the map's style is loaded (sources can be added). */
export function useMapLoaded(map: maplibregl.Map | null): boolean {
  const [state, setState] = useState<{ map: maplibregl.Map | null; loaded: boolean }>(() => ({
    map,
    loaded: map?.isStyleLoaded() ?? false,
  }));

  if (state.map !== map || (!state.loaded && map?.isStyleLoaded())) {
    setState({ map, loaded: map?.isStyleLoaded() ?? false });
  }

  useEffect(() => {
    if (!map) return;
    const markLoaded = () => {
      if (map.isStyleLoaded()) {
        setState((prev) => (prev.map === map && !prev.loaded ? { ...prev, loaded: true } : prev));
      }
    };
    map.on('load', markLoaded);
    map.on('sourcedata', markLoaded);
    map.on('styledata', markLoaded);
    map.on('idle', markLoaded);
    return () => {
      map.off('load', markLoaded);
      map.off('sourcedata', markLoaded);
      map.off('styledata', markLoaded);
      map.off('idle', markLoaded);
    };
  }, [map]);

  return state.loaded;
}
