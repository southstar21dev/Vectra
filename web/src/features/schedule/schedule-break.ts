export interface BreakInfo {
  startTime: string;
  endTime: string;
  gapMinutes: number;
  durationLabel: string;
}

/**
 * Calculates gap interval between consecutive class blocks.
 */
export function calculateBreakInfo(
  curEndTime?: string,
  nextStartTime?: string
): BreakInfo | null {
  if (!curEndTime || !nextStartTime) return null;
  const [eH, eM] = curEndTime.split(':').map(Number);
  const [sH, sM] = nextStartTime.split(':').map(Number);
  if (isNaN(eH) || isNaN(eM) || isNaN(sH) || isNaN(sM)) return null;

  const curEndMin = eH * 60 + eM;
  const nextStartMin = sH * 60 + sM;
  const gap = nextStartMin - curEndMin;

  if (gap <= 0) return null;

  const hours = Math.floor(gap / 60);
  const mins = gap % 60;
  const durationLabel =
    hours > 0
      ? mins > 0
        ? `${hours}h ${mins}m`
        : `${hours}h`
      : `${mins}m`;

  return {
    startTime: curEndTime,
    endTime: nextStartTime,
    gapMinutes: gap,
    durationLabel,
  };
}
