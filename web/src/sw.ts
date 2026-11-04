/// <reference lib="webworker" />
import { clientsClaim } from 'workbox-core';
import { precacheAndRoute, createHandlerBoundToURL } from 'workbox-precaching';
import { registerRoute, NavigationRoute } from 'workbox-routing';
import { NetworkFirst, StaleWhileRevalidate } from 'workbox-strategies';
import { ExpirationPlugin } from 'workbox-expiration';

declare const self: ServiceWorkerGlobalScope;

self.skipWaiting();
clientsClaim();

// Precache static assets built by Vite
precacheAndRoute(self.__WB_MANIFEST || []);

// Runtime cache for dynamically split feature chunks (the catalog JSON has its
// own network-first cache below).
// Bump this when a lazy feature's UI changes so an installed PWA cannot keep
// rendering an older nearby-explorer chunk after the main app updates.
const LAZY_CHUNK_CACHE = 'vectra-lazy-chunks-v7';
const CATALOG_CACHE = 'vectra-catalog-v1';

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(
        keys
          .filter(
            (key) =>
              (key.startsWith('vectra-lazy-chunks-') && key !== LAZY_CHUNK_CACHE) ||
              (key.startsWith('vectra-catalog-') && key !== CATALOG_CACHE)
          )
          .map((key) => caches.delete(key))
      )
    )
  );
});

registerRoute(
  ({ request, url }) => request.destination === 'script' && url.pathname.includes('/assets/'),
  new StaleWhileRevalidate({
    cacheName: LAZY_CHUNK_CACHE,
    plugins: [
      new ExpirationPlugin({
        maxEntries: 50,
        maxAgeSeconds: 30 * 24 * 60 * 60, // 30 days
      }),
    ],
  })
);

// The BKK catalog JSON (public/catalog/*.json) is regenerated daily but is
// deliberately excluded from the precache manifest (see vite.config.ts), so a
// data refresh never forces an app/service-worker update. Network-first with
// an offline fallback: online sessions read the current feed; the last copy
// serves offline.
registerRoute(
  ({ url }) => url.origin === self.location.origin && url.pathname.startsWith('/catalog/'),
  new NetworkFirst({
    cacheName: CATALOG_CACHE,
    networkTimeoutSeconds: 3,
    plugins: [
      new ExpirationPlugin({
        maxEntries: 10,
        maxAgeSeconds: 30 * 24 * 60 * 60, // 30 days
      }),
    ],
  })
);

// Register navigation handler bound to index.html for SPA offline routing
try {
  const handler = createHandlerBoundToURL('/index.html');
  const navigationRoute = new NavigationRoute(handler, {
    denylist: [/^\/api\//],
  });
  registerRoute(navigationRoute);
} catch (err) {
  console.warn('Navigation route binding fallback:', err);
}

// WebKit push platforms (iOS/iPadOS Safari PWA, macOS Safari 16.4+) APPEND a
// new notification on every same-tag showNotification instead of replacing
// (WebKit Bugzilla #258922), and suspend the SW shortly after each push — so
// they get no countdown timer and an explicit close-then-show replacement.
// Chromium on Mac (Chrome/Edge/Brave) and Android keep the standard path.
// UA-only because the SW cannot import app modules.
function isSafariPushPlatform(): boolean {
  const ua = navigator.userAgent;
  const ios = /iphone|ipad|ipod/i.test(ua);
  // iPadOS 13+ spoofs its UA as "Macintosh" — the Mac rule catches it too.
  const macSafari =
    /macintosh|mac os x/i.test(ua) && /safari/i.test(ua) && !/chrome|crios|chromium|edg|fxios/i.test(ua);
  return ios || macSafari;
}

// Handle Push Notifications
self.addEventListener('push', (event: PushEvent) => {
  if (!event.data) return;

  try {
    const payload = event.data.json();
    const { title, body, icon, badge, data, tag } = payload;
    const notifTag = tag || 'vectra-notification';

    // Icon is platform-conditional: the `icon` slot renders as the large icon
    // on the RIGHT of Android notifications (an extra icon the user doesn't
    // want), but as the app icon on the LEFT of desktop toasts (which they do
    // want). So Android gets a fully transparent placeholder (deterministic —
    // omitting it can fall back to the favicon), desktop gets the app owl.
    const isAndroid = typeof navigator !== 'undefined' && /android/i.test(navigator.userAgent);
    const options: NotificationOptions & { vibrate?: number[] } = {
      body: body || 'You have a new notification from Vectra',
      icon: isAndroid ? '/icons/transparent.png' : icon || '/icons/icon-192.png',
      // Monochrome badge (white owl silhouette on transparent) — Android renders this as
      // a silhouette from the alpha channel, so a full-color square would show
      // as a white box. A transparent icon also avoids the white square.
      badge: badge || '/icons/badge-96.png',
      tag: notifTag,
      data: data || { url: '/' },
      vibrate: [100, 50, 100],
    };

    // WebKit push platforms (iOS/iPadOS/macOS Safari): close all existing
    // notifications first, then show the update — tag replacement doesn't
    // work reliably across WebKit instances (Bugzilla #258922), so without this every
    // refresh would stack another notification.
    if (isSafariPushPlatform()) {
      event.waitUntil(
        self.registration
          .getNotifications()
          .then((notifs) => {
            return Promise.all(notifs.map((n) => n.close()));
          })
          .catch(() => {
            // Close is best-effort — notification must still be shown.
          })
          .then(() =>
            self.registration.showNotification(title || 'Vectra', {
              ...options,
            })
          )
      );
      return;
    }

    // Android / Chromium / desktop: direct showNotification replaces same-tag notifications cleanly.
    event.waitUntil(
      self.registration.showNotification(title || 'Vectra', {
        ...options,
      })
    );
  } catch (err) {
    console.error('Error handling push event:', err);
  }
});

// The browser rotated or expired the push subscription while the app may be
// closed. Re-subscribe with the old key so pushes keep a live endpoint, and
// leave a marker with the old endpoint: the worker has no auth token, so the
// app swaps the server row on its next signed-in open
// (resyncRotatedPushSubscription in use-push-subscription.ts).
self.addEventListener('pushsubscriptionchange', (event: PushSubscriptionChangeEvent) => {
  const oldSubscription = event.oldSubscription;
  const applicationServerKey = oldSubscription?.options.applicationServerKey;
  event.waitUntil(
    (async () => {
      // No old key means nothing to carry over (or the subscription was never ours).
      if (!event.newSubscription && !applicationServerKey) return;
      try {
        if (!event.newSubscription && applicationServerKey) {
          await self.registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey });
        }
        const cache = await caches.open('vectra-push-state');
        await cache.put(
          '/__push-resync',
          new Response(JSON.stringify({ oldEndpoint: oldSubscription?.endpoint ?? null }), {
            headers: { 'Content-Type': 'application/json' },
          })
        );
      } catch (err) {
        console.warn('Push resubscribe failed:', err);
      }
    })()
  );
});

// Handle Notification Click
self.addEventListener('notificationclick', (event: NotificationEvent) => {
  event.notification.close();

  const targetUrl = event.notification.data?.url || '/';

  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(async (clientList) => {
      for (const client of clientList) {
        if (client.url.includes(self.location.origin) && 'focus' in client) {
          const windowClient = client as WindowClient;
          try {
            // navigate() rejects for a tab this worker doesn't control yet.
            await windowClient.navigate(targetUrl);
            return await windowClient.focus();
          } catch {
            break;
          }
        }
      }
      if (self.clients.openWindow) {
        return self.clients.openWindow(targetUrl);
      }
    })
  );
});
