import { useEffect, useState } from 'react';
import { APP_PRESENTED_FALLBACK_MS, hasAppPresented, markAppPresented, onAppPresented } from '@/lib/app-presented';

/**
 * Whether the boot splash has cleared (see `@/lib/app-presented`).
 *
 * Starts from the current value, so a component mounted after boot animates
 * right away, and latches through a listener for the components that were
 * already on screen behind the splash.
 */
export function useAppPresented(): boolean {
  const [presented, setPresented] = useState(hasAppPresented);

  useEffect(() => {
    if (presented) return;

    const unsubscribe = onAppPresented(() => setPresented(true));
    // Safety net: a splash that never clears must not leave the screen blank.
    const fallback = setTimeout(markAppPresented, APP_PRESENTED_FALLBACK_MS);
    return () => {
      unsubscribe();
      clearTimeout(fallback);
    };
  }, [presented]);

  return presented;
}
