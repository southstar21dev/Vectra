import { Component, useState, useEffect, useTransition, Suspense, type ReactNode } from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { queryClient } from '@/lib/query-client';
import { markScrollOnNextPage } from '@/lib/navigation-scroll';
import { ensureHomeBeneath, isKnownAppPath, navigateInApp } from '@/lib/app-navigation';
import { AuthGuard } from '@/features/auth/auth-guard';
import { useSessionWatchdog } from '@/features/auth/use-session-watchdog';
import { registerServiceWorker } from '@/features/notifications/push-manager';
import { resyncRotatedPushSubscription } from '@/features/notifications/use-push-subscription';
import { PwaInstallBanner } from '@/components/ui/pwa-install-banner';
import { AppearanceManager } from '@/features/settings/appearance-manager';
import { PageLoader } from '@/components/ui/page-loader';
import { AppSplash } from '@/components/ui/app-splash';
import { AppShell } from '@/components/layout/app-shell';
import { prefetchRouteForPath } from '@/lib/route-prefetch';
import { ensureDialogOriginTracking } from '@/components/ui/dialog-origin';

import { useDevicePlatform } from '@/features/onboarding/hooks/use-device-platform';
import { PwaMobileGate } from '@/features/onboarding/components/pwa-mobile-gate';

// Route pages are imported EAGERLY (no lazy chunk): navigation never waits on a
// chunk, so a page change always has the real page ready to animate. The maps
// (the heavy part) stay lazy — see the lazy map components in the transit/trips
// features — so maplibre never enters the eager bundle.
import { DashboardPage } from '@/features/dashboard/dashboard-page';
import { SchedulePage } from '@/features/schedule/schedule-page';
import { TransitPage } from '@/features/transit/transit-page';
import { SettingsPage } from '@/features/settings/settings-page';
import { LoginPage } from '@/features/auth/login-page';

/** A failed route-chunk load (e.g. offline without a controlling service
 *  worker) would otherwise unmount the whole app into a black screen. Show a
 *  graceful message + reload instead. */
class LazyErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  render() {
    if (this.state.failed) {
      return (
        <div className="flex flex-col items-center justify-center gap-3 px-6 py-20 text-center">
          <p className="text-xs leading-relaxed text-secondary">
            Couldn't load this page. You may be offline.
          </p>
          <button
            className="cursor-pointer rounded-lg border border-subtle bg-surface-overlay px-3 py-1.5 text-[10px] font-bold text-primary transition-colors hover:bg-surface-overlay"
            onClick={() => window.location.reload()}
          >
            Try again
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}

/**
 * The current pathname, or Home when the router has no page for it. An unknown
 * URL is replaced with `/` so the address bar, back stack and a reload all
 * agree with the dashboard that renders (rather than keeping the bad URL).
 */
function routablePath(): string {
  const current = window.location.pathname;
  if (isKnownAppPath(current)) return current;
  window.history.replaceState(window.history.state, '', '/');
  return '/';
}

function AppRouter() {
  const [path, setPath] = useState(routablePath);
  // The navbar's path updates urgently (outside the transition) so the active
  // pill starts moving on the tap frame, while the page itself renders in the
  // transition behind it.
  const [navPath, setNavPath] = useState(() => window.location.pathname);
  const [, startTransition] = useTransition();
  const [skipPageEnter, setSkipPageEnter] = useState(false);
  const { isMobileWebGateActive } = useDevicePlatform();

  useEffect(() => {
    // Register Service Worker for PWA & Push
    registerServiceWorker();
    // Adopt a push endpoint the browser rotated while the app was closed
    // (see the `pushsubscriptionchange` handler in sw.ts). No-op signed out.
    void resyncRotatedPushSubscription();

    // Track the tap point from app start so the very first dialog can open from
    // the control that triggered it (see components/ui/dialog-origin.ts).
    ensureDialogOriginTracking();

    // Back from a deep-linked page lands on Home before leaving the app.
    ensureHomeBeneath(
      window.location.pathname,
      window.location.pathname + window.location.search + window.location.hash,
      window.history
    );

    const handlePopState = (e: PopStateEvent) => {
      const nextPath = routablePath();
      // Chrome's back-swipe preview already animated this navigation; a second
      // page fade on top of it reads as a glitch.
      const uaAnimated = (e as PopStateEvent & { hasUAVisualTransition?: boolean }).hasUAVisualTransition === true;
      setNavPath(nextPath);
      startTransition(() => {
        setSkipPageEnter(uaAnimated);
        setPath(nextPath);
      });
    };

    window.addEventListener('popstate', handlePopState);

    // Intercept internal anchor link clicks for SPA feel. Modified clicks
    // (new tab/window, download), non-primary buttons, clicks a handler
    // already claimed, and links outside the app's routes keep the browser's
    // default behaviour.
    const handleLinkClick = (e: MouseEvent) => {
      if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
      const target = (e.target as HTMLElement).closest('a');
      if (
        target &&
        target.href &&
        target.href.startsWith(window.location.origin) &&
        isKnownAppPath(target.pathname) &&
        !target.hasAttribute('download') &&
        (!target.target || target.target === '_self')
      ) {
        e.preventDefault();
        const nextUrl = target.pathname + target.search;
        if (nextUrl !== window.location.pathname + window.location.search) {
          markScrollOnNextPage();
          if (navigateInApp(window.location.pathname, nextUrl, window.history)) {
            setNavPath(target.pathname);
            startTransition(() => {
              setSkipPageEnter(false);
              setPath(target.pathname);
            });
          }
        }
      }
    };

    document.addEventListener('click', handleLinkClick);

    // Warm a route's chunk as soon as the user shows intent to open it (hover /
    // pointer-down on any internal link), so the module is parsed/evaluated
    // before the tap resolves instead of during the transition.
    const handleLinkIntent = (e: Event) => {
      const target = (e.target as HTMLElement).closest('a');
      if (
        target &&
        target.href &&
        target.href.startsWith(window.location.origin) &&
        !target.hasAttribute('download') &&
        target.target !== '_blank'
      ) {
        prefetchRouteForPath(target.pathname);
      }
    };
    document.addEventListener('pointerover', handleLinkIntent, { passive: true });
    document.addEventListener('pointerdown', handleLinkIntent, { passive: true, capture: true });

    return () => {
      window.removeEventListener('popstate', handlePopState);
      document.removeEventListener('click', handleLinkClick);
      document.removeEventListener('pointerover', handleLinkIntent);
      document.removeEventListener('pointerdown', handleLinkIntent, { capture: true });
    };
  }, []);

  // Strict Mobile Browser PWA Gate: Mobile browsers cannot do anything until installed as PWA
  if (isMobileWebGateActive) {
    return <PwaMobileGate />;
  }

  // Route matching
  if (path === '/login' || path === '/signup') {
    return (
      <Suspense fallback={<PageLoader />}>
        <LoginPage initialMode={path === '/signup' ? 'signup' : 'login'} />
      </Suspense>
    );
  }

  // Authenticated routes
  let pageContent = <DashboardPage />;

  if (path.startsWith('/schedule')) {
    pageContent = <SchedulePage />;
  } else if (path.startsWith('/transit')) {
    pageContent = <TransitPage />;
  } else if (path.startsWith('/settings')) {
    pageContent = <SettingsPage />;
  }

  return (
    <AuthGuard>
      <AppShell routeKey={path} navPath={navPath} skipPageEnter={skipPageEnter}>
        <Suspense fallback={<PageLoader />}>
          <LazyErrorBoundary>{pageContent}</LazyErrorBoundary>
        </Suspense>
      </AppShell>
    </AuthGuard>
  );
}

export function App() {
  useSessionWatchdog();
  return (
    <QueryClientProvider client={queryClient}>
      <AppearanceManager />
      <AppRouter />
      {/* Boot splash: letter-by-letter wordmark; self-dismisses once the app's
          first content has rendered (min duration + ready). Mounted once, so
          it only plays on cold load, not on navigation. */}
      <AppSplash />
      <PwaInstallBanner />
    </QueryClientProvider>
  );
}

export default App;
