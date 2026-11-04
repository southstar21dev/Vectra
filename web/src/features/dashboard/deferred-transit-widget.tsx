import { lazy, Suspense } from 'react';
import { RiMapPinLine } from '@remixicon/react';
import { DeferredMount } from '@/components/ui/deferred-mount';
import type { TransitTracker } from '@/features/transit/use-transit';

const TransitWidget = lazy(() => import('./transit-widget').then((m) => ({ default: m.TransitWidget })));

/** Roughly the live card's footprint so little shifts when it swaps in. */
function TransitWidgetPlaceholder() {
  return (
    <section className="min-w-0" aria-hidden>
      <div className="h-80 w-full rounded-3xl bg-surface-secondary/60 border border-subtle flex items-center justify-center">
        <div className="flex flex-col items-center gap-2 text-muted">
          <RiMapPinLine className="h-4 w-4" />
          <p className="text-xs font-medium">Transit</p>
        </div>
      </div>
    </section>
  );
}

/**
 * Mounts the transit card only once it scrolls near the viewport. It pulls
 * maplibre (~1 MB + worker) and the transit catalog JSON (~1 MB, fetched at
 * runtime), so on phones — where it sits below the schedule — most cold
 * starts skip it until the user actually scrolls.
 */
export function DeferredTransitWidget(props: {
  favorites: TransitTracker[];
  onOpenTracker: (tracker: TransitTracker) => void;
}) {
  return (
    <DeferredMount placeholder={<TransitWidgetPlaceholder />}>
      <Suspense fallback={<TransitWidgetPlaceholder />}>
        <TransitWidget {...props} />
      </Suspense>
    </DeferredMount>
  );
}
