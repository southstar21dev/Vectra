import { describe, expect, it } from 'vitest';
import { buildCommuteSessions, minutesOfDay, resolveCommute } from './commute';
import type { TripPlan } from './types';

function trip(id: string): TripPlan {
  return { id, userId: 'u1', name: id, legs: [], sortOrder: 0, createdAt: '' };
}

const toUni = trip('to-uni');
const toHome = trip('to-home');

describe('minutesOfDay', () => {
  it('parses HH:mm into minutes since midnight', () => {
    expect(minutesOfDay('00:00')).toBe(0);
    expect(minutesOfDay('09:05')).toBe(545);
    expect(minutesOfDay('23:59')).toBe(1439);
  });

  it('returns null for missing or malformed times', () => {
    expect(minutesOfDay(null)).toBeNull();
    expect(minutesOfDay('9:5')).toBeNull();
    expect(minutesOfDay('24:00')).toBeNull();
    expect(minutesOfDay('10:60')).toBeNull();
    expect(minutesOfDay('noon')).toBeNull();
  });
});

describe('buildCommuteSessions', () => {
  it('sorts sessions by start time', () => {
    const sessions = buildCommuteSessions([
      { startTime: '12:00', endTime: '14:00' },
      { startTime: '08:00', endTime: '10:00' },
    ]);
    expect(sessions.map((s) => s.startMinutes)).toEqual([480, 720]);
  });

  it('drops malformed and zero/negative-length sessions', () => {
    const sessions = buildCommuteSessions([
      { startTime: '08:00', endTime: '10:00' },
      { startTime: 'oops', endTime: '11:00' },
      { startTime: '11:00', endTime: '11:00' },
      { startTime: undefined, endTime: '12:00' },
    ]);
    expect(sessions).toEqual([{ startMinutes: 480, endMinutes: 600 }]);
  });
});

describe('resolveCommute', () => {
  const classA = { startMinutes: 600, endMinutes: 660 }; // 10:00–11:00
  const classB = { startMinutes: 780, endMinutes: 840 }; // 13:00–14:00 (exactly 2h break)
  const classC = { startMinutes: 781, endMinutes: 840 }; // 13:01–14:00 (2h01 break)

  it('shows to uni from 60 min before class until 30 min after it starts', () => {
    const input = { sessions: [classA], toUniTrip: toUni, toHomeTrip: null };
    expect(resolveCommute({ ...input, nowMinutes: 539 })).toBeNull();
    expect(resolveCommute({ ...input, nowMinutes: 540 })).toEqual({ kind: 'to_uni', trip: toUni });
    expect(resolveCommute({ ...input, nowMinutes: 630 })).toEqual({ kind: 'to_uni', trip: toUni });
    expect(resolveCommute({ ...input, nowMinutes: 631 })).toBeNull();
  });

  it('treats exactly 2 hours as a short break with no home card', () => {
    expect(
      resolveCommute({ sessions: [classA, classB], toUniTrip: toUni, toHomeTrip: toHome, nowMinutes: 690 })
    ).toBeNull();
  });

  it('shows the home card during a break longer than 2 hours', () => {
    expect(
      resolveCommute({ sessions: [classA, classC], toUniTrip: toUni, toHomeTrip: toHome, nowMinutes: 690 })
    ).toEqual({ kind: 'to_home', trip: toHome });
  });

  it('gives the break’s final hour to the to-uni window', () => {
    const input = { sessions: [classA, classC], toUniTrip: toUni, toHomeTrip: toHome };
    expect(resolveCommute({ ...input, nowMinutes: 720 })).toEqual({ kind: 'to_home', trip: toHome });
    expect(resolveCommute({ ...input, nowMinutes: 721 })).toEqual({ kind: 'to_uni', trip: toUni });
  });

  it('runs the home card to the next class start when no to-uni trip is designated', () => {
    const input = { sessions: [classA, classC], toUniTrip: null, toHomeTrip: toHome };
    expect(resolveCommute({ ...input, nowMinutes: 780 })).toEqual({ kind: 'to_home', trip: toHome });
    expect(resolveCommute({ ...input, nowMinutes: 781 })).toBeNull();
  });

  it('shows the home card for 60 min after the last class', () => {
    const input = { sessions: [classA], toUniTrip: null, toHomeTrip: toHome };
    expect(resolveCommute({ ...input, nowMinutes: 659 })).toBeNull();
    expect(resolveCommute({ ...input, nowMinutes: 660 })).toEqual({ kind: 'to_home', trip: toHome });
    expect(resolveCommute({ ...input, nowMinutes: 720 })).toEqual({ kind: 'to_home', trip: toHome });
    expect(resolveCommute({ ...input, nowMinutes: 721 })).toBeNull();
  });

  it('resolves nothing without sessions or without a designated trip', () => {
    expect(resolveCommute({ sessions: [], toUniTrip: toUni, toHomeTrip: toHome, nowMinutes: 600 })).toBeNull();
    expect(resolveCommute({ sessions: [classA], toUniTrip: null, toHomeTrip: null, nowMinutes: 600 })).toBeNull();
  });

  it('cannot fabricate a break from nested or overlapping sessions', () => {
    // A 10:00–13:00 with B 11:00–11:30 nested inside it, then C 15:00–16:00.
    const input = {
      sessions: [
        { startMinutes: 600, endMinutes: 780 },
        { startMinutes: 660, endMinutes: 690 },
        { startMinutes: 900, endMinutes: 960 },
      ],
      toUniTrip: null,
      toHomeTrip: toHome,
    };
    // Mid-A: B's end must not masquerade as a gap while A is still running.
    expect(resolveCommute({ ...input, nowMinutes: 720 })).toBeNull();
    // The true union gap is 13:00 → 15:00 = exactly 120 min, not > 120.
    expect(resolveCommute({ ...input, nowMinutes: 780 })).toBeNull();
  });

  it('keeps a back-to-back block free of a home card', () => {
    const input = {
      sessions: [
        { startMinutes: 600, endMinutes: 660 },
        { startMinutes: 660, endMinutes: 720 },
      ],
      toUniTrip: null,
      toHomeTrip: toHome,
    };
    expect(resolveCommute({ ...input, nowMinutes: 665 })).toBeNull();
  });

  it('keeps both home windows on a multi-session day', () => {
    const input = {
      sessions: [
        { startMinutes: 600, endMinutes: 660 },
        { startMinutes: 781, endMinutes: 840 },
        { startMinutes: 960, endMinutes: 1020 },
      ],
      toUniTrip: null,
      toHomeTrip: toHome,
    };
    expect(resolveCommute({ ...input, nowMinutes: 700 })).toEqual({ kind: 'to_home', trip: toHome });
    expect(resolveCommute({ ...input, nowMinutes: 900 })).toBeNull();
    expect(resolveCommute({ ...input, nowMinutes: 1050 })).toEqual({ kind: 'to_home', trip: toHome });
  });

  it('shows to uni only before the first class or after a long break', () => {
    const input = {
      sessions: [
        { startMinutes: 600, endMinutes: 690 }, // 10:00–11:30
        { startMinutes: 705, endMinutes: 795 }, // 11:45–13:15 (15 min gap)
        { startMinutes: 960, endMinutes: 1050 }, // 16:00–17:30 (165 min gap)
      ],
      toUniTrip: toUni,
      toHomeTrip: toHome,
    };
    // Before the first class.
    expect(resolveCommute({ ...input, nowMinutes: 560 })).toEqual({ kind: 'to_uni', trip: toUni });
    // Between the back-to-back classes: already on campus, no to-uni card.
    expect(resolveCommute({ ...input, nowMinutes: 695 })).toBeNull();
    expect(resolveCommute({ ...input, nowMinutes: 720 })).toBeNull();
    // After the long break, the next class is an arrival again.
    expect(resolveCommute({ ...input, nowMinutes: 900 })).toEqual({ kind: 'to_uni', trip: toUni });
  });

  it('measures the break from the union of overlapping sessions', () => {
    const input = {
      sessions: [
        { startMinutes: 600, endMinutes: 780 }, // 10:00–13:00
        { startMinutes: 660, endMinutes: 690 }, // nested
        { startMinutes: 840, endMinutes: 900 }, // 14:00 — only 60 min after the union end
      ],
      toUniTrip: toUni,
      toHomeTrip: null,
    };
    expect(resolveCommute({ ...input, nowMinutes: 800 })).toBeNull();
  });
});
