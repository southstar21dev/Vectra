import type { GradingScaleTier } from './tracker-types';

export type Grade = 1 | 2 | 3 | 4 | 5;

/**
 * One hue per grade, shared by the grade card, the assessment scores and the
 * Rules cutoffs so a grade reads the same colour everywhere. `text` is a theme
 * token (it has to hold contrast on light surfaces too); fills use the raw
 * palette, which reads on both. `dim` is the fill at the strength the grade
 * scale uses for the grades below the current one.
 */
export const GRADE_TONES: Record<Grade, { fill: string; dim: string; zone: string; text: string }> = {
  5: { fill: 'bg-emerald-500', dim: 'bg-emerald-500/45', zone: 'bg-emerald-500/75', text: 'text-tint-emerald' },
  4: { fill: 'bg-sky-500', dim: 'bg-sky-500/45', zone: 'bg-sky-500/75', text: 'text-tint-sky' },
  3: { fill: 'bg-amber-500', dim: 'bg-amber-500/45', zone: 'bg-amber-500/75', text: 'text-tint-amber' },
  2: { fill: 'bg-orange-500', dim: 'bg-orange-500/45', zone: 'bg-orange-500/75', text: 'text-tint-amber' },
  1: { fill: 'bg-red-500', dim: 'bg-red-500/45', zone: 'bg-red-500/35', text: 'text-tint-red' },
};

/** What each grade means on the Hungarian five-point scale. */
export const GRADE_NAMES: Record<Grade, string> = {
  5: 'Excellent',
  4: 'Good',
  3: 'Satisfactory',
  2: 'Pass',
  1: 'Fail',
};

/** The grade a percentage earns on `scale` (1 when it clears no cutoff). */
export function gradeForPercent(percent: number, scale: GradingScaleTier[]): Grade {
  return scale
    .filter((t) => t.grade >= 2 && percent >= t.minPercentage)
    .reduce<Grade>((best, t) => (t.grade > best ? t.grade : best), 1);
}
