/**
 * Synchronous online check for use in non-hook contexts (queryFn, etc.).
 */
export function isOnline(): boolean {
  return typeof navigator === 'undefined' || navigator.onLine;
}
