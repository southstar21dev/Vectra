import type { LatLon } from '../route-geometry';

export type { LatLon };

export type MapStopKind =
  | 'regular'
  | 'terminus'
  | 'tracked'
  | 'selected'
  | 'secondary'
  | 'secondary-terminus';

export interface MapStop {
  id?: string;
  name: string;
  lat: number;
  lon: number;
  kind?: MapStopKind;
}

/** Label collision priority: selected > tracked > terminus > regular > secondary. */
export function labelPriority(kind: MapStopKind | undefined): number {
  switch (kind) {
    case 'selected':
      return 4;
    case 'tracked':
      return 3;
    case 'terminus':
      return 2;
    case 'secondary':
    case 'secondary-terminus':
      return 0;
    default:
      return 1;
  }
}

export interface RouteMapStop {
  name: string;
  lat: number;
  lon: number;
  isTracked: boolean;
  isSelected?: boolean;
}

export interface RouteMapVehicle {
  vehicleId: string;
  location: { lat: number; lon: number };
  isApproximate?: boolean;
  bearing?: number;
}

export interface RouteMapSecondaryLine {
  stops: RouteMapStop[];
  polyline: LatLon[];
  vehicles: RouteMapVehicle[];
}
