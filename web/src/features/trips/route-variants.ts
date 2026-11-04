import { normalizeSearchText, normalizeStopIdToStation } from '@/features/transit/transit-utils';

/** One direction of a route, from route-details `variants[]`. */
export interface RouteVariant {
  headsign?: string;
  name?: string;
  stopIds?: string[];
}

/** references.stops from route-details — id → stop metadata. */
export interface StopRefs {
  [id: string]: { name?: string };
}

export interface StopRef {
  id: string;
  name: string;
}

/** A stop reference as the user picked it (catalog id + catalog name). */
export interface StopPair {
  departure: StopRef;
  destination: StopRef;
}

/**
 * Normalized stop name for matching: accent-folded, parenthesized notes and
 * station suffixes dropped ("II. János Pál pápa tér M" → "ii. janos pal papa
 * ter"). Deliberately lenient so catalog names match route-details names.
 * Must stay in sync with the build script's normalizer
 * (scripts/build-catalog.mjs) that produced public/catalog/route-variants.json.
 */
export function normalizeStopName(s: string): string {
  return normalizeSearchText(s)
    .replace(/\([^)]*\)/g, '')
    .replace(/\b(m|h|pu|vasutallomas|allomas)\b/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

function namesOf(stops: string[], refs: StopRefs | undefined): string[] {
  return stops.map((id) => normalizeStopName(refs?.[id]?.name ?? ''));
}

function findStopIndex(names: string[], target: string): number {
  if (!target) return -1;
  return names.findIndex((n) => n === target || (n && n.includes(target)) || target.includes(n));
}

/** Does a variant serve the departure stop BEFORE the destination stop?
 *  Matches by station-normalized id first; when the ids don't line up (the
 *  catalog and the route API use different id schemes), falls back to
 *  normalized stop names resolved through `refs`. */
export function variantServes(
  v: RouteVariant,
  pair: StopPair,
  refs?: StopRefs
): boolean {
  const stops = v.stopIds ?? [];
  const normIds = stops.map(normalizeStopIdToStation);
  const depIdx = normIds.indexOf(normalizeStopIdToStation(pair.departure.id));
  const destIdx = normIds.indexOf(normalizeStopIdToStation(pair.destination.id));
  if (depIdx >= 0 && destIdx > depIdx) return true;

  const names = namesOf(stops, refs);
  const depName = normalizeStopName(pair.departure.name);
  const destName = normalizeStopName(pair.destination.name);
  const dIdx = findStopIndex(names, depName);
  const tIdx = findStopIndex(names, destName);
  return dIdx >= 0 && tIdx > dIdx;
}

/**
 * Pick the direction (headsign) whose stop order runs the departure stop
 * before the destination stop. Ids are matched station-normalized, with a
 * name-based fallback through `refs` for the same id-scheme mismatch. Returns
 * '' when no variant matches — the caller then treats the leg as
 * all-directions (empty headsign).
 */
export function deriveHeadsign(
  variants: RouteVariant[],
  pair: StopPair,
  refs?: StopRefs
): string {
  for (const v of variants) {
    if (variantServes(v, pair, refs)) {
      return v.headsign || v.name || '';
    }
  }
  return '';
}