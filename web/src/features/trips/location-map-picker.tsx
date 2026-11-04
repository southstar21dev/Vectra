import { useEffect, useRef, useState } from 'react';
import * as maplibregl from '@/lib/maplibre';
import type { LatLon } from '@/features/transit/route-geometry';
import { TransitMap } from '@/features/transit/map/TransitMap';

interface LocationMapPickerProps {
  initialCenter?: LatLon;
  selectedPoint: LatLon | null;
  onMapClick: (point: LatLon) => void;
}

/** Small, deliberately interaction-focused map for choosing an address point. */
export function LocationMapPicker({
  initialCenter = { lat: 47.4979, lon: 19.0402 },
  selectedPoint,
  onMapClick,
}: LocationMapPickerProps) {
  const [map, setMap] = useState<maplibregl.Map | null>(null);
  const markerRef = useRef<maplibregl.Marker | null>(null);

  useEffect(() => {
    if (!map) return;
    const handleClick = (event: maplibregl.MapMouseEvent) => {
      onMapClick({ lat: event.lngLat.lat, lon: event.lngLat.lng });
    };
    map.on('click', handleClick);
    map.getCanvas().style.cursor = 'crosshair';
    return () => {
      map.off('click', handleClick);
      map.getCanvas().style.cursor = '';
    };
  }, [map, onMapClick]);

  useEffect(() => {
    if (!map) return;
    markerRef.current?.remove();
    markerRef.current = null;
    if (!selectedPoint) return;
    markerRef.current = new maplibregl.Marker({ color: '#38bdf8' })
      .setLngLat([selectedPoint.lon, selectedPoint.lat])
      .addTo(map);
    map.easeTo({ center: [selectedPoint.lon, selectedPoint.lat], duration: 350 });
    return () => {
      markerRef.current?.remove();
      markerRef.current = null;
    };
  }, [map, selectedPoint]);

  useEffect(() => {
    return () => {
      markerRef.current?.remove();
    };
  }, []);

  return (
    <div className="overflow-hidden rounded-2xl border border-subtle bg-surface-overlay">
      <TransitMap
        initialCenter={initialCenter}
        initialZoom={12}
        minZoom={9}
        maxZoom={19}
        dragRotate={false}
        attributionControl
        ariaLabel="Choose a trip location on the map"
        ariaRole="application"
        style={{ height: 260 }}
        className="w-full"
        onReady={setMap}
      />
    </div>
  );
}
