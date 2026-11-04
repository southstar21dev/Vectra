import type { SemesterInfo } from './semester-status-banner';

/**
 * Whether the semester status banner has anything to say about this semester.
 *
 * Lives outside the component so the page can ask the same question before
 * wrapping it in an entrance: an empty `Reveal` would still be a box in the
 * schedule's `space-y-4` stack, and would add a gap where there is no banner.
 */
export function shouldShowSemesterStatus(
  semesterInfo: SemesterInfo,
  selectedSemester: string
): boolean {
  return (
    semesterInfo.status === 'upcoming' ||
    (semesterInfo.status === 'past' && selectedSemester !== 'all')
  );
}
