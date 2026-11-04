import { useEffect, useId, useRef } from 'react';
import { createDialogHistory, type DialogHistory } from '@/lib/dialog-history';
import { attachIosEdgeSwipeFallback, isIosStandaloneApp } from '@/lib/ios-edge-swipe';

let singleton: DialogHistory | null = null;
let listenerAttached = false;
let detachEdgeSwipe: (() => void) | null = null;

function popstateHandler() {
  singleton?.handlePopState();
}

function getDialogHistory(): DialogHistory {
  if (!singleton) {
    singleton = createDialogHistory({
      pathname: () => window.location.pathname,
      pushState: (state) => window.history.pushState(state, ''),
      back: () => window.history.back(),
      state: () => (window.history.state as Record<string, unknown> | null) ?? null,
      schedule: (fn) => {
        const timer = window.setTimeout(fn, 0);
        return () => window.clearTimeout(timer);
      },
    });
    const standalone =
      typeof navigator !== 'undefined'
        ? (navigator as Navigator & { standalone?: boolean }).standalone
        : undefined;
    if (!detachEdgeSwipe && isIosStandaloneApp(standalone)) {
      detachEdgeSwipe = attachIosEdgeSwipeFallback({
        dialogCount: () => singleton?.size() ?? 0,
        dismissTop: () => singleton?.dismissTop(),
        topPanel: () => {
          // A panel hidden by a just-committed swipe stays in the DOM until its
          // exit finishes; it must never be picked as the next gesture's target.
          const panels = Array.from(document.querySelectorAll<HTMLElement>('[role="dialog"]')).filter(
            (el) => el.style.display !== 'none' && el.getClientRects().length > 0
          );
          return panels.length > 0 ? panels[panels.length - 1] : null;
        },
      });
    }
  }
  if (!listenerAttached) {
    window.addEventListener('popstate', popstateHandler);
    listenerAttached = true;
  }
  return singleton;
}

export function resetDialogHistoryForTests(): void {
  singleton?.reset();
  singleton = null;
  detachEdgeSwipe?.();
  detachEdgeSwipe = null;
  if (listenerAttached) {
    window.removeEventListener('popstate', popstateHandler);
    listenerAttached = false;
  }
}

/**
 * Registers an open dialog so a back gesture closes the topmost one. The URL
 * never changes; the entry is a same-URL history trap.
 */
export function useDialogHistoryDismiss(
  open: boolean,
  onOpenChange: (open: boolean) => void
): void {
  const id = useId();
  const onOpenChangeRef = useRef(onOpenChange);
  useEffect(() => {
    onOpenChangeRef.current = onOpenChange;
  });

  useEffect(() => {
    if (!open || typeof window === 'undefined') return;
    return getDialogHistory().register(id, () => onOpenChangeRef.current(false));
  }, [open, id]);
}

// Escape stack: one window listener for every open dialog, so a single press
// closes only the most recently opened (topmost) one.
interface EscapeEntry {
  id: string;
  close: () => void;
}
let escapeStack: EscapeEntry[] = [];
let escapeListenerAttached = false;

function escapeHandler(e: KeyboardEvent) {
  // A control inside the dialog (e.g. an open Select) already handled it.
  if (e.key !== 'Escape' || e.defaultPrevented) return;
  const top = escapeStack[escapeStack.length - 1];
  if (top) top.close();
}

/** Registers an open dialog so Escape closes it only while it is topmost. */
export function useDialogEscapeDismiss(
  open: boolean,
  onOpenChange: (open: boolean) => void
): void {
  const id = useId();
  const onOpenChangeRef = useRef(onOpenChange);
  useEffect(() => {
    onOpenChangeRef.current = onOpenChange;
  });

  useEffect(() => {
    if (!open || typeof window === 'undefined') return;
    escapeStack = [
      ...escapeStack.filter((entry) => entry.id !== id),
      { id, close: () => onOpenChangeRef.current(false) },
    ];
    if (!escapeListenerAttached) {
      window.addEventListener('keydown', escapeHandler);
      escapeListenerAttached = true;
    }
    return () => {
      escapeStack = escapeStack.filter((entry) => entry.id !== id);
      if (escapeStack.length === 0 && escapeListenerAttached) {
        window.removeEventListener('keydown', escapeHandler);
        escapeListenerAttached = false;
      }
    };
  }, [open, id]);
}
