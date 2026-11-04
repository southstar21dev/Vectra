import type { RadarDepartureItem } from './nearby-stops';

export interface LineGroup {
  routeId: string;
  shortName: string;
  color: string;
  textColor: string;
  directions: RadarDepartureItem[];
}

const MAX_LINES = 2;

/** The next departures at the nearest stop grouped by line, so a line serving
 *  both directions reads as one well with two destinations. */
export function groupByLine(items: RadarDepartureItem[], stopId: string, maxLines = MAX_LINES): LineGroup[] {
  const groups: LineGroup[] = [];
  for (const r of items) {
    if (r.stopId !== stopId) continue;
    let g = groups.find((x) => x.routeId === r.routeId);
    if (!g) {
      if (groups.length >= maxLines) continue;
      g = { routeId: r.routeId, shortName: r.shortName, color: r.color, textColor: r.textColor, directions: [] };
      groups.push(g);
    }
    if (g.directions.length >= 2 || g.directions.some((d) => d.headsign === r.headsign)) continue;
    g.directions.push(r);
  }
  return groups;
}
