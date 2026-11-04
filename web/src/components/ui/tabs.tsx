import * as React from 'react';
import { motion, useReducedMotion } from 'framer-motion';
import { cn } from '@/lib/utils';

export interface TabsProps extends React.HTMLAttributes<HTMLDivElement> {
  value: string;
  onValueChange: (value: string) => void;
  /** Kept for callers that pass it; the pill no longer uses Framer's shared layout. */
  layoutId?: string;
}

const TabsContext = React.createContext<{
  value: string;
  onValueChange: (value: string) => void;
  baseId: string;
}>({ value: '', onValueChange: () => {}, baseId: 'tabs' });

export function Tabs({ value, onValueChange, layoutId: _layoutId, children, className, ...props }: TabsProps) {
  const generatedId = React.useId();
  const baseId = React.useMemo(() => `tabs-${generatedId.replace(/:/g, '')}`, [generatedId]);

  return (
    <TabsContext.Provider value={{ value, onValueChange, baseId }}>
      <div className={cn('w-full', className)} {...props}>
        {children}
      </div>
    </TabsContext.Provider>
  );
}

interface PillBox {
  x: number;
  y: number;
  width: number;
  height: number;
}

const PILL_SPRING = { type: 'spring', stiffness: 500, damping: 38 } as const;

/**
 * The tab strip owns one selected pill and places it by measuring the selected
 * trigger's offset box. That keeps the pill off Framer's layout-projection
 * tree: it is a plain spring on x/y/width/height, so it never re-measures
 * against a dialog that is still scaling in (the jiggle on open), never
 * nudges unrelated layout elements such as the navbar pill, and glides on the
 * very first switch.
 */
export function TabsList({ className, children, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  const { value } = React.useContext(TabsContext);
  const listRef = React.useRef<HTMLDivElement>(null);
  const [box, setBox] = React.useState<PillBox | null>(null);
  const reduceMotion = useReducedMotion();

  const measure = React.useCallback(() => {
    const list = listRef.current;
    const tab = list?.querySelector<HTMLElement>('[role="tab"][aria-selected="true"]');
    if (!list || !tab) {
      setBox(null);
      return;
    }
    // Offsets are layout values: a transform on an ancestor (a dialog mid
    // entrance) does not reach them, unlike getBoundingClientRect.
    const next = { x: tab.offsetLeft, y: tab.offsetTop, width: tab.offsetWidth, height: tab.offsetHeight };
    setBox((prev) =>
      prev && prev.x === next.x && prev.y === next.y && prev.width === next.width && prev.height === next.height
        ? prev
        : next
    );
  }, []);

  // Before paint on every selection change, so the pill never shows a stale
  // slot for a frame; then follow any resize of the strip or its tabs (font
  // swap, container width, tabs added or removed).
  React.useLayoutEffect(measure, [measure, value, children]);
  React.useEffect(() => {
    const list = listRef.current;
    if (!list) return;
    const ro = new ResizeObserver(measure);
    ro.observe(list);
    for (const child of Array.from(list.children)) ro.observe(child);
    return () => ro.disconnect();
  }, [measure, children]);

  return (
    <div
      ref={listRef}
      role="tablist"
      aria-orientation="horizontal"
      className={cn(
        'card-glass relative inline-flex h-10 items-center justify-center rounded-full p-1 text-secondary',
        className
      )}
      {...props}
    >
      {box && (
        <motion.div
          data-tabs-pill=""
          aria-hidden="true"
          initial={false}
          // Only the position springs. The size is applied outright, the way
          // the old position-only layout animation did: a pill that also
          // tweens its width between tabs of different sizes reads as
          // stretching rather than moving.
          animate={{ x: box.x, y: box.y }}
          transition={reduceMotion ? { duration: 0 } : PILL_SPRING}
          style={{ width: box.width, height: box.height }}
          className="absolute left-0 top-0 rounded-full bg-accent-primary border border-accent-primary/50 nav-pill-glow pointer-events-none"
        >
          <div className="absolute top-0 inset-x-2 h-[1px] bg-gradient-to-r from-transparent via-white/50 to-transparent pointer-events-none" />
        </motion.div>
      )}
      {children}
    </div>
  );
}

export interface TabsTriggerProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  value: string;
}

export function TabsTrigger({ value, className, children, ...props }: TabsTriggerProps) {
  const context = React.useContext(TabsContext);
  const isSelected = context.value === value;
  const ref = React.useRef<HTMLButtonElement>(null);

  // Roving tabindex + arrow-key navigation (WAI-ARIA tabs pattern): focus
  // moves with the arrows and activates the newly focused tab.
  const handleKeyDown = (e: React.KeyboardEvent<HTMLButtonElement>) => {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) return;
    const list = ref.current?.closest('[role="tablist"]');
    const tabs = Array.from(list?.querySelectorAll<HTMLElement>('[role="tab"]') ?? []);
    const currentIndex = tabs.indexOf(ref.current as HTMLElement);
    if (currentIndex === -1 || tabs.length === 0) return;
    e.preventDefault();
    let nextIndex = currentIndex;
    if (e.key === 'ArrowLeft') nextIndex = (currentIndex - 1 + tabs.length) % tabs.length;
    if (e.key === 'ArrowRight') nextIndex = (currentIndex + 1) % tabs.length;
    if (e.key === 'Home') nextIndex = 0;
    if (e.key === 'End') nextIndex = tabs.length - 1;
    tabs[nextIndex]?.focus();
    tabs[nextIndex]?.click();
  };

  return (
    <button
      ref={ref}
      type="button"
      role="tab"
      id={`${context.baseId}-tab-${value}`}
      aria-selected={isSelected}
      aria-controls={`${context.baseId}-panel-${value}`}
      tabIndex={isSelected ? 0 : -1}
      onClick={() => context.onValueChange(value)}
      onKeyDown={handleKeyDown}
      className={cn(
        'relative z-10 inline-flex h-full items-center justify-center whitespace-nowrap rounded-full px-3 text-xs font-semibold flex-1 cursor-pointer select-none transition-colors duration-200 focus:outline-none focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent-primary active:scale-[0.98]',
        isSelected
          ? 'text-white font-bold'
          : 'text-secondary hover:text-primary hover:bg-surface-overlay/50',
        className
      )}
      {...props}
    >
      <span className="relative flex items-center justify-center gap-1.5 w-full">
        {children}
      </span>
    </button>
  );
}

export interface TabsContentProps extends React.HTMLAttributes<HTMLDivElement> {
  value: string;
}

export function TabsContent({ value, className, children, ...props }: TabsContentProps) {
  const context = React.useContext(TabsContext);
  if (context.value !== value) return null;

  return (
    <div
      role="tabpanel"
      id={`${context.baseId}-panel-${value}`}
      aria-labelledby={`${context.baseId}-tab-${value}`}
      className={cn('mt-4', className)}
      {...props}
    >
      {children}
    </div>
  );
}
