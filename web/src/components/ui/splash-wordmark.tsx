import { motion, useReducedMotion } from 'framer-motion';
import { EASE_OUT } from './motion-tokens';

/**
 * Injected by Vite at build time (see vite.config.ts `define`): the version
 * from package.json, or VITE_APP_VERSION set at build time.
 */
declare const __APP_VERSION__: string;

/**
 * The Vectra wordmark on the boot splash: the wordmark rises and scales in,
 * then the build version fades up. On `exiting` both drift up and fade with
 * the splash's sunrise exit.
 *
 * Only opacity and transforms are animated — no filters, no backdrop-filter,
 * no per-letter spans — so every platform renders it the same way.
 */
export function SplashWordmark({ exiting = false }: { exiting?: boolean }) {
  const reduceMotion = useReducedMotion();
  const exit = { opacity: 0, y: reduceMotion ? 0 : -36 };
  const exitTransition = { duration: reduceMotion ? 0.15 : 0.5, ease: EASE_OUT };

  return (
    <>
      <motion.span
        initial={reduceMotion ? { opacity: 0 } : { opacity: 0, y: 16, scale: 0.95 }}
        animate={exiting ? exit : { opacity: 1, y: 0, scale: 1 }}
        transition={
          exiting
            ? exitTransition
            : reduceMotion
              ? { duration: 0.15, ease: 'easeOut' }
              : { duration: 0.5, delay: 0.15, ease: EASE_OUT }
        }
        className="select-none text-5xl sm:text-6xl font-black tracking-tight text-primary"
      >
        Vectra
      </motion.span>

      {/* Build version fades up once the wordmark has landed */}
      <motion.span
        initial={reduceMotion ? { opacity: 0 } : { opacity: 0, y: 8 }}
        animate={exiting ? exit : { opacity: 1, y: 0 }}
        transition={
          exiting
            ? exitTransition
            : reduceMotion
              ? { duration: 0.15, ease: 'easeOut' }
              : { duration: 0.35, delay: 0.45, ease: EASE_OUT }
        }
        className="mt-4 select-none text-xs font-medium tracking-widest text-muted tabular-nums"
      >
        v{__APP_VERSION__}
      </motion.span>
    </>
  );
}
