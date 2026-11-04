/**
 * Full-screen fallback for Suspense boundaries and auth boot — effectively
 * cold-start only: in-app navigation keeps the outgoing page on screen while
 * the next chunk loads (the router wraps navigation in startTransition) and
 * then cross-fades to it.
 *
 * Intentionally renders nothing: the boot splash already covers cold start, so
 * any visible spinner or mark would just read as a second loading screen. The
 * invisible `role="status"` marker is what the splash watches to know the
 * app's first content is ready.
 */
export function PageLoader() {
  return (
    <div
      className="min-h-screen"
      role="status"
      aria-label="Loading"
    />
  );
}
