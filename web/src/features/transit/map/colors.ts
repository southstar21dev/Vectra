export function withHash(color: string): string {
  return color.startsWith('#') ? color : `#${color}`;
}

export function cssVar(name: string, fallback: string): string {
  if (typeof document === 'undefined') return fallback;
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || fallback;
}

export function isLightTheme(): boolean {
  return typeof document !== 'undefined' && document.documentElement.dataset.theme === 'light';
}

export interface LiveLineColors {
  casing: string;
  casingSecondary: string;
  baseGray: string;
}

export function liveLineColors(isLight: boolean): LiveLineColors {
  return {
    casing: cssVar('--map-line-casing', '#223a5e'),
    casingSecondary: cssVar('--map-line-casing-secondary', '#1f2c45'),
    baseGray: isLight ? '#9ca3af' : '#52525b',
  };
}

export interface ExplorerRouteColors {
  isLight: boolean;
  casing: string;
  casingWidth: number;
  halo: string;
  haloWidth: number;
  lineWidth: number;
  lineBlur: number;
  stopFill: string;
  stopStroke: string;
}

export function explorerRouteColors(isLight: boolean): ExplorerRouteColors {
  return {
    isLight,
    casing: isLight ? 'rgba(0,0,0,0.5)' : 'rgba(255,255,255,0.24)',
    casingWidth: isLight ? 12 : 13,
    halo: '#ffffff',
    haloWidth: isLight ? 8 : 9.5,
    lineWidth: isLight ? 5.5 : 6,
    lineBlur: isLight ? 1.5 : 2,
    // These match RouteMap's regular stop dots exactly.
    stopFill: '#52525b',
    stopStroke: '#71717a',
  };
}
