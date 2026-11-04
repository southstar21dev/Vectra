import type { HTMLAttributes } from 'react';
import type { RemixiconComponentType } from '@remixicon/react';
import { cn } from '@/lib/utils';

export type StatusTone =
  | 'amber'
  | 'blue'
  | 'emerald'
  | 'green'
  | 'purple'
  | 'red'
  | 'sky'
  | 'violet'
  | 'neutral';

/** Tone → class colouring the icon / dot (never the label). */
export const STATUS_TONE_CLASS: Record<StatusTone, string> = {
  amber: 'text-tint-amber',
  blue: 'text-tint-blue',
  emerald: 'text-tint-emerald',
  green: 'text-tint-green',
  purple: 'text-tint-purple',
  red: 'text-tint-red',
  sky: 'text-tint-sky',
  violet: 'text-tint-violet',
  neutral: 'text-muted',
};

export interface StatusLabelProps extends HTMLAttributes<HTMLSpanElement> {
  tone?: StatusTone;
  /** Leading icon in the tone colour; without one a small tone dot is shown. */
  icon?: RemixiconComponentType;
}

/** Inline metadata label — a tone-coloured icon or dot beside a quiet grey label,
 *  with no pill container. Used for types, statuses, alerts and short notes. */
export function StatusLabel({ tone = 'neutral', icon: Icon, className, children, ...props }: StatusLabelProps) {
  const toneClass = STATUS_TONE_CLASS[tone];
  return (
    <span
      className={cn('inline-flex shrink-0 items-center gap-1.5 text-xs font-semibold text-secondary', className)}
      {...props}
    >
      {Icon ? (
        <Icon className={cn('h-3.5 w-3.5 shrink-0', toneClass)} aria-hidden />
      ) : (
        <span
          className={cn('flex h-3.5 w-3.5 shrink-0 items-center justify-center rounded-full bg-current/20', toneClass)}
          aria-hidden
        >
          <span className="h-2 w-2 rounded-full bg-current" />
        </span>
      )}
      {children}
    </span>
  );
}
