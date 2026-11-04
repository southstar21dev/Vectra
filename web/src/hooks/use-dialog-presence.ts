import { useCallback, useRef, useState } from 'react';

export interface DialogPresence<T> {
  /** Data for the presented dialog; stays populated through the exit. */
  value: T | null;
  /** Whether the dialog should be open (pass to the dialog's `open`). */
  open: boolean;
  /** Open (or replace) the dialog with `value`. */
  openWith: (value: T) => void;
  /** Pass to the dialog's `onOpenChange`. */
  onOpenChange: (open: boolean) => void;
  /** Pass to the dialog's `onExited` (Dialog forwards AnimatePresence completion). */
  onExited: () => void;
}

/**
 * Keeps a conditionally-mounted dialog rendered until its close animation
 * finishes.
 *
 * The common pattern `{state && <Dialog open={Boolean(state)} .../>}` unmounts
 * the dialog the instant the state clears, so framer's exit animation never
 * runs. This hook holds the value while the panel animates out and releases it
 * only in `onExited`:
 *
 *   const tracker = useDialogPresence<ScheduleEvent>();
 *   open:   tracker.openWith(event)
 *   render: {tracker.value && (
 *             <CourseTrackerModal
 *               open={tracker.open}
 *               onOpenChange={tracker.onOpenChange}
 *               onExited={tracker.onExited}
 *               courseName={tracker.value.course_name}
 *               ...
 *             />
 *           )}
 *
 * For dialogs without payload data use `useDialogPresence<true>()` and
 * `openWith(true)`.
 */
export function useDialogPresence<T>(): DialogPresence<T> {
  const [value, setValue] = useState<T | null>(null);
  const [open, setOpen] = useState(false);
  const openRef = useRef(false);

  const openWith = useCallback((next: T) => {
    openRef.current = true;
    setValue(next);
    setOpen(true);
  }, []);

  const onOpenChange = useCallback((next: boolean) => {
    if (next) return;
    openRef.current = false;
    setOpen(false);
  }, []);

  const onExited = useCallback(() => {
    // A reopen during the exit must not be wiped by its completion callback.
    if (!openRef.current) setValue(null);
  }, []);

  return { value, open, openWith, onOpenChange, onExited };
}
