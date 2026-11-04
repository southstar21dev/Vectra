import type { HTMLAttributes, ReactNode } from 'react';
import { RiCheckLine, RiArrowRightSLine } from '@remixicon/react';
import { Select } from '@/components/ui/select';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { StatusLabel } from '@/components/ui/status-label';
import { vibrate } from '@/lib/haptics';
import type { TrackerStop } from './types';
import { cn } from '@/lib/utils';
import { MAX_TRACKER_STOPS } from './transit-utils';

/**
 * List furniture shared by the tracker sheets: a tappable row inside a
 * FormGroup, a direction picker for a tracked stop, and the "n of 4" pill.
 */

interface ListRowProps {
  onClick?: () => void;
  disabled?: boolean;
  /** Draws a check on the right in place of `trailing`. */
  selected?: boolean;
  /** Draws a chevron on the right: the row navigates somewhere. */
  navigates?: boolean;
  leading?: ReactNode;
  title: ReactNode;
  subtitle?: ReactNode;
  trailing?: ReactNode;
  className?: string;
}

export function ListRow({ onClick, disabled, selected, navigates, leading, title, subtitle, trailing, className }: ListRowProps) {
  const Tag = onClick ? 'button' : 'div';
  return (
    <Tag
      type={onClick ? 'button' : undefined}
      onClick={onClick}
      disabled={onClick ? disabled : undefined}
      aria-pressed={onClick && selected !== undefined ? selected : undefined}
      className={cn(
        'flex w-full items-center gap-3 px-4 py-3 text-left transition-colors',
        onClick &&
          'cursor-pointer hover:bg-surface-overlay/70 active:bg-surface-overlay focus-visible:outline focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-accent-primary',
        disabled && 'cursor-not-allowed opacity-50',
        selected && 'bg-accent-primary/8',
        className
      )}
    >
      {leading && <span className="flex shrink-0 items-center">{leading}</span>}
      <span className="min-w-0 flex-1">
        <span className="block truncate text-sm font-semibold text-primary">{title}</span>
        {subtitle && <span className="mt-0.5 block truncate text-xs text-secondary">{subtitle}</span>}
      </span>
      {trailing && <span className="flex shrink-0 items-center gap-2">{trailing}</span>}
      {selected && <RiCheckLine className="h-4 w-4 shrink-0 text-accent-primary" aria-hidden="true" />}
      {navigates && <RiArrowRightSLine className="h-4 w-4 shrink-0 text-muted" aria-hidden="true" />}
    </Tag>
  );
}

interface DirectionSelectProps {
  value: string;
  headsigns: string[];
  onChange: (headsign: string) => void;
  label: string;
}

/** Which direction a tracked stop watches; empty means every direction. */
export function DirectionSelect({ value, headsigns, onChange, label }: DirectionSelectProps) {
  return (
    <Select value={value} onChange={(e) => onChange(e.target.value)} aria-label={label} className="h-9 text-xs">
      <option value="">All directions</option>
      {headsigns.map((h) => (
        <option key={h} value={h}>
          {h}
        </option>
      ))}
    </Select>
  );
}

/** "2 / 4" count of tracked stops; its dot turns amber at the limit. */
export function StopCountPill({ count }: { count: number }) {
  const atLimit = count >= MAX_TRACKER_STOPS;
  return (
    <StatusLabel tone={atLimit ? 'amber' : 'neutral'} className="tabular-nums">
      {count} / {MAX_TRACKER_STOPS}
    </StatusLabel>
  );
}

/** Centred status block for loading, errors and empty lists inside a sheet. */
export function ListStatus({ children, className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div className={cn('flex flex-col items-center gap-3 py-10 text-center', className)} {...props}>
      {children}
    </div>
  );
}

const STOP_COLUMNS: Record<number, string> = { 2: 'grid-cols-2', 3: 'grid-cols-3', 4: 'grid-cols-4' };

interface TrackedStopTabsProps {
  stops: TrackerStop[];
  activeStopId: string | undefined;
  onSelect: (stopId: string) => void;
}

/** The tracked stops as the sheet's segmented pill, pinned under the bar;
 *  switching stops retargets the live view. */
export function TrackedStopTabs({ stops, activeStopId, onSelect }: TrackedStopTabsProps) {
  if (stops.length < 2) return null;
  return (
    <Tabs
      value={activeStopId ?? stops[0].stop_id}
      onValueChange={(id) => {
        if (id === activeStopId) return;
        vibrate(10);
        onSelect(id);
      }}
    >
      <TabsList aria-label="Tracked stop" className={cn('grid w-full', STOP_COLUMNS[Math.min(stops.length, 4)] ?? 'grid-cols-4')}>
        {stops.map((s) => (
          <TabsTrigger key={s.stop_id} value={s.stop_id} title={s.stop_name} className="min-w-0 px-2">
            <span className="truncate">{s.stop_name}</span>
          </TabsTrigger>
        ))}
      </TabsList>
    </Tabs>
  );
}
