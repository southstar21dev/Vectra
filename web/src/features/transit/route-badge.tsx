import { cn } from '@/lib/utils';

/**
 * Route chip with adaptive sizing — long route names (e.g. "N7583", "7-200")
 * shrink their font and truncate so they never overflow the fixed badge box.
 * Sizes match the app's badges: xs = step-header chip, sm = list rows,
 * md = route grid, lg = card/dialog headers.
 */
export function RouteBadge({
  name,
  color,
  textColor,
  size = 'md',
}: {
  name: string;
  color?: string | null;
  textColor?: string | null;
  size?: 'xs' | 'sm' | 'md' | 'lg' | '2xs';
}) {
  // Combined routes read as one tight token on a badge: "28 / 28A" → "28/28A".
  const formattedName = name.replace(/\s*\/\s*/g, '/');
  const n = formattedName.length;
  const box =
    size === '2xs'
      ? 'min-w-6 h-6 px-1.5 rounded-full'
      : size === 'xs'
        ? 'min-w-7 h-7 px-1 rounded-full'
        : size === 'sm'
          ? 'min-w-8 h-8 px-1.5 rounded-lg'
          : size === 'lg'
            ? 'min-w-12 h-12 px-2 rounded-xl'
            : 'min-w-9 h-9 px-1.5 rounded-lg';
  const font =
    n <= 3
      ? size === '2xs'
        ? 'text-[8px]'
        : size === 'xs'
          ? 'text-[10px]'
          : size === 'sm'
            ? 'text-xs'
          : size === 'lg'
            ? 'text-lg'
            : 'text-sm'
      : n <= 5
        ? size === '2xs'
          ? 'text-[7.5px]'
        : size === 'xs'
          ? 'text-[9px]'
          : size === 'sm'
            ? 'text-[10px]'
            : size === 'lg'
              ? 'text-sm'
              : 'text-[11px]'
        : size === '2xs'
          ? 'text-[7px]'
          : size === 'xs'
            ? 'text-[7px]'
            : size === 'sm'
              ? 'text-[9px]'
              : size === 'lg'
                ? 'text-xs'
                : 'text-[10px]';
  const display = n > 12 ? `${formattedName.slice(0, 10)}…` : formattedName;
  // No route color in the catalog → a glass badge in surface tokens, never a
  // white chip (a lone white block nothing else in the palette uses).
  const styleProps = color
    ? { backgroundColor: `#${color}`, color: `#${textColor || '000000'}` }
    : undefined;
  const fallbackClass = color ? '' : 'bg-surface-overlay text-primary';
  return (
    <span
      className={cn('inline-flex items-center justify-center font-black font-mono shrink-0 border border-black/15 dark:border-white/20 shadow-xs whitespace-nowrap', box, font, fallbackClass)}
      style={styleProps}
    >
      {display}
    </span>
  );
}
