/**
 * Exam classification, shared by the ICS importer and the UI.
 *
 * Neptun marks Hungarian exams with a "- Vizsga" suffix, but an exam titled in
 * English names itself instead ("Elements of Accounting Midterm Exam"). A
 * suffix-only test classified every one of those as a recurring class, so the
 * row said event_type 'class' and the UI drew a CLASS badge and offered
 * attendance check-in for an exam that happens once.
 *
 * This lives in its own module rather than in ics-parser.ts because the parser
 * is deliberately kept off the main bundle (it runs in ics-worker.ts), and the
 * card needs the same judgement.
 */

/** Exam-type parenthetical Neptun puts where a class would carry a course code. */
const EXAM_TYPE_PARENTHETICAL = /^(írásbeli|szóbeli|irasbeli|szobeli|written|oral)$/i;

/**
 * Whether a parenthetical is an exam type rather than a course code, e.g.
 * "Elements of Accounting (Írásbeli)" — the parser must not read "Írásbeli" as
 * the course code.
 */
export function isExamTypeParenthetical(value: string): boolean {
  return EXAM_TYPE_PARENTHETICAL.test(value.trim().replace(/^-\s*/, '').trim());
}

/**
 * Hungarian midterm markers: "zárthelyi (dolgozat)" and its abbreviation "ZH"
 * ("1.zh", "ZH2", "pótZH" for the resit). "ZH" is only matched as a whole
 * token so it never fires inside an ordinary word. The looser "évközi" is
 * deliberately not a marker: it also names a grading form ("évközi jegy") that
 * appears in ordinary course titles.
 */
const HUNGARIAN_MIDTERM = [/z[aá]rthelyi/i, /\bzh\d*\b/i, /p[oó]t\s*-?\s*zh\d*\b/i];

/**
 * Whether a Neptun summary describes an exam rather than a recurring class.
 *
 * A bare "exam" is treated as a positive signal: in a course calendar it is an
 * exam marker far more often than it is part of a course title.
 */
export function isExamSummary(summary: string): boolean {
  if (!summary) return false;
  if (/-\s*Vizsga\s*$/i.test(summary)) return true;
  const parenthetical = summary.match(/\(\s*(?:-\s*)?([^)]+)\)/);
  if (parenthetical && EXAM_TYPE_PARENTHETICAL.test(parenthetical[1].trim())) return true;
  if (HUNGARIAN_MIDTERM.some((pattern) => pattern.test(summary))) return true;
  return /\bexam\b/i.test(summary);
}

export interface ExamClassifiableEvent {
  event_type?: string | null;
  course_name?: string | null;
}

/**
 * Whether a stored schedule event is an exam.
 *
 * Rows imported before isExamSummary covered English titles still carry
 * event_type 'class', so the title is checked as a fallback — otherwise the
 * importer fix would need a full re-sync before the UI agreed with it. An
 * explicit non-class type ('other') is never overridden.
 */
export function isExamEvent(event: ExamClassifiableEvent): boolean {
  const type = String(event.event_type || '').toLowerCase();
  if (type === 'exam') return true;
  if (type && type !== 'class') return false;
  return isExamSummary(String(event.course_name || ''));
}
