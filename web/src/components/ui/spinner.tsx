import { cn } from '@/lib/utils';

/** The app's loading spinner: a fading accent arc. Size it with `size-*`. */
export function Spinner({ className }: { className?: string }) {
  return <span aria-hidden className={cn('spinner-arc inline-block size-8 shrink-0 animate-spin', className)} />;
}
