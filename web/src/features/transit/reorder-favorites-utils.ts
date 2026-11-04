import { formatRouteDisplayName } from './transit-utils';
import type { TransitTracker } from './use-transit';

/**
 * Pure helper to reorder a favorite item within a list of all trackers,
 * preserving the non-favorite items' positions while swapping the favorites.
 */
export function reorderFavoriteSlots(
  allItems: TransitTracker[],
  favIndex: number,
  direction: -1 | 1
): TransitTracker[] {
  const currentFavs = allItems.filter((t) => t.is_favorite);
  const targetFavIndex = favIndex + direction;
  if (targetFavIndex < 0 || targetFavIndex >= currentFavs.length) {
    return allItems;
  }

  const newFavs = [...currentFavs];
  const temp = newFavs[favIndex];
  newFavs[favIndex] = newFavs[targetFavIndex];
  newFavs[targetFavIndex] = temp;

  let favCounter = 0;
  return allItems.map((item) => {
    if (item.is_favorite) {
      const replacement = newFavs[favCounter];
      favCounter++;
      return replacement;
    }
    return item;
  });
}

/**
 * Extract numerical value from route name for lowest-to-highest ordering.
 * Matches routes like '1', '28/28A', 'M1', 'M2', 'H5', '988', 'S30'.
 */
export function extractRouteNumber(name: string): { num: number; prefix: string; raw: string } {
  const match = name.match(/^([A-Za-z]*)\s*(\d+)/);
  if (!match) {
    return { num: Number.MAX_SAFE_INTEGER, prefix: '', raw: name };
  }
  return {
    num: parseInt(match[2], 10),
    prefix: match[1].toUpperCase(),
    raw: name,
  };
}

/**
 * Pure helper to sort transit trackers by their line numbers from lowest to highest.
 */
export function sortTrackersByNumber(trackers: TransitTracker[]): TransitTracker[] {
  return [...trackers].sort((a, b) => {
    const nameA = formatRouteDisplayName(a.route_short_name, a.route_id);
    const nameB = formatRouteDisplayName(b.route_short_name, b.route_id);

    const aData = extractRouteNumber(nameA);
    const bData = extractRouteNumber(nameB);

    if (aData.num !== bData.num) {
      return aData.num - bData.num;
    }

    return nameA.localeCompare(nameB, undefined, { numeric: true, sensitivity: 'base' });
  });
}

/**
 * Pure helper to reorder any item in the full trackers list.
 */
export function reorderAllSlots(
  allItems: TransitTracker[],
  index: number,
  direction: -1 | 1
): TransitTracker[] {
  const targetIndex = index + direction;
  if (targetIndex < 0 || targetIndex >= allItems.length) {
    return allItems;
  }

  const next = [...allItems];
  const temp = next[index];
  next[index] = next[targetIndex];
  next[targetIndex] = temp;
  return next;
}

/**
 * Pure helper to drop a new favorites order into the favorite slots of the
 * full list, leaving every non-favorite where it was.
 */
export function applyFavoriteOrder(
  allItems: TransitTracker[],
  orderedFavs: TransitTracker[]
): TransitTracker[] {
  let favCounter = 0;
  return allItems.map((item) => {
    if (item.is_favorite) {
      const replacement = orderedFavs[favCounter] ?? item;
      favCounter++;
      return replacement;
    }
    return item;
  });
}
