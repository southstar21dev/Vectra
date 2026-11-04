import type { ReactNode } from 'react';
import { motion, useReducedMotion } from 'framer-motion';
import { REVEAL_RISE_PX, REVEAL_TRANSITION, revealDelay } from './reveal-motion';
import { useAppPresented } from '@/hooks/use-app-presented';

interface RevealProps {
  /** Position in the entrance order (0-based, reading order) — drives the stagger. */
  index: number;
  /** Render as this element so page layouts keep the element they expect. */
  as?: 'div' | 'section';
  className?: string;
  children: ReactNode;
  /** Optional callback ref for measuring the revealed element. */
  ref?: (el: HTMLElement | null) => void;
}

/**
 * One card's entrance, used by every page: it fades and rises into place,
 * staggered behind the cards before it. Pages swap instantly (no page-level
 * transition), so this per-card entrance is what animates on navigation.
 *
 * The reveal is held until the boot splash clears (`useAppPresented`), so on a
 * cold start it plays as the splash fades rather than behind it. Pass the card's
 * own classes to `Reveal` itself (it renders the element, so the DOM keeps its
 * exact shape). Only opacity and a `transform` string animate, so the entrance
 * is hardware-accelerated. Reduced-motion users get the
 * plain element with no wrapper.
 */
export function Reveal({ index, as = 'div', className, children, ref }: RevealProps) {
  const reduceMotion = useReducedMotion();
  const presented = useAppPresented();

  if (reduceMotion) {
    return as === 'section' ? (
      <section className={className}>{children}</section>
    ) : (
      <div className={className}>{children}</div>
    );
  }

  // Full transform strings (not `y`) so Motion runs this on the compositor.
  const hidden = { opacity: 0, transform: `translateY(${REVEAL_RISE_PX}px)` };
  // `transitionEnd` drops the settled transform so a finished card holds no
  // layer and is not a containing block for fixed-position children.
  const settled = { opacity: 1, transform: 'translateY(0px)', transitionEnd: { transform: 'none' } };
  const transition = { ...REVEAL_TRANSITION, delay: revealDelay(index) };

  return as === 'section' ? (
    <motion.section
      ref={ref}
      className={className}
      initial={hidden}
      animate={presented ? settled : hidden}
      transition={transition}
    >
      {children}
    </motion.section>
  ) : (
    <motion.div
      ref={ref}
      className={className}
      initial={hidden}
      animate={presented ? settled : hidden}
      transition={transition}
    >
      {children}
    </motion.div>
  );
}
