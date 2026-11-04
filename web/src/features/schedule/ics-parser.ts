/**
 * Neptun Corvinus iCalendar (.ics) parser and semester categorizer.
 */

import { getErrorMessage } from '@/lib/error-utils';
import { nowInBudapest } from '@/lib/date-utils';
import { isExamSummary, isExamTypeParenthetical } from './exam-detection';
import { timeoutSignal } from '@/lib/timeout-signal';

/** The marked Neptun code form: "( - MATH101-E01)" — unambiguous, so it wins. */
const MARKED_CODE_PARENTHETICAL = /\(\s*-\s*([^)]*)\)/;
/**
 * The bare form some calendars emit: "(G04)". Restricted to a single unbroken
 * token so a descriptive parenthetical with spaces ("(Probability and
 * Statistics)") can never be mistaken for a code.
 */
const BARE_CODE_PARENTHETICAL = /\(\s*([A-Za-z0-9][\w./-]*)\s*\)/;

export interface ParsedICSEvent {
  course_name: string;
  course_code: string;
  instructor: string;
  location: string;
  day_of_week: number; // 0 = Sun, 1 = Mon, ..., 6 = Sat
  start_time: string; // HH:mm
  end_time: string; // HH:mm
  color: string;
  semester: string; // e.g. "2025/26/1"
  semester_start: string | null; // YYYY-MM-DD
  semester_end: string | null; // YYYY-MM-DD
  event_type?: 'class' | 'exam' | 'other';
}

export interface ParseICSResult {
  valid: ParsedICSEvent[];
  errors: Array<{ row: number; message: string }>;
  semesters: string[];
  eventsBySemester: Record<string, ParsedICSEvent[]>;
}

/**
 * Determine Corvinus semester tag from date.
 * Fall Semester (1st semester): August to January (e.g. Aug 2025 - Jan 2026 => "2025/26/1")
 * Spring Semester (2nd semester): February to July (e.g. Feb 2026 - July 2026 => "2025/26/2")
 */
export function getSemesterFromDate(date: Date): string {
  return getSemesterFromYearMonth(date.getFullYear(), date.getMonth() + 1);
}

/** Corvinus semester tag for a given year and month (1-12). */
export function getSemesterFromYearMonth(year: number, month: number): string {
  if (month >= 8 && month <= 12) {
    const nextY = String(year + 1).slice(-2);
    return `${year}/${nextY}/1`;
  } else if (month === 1) {
    const prevY = year - 1;
    const currY = String(year).slice(-2);
    return `${prevY}/${currY}/1`;
  } else {
    const prevY = year - 1;
    const currY = String(year).slice(-2);
    return `${prevY}/${currY}/2`;
  }
}

/**
 * The semester currently in session, judged by the Budapest wall clock so it
 * matches the semester tags the parser assigns to events.
 */
export function getCurrentSemester(at: Date = new Date()): string {
  const now = nowInBudapest(at);
  return getSemesterFromYearMonth(now.year, now.month);
}

/**
 * Splits a content line into its name+parameters and its value at the first
 * colon outside double quotes — a quoted parameter may itself contain a colon
 * (`DTSTART;TZID="GMT+01:00 Budapest":20251006T080000`). Returns `null` when the
 * line has no unquoted colon.
 */
export function splitICSProperty(line: string): { name: string; params: Record<string, string>; value: string } | null {
  let inQuotes = false;
  let colonIdx = -1;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (ch === '"') inQuotes = !inQuotes;
    else if (ch === ':' && !inQuotes) {
      colonIdx = i;
      break;
    }
  }
  if (colonIdx === -1) return null;
  const head = line.substring(0, colonIdx);
  const value = line.substring(colonIdx + 1);

  // Split parameters on semicolons outside quotes.
  const segments: string[] = [];
  let current = '';
  inQuotes = false;
  for (const ch of head) {
    if (ch === '"') inQuotes = !inQuotes;
    if (ch === ';' && !inQuotes) {
      segments.push(current);
      current = '';
    } else {
      current += ch;
    }
  }
  segments.push(current);

  const params: Record<string, string> = {};
  for (const segment of segments.slice(1)) {
    const eq = segment.indexOf('=');
    if (eq === -1) continue;
    const paramName = segment.substring(0, eq).trim().toUpperCase();
    const paramValue = segment.substring(eq + 1).trim().replace(/^"(.*)"$/, '$1');
    params[paramName] = paramValue;
  }
  return { name: segments[0].trim().toUpperCase(), params, value };
}

export interface ICSTimestampInfo {
  date: Date;
  dateStr: string;
  timeStr: string;
  dayOfWeek: number;
  /** True for a date-only value (`VALUE=DATE` / `YYYYMMDD`): an all-day event. */
  allDay: boolean;
}

/**
 * Parse standard iCal date string (e.g., "20250919T075000Z", "20250919T075000", "20250919")
 * into Date object converted to Europe/Budapest wall clock time. Accepts a bare
 * value or a whole property line (`DTSTART;TZID=...:20250919T075000`).
 */
export function parseICSTimestamp(dtStr: string): ICSTimestampInfo | null {
  if (!dtStr) return null;
  const trimmedInput = dtStr.trim();
  const property = /^\d{8}/.test(trimmedInput) ? null : splitICSProperty(trimmedInput);
  const tzid = property?.params.TZID || undefined;
  const clean = property ? property.value.trim() : trimmedInput;

  const match = clean.match(/^(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})(\d{2})?(Z)?)?/);
  if (!match) return null;

  const [, year, month, day, hour, min = '00', sec = '00', isUtc] = match;
  const allDay = hour === undefined || property?.params.VALUE?.toUpperCase() === 'DATE';
  if (allDay) {
    // A calendar date, not an instant: no zone conversion may move it.
    const d = new Date(Date.UTC(+year, +month - 1, +day));
    if (isNaN(d.getTime())) return null;
    return { date: d, dateStr: `${year}-${month}-${day}`, timeStr: '00:00', dayOfWeek: d.getUTCDay(), allDay: true };
  }

  // UTC stamps are instants. Floating and TZID stamps are wall-clock times in
  // that zone (floating = Budapest, Neptun's zone) — never the device's zone,
  // which would shift every class on a phone set to another timezone.
  const d = isUtc
    ? new Date(Date.UTC(+year, +month - 1, +day, +hour, +min, +sec))
    : new Date(wallClockToInstant(tzid ?? 'Europe/Budapest', +year, +month, +day, +hour, +min, +sec));

  if (isNaN(d.getTime())) return null;
  return budapestInfoForInstant(d);
}

/** Budapest wall-clock date/time/weekday for an instant. */
function budapestInfoForInstant(d: Date): ICSTimestampInfo {
  // Format using Europe/Budapest timezone for accurate local time
  const formatter = new Intl.DateTimeFormat('en-US', {
    timeZone: 'Europe/Budapest',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    weekday: 'short',
    hour12: false,
  });

  const parts = formatter.formatToParts(d);
  let y = '', m = '', da = '', h = '', mi = '';

  for (const part of parts) {
    if (part.type === 'year') y = part.value;
    if (part.type === 'month') m = part.value;
    if (part.type === 'day') da = part.value;
    if (part.type === 'hour') h = part.value;
    if (part.type === 'minute') mi = part.value;
  }

  // Ensure 24h hour formatting
  if (h === '24') h = '00';
  h = String(h).padStart(2, '0');
  mi = String(mi).padStart(2, '0');

  // Weekday of the Budapest calendar date (0 = Sun, 1 = Mon ...).
  const dayOfWeek = new Date(Date.UTC(+y, +m - 1, +da)).getUTCDay();

  return {
    date: d,
    dateStr: `${y}-${m}-${da}`,
    timeStr: `${h}:${mi}`,
    dayOfWeek,
    allDay: false,
  };
}

/** Offset (ms) of `timeZone` from UTC at `instantMs`. */
function zoneOffsetMs(timeZone: string, instantMs: number): number {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone,
    hourCycle: 'h23',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).formatToParts(new Date(instantMs));
  const get = (type: Intl.DateTimeFormatPartTypes) => Number(parts.find((p) => p.type === type)?.value ?? 0);
  return Date.UTC(get('year'), get('month') - 1, get('day'), get('hour'), get('minute'), get('second')) - instantMs;
}

/**
 * Instant (epoch ms) for a wall-clock time in `timeZone`. Unknown zone names
 * (e.g. Windows-style TZIDs) fall back to Europe/Budapest.
 */
function wallClockToInstant(timeZone: string, y: number, mo: number, d: number, h: number, mi: number, sec: number): number {
  const asUtc = Date.UTC(y, mo - 1, d, h, mi, sec);
  try {
    // Two passes settle the offset across a DST transition.
    const first = asUtc - zoneOffsetMs(timeZone, asUtc);
    return asUtc - zoneOffsetMs(timeZone, first);
  } catch {
    return timeZone === 'Europe/Budapest' ? asUtc : wallClockToInstant('Europe/Budapest', y, mo, d, h, mi, sec);
  }
}

/** Decodes RFC 5545 TEXT escapes (\\, \; \, \n). */
function unescapeICSText(value: string): string {
  return value.replace(/\\([\\;,nN])/g, (_, ch: string) => (ch === 'n' || ch === 'N' ? '\n' : ch));
}

/**
 * Unfold wrapped lines in iCalendar RFC 5545 format
 */
function unfoldICS(rawText: string): string {
  return rawText.replace(/\r\n[ \t]/g, '').replace(/\n[ \t]/g, '');
}

/**
 * Color palette generator for courses
 */
const COURSE_COLORS = [
  '#3B82F6', // Blue
  '#10B981', // Emerald
  '#8B5CF6', // Purple
  '#F59E0B', // Amber
  '#EC4899', // Pink
  '#06B6D4', // Cyan
  '#6366F1', // Indigo
  '#F97316', // Orange
  '#14B8A6', // Teal
  '#A855F7', // Violet
];

/**
 * Clusters an array of date strings (YYYY-MM-DD) into contiguous weekly ranges.
 * If consecutive occurrences are <= 8 days apart (e.g. weekly or intensive block days),
 * they remain grouped in the same cluster.
 * If occurrences have gaps > 8 days (skipped weeks, bi-weekly, or irregular dates),
 * they are split into separate active date ranges.
 */
export function clusterConsecutiveWeeks(dates: string[]): Array<{ start: string; end: string }> {
  if (dates.length === 0) return [];
  const sorted = Array.from(new Set(dates)).sort();

  const clusters: Array<{ start: string; end: string }> = [];
  let clusterStart = sorted[0];
  let clusterEnd = sorted[0];

  for (let i = 1; i < sorted.length; i++) {
    const prevTime = new Date(sorted[i - 1] + 'T00:00:00').getTime();
    const currTime = new Date(sorted[i] + 'T00:00:00').getTime();
    const diffDays = Math.round((currTime - prevTime) / (1000 * 60 * 60 * 24));

    if (diffDays <= 8) {
      clusterEnd = sorted[i];
    } else {
      clusters.push({ start: clusterStart, end: clusterEnd });
      clusterStart = sorted[i];
      clusterEnd = sorted[i];
    }
  }

  clusters.push({ start: clusterStart, end: clusterEnd });
  return clusters;
}

interface RawICSEvent {
  dtstart: string;
  dtend: string;
  duration: string;
  rrule: string;
  exdates: string[];
  recurrenceId: string;
  status: string;
  uid: string;
  summary: string;
  location: string;
  description: string;
}

function createRawEvent(): RawICSEvent {
  return {
    dtstart: '',
    dtend: '',
    duration: '',
    rrule: '',
    exdates: [],
    recurrenceId: '',
    status: '',
    uid: '',
    summary: '',
    location: '',
    description: '',
  };
}

/** RFC 5545 DURATION (`PT1H30M`, `P1D`, `P2W`) in milliseconds; `null` when malformed. */
export function parseICSDurationMs(value: string): number | null {
  const match = value
    .trim()
    .toUpperCase()
    .match(/^([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$/);
  if (!match || /^[+-]?PT?$/.test(value.trim().toUpperCase())) return null;
  const [, sign, w = '0', d = '0', h = '0', m = '0', sec = '0'] = match;
  const ms = ((+w * 7 + +d) * 86_400 + +h * 3_600 + +m * 60 + +sec) * 1000;
  return sign === '-' ? -ms : ms;
}

const ICS_WEEKDAYS: Record<string, number> = { SU: 0, MO: 1, TU: 2, WE: 3, TH: 4, FR: 5, SA: 6 };
/** Hard stop for a malformed or unbounded rule. */
const MAX_RECURRENCE_OCCURRENCES = 1000;
/** A rule with neither COUNT nor UNTIL is expanded for one year. */
const UNBOUNDED_RECURRENCE_DAYS = 366;
const DAY_MS = 86_400_000;

function ymdCompact(utcMs: number): string {
  const d = new Date(utcMs);
  return `${d.getUTCFullYear()}${String(d.getUTCMonth() + 1).padStart(2, '0')}${String(d.getUTCDate()).padStart(2, '0')}`;
}

/**
 * Expands an RRULE (FREQ=WEEKLY or DAILY with INTERVAL, COUNT, UNTIL, BYDAY
 * and WKST) into one start per occurrence, minus EXDATEs, so a recurring event
 * yields the same per-occurrence stream as Neptun's pre-expanded feed and is
 * clustered into semester_start/semester_end ranges the same way. Occurrences
 * keep DTSTART's wall-clock time in DTSTART's own zone (so a Budapest class
 * stays at 08:00 across the DST change). Unsupported frequencies yield DTSTART
 * alone.
 */
function expandRecurrence(
  dtstartLine: string,
  first: ICSTimestampInfo,
  rrule: string,
  exdateLines: string[]
): ICSTimestampInfo[] {
  const rule: Record<string, string> = {};
  for (const part of rrule.split(';')) {
    const eq = part.indexOf('=');
    if (eq > 0) rule[part.substring(0, eq).trim().toUpperCase()] = part.substring(eq + 1).trim();
  }

  const excludedInstants = new Set<number>();
  const excludedDates = new Set<string>();
  for (const line of exdateLines) {
    const property = splitICSProperty(line);
    if (!property) continue;
    const head = line.substring(0, line.length - property.value.length);
    for (const value of property.value.split(',')) {
      const info = parseICSTimestamp(`${head}${value.trim()}`);
      if (!info) continue;
      if (info.allDay) excludedDates.add(info.dateStr);
      else excludedInstants.add(info.date.getTime());
    }
  }
  const isExcluded = (info: ICSTimestampInfo) =>
    excludedInstants.has(info.date.getTime()) || excludedDates.has(info.dateStr);

  const freq = (rule.FREQ || '').toUpperCase();
  if (freq !== 'WEEKLY' && freq !== 'DAILY') return isExcluded(first) ? [] : [first];

  const property = splitICSProperty(dtstartLine);
  const value = property ? property.value.trim() : dtstartLine.trim();
  const dateMatch = value.match(/^(\d{4})(\d{2})(\d{2})(.*)$/);
  if (!dateMatch) return [first];
  const head = property ? dtstartLine.trim().slice(0, dtstartLine.trim().length - property.value.length) : '';
  const timeSuffix = dateMatch[4];
  const startDayMs = Date.UTC(+dateMatch[1], +dateMatch[2] - 1, +dateMatch[3]);
  const startDow = new Date(startDayMs).getUTCDay();

  const interval = Math.max(1, parseInt(rule.INTERVAL || '1', 10) || 1);
  const count = rule.COUNT ? parseInt(rule.COUNT, 10) : null;
  const until = rule.UNTIL ? parseICSTimestamp(rule.UNTIL) : null;
  const hardStopMs = startDayMs + UNBOUNDED_RECURRENCE_DAYS * DAY_MS;
  const byDay = (rule.BYDAY || '')
    .split(',')
    .map((code) => ICS_WEEKDAYS[code.trim().toUpperCase().replace(/^[+-]?\d+/, '')])
    .filter((day): day is number => day !== undefined);
  const wkst = ICS_WEEKDAYS[(rule.WKST || 'MO').toUpperCase()] ?? 1;

  const pastUntil = (info: ICSTimestampInfo) =>
    !!until && (until.allDay ? info.dateStr > until.dateStr : info.date.getTime() > until.date.getTime());

  const result: ICSTimestampInfo[] = [];
  let emitted = 0;
  const emit = (info: ICSTimestampInfo) => {
    emitted++;
    // EXDATEs still count towards COUNT (RFC 5545 §3.8.5.1).
    if (!isExcluded(info)) result.push(info);
  };

  // DTSTART is always the first occurrence.
  emit(first);

  const candidateDays = (step: number): number[] => {
    if (freq === 'DAILY') {
      const dayMs = startDayMs + step * interval * DAY_MS;
      const dow = new Date(dayMs).getUTCDay();
      return byDay.length === 0 || byDay.includes(dow) ? [dayMs] : [];
    }
    const weekStartMs = startDayMs - ((startDow - wkst + 7) % 7) * DAY_MS + step * interval * 7 * DAY_MS;
    const days = byDay.length > 0 ? byDay : [startDow];
    return days.map((dow) => weekStartMs + ((dow - wkst + 7) % 7) * DAY_MS).sort((a, b) => a - b);
  };

  for (let step = 0; step < MAX_RECURRENCE_OCCURRENCES; step++) {
    for (const dayMs of candidateDays(step)) {
      if (dayMs <= startDayMs) continue;
      if (count !== null && emitted >= count) return result;
      if (!until && count === null && dayMs > hardStopMs) return result;
      if (emitted >= MAX_RECURRENCE_OCCURRENCES) return result;
      const info = parseICSTimestamp(`${head}${ymdCompact(dayMs)}${timeSuffix}`);
      if (!info) return result;
      if (pastUntil(info)) return result;
      emit(info);
    }
  }
  return result;
}

/**
 * Parse Neptun ICS text string and extract categorized schedule events
 */
export function parseICSContent(icsText: string, options: { includeExams?: boolean } = {}): ParseICSResult {
  const unfolded = unfoldICS(icsText);
  const lines = unfolded.split(/\r?\n/);
  
  const events: RawICSEvent[] = [];

  let inEvent = false;
  // Nested components (VALARM) carry their own DESCRIPTION/DURATION/etc.; their
  // properties must not overwrite the event's.
  let nestedDepth = 0;
  let currentEvent: RawICSEvent = createRawEvent();

  for (const line of lines) {
    const trimmed = line.trim();
    if (trimmed === 'BEGIN:VEVENT') {
      inEvent = true;
      nestedDepth = 0;
      currentEvent = createRawEvent();
      continue;
    }
    if (trimmed === 'END:VEVENT') {
      if (inEvent) events.push(currentEvent);
      inEvent = false;
      continue;
    }

    if (inEvent) {
      if (/^BEGIN:/i.test(trimmed)) {
        nestedDepth++;
        continue;
      }
      if (/^END:/i.test(trimmed)) {
        nestedDepth = Math.max(0, nestedDepth - 1);
        continue;
      }
      if (nestedDepth > 0) continue;
      const property = splitICSProperty(trimmed);
      if (!property) continue;
      switch (property.name) {
        // Date properties keep their parameters: TZID / VALUE decide how to read them.
        case 'DTSTART':
          currentEvent.dtstart = trimmed;
          break;
        case 'DTEND':
          currentEvent.dtend = trimmed;
          break;
        case 'RECURRENCE-ID':
          currentEvent.recurrenceId = trimmed;
          break;
        case 'EXDATE':
          currentEvent.exdates.push(trimmed);
          break;
        case 'DURATION':
          currentEvent.duration = property.value.trim();
          break;
        case 'RRULE':
          currentEvent.rrule = property.value.trim();
          break;
        case 'STATUS':
          currentEvent.status = property.value.trim().toUpperCase();
          break;
        case 'UID':
          currentEvent.uid = property.value.trim();
          break;
        case 'SUMMARY':
          currentEvent.summary = unescapeICSText(property.value);
          break;
        case 'LOCATION':
          currentEvent.location = unescapeICSText(property.value);
          break;
        case 'DESCRIPTION':
          currentEvent.description = unescapeICSText(property.value);
          break;
        default:
          break;
      }
    }
  }

  // A modified single occurrence (RECURRENCE-ID) replaces that occurrence of
  // its series: the series must not also produce it.
  const overriddenByUid = new Map<string, string[]>();
  for (const evt of events) {
    if (!evt.uid || !evt.recurrenceId) continue;
    const list = overriddenByUid.get(evt.uid) ?? [];
    list.push(evt.recurrenceId);
    overriddenByUid.set(evt.uid, list);
  }

  const parseErrors: Array<{ row: number; message: string }> = [];
  const eventMap = new Map<
    string,
    {
      event: ParsedICSEvent;
      dates: string[];
    }
  >();

  const courseColorMap = new Map<string, string>();
  let colorIndex = 0;

  events.forEach((rawEvt, index) => {
    const summary = rawEvt.summary.trim();
    if (!summary) return;

    // A cancelled occurrence (or series) did not happen.
    if (rawEvt.status === 'CANCELLED') return;

    // Skip holidays / szünnap
    if (summary.toLowerCase().includes('szünnap') || summary.toLowerCase().includes('szünet')) {
      return;
    }

    const isExam = isExamSummary(summary);
    const includeExams = options.includeExams !== false;

    // Exams are part of the unified schedule by default; keep the option only
    // as an explicit parser-level compatibility escape hatch.
    if (isExam && !includeExams) {
      return;
    }

    const firstStart = parseICSTimestamp(rawEvt.dtstart);
    if (!firstStart) {
      parseErrors.push({ row: index + 1, message: `Invalid date format in event: ${summary}` });
      return;
    }
    // All-day entries (VALUE=DATE) are notices — holidays, registration
    // periods — not timed classes; the schedule has no slot to put them in.
    if (firstStart.allDay) return;

    const firstEnd = rawEvt.dtend ? parseICSTimestamp(rawEvt.dtend) : null;
    const durationMs = firstEnd
      ? firstEnd.date.getTime() - firstStart.date.getTime()
      : rawEvt.duration
        ? parseICSDurationMs(rawEvt.duration)
        : null;
    if (durationMs === null || durationMs < 0) {
      parseErrors.push({ row: index + 1, message: `Invalid date format in event: ${summary}` });
      return;
    }

    const occurrenceStarts = rawEvt.rrule && !rawEvt.recurrenceId
      ? expandRecurrence(rawEvt.dtstart, firstStart, rawEvt.rrule, [
          ...rawEvt.exdates,
          ...(rawEvt.uid ? overriddenByUid.get(rawEvt.uid) ?? [] : []),
        ])
      : [firstStart];

    for (const startInfo of occurrenceStarts) {
      const rawEnd = budapestInfoForInstant(new Date(startInfo.date.getTime() + durationMs));
      // An event running to (or past) midnight ends at the end of its own day:
      // "00:00" would read as an end before the start.
      const endInfo = rawEnd.dateStr !== startInfo.dateStr ? { ...rawEnd, timeStr: '23:59' } : rawEnd;
      addOccurrence(rawEvt, summary, isExam, startInfo, endInfo);
    }
  });

  function addOccurrence(
    rawEvt: RawICSEvent,
    summary: string,
    isExam: boolean,
    startInfo: ICSTimestampInfo,
    endInfo: ICSTimestampInfo
  ) {
    // Parse summary into Course Name, Course Code, Instructor
    // Standard Neptun Format: "Course Name ( - Course Code) - Instructor - Tanóra"
    // Exam Neptun Format: "Course Name (Írásbeli) - Instructor - Vizsga"
    let courseName = summary;
    let courseCode = '';
    let instructor = '';

    const suffixIndex = summary.lastIndexOf(' - ');
    let cleanSummary = summary;
    if (suffixIndex !== -1) {
      cleanSummary = summary.substring(0, suffixIndex).trim();
    }

    // Neptun marks the course/group code with a leading dash inside the
    // parenthetical — "Calculus I ( - MATH101-E01)" — and some calendars emit the
    // bare code instead: "... dev (G04)". A title can ALSO carry a descriptive
    // parenthetical ("Data Analysis II. (Probability and Statistics)"), which is
    // not a code. Matching whichever parenthetical came first read that subtitle
    // as the code: the name kept a dangling "( - E01)", the instructor line began
    // mid-parenthetical, and the class lost its E01/L01/G01 group badge.
    const codeMatch = cleanSummary.match(MARKED_CODE_PARENTHETICAL)
      ?? cleanSummary.match(BARE_CODE_PARENTHETICAL);
    if (codeMatch && !isExamTypeParenthetical(codeMatch[1])) {
      courseCode = codeMatch[1].trim();
      cleanSummary = cleanSummary.replace(codeMatch[0], '').trim();
    }

    // Remaining string is "Course Name - Instructor" or just "Course Name"
    const parts = cleanSummary.split(' - ');
    if (parts.length >= 2) {
      courseName = parts[0].trim();
      instructor = parts.slice(1).join(' - ').trim();
    } else {
      courseName = cleanSummary.trim();
    }

    // Determine semester
    // From the Budapest calendar date, not the device-local month of the instant.
    const [semYear, semMonth] = startInfo.dateStr.split('-').map(Number);
    const semester = getSemesterFromYearMonth(semYear, semMonth);

    // Color assignment
    let color = courseColorMap.get(courseName);
    if (!color) {
      color = COURSE_COLORS[colorIndex % COURSE_COLORS.length];
      courseColorMap.set(courseName, color);
      colorIndex++;
    }

    // Deduplication Key for recurring weekly classes per semester
    const dedupKey = `${semester}|${courseName.toLowerCase()}|${courseCode.toLowerCase()}|${startInfo.dayOfWeek}|${startInfo.timeStr}|${endInfo.timeStr}|${isExam ? `exam:${startInfo.dateStr}` : 'class'}`;

    const existing = eventMap.get(dedupKey);

    if (existing) {
      existing.dates.push(startInfo.dateStr);
    } else {
      eventMap.set(dedupKey, {
        event: {
          course_name: courseName,
          course_code: courseCode,
          instructor,
          location: rawEvt.location.trim(),
          day_of_week: startInfo.dayOfWeek,
          start_time: startInfo.timeStr,
          end_time: endInfo.timeStr,
          color,
          semester,
          semester_start: startInfo.dateStr,
          semester_end: startInfo.dateStr,
          event_type: isExam ? 'exam' : 'class',
        },
        dates: [startInfo.dateStr],
      });
    }
  }

  const valid: ParsedICSEvent[] = [];
  const eventsBySemester: Record<string, ParsedICSEvent[]> = {};

  eventMap.forEach(({ event, dates }) => {
    const clusters = event.event_type === 'exam'
      ? Array.from(new Set(dates)).map((date) => ({ start: date, end: date }))
      : clusterConsecutiveWeeks(dates);
    clusters.forEach((cluster) => {
      const clusteredEvent: ParsedICSEvent = {
        ...event,
        semester_start: cluster.start,
        semester_end: cluster.end,
      };
      valid.push(clusteredEvent);

      if (!eventsBySemester[event.semester]) {
        eventsBySemester[event.semester] = [];
      }
      eventsBySemester[event.semester].push(clusteredEvent);
    });
  });

  // Sort semesters chronologically
  const semesters = Object.keys(eventsBySemester).sort((a, b) => a.localeCompare(b));

  return {
    valid,
    errors: parseErrors,
    semesters,
    eventsBySemester,
  };
}

let icsWorker: Worker | null = null;
let nextWorkerRequestId = 1;
const pendingWorkerRequests = new Map<number, { resolve: (r: ParseICSResult) => void; fallback: () => void }>();

/** The worker died: finish every in-flight request on the main thread. */
function fallBackAllPendingRequests() {
  const pending = Array.from(pendingWorkerRequests.values());
  pendingWorkerRequests.clear();
  pending.forEach(({ fallback }) => fallback());
}

/**
 * Parse ICS content in a Web Worker so large calendar files never block the UI thread.
 * Falls back to the synchronous parser if workers are unavailable or fail to load.
 */
export function parseICSContentAsync(
  icsText: string,
  options: { includeExams?: boolean } = {}
): Promise<ParseICSResult> {
  if (typeof Worker === 'undefined') {
    return Promise.resolve(parseICSContent(icsText, options));
  }

  return new Promise((resolve, reject) => {
    const parseOnMainThread = () => {
      try {
        resolve(parseICSContent(icsText, options));
      } catch (err) {
        reject(err instanceof Error ? err : new Error(String(err)));
      }
    };

    if (!icsWorker) {
      try {
        icsWorker = new Worker(new URL('./ics-worker.ts', import.meta.url), { type: 'module' });
        icsWorker.onerror = (event) => {
          console.warn('ICS worker failed, falling back to synchronous parse:', event.message);
          icsWorker?.terminate();
          icsWorker = null;
          fallBackAllPendingRequests();
        };
      } catch (err) {
        // Worker creation itself can throw (e.g. blocked by CSP / bundler issues)
        console.warn('Could not create ICS worker, falling back to synchronous parse:', err);
        parseOnMainThread();
        return;
      }
    }

    const id = nextWorkerRequestId++;
    pendingWorkerRequests.set(id, { resolve, fallback: parseOnMainThread });

    const handleMessage = (event: MessageEvent) => {
      const response = event.data as { id?: number; result?: ParseICSResult; error?: string };
      if (!response || response.id !== id) return;
      icsWorker?.removeEventListener('message', handleMessage);
      pendingWorkerRequests.delete(id);

      if (response.error) {
        reject(new Error(response.error));
        return;
      }
      if (response.result) {
        resolve(response.result);
        return;
      }
      reject(new Error('ICS worker returned an empty result.'));
    };

    icsWorker.addEventListener('message', handleMessage);
    icsWorker.postMessage({ id, text: icsText, includeExams: options.includeExams });
  });
}

const ICS_FETCH_TIMEOUT_MS = 30_000;

/**
 * Fetch live ICS file from Neptun subscription URL with CORS proxy fallback
 */
export async function fetchNeptunICS(url: string, maxRetries = 3): Promise<string> {
  const cleanUrl = url.trim();
  if (!cleanUrl.startsWith('http://') && !cleanUrl.startsWith('https://')) {
    throw new Error('Invalid URL format. Please provide a valid http:// or https:// URL.');
  }

  const scriptUrl = 'https://script.google.com/macros/s/AKfycbxp_HKH85vTNjtVC5L2d8Ojhwt7BJQzxFVvvOi00UQK1w1G89RrMX11OaAMRHnD-5kj/exec';

  for (let attempt = 1; attempt <= maxRetries; attempt++) {
    try {
      const res = await fetch(`${scriptUrl}?url=${encodeURIComponent(cleanUrl)}`, { signal: timeoutSignal(ICS_FETCH_TIMEOUT_MS) });
      if (res.ok) {
        const text = await res.text();
        if (text && text.includes('BEGIN:VCALENDAR')) {
          return text;
        }
      } else {
        console.warn(`[ICS Fetch] Attempt ${attempt} failed with status ${res.status}`);
      }
    } catch (err) {
      console.warn(`[ICS Fetch] Attempt ${attempt} error:`, getErrorMessage(err, String(err)));
    }

    // Wait before retrying
    if (attempt < maxRetries) {
      await new Promise(resolve => setTimeout(resolve, 1500 * attempt));
    }
  }

  throw new Error(
    "We couldn't download your Neptun calendar — the university's servers may be blocking the sync, or the connection timed out. Check your connection and try again in a few minutes."
  );
}
