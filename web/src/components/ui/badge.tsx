import * as React from 'react';
import { cn } from '@/lib/utils';

export type BadgeProps = React.HTMLAttributes<HTMLDivElement>;

/** Neutral chip. Tinted status/type tags use StatusLabel instead. */
export function Badge({ className, ...props }: BadgeProps) {
  return (
    <div
      className={cn(
        'inline-flex items-center gap-1.5 rounded-md border border-subtle bg-surface-overlay px-2 py-0.5 text-[10px] font-bold text-secondary transition-colors',
        className
      )}
      {...props}
    />
  );
}
