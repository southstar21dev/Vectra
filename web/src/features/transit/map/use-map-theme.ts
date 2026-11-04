import { useEffect, useState } from 'react';
import { isLightTheme } from './colors';

export interface MapTheme {
  isLight: boolean;
  themeNonce: number;
}

/** Reactive `data-theme` observer for maps: tiles and paint re-apply on flip. */
export function useMapTheme(): MapTheme {
  const [state, setState] = useState<MapTheme>(() => ({ isLight: isLightTheme(), themeNonce: 0 }));

  useEffect(() => {
    if (typeof document === 'undefined') return;
    const observer = new MutationObserver(() => {
      setState((prev) => {
        const isLight = isLightTheme();
        if (isLight === prev.isLight) return prev;
        return { isLight, themeNonce: prev.themeNonce + 1 };
      });
    });
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['data-theme'],
    });
    return () => observer.disconnect();
  }, []);

  return state;
}
