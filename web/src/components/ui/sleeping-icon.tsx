import type { SVGProps } from 'react';
import { cn } from '@/lib/utils';

// Big → small, rising to the top-right. Each letter is one stroked "Z" path.
const LETTERS = [
  { d: 'M3 15h6l-6 6h6', delay: '0s' },
  { d: 'M11.5 9.5h4.5l-4.5 4.5h4.5', delay: '0.3s' },
  { d: 'M17.5 4h3.5l-3.5 3.5h3.5', delay: '0.6s' },
];

/** "No service / sleeping" glyph — an animated Zzz whose letters drift up and
 *  fade in sequence. Sized and coloured like a Remix icon (className, currentColor).
 *  Reduced motion shows a static Zzz (see `.zzz-letter` in globals.css). */
export function SleepingIcon({ className, ...props }: SVGProps<SVGSVGElement>) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={cn('h-6 w-6', className)}
      {...props}
    >
      {LETTERS.map(({ d, delay }) => (
        <path key={d} d={d} className="zzz-letter" style={{ animationDelay: delay }} />
      ))}
    </svg>
  );
}
