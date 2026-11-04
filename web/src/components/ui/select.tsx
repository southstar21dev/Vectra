import * as React from 'react';
import { createPortal } from 'react-dom';
import { RiArrowDownSLine, RiCheckFill, type RemixiconComponentType } from '@remixicon/react';
import { cn } from '@/lib/utils';
import {
  type OptionItem,
  type PopupPositionResult,
  collectOptions,
  calculatePopupPosition,
} from './select-utils';

export interface SelectProps
  extends Omit<React.SelectHTMLAttributes<HTMLSelectElement>, 'value' | 'onChange' | 'size'> {
  value?: string | number | readonly string[];
  onChange?: (e: React.ChangeEvent<HTMLSelectElement>) => void;
  containerClassName?: string;
  /** Optional leading icon, shown inside the control on the left. */
  icon?: RemixiconComponentType;
}

export type { OptionItem, PopupPositionResult };

/** Custom dropdown that keeps the native select's API (value + onChange with
 *  e.target.value, <option> children) so every existing usage works as-is.
 *  The selected value is centered with flexbox, which every browser honors.
 *  The option list is portaled to <body> with collision-aware positioning and
 *  mobile touch safety. */
export function Select({
  className,
  containerClassName,
  children,
  value,
  onChange,
  icon: Icon,
  disabled,
  title,
  ...props
}: SelectProps) {
  const [open, setOpen] = React.useState(false);
  const [coords, setCoords] = React.useState<PopupPositionResult | null>(null);
  const [highlightedIndex, setHighlightedIndex] = React.useState<number>(-1);

  const triggerRef = React.useRef<HTMLButtonElement>(null);
  const popupRef = React.useRef<HTMLDivElement>(null);

  const options = React.useMemo(() => collectOptions(children), [children]);
  const current = String(value ?? '');
  const selectedIndex = options.findIndex((o) => o.value === current);
  const selected = options[selectedIndex >= 0 ? selectedIndex : 0];

  const updatePosition = React.useCallback(() => {
    const el = triggerRef.current;
    if (!el || typeof window === 'undefined') return;

    const triggerRect = el.getBoundingClientRect();
    const popupEl = popupRef.current;

    const pos = calculatePopupPosition({
      triggerRect,
      popupRect: {
        width: popupEl ? popupEl.offsetWidth : triggerRect.width,
        height: popupEl ? popupEl.offsetHeight : 160,
      },
      viewportWidth: window.innerWidth,
      viewportHeight: window.innerHeight,
      padding: 8,
    });

    setCoords(pos);
  }, []);

  React.useLayoutEffect(() => {
    if (open) updatePosition();
  }, [open, updatePosition]);

  React.useEffect(() => {
    if (!open) return;

    // Mobile & Desktop outside click handler
    const onPointerDown = (e: PointerEvent) => {
      const target = e.target as Node;
      if (triggerRef.current?.contains(target) || popupRef.current?.contains(target)) {
        return;
      }
      setOpen(false);
    };

    const onResize = () => {
      updatePosition();
    };

    window.addEventListener('pointerdown', onPointerDown);
    window.addEventListener('resize', onResize);

    return () => {
      window.removeEventListener('pointerdown', onPointerDown);
      window.removeEventListener('resize', onResize);
    };
  }, [open, updatePosition]);

  const openPopup = () => {
    if (disabled) return;
    setHighlightedIndex(selectedIndex >= 0 ? selectedIndex : 0);
    const el = triggerRef.current;
    if (el && typeof window !== 'undefined') {
      const triggerRect = el.getBoundingClientRect();
      const pos = calculatePopupPosition({
        triggerRect,
        popupRect: { width: triggerRect.width, height: 160 },
        viewportWidth: window.innerWidth,
        viewportHeight: window.innerHeight,
        padding: 8,
      });
      setCoords(pos);
    }
    setOpen(true);
  };

  const closePopup = () => {
    setOpen(false);
    setCoords(null);
  };

  const toggle = () => {
    if (disabled) return;
    if (open) {
      closePopup();
      return;
    }
    openPopup();
  };

  const pick = (opt: OptionItem) => {
    closePopup();
    if (disabled || opt.value === current) return;
    onChange?.({ target: { value: opt.value } } as React.ChangeEvent<HTMLSelectElement>);
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (disabled) return;

    if (!open) {
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp' || e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        openPopup();
      }
      return;
    }

    if (e.key === 'Escape' || e.key === 'Tab') {
      // Escape closes only the popup: stop it reaching the window listener
      // that dismisses the surrounding dialog.
      if (e.key === 'Escape') {
        e.preventDefault();
        e.stopPropagation();
      }
      closePopup();
      return;
    }

    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setHighlightedIndex((prev) => (prev < options.length - 1 ? prev + 1 : 0));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setHighlightedIndex((prev) => (prev > 0 ? prev - 1 : options.length - 1));
    } else if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      if (highlightedIndex >= 0 && highlightedIndex < options.length) {
        pick(options[highlightedIndex]);
      }
    }
  };

  return (
    <div className={cn('relative w-full sm:w-auto', containerClassName)} title={title} id={props.id}>
      <button
        ref={triggerRef}
        type="button"
        onClick={toggle}
        disabled={disabled}
        title={title}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={props['aria-label']}
        onKeyDown={handleKeyDown}
        className={cn(
          'relative flex h-9 w-full cursor-pointer items-center justify-center rounded-full card-glass pl-4 pr-9 py-1.5 text-xs font-semibold text-primary transition-[color,background-color,border-color,box-shadow,transform,opacity] whitespace-nowrap hover:bg-surface-overlay hover:border-subtle focus:outline-none focus:border-accent-primary focus:ring-1 focus:ring-accent-primary disabled:cursor-not-allowed disabled:opacity-50 select-none active:scale-[0.98] shadow-xs',
          open && 'border-accent-primary ring-1 ring-accent-primary/40 shadow-[0_0_16px_color-mix(in_srgb,var(--accent-primary)_25%,transparent)]',
          Icon && 'pl-9',
          className
        )}
      >
        {Icon && (
          <Icon className={cn("pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 transition-colors", open ? "text-accent-primary" : "text-muted")} />
        )}
        <span className="truncate">{selected?.label ?? ''}</span>
        <RiArrowDownSLine
          className={cn(
            'pointer-events-none absolute right-3.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 transition-transform duration-200',
            open ? 'rotate-180 text-accent-primary' : 'text-secondary'
          )}
        />
      </button>

      {open && coords && typeof document !== 'undefined' &&
        createPortal(
          <div
            ref={popupRef}
            role="listbox"
            aria-label={props['aria-label']}
            className="fixed z-[9999] overflow-y-auto rounded-2xl bg-surface-secondary/95 glass-popup border border-subtle text-primary p-1.5 shadow-2xl no-scrollbar animate-scale-in nav-glow"
            style={{
              top: coords.top,
              left: coords.left,
              minWidth: coords.minWidth,
              maxWidth: typeof window !== 'undefined' ? window.innerWidth - 16 : '95vw',
              maxHeight: coords.maxHeight,
              // Scale from the trigger edge: opens below → grow from the top;
              // opens above → grow from the bottom.
              transformOrigin: coords.openAbove ? 'bottom center' : 'top center',
            }}
          >
            {options.map((opt, idx) => {
              const isSelected = opt.value === current;
              const isHighlighted = idx === highlightedIndex;

              return (
                <button
                  key={opt.value}
                  type="button"
                  role="option"
                  aria-selected={isSelected}
                  onPointerDown={(e) => {
                    e.preventDefault();
                    pick(opt);
                  }}
                  onMouseEnter={() => setHighlightedIndex(idx)}
                  className={cn(
                    'flex w-full cursor-pointer items-center justify-between gap-2.5 rounded-xl px-3 py-2 text-left text-xs font-semibold transition-transform min-h-[36px] active:scale-[0.98]',
                    isSelected
                      ? 'pill-depth-accent font-bold text-white shadow-xs'
                      : isHighlighted
                      ? 'bg-surface-overlay text-primary'
                      : 'text-secondary hover:bg-surface-overlay hover:text-primary'
                  )}
                >
                  <span className="truncate flex-1">{opt.label}</span>
                  {isSelected && <RiCheckFill className="h-3.5 w-3.5 shrink-0 text-white" />}
                </button>
              );
            })}
          </div>,
          document.body
        )}
    </div>
  );
}

export default Select;
