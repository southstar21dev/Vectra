export interface TripRouteOption {
  routeId: string;
  routeShortName: string;
  routeColor: string;
  routeTextColor: string;
  /** Direction headsign for THIS route (either/or alternatives can differ). */
  headsign?: string;
}

export interface TripAddress {
  name: string;
  distanceMeters: number;
  walkingMinutes?: number;
  lat?: number;
  lon?: number;
  stopId?: string;
  stopName?: string;
}

export interface TripLeg {
  routes: TripRouteOption[];
  routeType: string;
  routeLongName: string;
  departureStopId: string;
  departureStopName: string;
  destinationStopId: string;
  destinationStopName: string;
  headsign: string;
  waitMinutes: number;
  /** 'WALK' = walking segment (routes: []); absent or 'TRANSIT' = transit leg. */
  mode?: 'TRANSIT' | 'WALK';
  /** Epoch ms of the leg's scheduled/predicted departure (computed timeline). */
  departureMs?: number;
  /** Epoch ms of the leg's scheduled/predicted arrival (computed timeline). */
  arrivalMs?: number;
  /** Walking segments only: distance in meters. */
  distanceMeters?: number;
  /** Walking segments only: derived duration in minutes. */
  durationMinutes?: number;
  /** Display-only: the departure time came from the live (predicted) feed. */
  isPredicted?: boolean;
  /** First leg only: the trip starts with a walk from this address to the stop.
   *  lat/lon are the address' coordinates (absent for legacy trips); stopId is
   *  the stop the address snapped to, so the address only ever attaches to the
   *  leg that departs from that exact stop. walkingMinutes comes from pedestrian
   *  routing when the address was selected from GPS. */
  originAddress?: TripAddress;
  /** Last leg only: the trip ends with a walk from the stop to this address.
   *  lat/lon are the address' coordinates (absent for legacy trips); stopId is
   *  the stop the address snapped to. */
  destinationAddress?: TripAddress;
  /** Which alternative route's departure was the earliest (computed timeline). */
  resolvedRouteId?: string;
  resolvedRouteShortName?: string;
  resolvedHeadsign?: string;
  /** The trip whose departure was chosen for this leg (computed timeline) —
   *  links to vehicles-for-route so the map can isolate THE vehicle to take. */
  departureTripId?: string;
}

export type TripDirection = 'to_uni' | 'to_home';

export interface TripPlan {
  id: string;
  userId: string;
  name: string;
  legs: TripLeg[];
  sortOrder: number;
  createdAt: string;
  /** Designated commute direction; absent or null = not designated. */
  direction?: TripDirection | null;
}

export interface NewTripInput {
  name: string;
  legs: TripLeg[];
  direction?: TripDirection | null;
}

export interface UpdateTripInput {
  id: string;
  name?: string;
  legs?: TripLeg[];
  sortOrder?: number;
  direction?: TripDirection | null;
}

/** Canonical identity of a saved journey. Two trips sharing this key render as
 *  the same card: same origin/destination addresses, same stop sequence and
 *  same routes (route order ignored). */
export function tripJourneyKey(trip: TripPlan): string {
  const legs = trip.legs
    .map((l) =>
      [
        l.departureStopId,
        l.destinationStopId,
        l.routes
          .map((r) => r.routeId)
          .sort()
          .join('+'),
      ].join('|')
    )
    .join(';');
  const origin = trip.legs[0]?.originAddress?.name ?? '';
  const destination = trip.legs[trip.legs.length - 1]?.destinationAddress?.name ?? '';
  return `${origin}>>>${legs}>>>${destination}`;
}

export function normalizeTripDirection(value: unknown): TripDirection | null {
  return value === 'to_uni' || value === 'to_home' ? value : null;
}

/** Optimistic cache update: moves a designation, clearing the same direction
 *  on every other trip and leaving the opposite direction untouched. */
export function applyDirectionChange(
  trips: TripPlan[],
  tripId: string,
  direction: TripDirection | null
): TripPlan[] {
  return trips.map((trip) => {
    if (trip.id === tripId) return { ...trip, direction };
    if (direction != null && trip.direction === direction) return { ...trip, direction: null };
    return trip;
  });
}

/** Normalize legacy leg data (pre-OR format) to current format. */
export function normalizeLeg(leg: any): TripLeg {
  if (leg.routes) return leg;
  // Legacy: single routeId + stopId
  return {
    routes: leg.routeId ? [{
      routeId: leg.routeId,
      routeShortName: leg.routeShortName ?? '?',
      routeColor: leg.routeColor ?? '666666',
      routeTextColor: leg.routeTextColor ?? 'FFFFFF',
    }] : [],
    routeType: leg.routeType ?? 'BUS',
    routeLongName: leg.routeLongName ?? '',
    departureStopId: leg.stopId ?? '',
    departureStopName: leg.stopName ?? '',
    destinationStopId: '',
    destinationStopName: '',
    headsign: leg.headsign ?? '',
    waitMinutes: leg.waitMinutes ?? 5,
  };
}
