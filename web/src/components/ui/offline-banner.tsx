import { RiWifiOffLine } from '@remixicon/react';
import { StatusLabel } from './status-label';
import { cn } from '@/lib/utils';

export interface OfflineBannerProps {
  /** Custom message to display. Defaults to a generic offline message. */
  message?: string;
  /** Badge label. Defaults to "Offline mode". */
  badgeLabel?: string;
  className?: string;
}

/**
 * Consistent amber offline warning banner used across Dashboard, Schedule, and Transit pages.
 */
export function OfflineBanner({
  message = 'No internet connection — operating in cached offline mode',
  badgeLabel = 'Offline mode',
  className,
}: OfflineBannerProps) {
  return (
    <div
      className={cn(
        'mb-4 p-3 rounded-2xl card-glass border-tint-amber text-tint-amber text-xs flex items-center justify-between flex-wrap gap-2 shadow-sm',
        className
      )}
    >
      <div className="flex items-center gap-2">
        <RiWifiOffLine className="w-4 h-4 text-tint-amber shrink-0" />
        <span>{message}</span>
      </div>
      <StatusLabel tone="amber">{badgeLabel}</StatusLabel>
    </div>
  );
}
