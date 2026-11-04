import { describe, it, expect } from 'vitest';
import { shouldShowSemesterStatus } from './semester-status-banner-logic';
import type { SemesterInfo } from './semester-status-banner';

const status = (status: SemesterInfo['status']): SemesterInfo => ({ status });

describe('shouldShowSemesterStatus', () => {
  it('shows the banner while a semester is upcoming', () => {
    expect(shouldShowSemesterStatus(status('upcoming'), '2026/27/1')).toBe(true);
  });

  it('shows the banner for a past semester the user picked', () => {
    expect(shouldShowSemesterStatus(status('past'), '2025/26/2')).toBe(true);
  });

  it('hides the past-semester banner in the all-semesters view', () => {
    expect(shouldShowSemesterStatus(status('past'), 'all')).toBe(false);
  });

  it('hides the banner for the current semester', () => {
    expect(shouldShowSemesterStatus(status('current'), '2026/27/1')).toBe(false);
    expect(shouldShowSemesterStatus(status('active'), '2026/27/1')).toBe(false);
  });
});
