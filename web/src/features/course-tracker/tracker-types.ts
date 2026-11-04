import { z } from 'zod';
import { genId } from '@/lib/id';

export type AttendanceStatus = 'present' | 'absent' | 'excused' | 'cancelled' | 'unmarked';
export type SessionType = 'lecture' | 'seminar' | 'lab' | 'consultation' | 'exam' | 'other';

/** Default course total points — the denominator of the Points Earned card. */
export const DEFAULT_COURSE_TOTAL_POINTS = 100;

/**
 * Uniquely identifies a course regardless of where it is seen (schedule event,
 * check-in button, Course Hub modal). Course codes may carry a group/session
 * suffix (e.g. `X5VX18SUBM01M-G10`); the canonical key strips those.
 */
export interface CourseIdentity {
  courseCode?: string | null;
  courseName?: string | null;
  semester?: string | null;
  instructor?: string | null;
  location?: string | null;
  color?: string | null;
}

export const attendanceStatusSchema = z.enum(['present', 'absent', 'excused', 'cancelled', 'unmarked']);
export const sessionTypeSchema = z.enum(['lecture', 'seminar', 'lab', 'consultation', 'exam', 'other']);

export const attendanceSessionSchema = z.object({
  id: z.string().min(1),
  date: z.string(), // ISO format 'YYYY-MM-DD'
  startTime: z.string().optional(),
  endTime: z.string().optional(),
  title: z.string(),
  sessionType: sessionTypeSchema,
  status: attendanceStatusSchema,
  notes: z.string().optional(),
  isCustom: z.boolean().optional(),
});

export type AttendanceSession = z.infer<typeof attendanceSessionSchema>;

/**
 * Canonical slot key for a session (`date_startTime_sessionType`). Used to
 * remember deleted generated sessions (`removedSessionKeys`) so the schedule
 * reconciler never resurrects them, and to clear that tombstone when the
 * student marks the slot again on purpose.
 */
export function sessionSlotKey(
  session: Pick<AttendanceSession, 'date' | 'startTime' | 'sessionType'>
): string {
  const raw = (session.startTime || '').trim();
  const match = raw.match(/^(\d{1,2}):(\d{1,2})/);
  const time = match ? `${match[1].padStart(2, '0')}:${match[2].padStart(2, '0')}` : '';
  return `${(session.date || '').trim()}_${time}_${session.sessionType}`;
}

export const gradingScaleTierSchema = z.object({
  grade: z.union([z.literal(1), z.literal(2), z.literal(3), z.literal(4), z.literal(5)]),
  minPercentage: z.number().min(0).max(100),
});

export type GradingScaleTier = z.infer<typeof gradingScaleTierSchema>;

export const assessmentItemSchema = z.object({
  id: z.string().min(1),
  name: z.string().min(1),
  earnedPoints: z.number().min(0),
  maxPoints: z.number().min(0),
  weightPercent: z.number().min(0).max(100).nullable().optional(),
  isMustPass: z.boolean().optional(),
  minPassPoints: z.number().min(0).nullable().optional(),
  isCompleted: z.boolean().optional(),
  date: z.string().nullable().optional(),
  notes: z.string().optional(),
});

export type AssessmentItem = z.infer<typeof assessmentItemSchema>;

export const courseTrackerSchema = z.object({
  id: z.string().min(1),
  courseKey: z.string().min(1),
  courseName: z.string().min(1),
  courseCode: z.string().default(''),
  instructor: z.string().optional(),
  location: z.string().optional(),
  semester: z.string().default(''),
  color: z.string().optional(),
  /**
   * True once the student has confirmed the attendance limits and grading
   * scale against their syllabus (or edited them). Until then the Settings
   * tab shows a "check your syllabus" banner, because seeded defaults must
   * never masquerade as the course's actual rules.
   */
  settingsConfirmed: z.boolean().default(false),
  attendance: z.object({
    maxAbsencesAllowed: z.number().int().min(0).nullable(),
    minAttendancePercent: z.number().min(0).max(100).nullable(),
    sessions: z.array(attendanceSessionSchema),
    /**
     * Slot keys of generated sessions the student explicitly deleted. The
     * schedule reconciler must skip these so a delete is not silently undone
     * (and a logged absence is not regenerated as unmarked).
     */
    removedSessionKeys: z.array(z.string()).default([]),
  }),
  points: z.object({
    useWeightedCategories: z.boolean().default(false),
    gradingScale: z.array(gradingScaleTierSchema),
    assessments: z.array(assessmentItemSchema),
    /**
     * The course's total points — the denominator of the "Points Earned"
     * card. Defaults to 100 so a single 18/20 assessment reads 18/100 rather
     * than 18/20, and so "pts remaining" always answers "how much of the
     * course is left", not "how much of what I have logged".
     */
    totalPoints: z.number().min(1).max(10000).default(DEFAULT_COURSE_TOTAL_POINTS),
  }),
  updatedAt: z.string(),
});

export type CourseTracker = z.infer<typeof courseTrackerSchema>;

export const courseTrackerDataSchema = z.object({
  version: z.literal(1),
  trackers: z.record(z.string(), courseTrackerSchema),
});

export type CourseTrackerData = z.infer<typeof courseTrackerDataSchema>;

export const DEFAULT_GRADING_SCALE: GradingScaleTier[] = [
  { grade: 5, minPercentage: 87 },
  { grade: 4, minPercentage: 75 },
  { grade: 3, minPercentage: 62 },
  { grade: 2, minPercentage: 50 },
  { grade: 1, minPercentage: 0 },
];

/** Seeded attendance limits — defaults to confirm, never asserted facts. */
export const DEFAULT_ATTENDANCE_LIMITS = {
  maxAbsencesAllowed: 3,
  minAttendancePercent: null,
} as const;

export function normalizeCourseKey(courseCodeOrName: string, semester?: string): string {
  const sanitized = courseCodeOrName
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
  const sem = (semester || '').trim().toLowerCase().replace(/[^a-z0-9]+/g, '-');
  return sem ? `${sanitized}_${sem}` : sanitized;
}

export function createDefaultTracker(params: {
  courseName: string;
  courseCode?: string;
  semester?: string;
  instructor?: string;
  location?: string;
  color?: string;
}): CourseTracker {
  const courseCode = params.courseCode || '';
  const semester = params.semester || '';
  const keyIdentifier = courseCode || params.courseName;
  return {
    id: genId(),
    courseKey: normalizeCourseKey(keyIdentifier, semester),
    courseName: params.courseName.trim(),
    courseCode,
    instructor: params.instructor,
    location: params.location,
    semester,
    color: params.color,
    settingsConfirmed: false,
    attendance: {
      maxAbsencesAllowed: DEFAULT_ATTENDANCE_LIMITS.maxAbsencesAllowed,
      minAttendancePercent: DEFAULT_ATTENDANCE_LIMITS.minAttendancePercent,
      sessions: [],
      removedSessionKeys: [],
    },
    points: {
      useWeightedCategories: false,
      gradingScale: [...DEFAULT_GRADING_SCALE],
      assessments: [],
      totalPoints: DEFAULT_COURSE_TOTAL_POINTS,
    },
    updatedAt: new Date().toISOString(),
  };
}

export function createEmptyTrackerData(): CourseTrackerData {
  return {
    version: 1,
    trackers: {},
  };
}

export function parseTrackerData(raw: string | null | undefined): CourseTrackerData | null {
  if (!raw) return null;
  try {
    const parsed = courseTrackerDataSchema.parse(JSON.parse(raw));
    const nextTrackers: Record<string, CourseTracker> = {};
    for (const [key, tracker] of Object.entries(parsed.trackers || {})) {
      const seenSlots = new Map<string, AttendanceSession>();
      for (const s of tracker.attendance?.sessions || []) {
        if (!s || !s.date) continue;
        const d = s.date.trim();
        const slotKey = `${d}_${s.startTime || ''}_${s.sessionType || ''}`;
        const existing = seenSlots.get(slotKey);
        if (!existing) {
          seenSlots.set(slotKey, s);
        } else {
          const isExistingUnmarked = !existing.status || existing.status === 'unmarked';
          const isNewMarked = s.status && s.status !== 'unmarked';
          if (isExistingUnmarked && isNewMarked) {
            seenSlots.set(slotKey, { ...s, startTime: s.startTime || existing.startTime, endTime: s.endTime || existing.endTime });
          } else {
            seenSlots.set(slotKey, { ...existing, startTime: existing.startTime || s.startTime, endTime: existing.endTime || s.endTime });
          }
        }
      }
      nextTrackers[key] = {
        ...tracker,
        attendance: {
          ...tracker.attendance,
          sessions: Array.from(seenSlots.values()).sort((a, b) => {
            const dateComp = a.date.localeCompare(b.date);
            if (dateComp !== 0) return dateComp;
            return (a.startTime || '').localeCompare(b.startTime || '');
          }),
        },
      };
    }
    return { ...parsed, trackers: nextTrackers };
  } catch {
    return null;
  }
}

export function serializeTrackerData(data: CourseTrackerData): string {
  return JSON.stringify(data);
}
