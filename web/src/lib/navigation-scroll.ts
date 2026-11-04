/**
 * Tracks whether the next page that mounts should start scrolled to the top.
 *
 * In-app navigations push a history entry, and the router renders the next
 * page only once its lazy chunk is ready — so the outgoing page stays on
 * screen a beat longer while the page transition plays. Scrolling immediately
 * would visibly jump the outgoing page, so navigators mark the intent here and
 * the page transition consumes it when the new page mounts.
 *
 * Browser back/forward never marks, letting the platform's scroll restoration
 * behave as usual.
 */
let scrollOnNextPage = false;

/** Request that the next page to mount scroll to the top. */
export function markScrollOnNextPage(): void {
  scrollOnNextPage = true;
}

/** Read and clear the pending scroll request. */
export function consumeScrollOnNextPage(): boolean {
  const pending = scrollOnNextPage;
  scrollOnNextPage = false;
  return pending;
}
