import { RiBankLine, RiTreeLine, RiGobletLine, RiFlagLine, RiVipCrownLine } from '@remixicon/react';
import { getUpcomingHolidays, type Holiday } from './hungarian-holidays';
import { Card } from '@/components/ui/card';
import { useNow } from '@/hooks/use-now';
import { nowInBudapest } from '@/lib/date-utils';
import { StatusLabel } from '@/components/ui/status-label';

// Holiday dates are calendar dates (UTC midnight below), so format them in UTC.
const HOLIDAY_DATE = new Intl.DateTimeFormat(undefined, { timeZone: 'UTC', weekday: 'short', month: 'short', day: 'numeric' });

function ChristianCrossIcon({ className = 'w-4 h-4' }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.25"
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
    >
      <path d="M12 2v20M6.5 8h11" />
    </svg>
  );
}

/** Oct 23, 1956 — the hole-flag (lyukas zászló): tricolor with the emblem cut out. */
function RevolutionFlagIcon({ className = 'w-[18px] h-3' }: { className?: string }) {
  return (
    <svg viewBox="0 0 18 12" className={className} aria-hidden="true">
      <clipPath id="lyukas-flag">
        <rect width="18" height="12" rx="2" />
      </clipPath>
      <g clipPath="url(#lyukas-flag)">
        <rect width="18" height="4" fill="#CD2A37" />
        <rect y="4" width="18" height="4" fill="#fff" />
        <rect y="8" width="18" height="4" fill="#008751" />
        <circle cx="9" cy="6" r="2.4" fill="#fff" stroke="rgba(0,0,0,0.25)" strokeWidth="0.6" />
      </g>
    </svg>
  );
}

function getHolidayIcon(holiday: Holiday) {
  const nameLower = (holiday.name || '').toLowerCase();
  const hungarianLower = (holiday.hungarian || '').toLowerCase();

  // Oct 23 renders separately (bare hole-flag, no chip) — see HolidayCard body

  // Christmas Day & Boxing Day / 2nd Day of Christmas
  if (nameLower.includes('christmas') || hungarianLower.includes('karácsony')) {
    return <RiTreeLine className="w-4 h-4 text-tint-amber" />;
  }

  // New Year's Day
  if (nameLower.includes('new year') || hungarianLower.includes('újév')) {
    return <RiGobletLine className="w-4 h-4 text-tint-amber" />;
  }

  // Christian Holidays (Good Friday, Easter, Whit Sunday/Monday, All Saints)
  if (
    nameLower.includes('easter') ||
    nameLower.includes('friday') ||
    nameLower.includes('whit') ||
    nameLower.includes('pentecost') ||
    nameLower.includes('saints') ||
    hungarianLower.includes('húsvét') ||
    hungarianLower.includes('péntek') ||
    hungarianLower.includes('pünkösd') ||
    hungarianLower.includes('mindenszentek')
  ) {
    return <ChristianCrossIcon className="w-4 h-4 text-tint-amber" />;
  }

  // St. Stephen / State Foundation Day
  if (nameLower.includes('foundation') || hungarianLower.includes('istván')) {
    return <RiVipCrownLine className="w-4 h-4 text-tint-amber" />;
  }

  // National Revolutions & National Days (1848, 1956, National Holidays)
  if (
    nameLower.includes('revolution') ||
    nameLower.includes('national') ||
    nameLower.includes('flag') ||
    hungarianLower.includes('nemzeti')
  ) {
    return <RiFlagLine className="w-4 h-4 text-tint-amber" />;
  }

  // General fallback
  return <RiBankLine className="w-4 h-4 text-tint-amber" />;
}

export function HolidayCard() {
  // Minute clock so the countdown rolls over at midnight while the app is open.
  const now = new Date(useNow(60_000));
  const upcomingHolidays = getUpcomingHolidays(now);
  // The Budapest date the countdown is measured from (the device may be elsewhere).
  const today = nowInBudapest(now);

  // No upcoming holidays within 14 days (2 weeks) — hide entirely
  if (upcomingHolidays.length === 0) return null;

  return (
    <section className="min-w-0 mb-6 space-y-2.5">
      {upcomingHolidays.map(({ holiday, daysUntil }) => {
        const isToday = daysUntil === 0;
        // Oct 23 only — 1956 Revolution flies the bare hole-flag, larger and
        // without the amber chip (Mar 15 / 1848 keeps the plain Flag icon)
        const isRevolution = holiday.month === 10 && holiday.day === 23;

        return (
          <Card
            key={`${holiday.month}-${holiday.day}-${holiday.name}`}
            className="py-3.5 px-5 w-full min-w-0 card-glass border-subtle rounded-2xl shadow-xs transition-[color,background-color,border-color,box-shadow,transform,opacity]"
          >
            <div className="flex items-center gap-3 justify-between">
              <div className="flex items-center gap-3 min-w-0 flex-1">
                {isRevolution ? (
                  <RevolutionFlagIcon className="w-10 h-auto shrink-0" />
                ) : (
                  <div className="w-8 h-8 rounded-xl bg-tint-amber border border-tint-amber flex items-center justify-center shrink-0 shadow-xs text-tint-amber">
                    {getHolidayIcon(holiday)}
                  </div>
                )}
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-bold text-primary truncate">{holiday.name}</p>
                  <p className="mt-0.5 text-xs font-medium text-secondary truncate">
                    {HOLIDAY_DATE.format(new Date(Date.UTC(today.year, today.month - 1, today.day + daysUntil)))}
                    <span className="hidden sm:inline"> · {holiday.hungarian}</span>
                  </p>
                </div>
              </div>
              <div className="shrink-0 pl-2 text-right">
                {isToday ? (
                  <StatusLabel tone="emerald">Today</StatusLabel>
                ) : (
                  <p className="leading-none">
                    <span className="block text-[26px] font-bold tracking-tight tabular-nums text-primary">{daysUntil}</span>
                    <span className="mt-1 block text-[11px] font-semibold text-muted">
                      {daysUntil === 1 ? 'day to go' : 'days to go'}
                    </span>
                  </p>
                )}
              </div>
            </div>
          </Card>
        );
      })}
    </section>
  );
}
