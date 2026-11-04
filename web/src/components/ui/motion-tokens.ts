/**
 * Shared easing curves for Framer Motion transitions.
 *
 * These strong custom curves are deliberate — the built-in CSS easings
 * (`ease-out`, `ease-in-out`) are too weak for interface motion. The CSS
 * custom properties in `src/styles/globals.css` (`--ease-out`, …) mirror these
 * values for CSS transitions; keep the two in sync.
 *
 * Typed as 4-tuples so they satisfy Framer Motion's cubic-bezier definition.
 */

/** Entrances and exits: starts fast, feels responsive. */
export const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];

/** On-screen movement / morphing. */
export const EASE_IN_OUT: [number, number, number, number] = [0.77, 0, 0.175, 1];

/** Drawers and sheets (iOS-like). */
export const EASE_DRAWER: [number, number, number, number] = [0.32, 0.72, 0, 1];
