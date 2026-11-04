import { type ReactNode, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { motion, useReducedMotion } from 'framer-motion';
import { Navbar } from './navbar';
import { BackgroundGlow } from '@/components/ui/background-glow';
import { useAuth } from '@/features/auth/use-auth';
import { useNeptunSync, ONE_HOUR_MS } from '@/features/schedule/use-neptun-sync';
import { decryptNeptunUrl } from '@/lib/encryption';
import { consumeScrollOnNextPage } from '@/lib/navigation-scroll';
import { scheduleCommonRoutePrefetch } from '@/lib/route-prefetch';
import { EASE_DRAWER } from '@/components/ui/motion-tokens';

import { useAppearance } from '@/hooks/use-appearance';

interface AppShellProps {
  children: ReactNode;
  /** Current route (pathname). Changing it remounts the page and plays its entrance. */
  routeKey: string;
  /** Urgent copy of the route for the navbar, so its pill moves before the page renders. */
  navPath: string;
  /** The browser already animated this navigation (back-swipe preview): show the page without a fade. */
  skipPageEnter?: boolean;
}

import { Toaster } from 'sonner';

import { NavbarActionProvider } from '@/features/navbar/navbar-action-provider';

/** Scrolls a freshly entered page to the top before its first paint, but only
 *  when the navigation asked for it (forward link taps). Browser back/forward
 *  keeps the platform's scroll restoration. */
function ScrollToTopOnMount() {
  useLayoutEffect(() => {
    if (consumeScrollOnNextPage()) window.scrollTo(0, 0);
  }, []);
  return null;
}

export function AppShell({ children, routeKey, navPath, skipPageEnter = false }: AppShellProps) {
  const reduceMotion = useReducedMotion();
  // No page entrance on the boot route: the splash is already presenting it.
  const [bootKey] = useState(routeKey);
  const [navigated, setNavigated] = useState(false);
  if (!navigated && routeKey !== bootKey) setNavigated(true);
  const { profile } = useAuth();
  const { effective } = useAppearance();
  const { runSync } = useNeptunSync();
  const hasAttemptedAutoSync = useRef(false);

  useEffect(() => {
    if (hasAttemptedAutoSync.current || !profile?.neptun_ics_url) return;

    let cancelled = false;
    const icsUrl = profile?.neptun_ics_url;

    (async () => {
      try {
        const url = await decryptNeptunUrl(icsUrl.trim());
        if (cancelled || !url) return;

        // Profile is the Single Source of Truth for sync state — the synced
        // calendar lives in the DB, so there's nothing device-local to check.
        const lastSyncMs = profile.neptun_last_sync ? new Date(profile.neptun_last_sync).getTime() : null;
        const twentyFourHours = 24 * ONE_HOUR_MS;

        // Sync when never synced yet, or when the last sync is older than 24h.
        if (!lastSyncMs || Date.now() - lastSyncMs > twentyFourHours) {
          hasAttemptedAutoSync.current = true;
          runSync(url, true);
        }
      } catch (e) {
        console.warn('Auto-sync check failed:', e);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [profile, runSync]);

  // Warm the common route chunks once the browser is idle — the authenticated
  // shell only, so login/public pages never pay for it.
  useEffect(() => {
    scheduleCommonRoutePrefetch();
  }, []);

  return (
    <NavbarActionProvider>
      <div className="min-h-screen flex flex-col">
        {/* mobileOffset lifts toasts above the floating bottom nav pill on mobile
            (the pill sits ~24–84px from the bottom edge); desktop keeps the plain
            bottom-right corner. */}
        <Toaster
          position="bottom-right"
          theme={effective}
          mobileOffset={{ bottom: 'calc(100px + env(safe-area-inset-bottom, 0px))' }}
          toastOptions={{ style: { background: 'var(--surface-secondary)', border: '1px solid var(--border-subtle)', color: 'var(--text-primary)', fontFamily: 'var(--font-sans)', fontSize: '12px' } }}
        />
        <BackgroundGlow />

        {/* Unified Responsive Top & Mobile Navbar */}
        <Navbar currentPath={navPath} />

        <div className="relative flex-1 flex flex-col min-h-screen min-w-0 max-w-full">
          {/* Main Content */}
          <main className="flex-1 min-h-screen ml-0 min-w-0 max-w-full overflow-hidden">
            {/* Enter-only page fade: the whole page rises in as one layer
                (opacity + a `transform` string, so it runs on the compositor),
                and its cards stagger in through `Reveal`. No exit crossfade —
                the window scrolls, so two pages would overlap. The keyed
                wrapper remounts per route so `ScrollToTopOnMount` runs. */}
            <motion.div
              key={routeKey}
              initial={
                navigated && !skipPageEnter
                  ? reduceMotion
                    ? { opacity: 0 }
                    : { opacity: 0, transform: 'translateY(8px)' }
                  : false
              }
              animate={
                reduceMotion
                  ? { opacity: 1 }
                  : { opacity: 1, transform: 'translateY(0px)', transitionEnd: { transform: 'none' } }
              }
              transition={{ duration: 0.3, ease: EASE_DRAWER }}
              className="max-w-7xl mx-auto px-3 sm:px-6 lg:px-8 safe-area-content-top pb-24 xl:pb-6 min-w-0 max-w-full"
            >
              <ScrollToTopOnMount />
              {children}
            </motion.div>
          </main>
        </div>
      </div>
    </NavbarActionProvider>
  );
}
