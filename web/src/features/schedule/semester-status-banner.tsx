import { RiCalendarLine, RiResetLeftLine } from '@remixicon/react';
import { Button } from '@/components/ui/button';
import { StatusLabel } from '@/components/ui/status-label';
import { formatDDMMYYYY } from '@/lib/date-utils';
import { shouldShowSemesterStatus } from './semester-status-banner-logic';

export interface SemesterInfo {
  status: 'current' | 'active' | 'upcoming' | 'past' | string;
  startDate?: string | null;
  endDate?: string | null;
  daysUntil?: number;
  activeSemester?: string;
}

export interface SemesterStatusBannerProps {
  semesterInfo: SemesterInfo;
  selectedSemester: string;
  activeWeekRangeLabel: string;
  isAtDefaultWeek: boolean;
  onResetNavigation: () => void;
}

export function SemesterStatusBanner({
  semesterInfo,
  selectedSemester,
  activeWeekRangeLabel,
  isAtDefaultWeek,
  onResetNavigation,
}: SemesterStatusBannerProps) {
  if (!shouldShowSemesterStatus(semesterInfo, selectedSemester)) return null;

  if (semesterInfo.status === 'upcoming') {
    return (
      <div className="p-4 rounded-3xl card-glass schedule-flush-card bg-tint-blue border border-tint-blue text-xs shadow-md space-y-3">
        <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3">
          <div className="flex items-start gap-3 min-w-0">
            <div className="w-8 h-8 rounded-xl bg-tint-blue border border-tint-blue flex items-center justify-center text-tint-blue shrink-0 mt-0.5">
              <RiCalendarLine className="w-4 h-4" />
            </div>
            <div className="space-y-2">
              <div className="flex items-center gap-2.5 flex-wrap">
                <StatusLabel tone="blue">Upcoming semester</StatusLabel>
                <p className="font-bold text-primary text-xs">
                  {semesterInfo.startDate ? `Starts ${formatDDMMYYYY(semesterInfo.startDate)}` : 'Upcoming'}
                </p>
              </div>
              <p className="text-secondary text-[11px] leading-relaxed pt-1">
                Classes start in {semesterInfo.daysUntil ?? 0} days. Showing schedule for: {activeWeekRangeLabel}.
              </p>
            </div>
          </div>

          {!isAtDefaultWeek && (
            <Button
              size="sm"
              variant="ghost"
              onClick={onResetNavigation}
              className="h-8 w-full sm:w-auto rounded-full border border-subtle bg-surface-overlay px-2.5 text-xs font-semibold text-secondary hover:text-primary cursor-pointer justify-center shrink-0"
            >
              <RiResetLeftLine className="w-3.5 h-3.5 mr-1 text-tint-blue" aria-hidden />
              <span>Jump to semester start</span>
            </Button>
          )}
        </div>
      </div>
    );
  }

  // The only other semester the helper lets through is a past one.
  if (semesterInfo.status === 'past' && selectedSemester !== 'all') {
    return (
      <div className="p-4 rounded-3xl card-glass schedule-flush-card bg-tint-purple border border-tint-purple text-xs shadow-md space-y-3">
        <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3">
          <div className="flex items-start gap-3 min-w-0">
            <div className="w-8 h-8 rounded-xl bg-tint-purple border border-tint-purple flex items-center justify-center text-tint-purple shrink-0 mt-0.5">
              <RiResetLeftLine className="w-4 h-4" />
            </div>
            <div className="space-y-2">
              <div className="flex items-center gap-2.5 flex-wrap">
                <StatusLabel tone="purple">Past semester</StatusLabel>
                <p className="font-bold text-primary text-xs">
                  Semester {selectedSemester}
                </p>
              </div>
              <p className="text-secondary text-[11px] leading-relaxed pt-1">
                {semesterInfo.startDate ? `Semester started on ${formatDDMMYYYY(semesterInfo.startDate)}. ` : ''}
                Showing schedule for: {activeWeekRangeLabel}.
              </p>
            </div>
          </div>

          {!isAtDefaultWeek && (
            <Button
              size="sm"
              variant="ghost"
              onClick={onResetNavigation}
              className="h-8 w-full sm:w-auto rounded-full border border-subtle bg-surface-overlay px-2.5 text-xs font-semibold text-secondary hover:text-primary cursor-pointer justify-center shrink-0"
            >
              <RiResetLeftLine className="w-3.5 h-3.5 mr-1 text-tint-purple" aria-hidden />
              <span>Jump to semester start</span>
            </Button>
          )}
        </div>
      </div>
    );
  }

  return null;
}
