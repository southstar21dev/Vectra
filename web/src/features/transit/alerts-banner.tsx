import { useState } from 'react';
import { RiArrowDownSLine, RiExternalLinkLine, RiAlertLine } from '@remixicon/react';
import { cn } from '@/lib/utils';
import type { TransitAlert } from './types';

/**
 * One alert as a single collapsed line: icon, the headline truncated to one
 * line, and a chevron. Tapping it opens the full headline, the description and
 * the bkk.hu link; tapping again folds it back.
 */
function AlertCard({ alert }: { alert: TransitAlert }) {
  const [expanded, setExpanded] = useState(false);
  const bodyId = `alert-${alert.id}-body`;

  return (
    <div className="rounded-2xl border border-amber-500/25 bg-amber-500/10">
      <button
        type="button"
        onClick={() => setExpanded((v) => !v)}
        aria-expanded={expanded}
        aria-controls={bodyId}
        className="flex w-full items-center gap-3 px-4 py-3 text-left cursor-pointer focus-visible:outline focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-accent-primary"
      >
        <RiAlertLine className="h-4 w-4 shrink-0 text-tint-amber" aria-hidden="true" />
        <span className={cn('min-w-0 flex-1 text-sm font-bold leading-snug text-tint-amber', !expanded && 'truncate')}>
          {alert.header}
        </span>
        <RiArrowDownSLine
          className={cn('h-4 w-4 shrink-0 text-tint-amber/70 transition-transform duration-200', expanded && 'rotate-180')}
          aria-hidden="true"
        />
      </button>
      {expanded && (
        <div id={bodyId} className="px-4 pb-3.5 pl-11">
          {alert.description && <p className="text-xs leading-relaxed text-tint-amber/70">{alert.description}</p>}
          {alert.url && (
            <a
              href={alert.url}
              target="_blank"
              rel="noreferrer noopener"
              onClick={(e) => e.stopPropagation()}
              className="mt-2 inline-flex items-center gap-1 text-xs font-semibold text-tint-blue underline underline-offset-2"
            >
              Details on bkk.hu <RiExternalLinkLine className="h-3 w-3" aria-hidden="true" />
            </a>
          )}
        </div>
      )}
    </div>
  );
}

/**
 * Alert rows for the live-position sheet: each collapsed to one line until
 * tapped. Renders nothing when there are no active alerts. No section heading:
 * the amber rows are self-evidently alerts.
 */
export function AlertsSection({ alerts, className }: { alerts: TransitAlert[]; className?: string }) {
  if (alerts.length === 0) return null;
  return (
    <div className={cn('flex flex-col gap-2 shrink-0', className)}>
      {alerts.map((a) => (
        <AlertCard key={a.id} alert={a} />
      ))}
    </div>
  );
}
