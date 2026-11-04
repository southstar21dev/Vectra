import type { GradingScaleTier } from './tracker-types';

/**
 * Hungarian keyboards produce decimal commas ("12,5"). Treat comma and dot as
 * the same separator, keep a single decimal point, and leave clamping to the
 * commit step — flipping a partly typed "0" into "0.1" mid-keystroke made
 * values like "0.5" impossible to enter.
 */
export function normalizeDecimalInput(raw: string): string {
  const withDots = raw.replace(/,/g, '.');
  const cleaned = withDots.replace(/[^0-9.]/g, '');
  const parts = cleaned.split('.');
  return parts.length > 2 ? `${parts[0]}.${parts.slice(1).join('')}` : cleaned;
}

/**
 * Parses a draft field value. Returns null for empty input (or a lone
 * separator) and for values that do not survive `Number.isFinite`, so NaN can
 * never reach stored assessment or settings data. Raw decimal commas are
 * accepted even if a caller skipped the display normalization.
 */
export function parseFiniteNumber(raw: string): number | null {
  const normalized = normalizeDecimalInput(raw).trim();
  if (!normalized || normalized === '.') return null;
  const num = Number.parseFloat(normalized);
  return Number.isFinite(num) ? num : null;
}

export function clampNumber(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

/**
 * Grade cutoffs must be monotonic: Grade 5 ≥ Grade 4 ≥ Grade 3 ≥ Grade 2 ≥
 * Grade 1 (0). An out-of-order scale silently misgrades every estimate in the
 * Points tab, so the Settings tab blocks the write and explains what to fix.
 */
export function validateScaleOrder(scale: GradingScaleTier[]): string | null {
  const min = (grade: number) => scale.find((t) => t.grade === grade)?.minPercentage ?? 0;
  const pairs: Array<[number, number]> = [
    [5, 4],
    [4, 3],
    [3, 2],
    [2, 1],
  ];
  for (const [higher, lower] of pairs) {
    if (min(higher) < min(lower)) {
      return `Grade ${higher} cutoff must be at least the Grade ${lower} cutoff (${min(lower)}%).`;
    }
  }
  return null;
}
