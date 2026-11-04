import * as React from 'react';
import { createContext, useContext, useEffect, useId, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { RiCloseLine } from '@remixicon/react';
import { cn } from '@/lib/utils';
import { motion, AnimatePresence, useReducedMotion } from 'framer-motion';
import { useDialogEscapeDismiss, useDialogHistoryDismiss } from '@/hooks/use-dialog-history-dismiss';
import { useIsCompactViewport } from '@/hooks/use-is-compact-viewport';
import { resolveDialogPresentation, type DialogPresentation } from './dialog-presentation';
import { ensureDialogOriginTracking, resolveDialogOrigin, resolveRecentPointerTarget } from './dialog-origin';
import { resolveFocusReturnTarget, restoreFocusTo } from './dialog-focus';

export interface DialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  children: React.ReactNode;
  /** Edge-to-edge content: removes the default padding (full-screen maps). */
  fullscreen?: boolean;
  /** Called after the close (exit) animation finishes. */
  onExited?: () => void;
}

/** Links the panel to its title for assistive tech (aria-labelledby). */
interface DialogA11yContextValue {
  titleId: string;
  hasTitle: boolean;
  setHasTitle: (hasTitle: boolean) => void;
}
const DialogA11yContext = createContext<DialogA11yContextValue | null>(null);

let openDialogsCount = 0;
let previousBodyOverflow = '';
let previousBodyPaddingRight = '';
let previousHtmlOverflow = '';

function lockBodyScroll() {
  if (typeof document === 'undefined') return;
  if (openDialogsCount === 0) {
    previousBodyOverflow = document.body.style.overflow;
    previousBodyPaddingRight = document.body.style.paddingRight;
    previousHtmlOverflow = document.documentElement.style.overflow;

    const scrollbarWidth = window.innerWidth - document.documentElement.clientWidth;
    if (scrollbarWidth > 0) {
      document.body.style.paddingRight = `${scrollbarWidth}px`;
    }
    document.body.style.overflow = 'hidden';
    document.documentElement.style.overflow = 'hidden';
  }
  openDialogsCount++;
}

function unlockBodyScroll() {
  if (typeof document === 'undefined') return;
  openDialogsCount = Math.max(0, openDialogsCount - 1);
  if (openDialogsCount === 0) {
    document.body.style.overflow = previousBodyOverflow;
    document.body.style.paddingRight = previousBodyPaddingRight;
    document.documentElement.style.overflow = previousHtmlOverflow;
  }
}

export function Dialog({ open, onOpenChange, children, fullscreen, onExited }: DialogProps) {
  // Back gesture / browser back closes the topmost dialog via the shared
  // history stack (see use-dialog-history-dismiss).
  useDialogHistoryDismiss(open, onOpenChange);

  // Lock background body scroll while dialog is open
  useEffect(() => {
    if (!open) return;
    lockBodyScroll();
    return () => {
      unlockBodyScroll();
    };
  }, [open]);

  // Escape closes only the topmost open dialog (shared stack, see
  // use-dialog-history-dismiss). Runs before the SSR guard below so the hook
  // order stays stable.
  useDialogEscapeDismiss(open, onOpenChange);

  // Capture the tap point so any dialog can expand out of the control that
  // opened it (see dialog-origin.ts).
  useEffect(() => {
    ensureDialogOriginTracking();
  }, []);

  // Focus restore: returning focus to the opener on close keeps keyboard and
  // screen-reader users anchored where they left the page.
  // Capture during render, the moment `open` flips on: by the time any effect
  // runs, an `autoFocus` control inside the panel may already hold focus
  // (React applies it before layout effects, children first), and a click on
  // a non-focusable opener — or in Safari, which never focuses clicked
  // buttons — leaves only <body>; dialog-focus.ts falls back to the pressed
  // element then. Restore from a passive cleanup: it runs after the commit,
  // so React's own focus restoration (back to the still-mounted, exiting
  // panel) cannot undo it — and it also runs when the Dialog unmounts outright
  // (a conditionally rendered or re-keyed dialog).
  const [focusCapture, setFocusCapture] = useState<{ open: boolean; target: HTMLElement | null }>({
    open: false,
    target: null,
  });
  if (open !== focusCapture.open) {
    // Adjusting state during render: React's documented pattern for deriving
    // from a prop change (re-renders before committing, no effect round-trip).
    setFocusCapture({
      open,
      target:
        open && typeof document !== 'undefined'
          ? resolveFocusReturnTarget(document.activeElement, resolveRecentPointerTarget())
          : focusCapture.target,
    });
  }
  const returnTarget = focusCapture.target;
  useEffect(() => {
    if (!open) return;
    return () => restoreFocusTo(returnTarget);
  }, [open, returnTarget]);

  const titleId = useId();
  const [hasTitle, setHasTitle] = useState(false);
  const a11y = useMemo(() => ({ titleId, hasTitle, setHasTitle }), [titleId, hasTitle]);

  if (typeof document === 'undefined') return null;

  return createPortal(
    <DialogA11yContext.Provider value={a11y}>
      <AnimatePresence onExitComplete={onExited}>
        {open && (
          <div
            className={cn(
              'fixed inset-0 z-[1500] flex items-center justify-center overscroll-contain',
              !fullscreen && 'p-4 sm:p-6'
            )}
          >
            {/*
              The scrim is pre-promoted to its own compositor layer with a 3D
              transform. Its opacity is animated from JS (framer-motion 13 no
              longer adds `will-change`), so without an explicit layer hint an
              Android WebView repaints this full-viewport layer on the main
              thread every frame of the open/close animation — the raster can't
              keep up and the scrim blanks out for a frame (the dialog "blinks").
              This used to be forced by the backdrop blur the scrim carried, so
              dropping it in favor of the plain dark scrim exposed the flash.
            */}
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              transition={{ duration: 0.2 }}
              data-dialog-scrim=""
              className="fixed inset-0 bg-[var(--dialog-scrim)] transition-none overscroll-contain touch-none"
              style={{ transform: 'translate3d(0, 0, 0)' }}
              onClick={() => onOpenChange(false)}
            />
            {children}
          </div>
        )}
      </AnimatePresence>
    </DialogA11yContext.Provider>,
    document.body
  );
}

export interface DialogContentProps
  extends Omit<React.ComponentPropsWithoutRef<typeof motion.div>, 'children'> {
  onClose?: () => void;
  children?: React.ReactNode;
  /** `auto` (default) presents full-screen on compact (below-xl) viewports and
   *  centered elsewhere; `dialog` always centers (confirmations). */
  presentation?: DialogPresentation;
  /** Compact full-screen panels with no panel padding (map-style content). */
  edgeToEdge?: boolean;
}

export const DialogContent = React.forwardRef<HTMLDivElement, DialogContentProps>(({
  className,
  children,
  onClose,
  presentation = 'auto',
  edgeToEdge = false,
  style,
  ...props
}, ref) => {
  const contentRef = useRef<HTMLDivElement>(null);
  const a11y = useContext(DialogA11yContext);
  const labelledBy =
    props['aria-label'] || props['aria-labelledby'] || !a11y?.hasTitle ? undefined : a11y.titleId;
  const reduceMotion = useReducedMotion();
  const compact = useIsCompactViewport();
  const { isFullscreen, panelStyle, closeButtonStyle, motion: dialogMotion } = resolveDialogPresentation({
    presentation,
    compact,
    edgeToEdge,
    reduceMotion: !!reduceMotion,
  });
  const setRefs = (el: HTMLDivElement | null) => {
    contentRef.current = el;
    if (typeof ref === 'function') ref(el);
    else if (ref) (ref as React.MutableRefObject<HTMLDivElement | null>).current = el;
  };

  // Expand from the point that opened the dialog. Derived from the panel's
  // *layout* size (offsetWidth/Height ignore the transform Framer is about to
  // apply) and the fact that a centred panel's centre is the viewport centre —
  // so no measurement of the animating box is needed.
  useLayoutEffect(() => {
    const el = contentRef.current;
    if (!el) return;
    const origin = resolveDialogOrigin();
    if (!origin) return;
    const w = el.offsetWidth;
    const h = el.offsetHeight;
    const left = (window.innerWidth - w) / 2;
    const top = (window.innerHeight - h) / 2;
    const cx = left + w / 2;
    const cy = top + h / 2;
    // Full-screen panels fill the viewport, so the tap already sits inside
    // them — grow straight from it. Centred dialogs pull the origin halfway to
    // centre so a distant trigger reads as a settle, not a slide across screen.
    const factor = isFullscreen ? 1 : 0.5;
    const ox = cx + (origin.x - cx) * factor;
    const oy = cy + (origin.y - cy) * factor;
    el.style.transformOrigin = `${ox - left}px ${oy - top}px`;
  }, [isFullscreen]);

  // Initial focus + Tab trap: keyboard focus starts inside the dialog and
  // cycles within it until the dialog closes (screen-reader and keyboard
  // users must not tab out into the inert page behind a modal).
  useEffect(() => {
    contentRef.current?.focus({ preventScroll: true });
  }, []);

  const trapTab = (e: React.KeyboardEvent) => {
    if (e.key !== 'Tab') return;
    const root = contentRef.current;
    if (!root) return;
    const focusables = Array.from(
      root.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), textarea, input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])'
      )
    );
    if (focusables.length === 0) return;
    const first = focusables[0];
    const last = focusables[focusables.length - 1];
    const active = document.activeElement;
    if (e.shiftKey && (active === first || !root.contains(active))) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && (active === last || !root.contains(active))) {
      e.preventDefault();
      first.focus();
    }
  };

  return (
    <motion.div
      ref={setRefs}
      role="dialog"
      aria-modal="true"
      aria-labelledby={labelledBy}
      tabIndex={-1}
      onKeyDown={trapTab}
      initial={dialogMotion.initial}
      animate={dialogMotion.animate}
      exit={dialogMotion.exit}
      transition={dialogMotion.transition}
      // focus:outline-none — the panel focuses itself on mount (below) to seed
      // the Tab trap, and the browser paints its default blue focus ring around
      // whatever is focused. The ring reads as a stray selection border around
      // the whole dialog, so the panel opts out; visible rings stay on the
      // controls inside it, where focus actually lands for keyboard users.
      className={cn(
        'relative z-50 w-full max-w-lg rounded-2xl bg-surface-secondary border border-subtle p-4 sm:p-6 card-depth dialog-depth max-h-[90vh] flex flex-col overflow-y-auto no-scrollbar overscroll-contain focus:outline-none',
        className
      )}
      // Pre-promote the panel to its own compositor layer for the same reason
      // as the scrim: its open/close spring animates transform + opacity from
      // JS, and framer-motion 13 no longer sets `will-change` itself. Without
      // the hint the Android WebView re-rasterizes the panel (plus its layered
      // shadows) on the main thread on every frame, which can drop/blank
      // frames mid-animation.
      style={{ willChange: 'transform, opacity', ...panelStyle, ...style }}
      {...props}
    >
      {onClose && (
        <button
          onClick={onClose}
          aria-label="Close dialog"
          style={closeButtonStyle}
          className="absolute right-3 top-3 rounded-full w-9 h-9 flex items-center justify-center bg-surface-tertiary border border-subtle text-secondary hover:text-primary hover:bg-surface-secondary transition-colors cursor-pointer z-10 shadow-sm focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-primary active:scale-95"
        >
          <RiCloseLine className="h-4 w-4" />
        </button>
      )}
      {children}
    </motion.div>
  );
});
DialogContent.displayName = 'DialogContent';

export function DialogHeader({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn('flex flex-col space-y-2.5 text-left shrink-0 pt-1 pr-6', className)}
      {...props}
    />
  );
}

export function DialogTitle({ className, id, ...props }: React.HTMLAttributes<HTMLHeadingElement>) {
  const a11y = useContext(DialogA11yContext);
  const setHasTitle = a11y?.setHasTitle;
  useLayoutEffect(() => {
    if (!setHasTitle || id) return;
    setHasTitle(true);
    return () => setHasTitle(false);
  }, [setHasTitle, id]);
  return (
    <h2
      id={id ?? a11y?.titleId}
      className={cn('text-sm sm:text-base font-bold text-primary flex items-center gap-2 leading-normal', className)}
      {...props}
    />
  );
}

export function DialogDescription({ className, ...props }: React.HTMLAttributes<HTMLParagraphElement>) {
  return (
    <p className={cn('text-xs text-secondary leading-relaxed mt-1.5', className)} {...props} />
  );
}

export function DialogFooter({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn('flex flex-col-reverse sm:flex-row items-stretch sm:items-center justify-end gap-2.5 shrink-0 pt-2', className)}
      {...props}
    />
  );
}
