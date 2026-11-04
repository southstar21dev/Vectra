import { useState } from 'react';

/**
 * Returns true from the first time `open` becomes true, and stays true
 * afterwards. Lets a lazily-loaded dialog be mounted on first open while
 * staying mounted so its exit animation still plays.
 */
export function useLazyOpen(open: boolean): boolean {
  const [opened, setOpened] = useState(open);

  // Adjusted during render (not in an effect) so the consumer mounts in the
  // same commit the dialog opens — no one-frame stale render.
  if (open && !opened) setOpened(true);

  return opened;
}
