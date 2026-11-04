import type { ReactNode } from 'react';
import { cn } from '@/lib/utils';

/**
 * A postage stamp stuck onto a card: a small surface tile with a dashed accent
 * frame. Position and tilt it with `className`; shape the frame with
 * `innerClassName`.
 */
export function PostageStamp({
  className,
  innerClassName,
  children,
  'aria-hidden': ariaHidden,
}: {
  className?: string;
  innerClassName?: string;
  children: ReactNode;
  'aria-hidden'?: boolean;
}) {
  return (
    <div
      aria-hidden={ariaHidden}
      className={cn('rounded-md bg-surface-secondary p-1 shadow-[0_6px_16px_rgba(15,23,42,0.14)]', className)}
    >
      <div
        className={cn(
          'flex flex-col items-center justify-center gap-0.5 rounded border-[1.5px] border-dashed border-accent-primary/55',
          innerClassName
        )}
      >
        {children}
      </div>
    </div>
  );
}
