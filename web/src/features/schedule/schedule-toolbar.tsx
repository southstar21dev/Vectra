import React from 'react';
import { RiArrowLeftSLine, RiArrowRightSLine, RiResetLeftLine, RiGraduationCapLine } from '@remixicon/react';
import { Button } from '@/components/ui/button';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { cn } from '@/lib/utils';
import { getShortDayName } from '@/lib/date-utils';
import type { MonthWeek, AcademicWeekInfo } from './use-week-navigation';
import type { SemesterInfo } from './semester-status-banner';
import { dayOrder } from './use-schedule-filters';

export interface ScheduleToolbarProps {
  actions?: React.ReactNode;

  semesters?: string[];
  selectedSemester: string;

  activeWeek: MonthWeek;
  onPrevWeek: () => void;
  onNextWeek: () => void;
  onResetNavigation: () => void;
  isAtDefaultWeek: boolean;
  semesterInfo: SemesterInfo;
  academicWeekInfo: AcademicWeekInfo | null;

  selectedDay: number | 'all';
  onDayChange: (day: number | 'all') => void;
  onPillTouchStart?: (e: React.TouchEvent) => void;
  onPillTouchEnd?: (e: React.TouchEvent) => void;
}

export function ScheduleToolbar({
  actions,
  semesters = [],
  selectedSemester,
  activeWeek,
  onPrevWeek,
  onNextWeek,
  onResetNavigation,
  isAtDefaultWeek,
  semesterInfo,
  academicWeekInfo,
  selectedDay,
  onDayChange,
  onPillTouchStart,
  onPillTouchEnd,
}: ScheduleToolbarProps) {
  return (
    <div className="flex flex-col gap-3 p-3 sm:p-5 rounded-3xl card-glass schedule-flush-card shadow-lg min-w-0 max-w-full">
      <div className="flex items-center gap-2 w-full min-w-0">
        {/* Week switcher */}
        <div className="flex flex-1 min-w-0 items-center justify-between card-glass p-1 rounded-full h-10 shadow-xs">
          <Button
            variant="ghost"
            size="icon"
            onClick={onPrevWeek}
            title="Previous Week"
            className="h-8 w-8 rounded-full text-secondary hover:text-primary shrink-0"
          >
            <RiArrowLeftSLine className="w-4 h-4" />
          </Button>

          <div className="flex-1 flex items-center justify-center gap-1.5 px-1 min-w-0 font-mono">
            <span className="text-xs font-bold text-primary select-none whitespace-nowrap truncate">
              {activeWeek.rangeLabel}
            </span>
            {!isAtDefaultWeek && (
              <button
                onClick={onResetNavigation}
                title={semesterInfo.status === 'upcoming' ? 'Jump to semester start' : 'Jump to current week'}
                className="w-5 h-5 rounded-full inline-flex items-center justify-center text-tint-blue hover:text-tint-blue hover:bg-tint-blue/15 transition-colors cursor-pointer shrink-0"
              >
                <RiResetLeftLine className="w-3 h-3" />
              </button>
            )}
          </div>

          <Button
            variant="ghost"
            size="icon"
            onClick={onNextWeek}
            title="Next Week"
            className="h-8 w-8 rounded-full text-secondary hover:text-primary shrink-0"
          >
            <RiArrowRightSLine className="w-4 h-4" />
          </Button>
        </div>

        {/* Academic week number */}
        {academicWeekInfo && (
          <span
            className={cn(
              'inline-flex h-10 shrink-0 items-center gap-1.5 px-1.5 font-mono text-xs font-bold select-none',
              academicWeekInfo.isStarted ? 'text-accent-primary' : 'text-secondary'
            )}
          >
            <RiGraduationCapLine className="w-3.5 h-3.5 shrink-0" aria-hidden />
            {academicWeekInfo.label}
          </span>
        )}

      </div>

      {/* Day picker */}
      <div className="w-full min-w-0">
        <Tabs
          value={String(selectedDay)}
          onValueChange={(val) => onDayChange(val === 'all' ? 'all' : Number(val))}
          onTouchStart={onPillTouchStart}
          onTouchEnd={onPillTouchEnd}
          className="w-full"
        >
          <TabsList className="h-10 p-1 card-glass rounded-full flex items-center justify-between gap-1 w-full shadow-xs">
            <TabsTrigger
              value="all"
              data-value="all"
              className="h-8 px-2 sm:px-3.5 text-xs font-bold flex-1 min-w-0 text-center"
            >
              All
            </TabsTrigger>
            {dayOrder.map((d) => (
              <TabsTrigger
                key={d}
                value={String(d)}
                data-value={String(d)}
                className="h-8 px-1.5 sm:px-3 text-xs font-bold flex-1 min-w-0 text-center"
              >
                {getShortDayName(d)}
              </TabsTrigger>
            ))}
          </TabsList>
        </Tabs>
      </div>


      {/* Wide screens have no navbar action bubble: the semester and the
          sync/add actions sit here instead. */}
      <div className="hidden xl:flex items-center justify-between gap-3 border-t border-subtle pt-3 min-w-0">
        {semesters.length > 0 ? (
          <p className="min-w-0 truncate pl-1 font-mono text-xs font-bold text-muted" title={`Semester ${selectedSemester}`}>
            <span className="sr-only">Semester </span>
            {selectedSemester}
          </p>
        ) : (
          <span />
        )}
        {actions}
      </div>
    </div>
  );
}
