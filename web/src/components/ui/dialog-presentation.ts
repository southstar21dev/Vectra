import type { CSSProperties } from 'react';
import type { MotionProps } from 'framer-motion';

export type DialogPresentation = 'auto' | 'dialog';

export interface DialogPresentationInput {
  presentation: DialogPresentation;
  compact: boolean;
  edgeToEdge: boolean;
  reduceMotion: boolean;
}

export type DialogMotion = Pick<MotionProps, 'initial' | 'animate' | 'exit' | 'transition'>;

export interface DialogPresentationResult {
  isFullscreen: boolean;
  panelStyle: CSSProperties | undefined;
  closeButtonStyle: CSSProperties | undefined;
  motion: DialogMotion;
}

const SAFE_AREA_TOP = 'max(0.75rem, var(--safe-area-top, env(safe-area-inset-top, 0px)))';
const SAFE_AREA_CONTENT_TOP = 'max(1rem, var(--safe-area-top, env(safe-area-inset-top, 0px)))';
const SAFE_AREA_CONTENT_BOTTOM = 'max(1rem, env(safe-area-inset-bottom, 0px))';

const FADE_MOTION: DialogMotion = {
  initial: { opacity: 0 },
  animate: { opacity: 1 },
  exit: { opacity: 0 },
  transition: { duration: 0.15 },
};

// Centred dialogs (desktop, and confirmations everywhere): grow out of the tap
// point (transform-origin is set by DialogContent) with a soft spring.
const DESKTOP_MOTION: DialogMotion = {
  initial: { opacity: 0, scale: 0.9 },
  animate: { opacity: 1, scale: 1 },
  exit: { opacity: 0, scale: 0.9 },
  transition: { type: 'spring', stiffness: 420, damping: 34, mass: 0.9 },
};

// Full-screen on mobile: expand from the tap point to fill the screen — the
// dialog grows out of the control that opened it rather than sliding in from an
// edge.
const COMPACT_MOTION: DialogMotion = {
  initial: { opacity: 0, scale: 0.96 },
  animate: { opacity: 1, scale: 1 },
  exit: { opacity: 0, scale: 0.98 },
  transition: { type: 'spring', stiffness: 420, damping: 38, mass: 0.9 },
};

/**
 * Structural overrides use inline styles, not classes: consumers pass
 * responsive utilities like `sm:max-w-5xl` / `sm:p-6`, and Tailwind's
 * variant rules win over base utilities regardless of merge order. Inline
 * styles always win over both.
 */
export function resolveDialogPresentation(input: DialogPresentationInput): DialogPresentationResult {
  const isFullscreen = input.presentation === 'auto' && input.compact;

  if (!isFullscreen) {
    return {
      isFullscreen,
      panelStyle: undefined,
      closeButtonStyle: undefined,
      motion: input.reduceMotion ? FADE_MOTION : DESKTOP_MOTION,
    };
  }

  return {
    isFullscreen,
    panelStyle: {
      // Fixed positioning escapes the Dialog wrapper's padding so the panel
      // can cover the viewport edge to edge (100dvh, notch included).
      position: 'fixed',
      inset: 0,
      width: '100%',
      height: '100dvh',
      maxHeight: 'none',
      maxWidth: 'none',
      margin: 0,
      // Match the physical display radius on iOS home-screen apps (0px
      // fallback everywhere else, so desktop keeps square corners).
      borderRadius: 'var(--screen-radius, 0px)',
      borderWidth: 0,
      ...(input.edgeToEdge
        ? null
        : {
            paddingTop: SAFE_AREA_CONTENT_TOP,
            paddingBottom: SAFE_AREA_CONTENT_BOTTOM,
          }),
    },
    closeButtonStyle: { top: SAFE_AREA_TOP },
    motion: input.reduceMotion ? FADE_MOTION : COMPACT_MOTION,
  };
}
