/**
 * Browser-history integration for dialog back-gesture dismissal.
 *
 * A single marked history entry represents "at least one dialog is open".
 * The OS/browser owns screen-edge back gestures, so the only reliable way to
 * make them close a dialog is to let them pop this entry and react to the
 * resulting `popstate`.
 *
 * - The first registered dialog pushes the entry; nested dialogs do not push.
 * - A back event closes the top dialog and re-arms the entry when others
 *   remain, so nested dialogs unwind one back at a time.
 * - When the last dialog closes programmatically, entry removal is deferred
 *   one task so a StrictMode remount (or an immediate reopen) cancels it.
 *
 * Pure state machine with injected side effects: the window adapter
 * and React hook live in `@/hooks/use-dialog-history-dismiss`.
 */

export const DIALOG_HISTORY_KEY = '__vectraDialog';

export interface DialogHistoryDeps {
  pathname: () => string;
  pushState: (state: Record<string, unknown>) => void;
  back: () => void;
  state: () => Record<string, unknown> | null;
  /** Runs `fn` on the next task; returns a cancel function. */
  schedule: (fn: () => void) => () => void;
}

export interface DialogHistory {
  /** Registers an open dialog; returns its unregister function. */
  register: (id: string, close: () => void) => () => void;
  handlePopState: () => void;
  reset: () => void;
  size: () => number;
  /**
   * Closes the topmost dialog (edge-swipe fallback). When it closes the last
   * one, it removes the armed history entry itself.
   */
  dismissTop: () => void;
}

interface StackEntry {
  id: string;
  close: () => void;
}

export function createDialogHistory(deps: DialogHistoryDeps): DialogHistory {
  let stack: StackEntry[] = [];
  let entryPathname: string | null = null;
  let suppression = 0;
  let cancelPendingRemoval: (() => void) | null = null;

  const armed = () => deps.state()?.[DIALOG_HISTORY_KEY] === true;

  const pushEntry = () => {
    deps.pushState({ ...(deps.state() ?? {}), [DIALOG_HISTORY_KEY]: true });
    entryPathname = deps.pathname();
  };

  const cancelPendingRemovalIfAny = () => {
    cancelPendingRemoval?.();
    cancelPendingRemoval = null;
  };

  const scheduleHistoryRemoval = () => {
    if (!armed()) return; // the browser already consumed the entry
    cancelPendingRemovalIfAny();
    cancelPendingRemoval = deps.schedule(() => {
      cancelPendingRemoval = null;
      if (stack.length > 0 || !armed()) return; // re-registered before this ran
      suppression += 1;
      deps.back();
    });
  };

  /**
   * Closes the top dialog. `entryConsumed` is true when the browser popped the
   * history entry itself (popstate); false when we initiated the close
   * (edge-swipe fallback), in which case the entry must be removed once the
   * dialog is the last one.
   */
  const closeTop = (entryConsumed: boolean) => {
    const top = stack[stack.length - 1];
    if (!top) return;
    stack = stack.slice(0, -1);
    top.close();
    if (stack.length > 0 && !armed()) pushEntry();
    if (stack.length === 0 && !entryConsumed) scheduleHistoryRemoval();
  };

  const unregister = (id: string) => {
    const before = stack.length;
    stack = stack.filter((entry) => entry.id !== id);
    if (stack.length === before) return; // id was not registered
    if (stack.length > 0) return; // entry still represents the remaining dialogs
    scheduleHistoryRemoval();
  };

  const reset = () => {
    stack = [];
    entryPathname = null;
    suppression = 0;
    cancelPendingRemovalIfAny();
  };

  return {
    register(id, close) {
      // Reopening before the scheduled removal keeps the existing entry.
      cancelPendingRemovalIfAny();
      stack = [...stack.filter((entry) => entry.id !== id), { id, close }];
      if (!armed()) pushEntry();
      return () => unregister(id);
    },
    handlePopState() {
      if (suppression > 0) {
        suppression -= 1;
        return;
      }
      if (stack.length === 0) return;
      if (entryPathname !== null && deps.pathname() !== entryPathname) {
        // A page navigation (deep link, notification), not a dialog back.
        // Page-scoped dialogs unmount on their own.
        reset();
        return;
      }
      closeTop(true);
    },
    reset,
    size: () => stack.length,
    dismissTop: () => closeTop(false),
  };
}
