import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';
import type { StatusTone } from '@/components/ui/status-label';

/**
 * Merge Tailwind CSS classes with clsx
 */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/**
 * Generate a random color for schedule events
 */
export function generateEventColor(): string {
  const colors = [
    '#4A90D9', '#7B61FF', '#50C878', '#FF6B6B', '#FFB347',
    '#87CEEB', '#DDA0DD', '#98D8C8', '#F7DC6F', '#BB8FCE',
    '#85C1E9', '#82E0AA', '#F8C471', '#D98880', '#AED6F1',
  ];
  return colors[Math.floor(Math.random() * colors.length)];
}

/**
 * Check if an event or course carries an intensive keyword in its name, code
 * or type (e.g. "Intensive", "Intenzív", "Blokk"). Date-based detection is
 * handled separately by isIntensiveCourseEvents using the temporal rule.
 */
export function isIntensiveClass(params?: {
  course_name?: string | null;
  course_code?: string | null;
  event_type?: string | null;
} | null): boolean {
  if (!params) return false;
  const upper = `${params.course_name || ''} ${params.course_code || ''} ${params.event_type || ''}`.toUpperCase();
  return /INTENSIVE|INTENZ[IÍ]V|BLOCK|BLOKK/.test(upper);
}

/**
 * The calendar week (Monday–Sunday) that falls right before the week
 * containing `anchorDate` — i.e. the week immediately before classes start.
 * Returns null when the anchor date is missing or invalid.
 */
export function getWeekBeforeStart(anchorDate: string | null | undefined): { startStr: string; endStr: string } | null {
  if (!anchorDate || !/^\d{4}-\d{2}-\d{2}$/.test(anchorDate)) return null;
  const [y, m, d] = anchorDate.split('-').map(Number);
  const anchor = new Date(y, m - 1, d);
  const anchorDay = anchor.getDay();
  const weekMonDiff = 1 - (anchorDay === 0 ? 7 : anchorDay);
  const preMonday = new Date(y, m - 1, d + weekMonDiff - 7);
  const preSunday = new Date(y, m - 1, d + weekMonDiff - 1);
  const fmt = (dt: Date) =>
    `${dt.getFullYear()}-${String(dt.getMonth() + 1).padStart(2, '0')}-${String(dt.getDate()).padStart(2, '0')}`;
  return { startStr: fmt(preMonday), endStr: fmt(preSunday) };
}

/**
 * Determine whether a COURSE (given all of its schedule events) is an
 * intensive week class, relative to the official semester start.
 *
 * An intensive week class is simply a class that happens in the week right
 * before classes start and never happens again after: its entire date window
 * (earliest start to latest end across all its events) falls inside the week
 * immediately preceding the semester-start week.
 */
export function isIntensiveCourseEvents(
  events: Array<{
    course_name?: string | null;
    course_code?: string | null;
    event_type?: string | null;
    semester_start?: string | null;
    semester_end?: string | null;
  }>,
  officialStart: string | null | undefined
): boolean {
  if (events.length === 0) return false;

  // 1. Any event carrying an intensive keyword
  if (
    events.some((e) =>
      isIntensiveClass({
        course_name: e.course_name,
        course_code: e.course_code,
        event_type: e.event_type,
      })
    )
  ) {
    return true;
  }

  // 2. Temporal rule: the whole course happens in the week right before
  //    classes start and never happens again after.
  const preWeek = getWeekBeforeStart(officialStart);
  if (!preWeek) return false;

  const valid = (d?: string | null): d is string => !!d && /^\d{4}-\d{2}-\d{2}$/.test(d);
  const starts = events.map((e) => e.semester_start).filter(valid);
  if (starts.length === 0) return false;

  const minStart = starts.reduce((a, b) => (a < b ? a : b));
  const ends = events.map((e) => e.semester_end).filter(valid);
  const maxEnd = ends.length > 0 ? ends.reduce((a, b) => (a > b ? a : b)) : minStart;

  return minStart >= preWeek.startStr && maxEnd <= preWeek.endStr;
}

/**
 * Determine class type (Seminar/Lab/Lecture/Intensive) from course code or event type
 */
export function getClassType(code?: string | null): { label: string; tone: StatusTone } | null {
  if (!code) return null;
  const upper = code.trim().toUpperCase();

  if (/INTENSIVE|INTENZ[IÍ]V|BLOCK|BLOKK/.test(upper)) {
    return { label: 'Intensive', tone: 'amber' };
  }

  // 1. Direct keyword matches
  if (/LECTURE|ELŐADÁS|ELOADAS|\bEA\b/.test(upper)) {
    return { label: 'Lecture', tone: 'sky' };
  }
  if (/SEMINAR|SZEMINÁRIUM|SZEMINARIUM|GYAKORLAT|GYAK|\bGY\b|PRACTICE/.test(upper)) {
    return { label: 'Seminar', tone: 'emerald' };
  }
  if (/LAB|LABOR|LABORATÓRIUM|LABORATORIUM/.test(upper)) {
    return { label: 'Lab', tone: 'purple' };
  }

  // 2. Tokenized match (e.g. E01-GM-EoA -> ['E01', 'GM', 'EOA'])
  const tokens = upper.split(/[-_/.\s]+/);
  for (const token of tokens) {
    if (/^E\d*$/.test(token)) {
      return { label: 'Lecture', tone: 'sky' };
    }
    if (/^G\d*$/.test(token)) {
      return { label: 'Seminar', tone: 'emerald' };
    }
    if (/^L\d*$/.test(token)) {
      return { label: 'Lab', tone: 'purple' };
    }
  }

  // 3. Prefix match on whole string (e.g. E01GM, G02CS)
  const prefixMatch = upper.match(/^([GLE])\d+/);
  if (prefixMatch) {
    if (prefixMatch[1] === 'G') return { label: 'Seminar', tone: 'emerald' };
    if (prefixMatch[1] === 'E') return { label: 'Lecture', tone: 'sky' };
    if (prefixMatch[1] === 'L') return { label: 'Lab', tone: 'purple' };
  }

  // 4. Suffix match (e.g. MAT201-E, CS101-G2)
  const suffixMatch = upper.match(/([GLE])\d*$/);
  if (suffixMatch) {
    if (suffixMatch[1] === 'G') return { label: 'Seminar', tone: 'emerald' };
    if (suffixMatch[1] === 'E') return { label: 'Lecture', tone: 'sky' };
    if (suffixMatch[1] === 'L') return { label: 'Lab', tone: 'purple' };
  }

  return null;
}

/**
 * Extract group code (e.g. G01, E01, L01, G02, E03) from a course code or event string
 */
export function extractGroupCode(code?: string | null): string | null {
  if (!code) return null;
  const upper = code.trim().toUpperCase();

  // 1. Direct tokens (e.g. "7SO20NGMA01M-G01" -> "G01", "CS101_E02" -> "E02", "L01" -> "L01", "G1" -> "G01")
  const tokens = upper.split(/[-_/.\s]+/);
  for (const token of tokens) {
    const m = token.match(/^([GLE])(\d{1,3})$/);
    if (m) {
      return `${m[1]}${m[2].padStart(2, '0')}`;
    }
  }

  // 2. Pattern anywhere in string matching [GLE]\d+ (e.g. "MAT201G01" -> "G01", "7SO20NGMA01M-G01" -> "G01")
  const match = upper.match(/([GLE])(\d{1,3})\b/);
  if (match) {
    return `${match[1]}${match[2].padStart(2, '0')}`;
  }

  return null;
}

export function getGreeting(): string {
  const hour = new Date().getHours();
  if (hour < 12) return 'Good morning';
  if (hour < 17) return 'Good afternoon';
  return 'Good evening';
}
