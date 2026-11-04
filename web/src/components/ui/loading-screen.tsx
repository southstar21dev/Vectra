import { cn } from '@/lib/utils';

export interface LoadingScreenProps {
  label?: string;
  className?: string;
}

/** Full-viewport loading screen used as a Suspense fallback for lazy dialogs. */
export function LoadingScreen({ label = 'Loading…', className }: LoadingScreenProps) {
  return (
    <div
      role="status"
      aria-label={label}
      className={cn(
        'fixed inset-0 z-[1500] flex flex-col items-center justify-center gap-3 bg-surface-primary',
        className
      )}
    >
      <div className="h-8 w-8 animate-spin rounded-full border-2 border-tint-blue border-t-transparent motion-reduce:animate-none" />
      <p className="text-xs font-medium text-secondary">{label}</p>
    </div>
  );
}
