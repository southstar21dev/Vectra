import { RiAlertLine, RiCheckLine, RiRadioButtonLine, RiTimeLine, RiMapPinLine, RiRefreshLine } from '@remixicon/react';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import { StatusLabel } from '@/components/ui/status-label';
import { formatTime } from '@/lib/date-utils';
import type { ScheduleConflict } from './schedule-conflicts';
import type { ScheduleItemOccurrence } from './schedule-occurrences';
import type { TimeFormat } from '@/features/settings/appearance-prefs';

interface ScheduleItemCardProps {
  occurrence: ScheduleItemOccurrence;
  conflicts?: ScheduleConflict[];
  onClick?: (item: ScheduleItemOccurrence['item']) => void;
  onToggleComplete?: (item: ScheduleItemOccurrence['item']) => void;
  timeFormat?: TimeFormat;
  compact?: boolean;
  syncConflict?: boolean;
  isWaitingForAcknowledgment?: boolean;
  onResolveSyncConflict?: (resolution: 'server' | 'local') => void;
}

/** Shift and task accents mirror the class card's per-course colour bar. */
const SHIFT_ACCENT = '#10b981';
const TASK_ACCENT = '#8b5cf6';

/**
 * Midnight is stored as 24:00 so range math stays in one day, but a clock
 * should read 00:00 — `formatTime('24:00', '12h')` would render "0:00 PM".
 */
function formatClock(time: string, timeFormat: TimeFormat): string {
  return formatTime(time === '24:00' ? '00:00' : time, timeFormat);
}

export function ScheduleItemCard({
  occurrence,
  conflicts = [],
  onClick,
  onToggleComplete,
  timeFormat = '24h',
  compact = false,
  syncConflict = false,
  isWaitingForAcknowledgment = false,
  onResolveSyncConflict,
}: ScheduleItemCardProps) {
  const { item, continuation } = occurrence;
  const isTask = item.type === 'task';
  const conflictPending = conflicts.some((conflict) => conflict.needsAcknowledgment);
  const conflictAcknowledged = conflicts.length > 0 && !conflictPending;
  const accentColor = isTask ? TASK_ACCENT : SHIFT_ACCENT;
  const startLabel = formatClock(occurrence.startTime, timeFormat);
  const endLabel = formatClock(occurrence.endTime, timeFormat);
  const cardLabel = `${continuation ? 'Continued ' : ''}${isTask ? 'Task' : 'Work shift'}: ${item.title}`;

  const stateBorder = conflictPending
    ? 'border border-tint-red'
    : syncConflict
    ? 'border border-tint-purple'
    : conflictAcknowledged
    ? 'border border-tint-amber'
    : '';

  // The grid renders items as absolutely positioned boxes sized to the item's
  // duration, so that variant stays a single dense row.
  if (compact) {
    return (
      <button
        type="button"
        onClick={() => onClick?.(item)}
        aria-label={cardLabel}
        className={cn(
          'group flex h-full w-full min-w-0 flex-col justify-center gap-1 overflow-hidden rounded-2xl px-2.5 py-2 text-left card-glass-interactive cursor-pointer',
          item.completed && 'opacity-70',
          stateBorder
        )}
      >
        <span className="flex min-w-0 items-center gap-1.5">
          <span className="h-3.5 w-1 shrink-0 rounded-full" style={{ backgroundColor: accentColor }} />
          <span className={cn('truncate text-[11px] font-bold leading-tight', item.completed ? 'text-muted line-through' : 'text-primary')}>
            {item.title}
          </span>
        </span>
        <span className="flex min-w-0 items-center gap-1 font-mono text-[9.5px] font-semibold text-secondary">
          <RiTimeLine className="h-2.5 w-2.5 shrink-0" />
          <span className="truncate">{startLabel}–{endLabel}</span>
        </span>
      </button>
    );
  }

  const showFooter = Boolean(item.location) || syncConflict || (isTask && onToggleComplete);

  return (
    <div
      className={cn(
        'group w-full flex flex-col justify-between rounded-2xl card-glass-interactive cursor-pointer transition-[color,background-color,border-color,box-shadow,transform,opacity] duration-300 relative',
        item.completed && 'opacity-70',
        stateBorder
      )}
      style={conflictPending ? { boxShadow: '0 0 32px -6px rgba(239, 68, 68, 0.35), var(--glass-shadow)' } : undefined}
    >
      <button
        type="button"
        onClick={() => onClick?.(item)}
        aria-label={cardLabel}
        className="flex w-full min-w-0 items-center justify-between gap-3.5 rounded-2xl px-4 py-3.5 text-left focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-primary"
      >
        <span className="flex min-w-0 flex-1 items-center gap-3.5">
          {/* Accent bar — the item's stand-in for a course colour. */}
          <span className="w-1.5 self-stretch min-h-[36px] my-0.5 rounded-full shrink-0 shadow-sm" style={{ backgroundColor: accentColor }} />

          <span className="block min-w-0 flex-1 space-y-1">
            <span className="flex items-center gap-1.5 flex-nowrap overflow-x-auto no-scrollbar">
              <StatusLabel tone={isTask ? 'purple' : 'emerald'} className="select-none">{isTask ? 'Task' : 'Shift'}</StatusLabel>
              {syncConflict && (
                <StatusLabel tone="purple" icon={RiRefreshLine} className="select-none">
                  Sync conflict
                </StatusLabel>
              )}
              {conflicts.length > 0 && (
                <StatusLabel tone={conflictPending ? 'red' : 'amber'} icon={RiAlertLine} className="select-none">
                  {conflictPending ? 'Clash' : 'Acknowledged'}
                </StatusLabel>
              )}
              {continuation && (
                <span className="inline-flex shrink-0 select-none items-center rounded-full border border-subtle bg-surface-overlay px-1.5 py-0.5 text-[10px] font-bold uppercase tracking-wider text-secondary">
                  Overnight
                </span>
              )}
            </span>

            <span className="block">
              <span
                className={cn(
                  'block text-sm sm:text-base font-bold line-clamp-2 leading-snug transition-colors',
                  item.completed ? 'text-muted line-through' : 'text-primary group-hover:text-tint-sky'
                )}
              >
                {item.title}
              </span>
              {continuation && (
                <span className="text-xs text-muted font-medium truncate mt-0.5 block">Continues from the previous day</span>
              )}
              {(conflictPending || isWaitingForAcknowledgment) && (
                <span className="text-xs text-tint-red font-medium mt-0.5 block">
                  Open this item to review and acknowledge the clash
                </span>
              )}
            </span>
          </span>
        </span>

        {/* Right column: compact stacked time, same as a class card. */}
        <span className="flex flex-col items-center justify-center px-3.5 py-2 rounded-2xl border border-subtle bg-surface-overlay text-secondary shrink-0 font-mono transition-colors shadow-xs">
          <span className="text-xs font-bold tracking-tight leading-none text-primary">{startLabel}</span>
          <span className="w-3.5 h-[1px] bg-border-primary my-1 opacity-60" />
          <span className="text-xs font-bold tracking-tight leading-none text-secondary">{endLabel}</span>
        </span>
      </button>

      {showFooter && (
        <div className="border-t border-border-subtle/60 px-4 py-2 flex items-center justify-between gap-3 text-xs text-secondary relative z-10 bg-surface-overlay/20">
          {item.location ? (
            <div className="flex items-center gap-1.5 font-mono text-xs font-semibold text-secondary min-w-0 truncate">
              <RiMapPinLine className="w-3 h-3 text-secondary/80 shrink-0" />
              <span className="truncate">{item.location}</span>
            </div>
          ) : (
            <div />
          )}

          {syncConflict && onResolveSyncConflict ? (
            <div className="flex shrink-0 items-center gap-2">
              <Button type="button" variant="outline" size="sm" onClick={() => onResolveSyncConflict('server')}>
                Use server
              </Button>
              <Button type="button" variant="primary" size="sm" onClick={() => onResolveSyncConflict('local')}>
                Keep mine
              </Button>
            </div>
          ) : isTask && onToggleComplete ? (
            <button
              type="button"
              onClick={() => onToggleComplete(item)}
              aria-pressed={item.completed === true}
              title={item.completed ? 'Mark this task as not done' : 'Mark this task as done'}
              className="inline-flex items-center justify-center gap-1.5 rounded-full border border-subtle bg-surface-overlay px-2.5 py-1 text-xs font-semibold text-secondary hover:text-primary transition-[color,background-color,border-color,box-shadow,transform,opacity] select-none cursor-pointer hover:scale-105 active:scale-95 shrink-0"
            >
              {item.completed ? (
                <RiCheckLine className="h-3.5 w-3.5 shrink-0 text-tint-emerald" aria-hidden />
              ) : (
                <RiRadioButtonLine className="h-3.5 w-3.5 shrink-0 text-muted" aria-hidden />
              )}
              {item.completed ? 'Done' : 'Mark done'}
            </button>
          ) : null}
        </div>
      )}
    </div>
  );
}
