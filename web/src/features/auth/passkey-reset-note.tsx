import { RiErrorWarningLine } from '@remixicon/react';
import { cn } from '@/lib/utils';

/** A small amber caption with a warning icon. */
export function PasskeyResetNote({ text, className }: { text: string; className?: string }) {
  return (
    <p className={cn('flex items-start gap-1.5 text-xs text-tint-amber', className)}>
      <RiErrorWarningLine className="w-3.5 h-3.5 shrink-0 mt-px" aria-hidden="true" />
      <span>{text}</span>
    </p>
  );
}
