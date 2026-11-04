import { nowInBudapest } from '@/lib/date-utils';

export interface Holiday {
  month: number;
  day: number;
  name: string;
  hungarian: string;
}

/**
 * Easter Sunday date calculation using a simplified Gauss algorithm.
 * Returns { month, day } for the given year.
 */
function easterSunday(year: number): { month: number; day: number } {
  const a = year % 19;
  const b = Math.floor(year / 100);
  const c = year % 100;
  const d = Math.floor(b / 4);
  const e = b % 4;
  const f = Math.floor((b + 8) / 25);
  const g = Math.floor((b - f + 1) / 3);
  const h = (19 * a + b - d - g + 15) % 30;
  const i = Math.floor(c / 4);
  const k = c % 4;
  const l = (32 + 2 * e + 2 * i - h - k) % 7;
  const m = Math.floor((a + 11 * h + 22 * l) / 451);
  const month = Math.floor((h + l - 7 * m + 114) / 31);
  const day = ((h + l - 7 * m + 114) % 31) + 1;
  return { month, day };
}

/**
 * Fixed-date Hungarian public holidays (month/day).
 * Easter-based holidays are computed dynamically per year.
 */
const FIXED_HOLIDAYS: Holiday[] = [
  { month: 1, day: 1, name: "New Year's Day", hungarian: "Újév" },
  { month: 3, day: 15, name: "Revolution of 1848", hungarian: "Nemzeti ünnep" },
  { month: 5, day: 1, name: "Labour Day", hungarian: "A munka ünnepe" },
  { month: 8, day: 20, name: "State Foundation Day", hungarian: "Szent István ünnepe" },
  { month: 10, day: 23, name: "Revolution of 1956", hungarian: "Nemzeti ünnep" },
  { month: 11, day: 1, name: "All Saints' Day", hungarian: "Mindenszentek" },
  { month: 12, day: 25, name: "Christmas Day", hungarian: "Karácsony" },
  { month: 12, day: 26, name: "Second Day of Christmas", hungarian: "Karácsony másnapja" },
];

/**
 * Compute Easter-based Hungarian public holidays for a given year.
 */
function easterBasedHolidays(year: number): Holiday[] {
  const e = easterSunday(year);

  // UTC calendar arithmetic: independent of the device's zone and DST.
  const addDays = (days: number) => {
    const d = new Date(Date.UTC(year, e.month - 1, e.day + days));
    return { month: d.getUTCMonth() + 1, day: d.getUTCDate() };
  };

  const goodFriday = addDays(-2);
  const easterMonday = addDays(1);
  const whitSunday = addDays(49);
  const whitMonday = addDays(50);

  return [
    { ...goodFriday, name: "Good Friday", hungarian: "Nagypéntek" },
    { month: e.month, day: e.day, name: "Easter Sunday", hungarian: "Húsvétvasárnap" },
    { ...easterMonday, name: "Easter Monday", hungarian: "Húsvéthétfő" },
    { ...whitSunday, name: "Whit Sunday", hungarian: "Pünkösdvasárnap" },
    { ...whitMonday, name: "Whit Monday", hungarian: "Pünkösdhétfő" },
  ];
}

/**
 * Get all Hungarian holidays for a given year.
 */
export function getHungarianHolidays(year: number): Holiday[] {
  const easterHolidays = easterBasedHolidays(year);
  return [...FIXED_HOLIDAYS, ...easterHolidays];
}

export interface UpcomingHolidayResult {
  holiday: Holiday;
  daysUntil: number;
}

/**
 * Whether a Budapest calendar date (YYYY-MM-DD) is a Hungarian public holiday
 * (munkaszüneti nap): no classes are held, so weekly classes skip it.
 */
export function isHungarianPublicHoliday(ymd: string): boolean {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(ymd);
  if (!match) return false;
  const [year, month, day] = [Number(match[1]), Number(match[2]), Number(match[3])];
  return getHungarianHolidays(year).some((h) => h.month === month && h.day === day);
}

/**
 * Find all upcoming Hungarian holidays within `maxDaysAhead` (default 14 days / 2 weeks).
 * If multiple holidays fall within 2 weeks, returns all of them sorted chronologically.
 * "Today" is the Budapest calendar date of `now`, not the device's.
 */
export function getUpcomingHolidays(now: Date, maxDaysAhead: number = 14): UpcomingHolidayResult[] {
  const today = nowInBudapest(now);
  const currentYear = today.year;
  const todayMs = Date.UTC(currentYear, today.month - 1, today.day);

  // Build candidate holidays with their explicit year
  const candidates = [currentYear, currentYear + 1].flatMap((year) =>
    getHungarianHolidays(year).map((holiday) => ({
      holiday,
      resolvedMs: Date.UTC(year, holiday.month - 1, holiday.day),
    }))
  );

  return candidates
    .map(({ holiday, resolvedMs }) => ({ holiday, resolvedMs, daysUntil: Math.round((resolvedMs - todayMs) / 86_400_000) }))
    .filter((r) => r.daysUntil >= 0 && r.daysUntil <= maxDaysAhead)
    .sort((a, b) => a.resolvedMs - b.resolvedMs)
    .map(({ holiday, daysUntil }) => ({ holiday, daysUntil }));
}

/**
 * Find the next upcoming Hungarian holiday from `now`.
 * Returns today's holiday (daysUntil=0) if one falls on this date,
 * otherwise the nearest holiday within the next 14 days (2 weeks), or null.
 */
export function getNextHoliday(now: Date, maxDaysAhead: number = 14): UpcomingHolidayResult | null {
  const all = getUpcomingHolidays(now, maxDaysAhead);
  return all.length > 0 ? all[0] : null;
}
