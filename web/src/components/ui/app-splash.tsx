import { useEffect, useState } from 'react';
import { motion, useReducedMotion } from 'framer-motion';
import { shouldDismissSplash, splashExitDelay, watchAppReady } from './app-splash-logic';
import { markAppPresented } from '@/lib/app-presented';
import { cn } from '@/lib/utils';
import { SplashWordmark } from './splash-wordmark';
import { SplashSkylineCanvas } from './splash-skyline-canvas';
import { SUNRISE_EXIT_MS } from './splash-scene';

/** Reduced-motion exit: a short fade of the whole overlay. */
const REDUCED_EXIT_MS = 200;

/**
 * Full-screen boot splash: a faint dotted orb rising over a dotted Budapest
 * skyline, with the "Vectra" wordmark and build version above it.
 *
 * The city's lights come on left to right while the wordmark rises in. Once
 * the exit delay has elapsed AND the app's first content has rendered, the
 * sunrise exit plays, ending ~2.5s after boot (later only on a slow load): the orb rises out through the top, the city sinks
 * below the waterline, the wordmark drifts up, and the app shows through as
 * the backdrop fades. Reduced motion gets a still frame and a short fade.
 * Rendered once at app boot (PWA + Android APK alike); in-app navigation
 * cross-fades between routes instead.
 */
export function AppSplash() {
  const [minElapsed, setMinElapsed] = useState(false);
  const [appReady, setAppReady] = useState(false);
  const [done, setDone] = useState(false);
  const reduceMotion = useReducedMotion();
  const exitMs = reduceMotion ? REDUCED_EXIT_MS : SUNRISE_EXIT_MS;

  useEffect(() => {
    const t = setTimeout(() => setMinElapsed(true), splashExitDelay(exitMs));
    return () => clearTimeout(t);
  }, [exitMs]);

  useEffect(() => watchAppReady(() => setAppReady(true)), []);

  // Announce the clear *before* the fade starts, so a reveal that waits on the
  // first screen plays underneath the fading splash instead of after it (which
  // would be a fade onto a blank page, then a late pop-in).
  const dismiss = shouldDismissSplash(minElapsed, appReady);
  useEffect(() => {
    if (dismiss) markAppPresented();
  }, [dismiss]);

  useEffect(() => {
    if (!dismiss) return;
    const t = setTimeout(() => setDone(true), exitMs);
    return () => clearTimeout(t);
  }, [dismiss, exitMs]);

  if (done) return null;

  return (
    <motion.div
      className={cn('fixed inset-0 z-[100] overflow-hidden', dismiss && 'pointer-events-none')}
      initial={false}
      animate={{ opacity: dismiss && reduceMotion ? 0 : 1 }}
      transition={{ duration: REDUCED_EXIT_MS / 1000, ease: 'easeOut' as const }}
      aria-label="Vectra"
    >
      {/* The backdrop fades first, so the app shows through while the scene exits. */}
      <motion.div
        aria-hidden
        className="absolute inset-0 bg-surface-primary"
        initial={false}
        animate={{ opacity: dismiss ? 0 : 1 }}
        transition={{ duration: 0.35, ease: 'easeOut' as const }}
      />
      <SplashSkylineCanvas exiting={dismiss} still={!!reduceMotion} />
      {/* Sits above the centre so it clears the statue on Gellért Hill. */}
      <div className="relative flex h-full flex-col items-center justify-center pb-[22vh]">
        <SplashWordmark exiting={dismiss} />
      </div>
    </motion.div>
  );
}
