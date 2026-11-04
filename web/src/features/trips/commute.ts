import type { TripPlan } from './types';

export type CommuteKind = 'to_uni' | 'to_home';

export interface CommuteSession {
  startMinutes: number;
  endMinutes: number;
}

export interface CommuteResolution {
  kind: CommuteKind;
  trip: TripPlan;
}

export const COMMUTE_LEAD_MINUTES = 60;
export const COMMUTE_GRACE_MINUTES = 30;
export const LONG_BREAK_MINUTES = 120;
export const AFTER_LAST_CLASS_MINUTES = 60;

/** 'HH:mm' → minutes since midnight; null when the string isn't a valid time. */
export function minutesOfDay(time: string | null | undefined): number | null {
  if (!time) return null;
  const match = /^(\d{1,2}):(\d{2})$/.exec(time);
  if (!match) return null;
  const hours = Number(match[1]);
  const minutes = Number(match[2]);
  if (hours > 23 || minutes > 59) return null;
  return hours * 60 + minutes;
}

/** Today's sessions as sorted minute ranges — malformed or empty ranges dropped. */
export function buildCommuteSessions(
  events: Array<{ startTime?: string | null; endTime?: string | null }>
): CommuteSession[] {
  const sessions: CommuteSession[] = [];
  for (const event of events) {
    const startMinutes = minutesOfDay(event.startTime);
    const endMinutes = minutesOfDay(event.endTime);
    if (startMinutes == null || endMinutes == null || endMinutes <= startMinutes) continue;
    sessions.push({ startMinutes, endMinutes });
  }
  return sessions.sort((a, b) => a.startMinutes - b.startMinutes);
}

export function resolveCommute({
  sessions,
  toUniTrip,
  toHomeTrip,
  nowMinutes,
}: {
  sessions: CommuteSession[];
  toUniTrip: TripPlan | null;
  toHomeTrip: TripPlan | null;
  nowMinutes: number;
}): CommuteResolution | null {
  const ordered = [...sessions].sort((a, b) => a.startMinutes - b.startMinutes);

  if (toUniTrip) {
    // Only sessions the user actually travels in for: the day's first one, and
    // any that follows a long break (when the home card sent them home).
    // Between back-to-back classes they are already on campus.
    const arrivals: CommuteSession[] = [];
    let unionEnd = -Infinity;
    for (const session of ordered) {
      if (session.startMinutes - unionEnd > LONG_BREAK_MINUTES) arrivals.push(session);
      unionEnd = Math.max(unionEnd, session.endMinutes);
    }
    const inToUniWindow = arrivals.some(
      (session) =>
        nowMinutes >= session.startMinutes - COMMUTE_LEAD_MINUTES &&
        nowMinutes <= session.startMinutes + COMMUTE_GRACE_MINUTES
    );
    if (inToUniWindow) return { kind: 'to_uni', trip: toUniTrip };
  }

  if (toHomeTrip && ordered.length > 0) {
    const lastEnd = Math.max(...ordered.map((session) => session.endMinutes));
    if (nowMinutes >= lastEnd && nowMinutes <= lastEnd + AFTER_LAST_CLASS_MINUTES) {
      return { kind: 'to_home', trip: toHomeTrip };
    }
    let runningEnd = ordered[0].endMinutes;
    for (let i = 1; i < ordered.length; i++) {
      const next = ordered[i];
      if (
        next.startMinutes - runningEnd > LONG_BREAK_MINUTES &&
        nowMinutes >= runningEnd &&
        nowMinutes < next.startMinutes
      ) {
        return { kind: 'to_home', trip: toHomeTrip };
      }
      runningEnd = Math.max(runningEnd, next.endMinutes);
    }
  }

  return null;
}
