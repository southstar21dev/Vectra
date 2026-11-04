/**
 * Whether a vertical drag should pull the whole sheet down instead of being
 * left to the departure list's native scrolling.
 *
 * The sheet only takes over when it is expanded (`sheetY <= 1`), the finger is
 * moving down, and the list is already at (or above) its top. `feedScrollTop`
 * is `null` when the drag started outside the list (grabber/chip row), where
 * there is no scroll to conflict with.
 */
export function shouldPullSheet({
  sheetY,
  deltaY,
  feedScrollTop,
  threshold,
}: {
  sheetY: number;
  deltaY: number;
  feedScrollTop: number | null;
  threshold: number;
}): boolean {
  if (sheetY > 1) return false;
  if (deltaY <= threshold) return false;
  return feedScrollTop === null || feedScrollTop <= 1;
}

export interface ClickSuppressor {
  /** A drag started: the click it may synthesise should be swallowed. */
  arm: () => void;
  /** A new press started: any still-pending suppression is stale. */
  beginPress: () => void;
  /** True when this click belongs to a finished drag. */
  consume: () => boolean;
}

/**
 * Tracks the click a drag gesture synthesises so it can be swallowed without
 * eating the next, unrelated tap.
 *
 * A drag arms the suppressor and the click the browser fires for that same
 * gesture consumes it. If the drag never produces one, the flag would otherwise
 * stay armed and swallow the next genuine tap, so starting a new press clears
 * it.
 */
export function createClickSuppressor(): ClickSuppressor {
  let pending = false;
  return {
    arm() {
      pending = true;
    },
    beginPress() {
      pending = false;
    },
    consume() {
      if (!pending) return false;
      pending = false;
      return true;
    },
  };
}
