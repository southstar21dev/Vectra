import { useCallback, useEffect, useRef } from 'react';
import type * as maplibregl from '@/lib/maplibre';

/**
 * Distinguishes user camera moves from programmatic ones. Call programmatic
 * moves through `programmaticMove`; `userMovedRef` only turns true when the
 * user drags/zooms/pitches outside such a call. The flag clears on moveend,
 * with a timeout fallback for no-op moves, so it is safe for animated fits
 * (unlike the old synchronous flag that broke the preview map's refit).
 */
export function useProgrammaticGuard(map: maplibregl.Map | null, onUserMove?: () => void) {
  const programmaticRef = useRef(false);
  const userMovedRef = useRef(false);
  const timerRef = useRef<number | null>(null);
  const pendingClearRef = useRef<(() => void) | null>(null);
  const onUserMoveRef = useRef(onUserMove);

  const programmaticMove = useCallback(
    (fn: () => void) => {
      if (!map) {
        fn();
        return;
      }
      programmaticRef.current = true;
      if (timerRef.current != null) window.clearTimeout(timerRef.current);
      if (pendingClearRef.current) map.off('moveend', pendingClearRef.current);
      const clear = () => {
        programmaticRef.current = false;
        if (timerRef.current != null) {
          window.clearTimeout(timerRef.current);
          timerRef.current = null;
        }
        pendingClearRef.current = null;
      };
      pendingClearRef.current = clear;
      map.once('moveend', clear);
      fn();
      timerRef.current = window.setTimeout(clear, 1200);
    },
    [map]
  );

  useEffect(() => {
    onUserMoveRef.current = onUserMove;
  });

  useEffect(() => {
    if (!map) return;
    programmaticRef.current = false;
    if (timerRef.current != null) {
      window.clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    pendingClearRef.current = null;
    const handleUserMove = () => {
      if (!programmaticRef.current) {
        userMovedRef.current = true;
        onUserMoveRef.current?.();
      }
    };
    map.on('dragstart', handleUserMove);
    map.on('zoomstart', handleUserMove);
    map.on('rotatestart', handleUserMove);
    map.on('pitchstart', handleUserMove);
    return () => {
      map.off('dragstart', handleUserMove);
      map.off('zoomstart', handleUserMove);
      map.off('rotatestart', handleUserMove);
      map.off('pitchstart', handleUserMove);
      if (pendingClearRef.current) map.off('moveend', pendingClearRef.current);
      if (timerRef.current != null) window.clearTimeout(timerRef.current);
      programmaticRef.current = false;
      pendingClearRef.current = null;
    };
  }, [map]);

  const resetUserMoved = useCallback(() => {
    userMovedRef.current = false;
  }, []);

  return { programmaticMove, userMovedRef, resetUserMoved };
}
