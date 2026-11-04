import { normalizeCourseKey, sessionSlotKey, type AttendanceSession, type CourseIdentity, type SessionType } from './tracker-types';
import { genId } from '@/lib/id';
import type { ScheduleEvent } from '@/features/schedule/use-schedule';
import { getEventFrequency } from '@/features/schedule/use-schedule';
import { getClassType, isIntensiveCourseEvents } from '@/lib/utils';
import { isExamEvent } from '@/features/schedule/exam-detection';
import { isHungarianPublicHoliday } from '@/features/holidays/hungarian-holidays';
import { isYmdString, nowInBudapest } from '@/lib/date-utils';

export interface GenerateSessionParams {
  dayOfWeek: number; // 0=Sun, 1=Mon, ..., 6=Sat
  startTime?: string;
  endTime?: string;
  semesterStart?: string | null;
  semesterEnd?: string | null;
  sessionType?: SessionType;
  frequency?: 'weekly' | 'even' | 'odd';
  existingSessions?: AttendanceSession[];
  /** Slot keys of sessions the student deleted; they are never regenerated. */
  removedSessionKeys?: string[];
}

export interface AttendanceStats {
  totalSessions: number;
  presentCount: number;
  absentCount: number;
  excusedCount: number;
  cancelledCount: number;
  unmarkedCount: number;
  activeCount: number; // present + absent
  /**
   * Sessions attendance is measured against: every session except the ones
   * that must not count against the student — cancelled classes (they never
   * happened), excused absences (already justified) and unmarked sessions
   * that are still in the future (they have not happened yet, so "0/24
   * attended" on day one is not information). Unmarked sessions up to and
   * including today DO count, so forgetting to mark a past class can never
   * leave the percentage at a flattering 100%.
   */
  countedSessions: number;
  attendancePercent: number; // 0-100%
  absencesRemaining: number | null;
  isBelowMinPercent: boolean;
  isOverAbsenceLimit: boolean;
}

export type AttendanceRiskTier = 'unknown' | 'safe' | 'warning' | 'critical';

function formatDateIso(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

/**
 * Normalizes course title by removing session type suffixes and group tags
 * so Lectures, Seminars, and Labs for the same course match cleanly.
 */
export function getBaseCourseTitle(name?: string | null): string {
  if (!name) return '';
  return name
    .toLowerCase()
    .replace(/\b(lecture|seminar|lab|laboratory|labor|practice|consultation|exam|előadás|eloadas|gyakorlat|gyak|laboratórium|laboratorium|ea|gy)\b/gi, '')
    .replace(/[-_/\s]+(e|g|l)\d*\b/gi, '')
    .replace(/[()[\]{}]+/g, ' ')
    .replace(/[^a-z0-9\u00C0-\u024F]+/gi, ' ')
    .trim()
    .replace(/\s+/g, ' ');
}

export function getBaseCourseCode(code?: string | null): string {
  if (!code) return '';
  return code
    .toLowerCase()
    .replace(/[-_/\s]+(e|g|l)\d*$/i, '')
    .replace(/[^a-z0-9]+/g, '')
    .trim();
}

/**
 * Normalizes a time string to zero-padded `HH:mm` (e.g. `8:00` → `08:00`).
 * Feeds occasionally emit unpadded hours; storing and comparing times in a
 * single canonical form keeps session matching (and dedupe keys) stable.
 */
export function normalizeTime(time?: string | null): string | undefined {
  if (!time) return undefined;
  const match = time.trim().match(/^(\d{1,2}):(\d{1,2})/);
  if (!match) return undefined;
  const h = match[1].padStart(2, '0');
  const m = match[2].padStart(2, '0');
  if (Number(h) > 23 || Number(m) > 59) return undefined;
  return `${h}:${m}`;
}

/**
 * THE single source of truth for mapping a course to its canonical tracker key.
 *
 * Strips group/session-type suffixes from the code (or falls back to the base
 * title) and appends the semester, so the Neptun/CSV sync, the check-in button
 * and the Course Hub modal all address the same tracker. Any other derivation
 * (e.g. normalizing the raw code with the suffix intact) produces a different
 * key and silently forks the tracker — which is exactly what broke check-in
 * syncing for intensive classes.
 */
export function resolveCourseKey(identity: CourseIdentity): string {
  const baseCode = getBaseCourseCode(identity.courseCode);
  const baseTitle = getBaseCourseTitle(identity.courseName);

  // Short codes like "E01", "G01" (3 chars) are ambiguous — multiple different
  // courses can share the same group prefix. Use the base title instead, which
  // is unique per course (strips "Lecture"/"Seminar" suffixes so both map to
  // the same tracker for the same course).
  const base =
    baseCode && baseCode.length >= 4
      ? baseCode
      : baseTitle || baseCode || identity.courseCode || identity.courseName || 'unknown';

  return normalizeCourseKey(base, identity.semester ?? undefined);
}

/**
 * Robust course matching: returns true if two events belong to the same course.
 * Prevents accidental cross-course pollution when two different classes share a day or code prefix.
 */
export function isSameCourse(
  courseA: { course_name?: string | null; course_code?: string | null },
  courseB: { course_name?: string | null; course_code?: string | null }
): boolean {
  const rawA = (courseA.course_name || '').trim().toLowerCase();
  const rawB = (courseB.course_name || '').trim().toLowerCase();
  if (rawA && rawB && rawA === rawB) {
    return true;
  }

  const titleA = getBaseCourseTitle(courseA.course_name);
  const titleB = getBaseCourseTitle(courseB.course_name);

  // If base titles match, they belong to the same course (e.g. "Physics Lecture" vs "Physics Seminar")
  if (titleA && titleB && titleA === titleB) {
    return true;
  }

  // If base course codes match (e.g. CS101-E vs CS101-G), verify titles are not conflicting
  const codeA = getBaseCourseCode(courseA.course_code);
  const codeB = getBaseCourseCode(courseB.course_code);
  if (codeA && codeB && codeA.length >= 4 && codeA === codeB) {
    if (!titleA || !titleB || titleA.includes(titleB) || titleB.includes(titleA)) {
      return true;
    }
  }

  return false;
}

/**
 * Groups schedule events by their canonical course key (same keys used by the
 * course tracker).
 */
function groupEventsByCourse(events: ScheduleEvent[]): Map<string, ScheduleEvent[]> {
  const byCourse = new Map<string, ScheduleEvent[]>();
  for (const e of events) {
    const key = resolveCourseKey({
      courseCode: e.course_code,
      courseName: e.course_name,
      semester: e.semester,
    });
    const group = byCourse.get(key);
    if (group) {
      group.push(e);
    } else {
      byCourse.set(key, [e]);
    }
  }
  return byCourse;
}

/**
 * The official semester start (YYYY-MM-DD): when classes actually begin.
 *
 * An intensive week class happens only in the week before classes start, so it
 * must not define the anchor. The anchor is therefore the earliest start among
 * courses that run across multiple weeks (span > 7 days — the real semester
 * backbone). When every course fits in a single week (e.g. only an intensive
 * block), falls back to the earliest start overall.
 */
export function getOfficialSemesterStart(events: ScheduleEvent[]): string | null {
  const byCourse = groupEventsByCourse(events);
  if (byCourse.size === 0) return null;

  const courseWindows: Array<{ minStart: string; spanDays: number }> = [];
  let overallMin: string | null = null;

  for (const courseEvents of byCourse.values()) {
    const starts = courseEvents.map((e) => e.semester_start).filter(isYmdString);
    if (starts.length === 0) continue;
    const minStart = starts.reduce((a, b) => (a < b ? a : b));
    const ends = courseEvents.map((e) => e.semester_end).filter(isYmdString);
    const maxEnd = ends.length > 0 ? ends.reduce((a, b) => (a > b ? a : b)) : minStart;
    const spanDays = (new Date(maxEnd).getTime() - new Date(minStart).getTime()) / (1000 * 60 * 60 * 24);
    courseWindows.push({ minStart, spanDays });
    if (!overallMin || minStart < overallMin) overallMin = minStart;
  }

  const multiWeekStarts = courseWindows
    .filter((w) => w.spanDays > 7)
    .map((w) => w.minStart);
  if (multiWeekStarts.length > 0) {
    return multiWeekStarts.reduce((a, b) => (a < b ? a : b));
  }
  return overallMin;
}

/**
 * Returns the canonical course keys (same keys used by the course tracker)
 * whose events form an intensive week class: a class that happens in the week
 * right before classes start and never happens again after (or carries an
 * intensive keyword). See isIntensiveCourseEvents.
 */
export function getIntensiveCourseKeys(events: ScheduleEvent[]): Set<string> {
  const intensiveKeys = new Set<string>();

  // Group by semester first, then judge each semester's courses against that
  // semester's OWN classes-start anchor. A single anchor across all semesters
  // would resolve to the oldest semester's start, so the current semester's
  // intensive week (1 week before ITS classes start) would never match.
  const bySemester = new Map<string, ScheduleEvent[]>();
  for (const e of events) {
    const sem = (e.semester || '').trim();
    const list = bySemester.get(sem);
    if (list) {
      list.push(e);
    } else {
      bySemester.set(sem, [e]);
    }
  }

  for (const semesterEvents of bySemester.values()) {
    const officialStart = getOfficialSemesterStart(semesterEvents);
    const byCourse = groupEventsByCourse(semesterEvents);
    for (const [key, courseEvents] of byCourse) {
      if (isIntensiveCourseEvents(courseEvents, officialStart)) {
        intensiveKeys.add(key);
      }
    }
  }
  return intensiveKeys;
}

export function deduplicateSessions(sessions: AttendanceSession[]): AttendanceSession[] {
  if (!Array.isArray(sessions)) return [];
  const map = new Map<string, AttendanceSession>();
  for (const s of sessions) {
    if (!s || !s.date) continue;
    const dateStr = s.date.trim();
    const timeStr = (s.startTime || '').trim();
    const key = timeStr ? `${dateStr}_${timeStr}` : `${dateStr}_${s.sessionType || 'default'}`;
    const existing = map.get(key);
    if (!existing) {
      map.set(key, s);
    } else {
      const isExistingUnmarked = !existing.status || existing.status === 'unmarked';
      const isNewMarked = s.status && s.status !== 'unmarked';
      if (isExistingUnmarked && isNewMarked) {
        map.set(key, {
          ...s,
          startTime: s.startTime || existing.startTime,
          endTime: s.endTime || existing.endTime,
          title: s.title || existing.title,
        });
      } else {
        map.set(key, {
          ...existing,
          startTime: existing.startTime || s.startTime,
          endTime: existing.endTime || s.endTime,
        });
      }
    }
  }
  return Array.from(map.values()).sort((a, b) => {
    const dComp = a.date.localeCompare(b.date);
    if (dComp !== 0) return dComp;
    return (a.startTime || '').localeCompare(b.startTime || '');
  });
}

export function generateScheduleSessions(params: GenerateSessionParams): AttendanceSession[] {
  return generateSessionsFromScheduleEvents({
    courseName: 'Course',
    dayOfWeek: params.dayOfWeek,
    startTime: params.startTime,
    endTime: params.endTime,
    eventType: params.sessionType,
    existingSessions: params.existingSessions,
    removedSessionKeys: params.removedSessionKeys,
    semesterStart: params.semesterStart,
    semesterEnd: params.semesterEnd,
    frequency: params.frequency,
  });
}

/**
 * The attendance session type a schedule event generates: an explicit
 * attendance_type wins, then the group code's class type (E01 → lecture,
 * G01 → seminar, L01 → lab), then the event type.
 */
export function getEventSessionType(
  evt: Pick<ScheduleEvent, 'course_code' | 'event_type'> & { attendance_type?: ScheduleEvent['attendance_type'] }
): SessionType {
  const explicitAttendanceType = evt.attendance_type;
  const classified = explicitAttendanceType ? null : getClassType(evt.course_code || evt.event_type);
  let sessionType: SessionType = explicitAttendanceType === 'unknown' ? 'other' : 'lecture';
  if (explicitAttendanceType === 'seminar') sessionType = 'seminar';
  else if (explicitAttendanceType === 'lab') sessionType = 'lab';
  else if (explicitAttendanceType === 'lecture') sessionType = 'lecture';
  else if (!explicitAttendanceType) {
    const labelUpper = (classified?.label || '').toUpperCase();
    if (labelUpper === 'SEM' || labelUpper === 'SEMINAR') sessionType = 'seminar';
    else if (labelUpper === 'LAB' || labelUpper === 'LABORATORY') sessionType = 'lab';
    else if (evt.event_type === 'seminar' || evt.event_type === 'lab') sessionType = evt.event_type;
  }
  return sessionType;
}

export function generateSessionsFromScheduleEvents(params: {
  courseName: string;
  courseCode?: string;
  semester?: string;
  dayOfWeek?: number;
  startTime?: string;
  endTime?: string;
  eventType?: string;
  semesterStart?: string | null;
  semesterEnd?: string | null;
  frequency?: 'weekly' | 'even' | 'odd';
  scheduleEvents?: ScheduleEvent[];
  existingSessions?: AttendanceSession[];
  /** Slot keys of sessions the student deleted; they are never regenerated. */
  removedSessionKeys?: string[];
}): AttendanceSession[] {
  const normSemester = (params.semester || '').toLowerCase().trim();
  const allEvents = params.scheduleEvents || [];
  // Sessions the student deleted by hand must never come back, whether they
  // were generated slots or preserved marked/custom entries.
  const removedKeys = new Set(params.removedSessionKeys || []);

  // 1. Find strictly matching schedule events for THIS course using isSameCourse
  const isSameRequestedCourse = (event: ScheduleEvent) => isSameCourse(
    { course_name: params.courseName, course_code: params.courseCode },
    { course_name: event.course_name, course_code: event.course_code }
  );
  // Exams never become attendance sessions. isExamEvent also catches exam rows
  // imported as `class` (older ICS feeds only marked Hungarian exam titles), and
  // matching by title is the only way to keep those from generating a semester
  // of phantom "lecture" slots for a one-off exam.
  const hasMatchingExam = allEvents.some(
    (event) => isExamEvent(event) && isSameRequestedCourse(event)
  );
  let matching = allEvents.filter((e) => {
    if (isExamEvent(e)) return false;
    return isSameRequestedCourse(e);
  });

  // 2. Filter by semester if present
  if (normSemester) {
    const semMatches = matching.filter(
      (e) => (e.semester || '').toLowerCase().trim() === normSemester
    );
    if (semMatches.length > 0) {
      matching = semMatches;
    }
  }

  // 3. Determine all distinct meeting slots (Lectures, Seminars, Labs)
  interface ClassSlot {
    dayOfWeek: number;
    startTime?: string;
    endTime?: string;
    sessionType: SessionType;
    frequency?: string;
    startDate?: string | null;
    endDate?: string | null;
    /** Manual weekly classes skip public holidays (see classOccursOnDate). */
    skipHolidays: boolean;
  }

  const slots: ClassSlot[] = [];
  const seenSlots = new Set<string>();

  if (matching.length > 0) {
    for (const evt of matching) {
      const day = typeof evt.day_of_week === 'number' ? evt.day_of_week : 1;
      const sTime = normalizeTime(evt.start_time) ?? '';
      const eTime = normalizeTime(evt.end_time) ?? '';
      const sDate = evt.semester_start ? String(evt.semester_start).trim() : null;
      const eDate = evt.semester_end ? String(evt.semester_end).trim() : null;

      const sessionType = getEventSessionType(evt);

      const freq = getEventFrequency(evt.csv_metadata);

      const slotKey = `${day}_${sTime}_${sessionType}_${sDate || ''}_${eDate || ''}`;
      if (!seenSlots.has(slotKey)) {
        seenSlots.add(slotKey);
        slots.push({
          dayOfWeek: day,
          startTime: sTime || undefined,
          endTime: eTime || undefined,
          sessionType,
          frequency: freq,
          startDate: sDate || params.semesterStart || null,
          endDate: eDate || params.semesterEnd || null,
          // Neptun ranges already split around holidays; rows without a source
          // (a fresh sync's payload) are Neptun-shaped too, so the check is a
          // no-op for them.
          skipHolidays: evt.source !== 'neptun',
        });
      }
    }
  } else if (typeof params.dayOfWeek === 'number' && !hasMatchingExam) {
    slots.push({
      dayOfWeek: params.dayOfWeek,
      startTime: params.startTime,
      endTime: params.endTime,
      sessionType: (params.eventType as SessionType) || 'lecture',
      frequency: params.frequency,
      startDate: params.semesterStart || null,
      endDate: params.semesterEnd || null,
      skipHolidays: true,
    });
  }

  // No slots identified → return only existing user-marked/custom sessions.
  // Never fabricate phantom sessions for days/times the student doesn't have.
  if (slots.length === 0) {
    const preserved = (params.existingSessions || []).filter(
      (s) => s && s.date && (s.isCustom || (s.status && s.status !== 'unmarked'))
    );
    return deduplicateSessions(preserved.filter((s) => !removedKeys.has(sessionSlotKey(s))));
  }

  // 4. Determine semester start date
  const starts = [
    params.semesterStart,
    ...slots.map((s) => s.startDate),
  ].filter(Boolean) as string[];

  const validStarts = starts.filter((s) => /^\d{4}-\d{2}-\d{2}$/.test(s)).sort();

  let semesterStart: Date;
  if (validStarts.length > 0) {
    const [y, m, d] = validStarts[0].split('-').map(Number);
    semesterStart = new Date(y, m - 1, d);
  } else {
    const now = new Date();
    semesterStart = new Date(now.getFullYear(), now.getMonth(), 1);
  }

  // 5. Existing sessions lookup: key by session ID, exact date+startTime+type,
  // date+startTime, and (for untimed sessions only) date+type or date.
  //
  // The loose date/type keys must never match a TIMED existing session: an
  // intensive day has three seminar slots (08:00 / 09:50 / 11:40) and marking
  // one of them must not copy its id+status onto its siblings. Untimed sessions
  // (e.g. manually added) still map onto the day's slot via those keys.
  const existingMap = new Map<string, AttendanceSession>();
  for (const s of params.existingSessions || []) {
    if (!s || !s.date) continue;
    const dateTrim = s.date.trim();
    const timeTrim = (s.startTime || '').trim();
    const typeTrim = s.sessionType || '';

    if (s.id) existingMap.set(s.id, s);
    if (timeTrim && typeTrim) existingMap.set(`${dateTrim}_${timeTrim}_${typeTrim}`, s);
    if (timeTrim) existingMap.set(`${dateTrim}_${timeTrim}`, s);
    if (!timeTrim) {
      if (typeTrim) existingMap.set(`${dateTrim}_${typeTrim}`, s);
      if (!existingMap.has(dateTrim)) {
        existingMap.set(dateTrim, s);
      }
    }
  }

  // 6. Generate calendar sessions bounded by each slot's active date range
  const TOTAL_WEEKS = 14;
  const result: AttendanceSession[] = [];
  const generatedKeys = new Set<string>();

  for (let week = 1; week <= TOTAL_WEEKS; week++) {
    for (const slot of slots) {
      const firstDayOffset = (slot.dayOfWeek - semesterStart.getDay() + 7) % 7;
      const sessionDate = new Date(semesterStart);
      sessionDate.setDate(semesterStart.getDate() + firstDayOffset + (week - 1) * 7);

      const dateStr = formatDateIso(sessionDate);

      // Frequency filtering (even / odd weeks)
      if (slot.frequency === 'even' && week % 2 !== 0) continue;
      if (slot.frequency === 'odd' && week % 2 === 0) continue;

      // Active date range filtering per slot / block course
      if (slot.startDate && dateStr < slot.startDate) continue;
      if (slot.endDate && dateStr > slot.endDate) continue;
      if (slot.skipHolidays && isHungarianPublicHoliday(dateStr)) continue;

      const fullKey = `${dateStr}_${slot.startTime || ''}_${slot.sessionType}`;
      const timeKey = `${dateStr}_${slot.startTime || ''}`;
      const typeKey = `${dateStr}_${slot.sessionType}`;

      if (!generatedKeys.has(fullKey)) {
        generatedKeys.add(fullKey);

        const existing =
          existingMap.get(fullKey) ||
          existingMap.get(timeKey) ||
          existingMap.get(typeKey) ||
          existingMap.get(dateStr);

        result.push({
          id: existing?.id || genId(),
          date: dateStr,
          startTime: slot.startTime || existing?.startTime,
          endTime: slot.endTime || existing?.endTime,
          title: existing?.title || `Week ${week}`,
          sessionType: slot.sessionType,
          status: existing?.status || 'unmarked',
          notes: existing?.notes,
          isCustom: existing?.isCustom,
        });
      }
    }
  }

  // 7. Preserve any user-marked or custom sessions that weren't part of standard slots
  for (const s of params.existingSessions || []) {
    if (!s || !s.date) continue;
    const alreadyIncluded = result.some(
      (r) =>
        r.id === s.id ||
        (r.date === s.date && (r.startTime || '') === (s.startTime || '') && r.sessionType === s.sessionType)
    );
    if (!alreadyIncluded && (s.isCustom || (s.status && s.status !== 'unmarked'))) {
      result.push(s);
    }
  }

  result.sort((a, b) => {
    const dateCmp = a.date.localeCompare(b.date);
    if (dateCmp !== 0) return dateCmp;
    return (a.startTime || '').localeCompare(b.startTime || '');
  });

  return deduplicateSessions(result.filter((s) => !removedKeys.has(sessionSlotKey(s))));
}

export function calculateAttendanceStats(
  sessions: AttendanceSession[],
  limits: { maxAbsencesAllowed: number | null; minAttendancePercent: number | null },
  options?: { /** Budapest date (YYYY-MM-DD); defaults to today in Budapest. */ today?: string }
): AttendanceStats {
  const today = options?.today ?? nowInBudapest().ymd;
  let presentCount = 0;
  let absentCount = 0;
  let excusedCount = 0;
  let cancelledCount = 0;
  let unmarkedCount = 0;
  let futureUnmarkedCount = 0;

  for (const s of sessions) {
    switch (s.status) {
      case 'present':
        presentCount++;
        break;
      case 'absent':
        absentCount++;
        break;
      case 'excused':
        excusedCount++;
        break;
      case 'cancelled':
        cancelledCount++;
        break;
      case 'unmarked':
      default:
        unmarkedCount++;
        if (s.date && s.date > today) futureUnmarkedCount++;
        break;
    }
  }

  const activeCount = presentCount + absentCount;
  const countedSessions = sessions.length - cancelledCount - excusedCount - futureUnmarkedCount;
  const attendancePercent = countedSessions > 0 ? (presentCount / countedSessions) * 100 : 0;

  const maxAllowed = limits.maxAbsencesAllowed;
  const minPercent = limits.minAttendancePercent;

  const absencesRemaining = maxAllowed !== null ? Math.max(0, maxAllowed - absentCount) : null;
  const isBelowMinPercent = minPercent !== null ? attendancePercent < minPercent && countedSessions > 0 : false;
  const isOverAbsenceLimit = maxAllowed !== null ? absentCount > maxAllowed : false;

  return {
    totalSessions: sessions.length,
    presentCount,
    absentCount,
    excusedCount,
    cancelledCount,
    unmarkedCount,
    activeCount,
    countedSessions,
    attendancePercent,
    absencesRemaining,
    isBelowMinPercent,
    isOverAbsenceLimit,
  };
}

/**
 * Risk tier for the student's attendance standing.
 *
 * - `unknown` when nothing has been marked yet (a percentage of no data is not
 *   "100% good", it is no information).
 * - `critical` when absences exceed the quota or attendance falls below the
 *   minimum percentage.
 * - `warning` when the student is at the quota or one absence away from it.
 * - `safe` otherwise.
 */
export function getAttendanceRiskTier(input: {
  absentCount: number;
  maxAbsencesAllowed: number | null;
  attendancePercent: number;
  minAttendancePercent: number | null;
  activeCount: number;
}): AttendanceRiskTier {
  const { absentCount, maxAbsencesAllowed, attendancePercent, minAttendancePercent, activeCount } = input;

  if (activeCount === 0) return 'unknown';
  if (maxAbsencesAllowed !== null && absentCount > maxAbsencesAllowed) return 'critical';
  if (minAttendancePercent !== null && attendancePercent < minAttendancePercent) return 'critical';

  if (maxAbsencesAllowed !== null && maxAbsencesAllowed > 0 && absentCount === maxAbsencesAllowed) {
    return 'warning';
  }
  if (maxAbsencesAllowed !== null && maxAbsencesAllowed > 1 && absentCount === maxAbsencesAllowed - 1) {
    return 'warning';
  }
  return 'safe';
}
