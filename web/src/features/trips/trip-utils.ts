import { tripJourneyKey, type TripPlan } from './types';

/** Collapse true duplicates: the same journey saved multiple times would
 *  otherwise render as identical cards. */
export function dedupeTrips(trips: TripPlan[]): TripPlan[] {
  const seen = new Set<string>();
  const out: TripPlan[] = [];
  for (const t of trips) {
    const key = tripJourneyKey(t);
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(t);
  }
  return out;
}

/** Determines if a planned trip has upcoming departures scheduled/available. */
export function tripHasDepartures({
  hasTransit,
  optionsCount,
  firstDepartureCountdown,
  allTransitLegsHaveDepartures,
}: {
  hasTransit: boolean;
  optionsCount: number;
  firstDepartureCountdown?: string | null;
  allTransitLegsHaveDepartures: boolean;
}): boolean {
  if (!hasTransit) return true;
  if (optionsCount > 0) return true;
  return allTransitLegsHaveDepartures && Boolean(firstDepartureCountdown) && firstDepartureCountdown !== '--';
}
