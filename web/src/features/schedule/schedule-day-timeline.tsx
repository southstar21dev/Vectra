import { useMemo } from 'react';
import { RiCupLine } from '@remixicon/react';
import { ScheduleCard } from './schedule-card';
import { ScheduleItemCard } from './schedule-item-card';
import { isExamEvent } from './exam-detection';
import { calculateBreakInfo } from './schedule-break';
import type { ScheduleEvent } from './use-schedule';
import type { ScheduleConflict, ScheduleItem } from './schedule-conflicts';
import type { ScheduleItemOccurrence } from './schedule-occurrences';
import { formatTime } from '@/lib/date-utils';
import { StatusLabel } from '@/components/ui/status-label';
import { useAppearance } from '@/hooks/use-appearance';
import type { TimeFormat } from '@/features/settings/appearance-prefs';

export interface ScheduleDayTimelineProps {
  events: ScheduleEvent[];
  items?: ScheduleItemOccurrence[];
  dateStr?: string;
  isToday?: boolean;
  onEventClick?: (event: ScheduleEvent) => void;
  onItemClick?: (item: ScheduleItem) => void;
  onToggleTaskCompleted?: (item: ScheduleItem) => void;
  onResolveSyncConflict?: (id: string, resolution: 'server' | 'local') => void;
  syncConflictIds?: ReadonlySet<string>;
  needsAcknowledgmentIds?: ReadonlySet<string>;
  classConflictsByEventId?: ReadonlyMap<string, ScheduleConflict[]>;
  itemConflictsById?: ReadonlyMap<string, ScheduleConflict[]>;
  showQuickAttendance?: boolean;
  emptyPlaceholder?: React.ReactNode;
  timeFormat?: TimeFormat;
  /** Canonical course keys (from getIntensiveCourseKeys) of intensive block courses. */
  intensiveCourses?: ReadonlySet<string> | null;
}

type TimelineEntry =
  | { kind: 'class'; event: ScheduleEvent; startTime: string; endTime: string }
  | { kind: 'item'; occurrence: ScheduleItemOccurrence; startTime: string; endTime: string };

/**
 * Unified timeline component for a single day's classes and personal items.
 * Shared between Dashboard and Schedule views to guarantee 100% visual and behavioral parity.
 */
export function ScheduleDayTimeline({
  events,
  items = [],
  dateStr,
  onEventClick,
  onItemClick,
  onToggleTaskCompleted,
  onResolveSyncConflict,
  syncConflictIds,
  needsAcknowledgmentIds,
  classConflictsByEventId,
  itemConflictsById,
  showQuickAttendance = true,
  emptyPlaceholder,
  timeFormat: explicitTimeFormat,
  intensiveCourses,
}: ScheduleDayTimelineProps) {
  const { prefs } = useAppearance();
  const timeFormat = explicitTimeFormat || prefs.timeFormat;

  // Classes and personal items share one chronological column: a shift at 10:15
  // and a seminar at 15:30 are the same timeline, so the gaps between them are
  // real breaks and must be measured across both kinds of entry.
  const entries = useMemo<TimelineEntry[]>(() => [
    ...events.map((event) => ({ kind: 'class' as const, event, startTime: event.start_time, endTime: event.end_time })),
    ...items.map((occurrence) => ({ kind: 'item' as const, occurrence, startTime: occurrence.startTime, endTime: occurrence.endTime })),
  ].sort((a, b) => a.startTime.localeCompare(b.startTime) || a.kind.localeCompare(b.kind)), [events, items]);

  if (entries.length === 0) return emptyPlaceholder ? <>{emptyPlaceholder}</> : null;

  return (
    <div className="flex flex-col gap-0 relative w-full">
      {entries.map((entry, index) => {
        const next = entries[index + 1];
        const breakInfo = next ? calculateBreakInfo(entry.endTime, next.startTime) : null;

        return (
          <div
            key={entry.kind === 'class'
              ? `class:${entry.event.id}`
              : `item:${entry.occurrence.item.id}:${entry.occurrence.date}`}
            className="flex flex-col items-center w-full"
          >
            {/* Uniform Schedule Card */}
            <div className="w-full">
              {entry.kind === 'class' ? (
                /* Exam and clash state render inside the card's own badge row.
                   As absolutely positioned overlays pinned to `left-3 top-3` and
                   `right-3 top-3` they landed on top of that row — rendering a
                   second EXAM badge inside the first — and covered the stacked
                   time block. The card has a slot for both. */
                <ScheduleCard
                  event={entry.event}
                  dateStr={dateStr}
                  onClick={onEventClick}
                  showQuickAttendance={showQuickAttendance && !isExamEvent(entry.event)}
                  timeFormat={timeFormat}
                  intensiveCourses={intensiveCourses}
                  hasConflict={dateStr
                    ? (classConflictsByEventId?.get(`${dateStr}:${entry.event.id}`)?.length || 0) > 0
                    : (classConflictsByEventId?.get(entry.event.id)?.length || 0) > 0}
                />
              ) : (
                <ScheduleItemCard
                  occurrence={entry.occurrence}
                  conflicts={itemConflictsById?.get(`${entry.occurrence.date}:${entry.occurrence.item.id}`) || []}
                  onClick={onItemClick}
                  onToggleComplete={onToggleTaskCompleted}
                  syncConflict={syncConflictIds?.has(entry.occurrence.item.id)}
                  isWaitingForAcknowledgment={needsAcknowledgmentIds?.has(entry.occurrence.item.id)}
                  onResolveSyncConflict={onResolveSyncConflict ? (resolution) => onResolveSyncConflict(entry.occurrence.item.id, resolution) : undefined}
                  timeFormat={timeFormat}
                />
              )}
            </div>

            {/* Between-card connector or Break Capsule */}
            {breakInfo ? (
              <div className="flex flex-col items-center w-full">
                {/* Upper connector line (flush with card and capsule) */}
                <div className="w-[2px] h-3.5 bg-amber-500/60" />

                {/* Centered Break Capsule (flush with upper and lower lines) */}
                <div className="flex items-center justify-between gap-2.5 sm:gap-3 px-3.5 sm:px-4 py-2 sm:py-2.5 rounded-full card-glass bg-surface-secondary border border-subtle text-secondary shadow-xs max-w-sm sm:max-w-md w-full">
                  {/* Left: Coffee icon & Break label */}
                  <div className="flex items-center gap-1.5 sm:gap-2 text-xs sm:text-[13px] font-mono shrink-0 min-w-[64px] sm:min-w-[72px]">
                    <RiCupLine className="w-4 h-4 text-amber-700 shrink-0" />
                    <span className="text-primary font-bold">Break</span>
                  </div>

                  {/* Middle: Centered Time Interval */}
                  <div className="flex-1 text-center font-mono text-xs sm:text-[13px] text-secondary font-medium whitespace-nowrap min-w-0 truncate">
                    {formatTime(breakInfo.startTime, timeFormat)} – {formatTime(breakInfo.endTime, timeFormat)}
                  </div>

                  {/* Right: Duration badge */}
                  <div className="flex items-center justify-end shrink-0 min-w-[64px] sm:min-w-[72px]">
                    <StatusLabel tone="amber" className="font-mono">
                      {breakInfo.durationLabel}
                    </StatusLabel>
                  </div>
                </div>

                {/* Lower connector line (flush with capsule and card) */}
                <div className="w-[2px] h-3.5 bg-amber-500/60" />
              </div>
            ) : next ? (
              /* Direct connector line between consecutive entries with no break */
              <div className="w-[2px] h-4 bg-border-primary" />
            ) : null}
          </div>
        );
      })}
    </div>
  );
}
