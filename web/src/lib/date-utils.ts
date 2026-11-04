import type { TimeFormat } from '@/features/settings/appearance-prefs';

/**
 * Format a Date to YYYY-MM-DD string (zero-padded).
 * Used everywhere for date comparisons against semester_start/semester_end.
 *
 * NOTE: uses the runtime's local calendar date. For "what day is it for the
 * user's schedule" comparisons use `nowInBudapest()` instead — schedule data
 * is normalized to Europe/Budapest wall-clock by the ICS parser.
 */
export function formatYMD(date: Date = new Date()): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

export interface BudapestNow {
  year: number;
  /** 1–12. */
  month: number;
  day: number;
  /** 0 = Sunday … 6 = Saturday (same convention as Date#getDay). */
  dayOfWeek: number;
  hours: number;
  minutes: number;
  /** Budapest calendar date as YYYY-MM-DD. */
  ymd: string;
}

/**
 * The current Budapest wall-clock date/time, independent of the device's
 * timezone. Schedule events are stored as Budapest local dates and times, so
 * "today"/"now" checks must use this instead of `new Date()` accessors —
 * otherwise a device in another timezone highlights the wrong day/class.
 */
export function nowInBudapest(at: Date = new Date()): BudapestNow {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: 'Europe/Budapest',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(at);
  const part = (type: Intl.DateTimeFormatPartTypes): number => {
    const value = parts.find((p) => p.type === type)?.value ?? '0';
    return Number.parseInt(value, 10);
  };
  const year = part('year');
  const month = part('month');
  const day = part('day');
  // Compute the weekday from the Y-M-D triple in UTC — independent of the
  // runtime timezone (unlike new Date(y, m-1, d).getDay()).
  const dayOfWeek = new Date(Date.UTC(year, month - 1, day)).getUTCDay();
  return {
    year,
    month,
    day,
    dayOfWeek,
    hours: part('hour'),
    minutes: part('minute'),
    ymd: `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`,
  };
}

export interface BudapestClockOptions {
  /** 12-hour clock (en-US) instead of the default 24-hour (hu-HU). */
  hour12?: boolean;
}

/**
 * Budapest wall-clock time. The schedule/transit features store and compare
 * Budapest local times, so display must not depend on the device timezone.
 */
export function formatBudapestClock(ms: number, options: BudapestClockOptions = {}): string {
  if (options.hour12) {
    return new Date(ms).toLocaleTimeString('en-US', {
      hour: 'numeric',
      minute: '2-digit',
      timeZone: 'Europe/Budapest',
    });
  }
  return new Date(ms).toLocaleTimeString('hu-HU', {
    hour: '2-digit',
    minute: '2-digit',
    timeZone: 'Europe/Budapest',
  });
}

/** `HH:MM` in Budapest wall-clock (24h, zero-padded) for timelines. */
export function formatBudapestHm(ms: number): string {
  return new Intl.DateTimeFormat('en-GB', {
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
    timeZone: 'Europe/Budapest',
  }).format(ms);
}

/**
 * Strip seconds from time string to ensure 24h HH:mm format (e.g. "08:00:00" => "08:00")
 */
export function formatHHMM(time: string | null | undefined): string {
  if (!time) return '';
  const parts = String(time).split(':');
  if (parts.length >= 2) {
    return `${parts[0].padStart(2, '0')}:${parts[1].padStart(2, '0')}`;
  }
  return String(time);
}

/**
 * Format time according to user preference ('24h' | '12h')
 * Defaults to '24h' (standard zero-padded HH:mm).
 */
export function formatTime(time: string | null | undefined, format: TimeFormat = '24h'): string {
  if (!time) return '';
  const clean = formatHHMM(time);
  if (format === '24h') {
    return clean;
  }
  const [hours, minutes] = clean.split(':').map(Number);
  const period = hours >= 12 ? 'PM' : 'AM';
  const displayHours = hours % 12 || 12;
  return `${displayHours}:${String(minutes).padStart(2, '0')} ${period}`;
}

/**
 * Get day name from day number (0=Sunday, 6=Saturday)
 */
export function getDayName(dayOfWeek: number): string {
  const days = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
  return days[dayOfWeek] || '';
}

/**
 * Get short day name
 */
export function getShortDayName(dayOfWeek: number): string {
  const days = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  return days[dayOfWeek] || '';
}

/** Budapest wall-clock timestamp honoring the user's 12h/24h preference. */
export function formatClock(ms: number, format: TimeFormat = '24h'): string {
  return formatBudapestClock(ms, { hour12: format === '12h' });
}

/**
 * Format a date string or object to DD/MM/YYYY format
 */
export function formatDDMMYYYY(date: string | Date | null | undefined): string {
  if (!date) return '';
  // A bare calendar date parses as UTC midnight, which is the previous day
  // west of UTC; format its parts directly instead.
  if (typeof date === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(date)) {
    const [year, month, day] = date.split('-');
    return `${day}/${month}/${year}`;
  }
  const d = new Date(date);
  if (isNaN(d.getTime())) return String(date);
  const day = String(d.getDate()).padStart(2, '0');
  const month = String(d.getMonth() + 1).padStart(2, '0');
  const year = d.getFullYear();
  return `${day}/${month}/${year}`;
}

/** `12 October` from a `YYYY-MM-DD` calendar date (no time zone shift). */
export function formatDayMonth(ymd: string): string {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(ymd)) return ymd;
  const [year, month, day] = ymd.split('-').map(Number);
  return new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'long', timeZone: 'UTC' }).format(
    Date.UTC(year, month - 1, day)
  );
}

/** Full calendar validation: the Y-M-D triple must round-trip through UTC. */
export function isValidCalendarDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const [year, month, day] = value.split('-').map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  return date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day;
}

/** Regex-only YYYY-MM-DD guard (does not validate the calendar date). */
export function isYmdString(value: string | null | undefined): value is string {
  return !!value && /^\d{4}-\d{2}-\d{2}$/.test(value);
}

/** `HH:MM` from minutes since midnight; minutes ≥ 1440 fold to `24:00`. */
export function formatMinuteOfDay(minute: number): string {
  if (minute >= 1440) return '24:00';
  const normalized = Math.max(0, minute);
  return `${String(Math.floor(normalized / 60)).padStart(2, '0')}:${String(normalized % 60).padStart(2, '0')}`;
}
