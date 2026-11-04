import { RiCalendarLine } from '@remixicon/react';
import { PostageStamp } from '@/components/ui/postage-stamp';
import { cn } from '@/lib/utils';

/** Month, day and weekday for a tile, read from the ISO date itself. */
function tileParts(iso: string): { month: string; day: string; weekday: string } | null {
  const [y, m, d] = (iso || '').split('-').map(Number);
  if (!y || !m || !d) return null;
  const date = new Date(y, m - 1, d);
  return {
    // en-GB writes September as "Sept"; the tile keeps every month to three letters.
    month: date.toLocaleDateString('en-GB', { month: 'short' }).slice(0, 3).toUpperCase(),
    day: String(d),
    weekday: date.toLocaleDateString('en-GB', { weekday: 'short' }),
  };
}

/**
 * Today's stamp, as the schedule draws it: a solid accent frame and an accent
 * glow instead of the dashed frame. Kept tight so a row's clipped corners
 * never cut it off.
 */
export const TODAY_STAMP_GLOW =
  'shadow-[0_0_14px_color-mix(in_srgb,var(--accent-primary)_55%,transparent),0_6px_16px_rgba(15,23,42,0.14)]';

interface DateTileProps {
  /** ISO date (YYYY-MM-DD); a missing or malformed date draws a blank stamp. */
  date?: string | null;
  /** Today's date: the schedule's lit stamp instead of the dashed one. */
  today?: boolean;
  className?: string;
}

/**
 * A date as a postage stamp — month over day over weekday in the app's dashed
 * stamp frame — so a list reads like a timetable without a month heading per
 * group. Decorative: callers name the date in their own text.
 */
export function DateTile({ date, today = false, className }: DateTileProps) {
  const parts = date ? tileParts(date) : null;
  return (
    <PostageStamp
      aria-hidden
      // Centred, not stretched to the row: a stretched stamp leaves the frame
      // riding high in its tile. The frame's corners run concentric with the
      // tile's (10px tile radius less its 4px inset).
      className={cn('shrink-0 self-center bg-surface-tertiary', today && TODAY_STAMP_GLOW, className)}
      innerClassName={cn('h-[58px] w-12 gap-[3px] rounded-sm', today && 'border-solid border-accent-primary')}
    >
      {/* Each line sets its own leading: a text-size utility carries its own
          line height, which pushed the month and weekday into the frame. */}
      {parts ? (
        <>
          <span className="text-[10px] font-extrabold leading-none tracking-[0.12em] text-accent-primary">{parts.month}</span>
          <span className="text-lg font-black leading-none tabular-nums text-primary">{parts.day}</span>
          <span className="text-[11px] font-semibold leading-none text-secondary">{parts.weekday}</span>
        </>
      ) : (
        <RiCalendarLine className="h-5 w-5 text-muted" />
      )}
    </PostageStamp>
  );
}
