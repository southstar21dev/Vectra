import type { CSSProperties, ReactNode } from 'react';
import { cn } from '@/lib/utils';

/**
 * Band colors: the band must contrast with the text it sweeps over, so the
 * near-white primary text in dark theme gets an emerald band instead of a
 * white one.
 */
const TONES = {
  emerald: { base: 'var(--tint-emerald-text)', band: '#fff' },
  primary: { base: 'var(--text-primary)', band: 'var(--tint-emerald-text)' },
} as const;

/**
 * Gradient-clipped text with a light band that sweeps across it every few
 * seconds (driven by the `text-shimmer` keyframes in globals.css). The band
 * is a narrow stop at the middle of an oversized 300% background, which is
 * what gives it travel and leaves it off-text between sweeps — that geometry
 * is inline rather than left to the gradient utility.
 */
export function ShimmerText({
  children,
  tone = 'emerald',
  className,
}: {
  children: ReactNode;
  tone?: keyof typeof TONES;
  className?: string;
}) {
  const { base, band } = TONES[tone];
  const style: CSSProperties = {
    backgroundImage: `linear-gradient(90deg, ${base} 0%, ${base} 45%, ${band} 50%, ${base} 55%, ${base} 100%)`,
    backgroundSize: '300% 100%',
  };

  return (
    <span
      style={style}
      className={cn(
        'bg-clip-text text-transparent animate-[text-shimmer_3.6s_linear_infinite] motion-reduce:animate-none',
        className
      )}
    >
      {children}
    </span>
  );
}
