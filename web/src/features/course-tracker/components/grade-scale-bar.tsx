import { cn } from '@/lib/utils';
import { GRADE_TONES, type Grade } from '../grades';
import type { GradingScaleTier } from '../tracker-types';

interface Zone {
  grade: Grade;
  start: number;
  end: number;
}

/** The scale as contiguous bands from 0 to 100, lowest grade first. */
function zonesFor(scale: GradingScaleTier[]): Zone[] {
  const cutoffs = scale
    .filter((t) => t.grade >= 2 && t.minPercentage > 0 && t.minPercentage < 100)
    .sort((a, b) => a.minPercentage - b.minPercentage);
  const zones: Zone[] = [];
  let start = 0;
  let grade: Grade = 1;
  for (const tier of cutoffs) {
    if (tier.minPercentage > start) zones.push({ grade, start, end: tier.minPercentage });
    start = tier.minPercentage;
    grade = tier.grade;
  }
  zones.push({ grade, start, end: 100 });
  return zones;
}

interface GradeScaleBarProps {
  scale: GradingScaleTier[];
  ariaLabel: string;
  className?: string;
}

/**
 * The grading scale as a banded track, each band in its grade's hue, with the
 * percentage every band starts at written underneath.
 */
export function GradeScaleBar({ scale, ariaLabel, className }: GradeScaleBarProps) {
  const zones = zonesFor(scale);

  return (
    <div className={className}>
      <div className="flex h-3.5 gap-[3px] overflow-hidden rounded-full" role="img" aria-label={ariaLabel}>
        {zones.map((zone) => (
          <span
            key={zone.grade}
            className={cn('h-full', GRADE_TONES[zone.grade].zone)}
            style={{ width: `${zone.end - zone.start}%` }}
          />
        ))}
      </div>
      <div className="relative mt-1.5 h-3.5 font-mono text-[10px] tabular-nums text-muted" aria-hidden="true">
        {zones.map((zone) => (
          <span
            key={zone.grade}
            className={cn('absolute', zone.start > 0 && '-translate-x-1/2')}
            style={{ left: `${zone.start}%` }}
          >
            {zone.start}
          </span>
        ))}
      </div>
    </div>
  );
}
