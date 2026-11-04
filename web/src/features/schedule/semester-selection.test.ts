import { describe, expect, it } from 'vitest';
import { newestStoredSemester, selectSyncSemester } from './semester-selection';

const ev = (semester: string, semester_end: string) => ({ semester, semester_end });
// Budapest noon, so the calendar date is unambiguous.
const at = (ymd: string) => new Date(`${ymd}T10:00:00Z`);

const FEED = [
  ev('2024/25/2', '2025-05-16'),
  ev('2025/26/1', '2025-12-12'),
  ev('2025/26/1', '2026-01-20'), // January exam
  ev('2025/26/2', '2026-05-15'),
  ev('2026/27/1', '2026-12-11'),
];

describe('selectSyncSemester', () => {
  it('picks the semester in session mid-semester', () => {
    expect(selectSyncSemester(FEED, { at: at('2025-10-15') })).toBe('2025/26/1');
    expect(selectSyncSemester(FEED, { at: at('2026-03-10') })).toBe('2025/26/2');
  });

  it('keeps the autumn semester in January while its exams still run', () => {
    expect(selectSyncSemester(FEED, { at: at('2026-01-12') })).toBe('2025/26/1');
  });

  it('moves on to spring in late January once autumn has ended', () => {
    expect(selectSyncSemester(FEED, { at: at('2026-01-28') })).toBe('2025/26/2');
  });

  it('picks spring in February', () => {
    expect(selectSyncSemester(FEED, { at: at('2026-02-02') })).toBe('2025/26/2');
  });

  it('picks the published autumn semester in July after spring has ended', () => {
    expect(selectSyncSemester(FEED, { at: at('2026-07-10') })).toBe('2026/27/1');
  });

  it('stays on an ended current semester in July when autumn is not published yet', () => {
    const feed = FEED.filter((e) => e.semester !== '2026/27/1');
    expect(selectSyncSemester(feed, { at: at('2026-07-10') })).toBe('2025/26/2');
  });

  it('falls back to the nearest upcoming semester when the current one is missing', () => {
    const feed = [ev('2026/27/1', '2026-12-11'), ev('2027/28/1', '2027-12-10')];
    expect(selectSyncSemester(feed, { at: at('2026-07-10') })).toBe('2026/27/1');
  });

  it('returns null for a feed with only past semesters', () => {
    expect(selectSyncSemester([ev('2024/25/2', '2025-05-16')], { at: at('2025-10-15') })).toBeNull();
  });

  it('never replaces a stored semester with an older one', () => {
    // Synced in July into autumn; a later sync must not fall back to spring.
    expect(
      selectSyncSemester(FEED.filter((e) => e.semester !== '2026/27/1').concat(ev('2026/27/1', '2026-12-11')), {
        at: at('2026-07-01'),
        storedSemester: '2026/27/1',
      })
    ).toBe('2026/27/1');
    // The newer stored semester vanished from the feed: keep what is stored.
    const withoutAutumn = FEED.filter((e) => e.semester !== '2026/27/1');
    expect(selectSyncSemester(withoutAutumn, { at: at('2026-07-10'), storedSemester: '2026/27/1' })).toBeNull();
    // A newer pick still replaces an older stored semester.
    expect(selectSyncSemester(FEED, { at: at('2026-02-02'), storedSemester: '2025/26/1' })).toBe('2025/26/2');
  });
});

describe('newestStoredSemester', () => {
  it('reads only Neptun rows', () => {
    expect(
      newestStoredSemester([
        { semester: '2025/26/1', source: 'neptun' },
        { semester: '2026/27/1', source: 'manual' },
        { semester: '2025/26/2', source: 'neptun' },
      ])
    ).toBe('2025/26/2');
    expect(newestStoredSemester([])).toBeNull();
  });
});
