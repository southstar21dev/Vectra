export const METRO_FALLBACK_COLORS: Record<string, { color: string; textColor: string }> = {
  M1: { color: 'FFD800', textColor: '000000' },
  M2: { color: 'E41F18', textColor: 'FFFFFF' },
  M3: { color: '005CA5', textColor: 'FFFFFF' },
  M4: { color: '009645', textColor: 'FFFFFF' },
};

export const GROUP_LINE_COLORS: Record<string, string> = {
  M1: '#FFD800',
  M2: '#E41F18',
  M3: '#005CA5',
  M4: '#009645',
  POPULAR: '#f59e0b',
};

export type MetroView = 'M1' | 'M2' | 'M3' | 'M4' | 'POPULAR';
export type BrowseMode = 'stop' | 'line';

/** Sheet title for a curated group: "Metro M2", "Popular stops". */
export function metroViewLabel(view: MetroView): string {
  return view === 'POPULAR' ? 'Popular stops' : `Metro ${view}`;
}

/** Type chips shown above the By line catalog list. */
export const ROUTE_TYPE_FILTERS = ['ALL', 'SUBWAY', 'TRAM', 'TROLLEYBUS', 'BUS', 'RAIL', 'FERRY', 'COACH'];
