/**
 * Suspense fallback for the lazily-loaded map components. Sizes itself so the
 * map's container keeps its height while the (service-worker precached) maplibre
 * chunk resolves, avoiding a layout shift on first open.
 */
export function MapFallback() {
  return (
    <div className="flex h-full min-h-[180px] w-full items-center justify-center">
      <div className="h-5 w-5 animate-spin rounded-full border-2 border-tint-blue border-t-transparent" />
    </div>
  );
}
