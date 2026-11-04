import { describe, expect, it } from 'vitest';
import { calculateBreakInfo } from './schedule-break';

describe('calculateBreakInfo', () => {
  it('returns null when times are missing or invalid', () => {
    expect(calculateBreakInfo(undefined, '10:00')).toBeNull();
    expect(calculateBreakInfo('09:00', undefined)).toBeNull();
    expect(calculateBreakInfo('', '')).toBeNull();
    expect(calculateBreakInfo('invalid', '10:00')).toBeNull();
  });

  it('returns null when there is no gap or negative gap', () => {
    expect(calculateBreakInfo('10:00', '10:00')).toBeNull();
    expect(calculateBreakInfo('10:30', '10:00')).toBeNull();
  });

  it('correctly calculates break duration with hours and minutes (e.g. 7h 50m)', () => {
    const breakInfo = calculateBreakInfo('09:30', '17:20');
    expect(breakInfo).not.toBeNull();
    expect(breakInfo?.gapMinutes).toBe(470);
    expect(breakInfo?.durationLabel).toBe('7h 50m');
    expect(breakInfo?.startTime).toBe('09:30');
    expect(breakInfo?.endTime).toBe('17:20');
  });

  it('correctly formats whole hours (e.g. 2h)', () => {
    const breakInfo = calculateBreakInfo('10:00', '12:00');
    expect(breakInfo).not.toBeNull();
    expect(breakInfo?.gapMinutes).toBe(120);
    expect(breakInfo?.durationLabel).toBe('2h');
  });

  it('correctly formats minutes only (e.g. 45m)', () => {
    const breakInfo = calculateBreakInfo('10:00', '10:45');
    expect(breakInfo).not.toBeNull();
    expect(breakInfo?.gapMinutes).toBe(45);
    expect(breakInfo?.durationLabel).toBe('45m');
  });
});
