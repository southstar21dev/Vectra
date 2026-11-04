/**
 * Tiny haptics helper for premium-feeling touch feedback.
 *
 * Uses the Vibration API (navigator.vibrate), which works on Android
 * Chrome and Android PWAs. iOS Safari/PWA does not expose the Vibration API,
 * so on iOS this is a no-op — the visual press animations carry the feel
 * instead.
 */

let lastVibrateAt = 0;

/**
 * Fire a short vibration, if supported.
 *
 * @param pattern Vibration pattern in ms. A single number = one pulse;
 *                an array = pulses with pauses, e.g. [15, 40, 15] = double-tap.
 */
export function vibrate(pattern: number | number[] = 15): void {
  // NOTE: intentionally NOT gated on prefers-reduced-motion. Haptics are
  // tactile feedback, not visual motion, and Android devices frequently
  // report reduced-motion (e.g. Samsung "Remove animations", battery saver),
  // which would silently kill all vibration.

  // Rate-limit: ignore calls within 60ms of the last one so rapid taps
  // don't stack vibrations into a long buzz.
  const now = Date.now();
  if (now - lastVibrateAt < 60) return;
  lastVibrateAt = now;

  if (typeof navigator === 'undefined' || typeof navigator.vibrate !== 'function') return;

  try {
    navigator.vibrate(pattern);
  } catch {
    // Haptics are cosmetic, never critical.
  }
}
