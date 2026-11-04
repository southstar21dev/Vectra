import { forwardRef, useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { RiArrowLeftLine, RiCloseLine } from '@remixicon/react';
import { cn } from '@/lib/utils';

/**
 * Native-app chrome for the trip sheets: a navigation bar (leading button,
 * centred title, trailing action), a scrolling body, and an optional docked
 * footer. The bar grows a hairline the moment the body scrolls, the way a
 * platform sheet does, and the top/bottom safe areas are padded here so the
 * sheet can run edge to edge on phones.
 */

export const SHEET_GUTTER = 'px-5 sm:px-6';

const SAFE_TOP = 'calc(var(--safe-area-top, env(safe-area-inset-top, 0px)) + 0.5rem)';
const SAFE_BOTTOM = 'calc(env(safe-area-inset-bottom, 0px) + 1rem)';

interface SheetBarProps {
  title: ReactNode;
  titleId?: string;
  subtitle?: ReactNode;
  /** `close` shows an X, `back` an arrow. */
  leading: 'close' | 'back';
  onLeading: () => void;
  /** Right-hand action; a spacer keeps the title centred when absent. */
  trailing?: ReactNode;
  /** A second row under the title (a segmented control, a search field) that
   *  stays pinned with the bar; the scroll hairline lands beneath it. */
  accessory?: ReactNode;
  scrolled?: boolean;
  /** With a `back` leading button, also show a close X on the right (when no
   *  trailing action takes that slot), so a nested pane can be dismissed
   *  without stepping back first. */
  onClose?: () => void;
  /** Let a long title wrap onto a second line instead of truncating. */
  wrapTitle?: boolean;
  /** Extra classes for the bar, e.g. a background matching a sheet's canvas. */
  className?: string;
}

const BAR_BUTTON_CLASS =
  'flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-surface-overlay text-secondary transition-colors hover:bg-surface-tertiary hover:text-primary cursor-pointer active:scale-95 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-primary';

export function SheetBar({
  title,
  titleId,
  subtitle,
  leading,
  onLeading,
  trailing,
  accessory,
  scrolled,
  onClose,
  wrapTitle,
  className,
}: SheetBarProps) {
  const Icon = leading === 'close' ? RiCloseLine : RiArrowLeftLine;
  // Every sheet closes from the top-right; a back arrow is always leading,
  // and any trailing action moves to the left slot while the X is on the right.
  const onRight = leading === 'close';
  const leadingButton = (
    <button
      type="button"
      onClick={onLeading}
      aria-label={leading === 'close' ? 'Close' : 'Back'}
      className={BAR_BUTTON_CLASS}
    >
      <Icon className="h-5 w-5" />
    </button>
  );
  const rightSlot = onRight
    ? leadingButton
    : trailing ??
      (onClose ? (
        <button type="button" onClick={onClose} aria-label="Close" className={BAR_BUTTON_CLASS}>
          <RiCloseLine className="h-5 w-5" />
        </button>
      ) : undefined);
  return (
    <header
      className={cn(
        'relative z-20 shrink-0 border-b bg-surface-secondary pb-2 transition-colors duration-200',
        SHEET_GUTTER,
        scrolled ? 'border-subtle' : 'border-transparent',
        className
      )}
      style={{ paddingTop: SAFE_TOP }}
    >
      <div className="flex items-center gap-2">
      {onRight ? <div className="min-w-10 shrink-0">{trailing}</div> : leadingButton}
      <div className="min-w-0 flex-1 px-1 text-center">
        <h2
          id={titleId}
          className={cn(
            'text-base font-extrabold tracking-tight text-primary',
            wrapTitle ? 'break-words leading-snug' : 'truncate'
          )}
        >
          {title}
        </h2>
        {subtitle && <div className="truncate text-xs text-secondary">{subtitle}</div>}
      </div>
      <div className="flex min-w-10 shrink-0 justify-end">{rightSlot}</div>
      </div>
      {accessory && <div className="mt-3 pb-1">{accessory}</div>}
    </header>
  );
}

interface SheetBodyProps {
  children: ReactNode;
  className?: string;
  onScrolledChange?: (scrolled: boolean) => void;
  /** Fade content out under the bottom edge. Off when a sticky control
   *  lives at the bottom of the body, which the fade would dim. */
  fadeBottom?: boolean;
}

const FADE = '1.5rem';

/**
 * The scrolling body. Reports whether it has scrolled away from the top, and
 * fades content out under whichever edge it has scrolled past, so a card half
 * under the bar dissolves instead of ending in a hard line.
 */
export const SheetBody = forwardRef<HTMLDivElement, SheetBodyProps>(({ children, className, onScrolledChange, fadeBottom = true }, ref) => {
  const innerRef = useRef<HTMLDivElement | null>(null);
  const [edges, setEdges] = useState({ top: false, bottom: false });

  const measure = useCallback(() => {
    const el = innerRef.current;
    if (!el) return;
    const top = el.scrollTop > 2;
    const bottom = el.scrollTop + el.clientHeight < el.scrollHeight - 2;
    setEdges((prev) => (prev.top === top && prev.bottom === bottom ? prev : { top, bottom }));
    onScrolledChange?.(top);
  }, [onScrolledChange]);

  // Content grows and shrinks as views swap and data arrives; re-measure then.
  useEffect(() => {
    const el = innerRef.current;
    if (!el) return;
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    for (const child of Array.from(el.children)) ro.observe(child);
    return () => ro.disconnect();
  }, [measure, children]);

  const setRefs = (el: HTMLDivElement | null) => {
    innerRef.current = el;
    if (typeof ref === 'function') ref(el);
    else if (ref) ref.current = el;
  };

  return (
    <div
      ref={setRefs}
      onScroll={measure}
      className={cn('relative min-h-0 flex-1 overflow-y-auto overflow-x-hidden overscroll-contain no-scrollbar', className)}
      style={{
        maskImage: 'linear-gradient(to bottom, transparent, black var(--sheet-fade-top), black calc(100% - var(--sheet-fade-bottom)), transparent)',
        ['--sheet-fade-top' as string]: edges.top ? FADE : '0px',
        ['--sheet-fade-bottom' as string]: fadeBottom && edges.bottom ? FADE : '0px',
        transition: '--sheet-fade-top 200ms ease-out, --sheet-fade-bottom 200ms ease-out',
      }}
    >
      {children}
    </div>
  );
});
SheetBody.displayName = 'SheetBody';

/** Docked action area; pads the home-indicator inset on phones. */
export function SheetFooter({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div
      className={cn('shrink-0 border-t border-subtle bg-surface-secondary pt-3', SHEET_GUTTER, className)}
      style={{ paddingBottom: SAFE_BOTTOM }}
    >
      {children}
    </div>
  );
}

/** Section heading inside a sheet: one weight step up, no eyebrow. */
export function SheetSectionTitle({ id, children, action }: { id?: string; children: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-3">
      <h3 id={id} className="text-base font-extrabold tracking-tight text-primary">
        {children}
      </h3>
      {action}
    </div>
  );
}
