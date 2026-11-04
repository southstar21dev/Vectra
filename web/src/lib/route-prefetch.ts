/**
 * Chunk loaders for the app's routes. Shared by the lazy() route definitions and
 * the prefetchers so both issue the same module requests — the bundler's module
 * cache means a prefetched chunk resolves instantly when the route is visited.
 *
 * On the installed PWA/APK the chunks are service-worker precached, so warming a
 * route isn't a network fetch: it's JS parse + evaluate. Doing that ahead of the
 * tap (on hover / pointer-down, and on idle) keeps the parse off the navigation.
 */
export const loadDashboardRoute = () => import('@/features/dashboard/dashboard-page');
export const loadScheduleRoute = () => import('@/features/schedule/schedule-page');
export const loadTransitRoute = () => import('@/features/transit/transit-page');
export const loadSettingsRoute = () => import('@/features/settings/settings-page');

/** The map engine — big, and only needed once a map is shown. */
export const loadMaplibre = () => import('@/lib/maplibre');

/** The weather radar dialog — statically pulls maplibre; warmed on the dashboard. */
export const loadWeatherRadar = () => import('@/features/weather/weather-radar-dialog');

const COMMON_ROUTE_LOADERS = [loadScheduleRoute, loadTransitRoute, loadSettingsRoute, loadMaplibre, loadWeatherRadar];

let scheduled = false;

/**
 * Warm the route chunk for a pathname. Called on nav-link intent
 * (pointerenter / pointerdown) so the module is parsed/evaluated before the tap
 * resolves. Idempotent — the module cache makes repeat calls free.
 */
export function prefetchRouteForPath(pathname: string): void {
  let loader: (() => Promise<unknown>) | undefined;
  if (pathname === '/') loader = loadDashboardRoute;
  else if (pathname.startsWith('/schedule')) loader = loadScheduleRoute;
  else if (pathname.startsWith('/transit')) loader = loadTransitRoute;
  else if (pathname.startsWith('/settings')) loader = loadSettingsRoute;
  if (loader) void loader().catch(() => {});
}

/**
 * Best-effort warming of the common route chunks once the browser is idle, so
 * the first navigation to them doesn't wait on a fetch/parse. Skipped on
 * save-data / 2G connections; failures are swallowed.
 */
export function scheduleCommonRoutePrefetch(): void {
  if (scheduled) return;
  scheduled = true;

  const connection = (navigator as Navigator & {
    connection?: { saveData?: boolean; effectiveType?: string };
  }).connection;
  if (connection?.saveData) return;
  if (/2g/.test(connection?.effectiveType ?? '')) return;

  const prefetch = () => {
    for (const load of COMMON_ROUTE_LOADERS) {
      void load().catch(() => {});
    }
  };

  if (typeof window.requestIdleCallback === 'function') {
    window.requestIdleCallback(prefetch, { timeout: 3000 });
  } else {
    window.setTimeout(prefetch, 2000);
  }
}
