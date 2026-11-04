import { useState, useCallback } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query-keys';
import type { RecordModel } from 'pocketbase';
import { pb } from '@/lib/pocketbase';
import { requireSession, getSessionUserId } from '@/lib/auth-helpers';
import type { Json } from '@/lib/pocketbase-mappers';

/** Detect whether the app is running inside an installed iOS PWA (iOS 16.4+). */
function getIosState(): { isIOS: boolean; isStandalone: boolean; version: number | null } | null {
  if (typeof window === 'undefined') return null;
  const ua = navigator.userAgent;
  const isIPadOSDesktop =
    navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1; // iPadOS desktop-mode Safari
  const isIOS = /iPad|iPhone|iPod/.test(ua) || isIPadOSDesktop;
  if (!isIOS) return null;

  // On iPadOS desktop mode the UA reports a macOS version (e.g. 10_15_7), which is
  // meaningless here — only parse the iOS version from real iPhone/iPod/iPad UAs.
  const versionMatch = !isIPadOSDesktop ? ua.match(/OS (\d+)(?:_(\d+))?/) : null;
  const version = versionMatch ? parseFloat(`${versionMatch[1]}.${versionMatch[2] || '0'}`) : null;
  const isStandalone =
    (window.matchMedia && (
      window.matchMedia('(display-mode: standalone)').matches ||
      window.matchMedia('(display-mode: fullscreen)').matches
    )) ||
    (navigator as Navigator & { standalone?: boolean }).standalone === true;

  return { isIOS, isStandalone, version };
}

/** Helper to get the ready service worker with a timeout guard to avoid hanging the UI */
async function getReadyServiceWorkerWithTimeout(timeoutMs = 5000): Promise<ServiceWorkerRegistration> {
  if (typeof window === 'undefined' || !('serviceWorker' in navigator)) {
    throw new Error('Service workers not supported in this browser');
  }
  let registration = await navigator.serviceWorker.getRegistration();
  if (!registration) {
    registration = await navigator.serviceWorker.register('/sw.js');
  }
  const readyPromise = navigator.serviceWorker.ready;
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeoutPromise = new Promise<never>((_, reject) => {
    timer = setTimeout(
      () => reject(new Error(`Service worker readiness timed out after ${timeoutMs / 1000}s`)),
      timeoutMs
    );
  });
  try {
    return await Promise.race([readyPromise, timeoutPromise]);
  } finally {
    clearTimeout(timer);
  }
}

/** Automatically remove duplicate subscription rows from PocketBase */
export async function cleanupDuplicatePushSubscriptions(): Promise<void> {
  try {
    const userId = getSessionUserId();
    if (!userId) return;

    const subs = await pb.collection('push_subscriptions').getFullList({
      filter: pb.filter('user = {:u}', { u: userId }),
      sort: '-created_at',
    });

    if (!subs || subs.length <= 1) return;

    const seenEndpoints = new Set<string>();
    const toDelete: string[] = [];

    for (const sub of subs) {
      const endpoint = (sub.subscription as { endpoint?: string })?.endpoint;
      if (endpoint) {
        if (seenEndpoints.has(endpoint)) {
          toDelete.push(sub.id);
        } else {
          seenEndpoints.add(endpoint);
        }
      }
    }

    if (toDelete.length > 0) {
      await Promise.all(toDelete.map((id) => pb.collection('push_subscriptions').delete(id)));
    }
  } catch (err) {
    console.warn('Subscription deduplication warning:', err);
  }
}

/**
 * Remove this device's push subscription: deletes the matching
 * `push_subscriptions` row (while the session still exists) and unsubscribes
 * the Web Push endpoint. Used by the settings toggle AND on sign-out, so a
 * previous account's pushes can never keep arriving on a shared/second-hand
 * device.
 */
export async function cleanupDevicePushSubscription(): Promise<void> {
  if (typeof window === 'undefined' || !('serviceWorker' in navigator)) return;

  // A worker that isn't active within 3s (e.g. mid-update on sign-out) must not
  // skip the cleanup: the existing registration still owns the subscription.
  let registration: ServiceWorkerRegistration | undefined;
  try {
    registration = await getReadyServiceWorkerWithTimeout(3000);
  } catch (readyErr) {
    console.warn('Service worker not ready, using the existing registration:', readyErr);
    registration = await navigator.serviceWorker.getRegistration().catch(() => undefined);
  }
  if (!registration) return;

  const notifs = await registration.getNotifications().catch(() => []);
  notifs.forEach((n) => n.close());
  const subscription = await registration.pushManager.getSubscription().catch(() => null);
  if (!subscription) return;

  // Server row first (needs the session), then the local endpoint — each step
  // runs even when the other fails.
  const userId = getSessionUserId();
  if (userId) {
    try {
      // PocketBase can't filter into JSON — fetch the user's rows and match in JS.
      const subs = await pb.collection('push_subscriptions').getFullList({
        filter: pb.filter('user = {:u}', { u: userId }),
      });
      const matches = subs.filter((s) => (s.subscription as { endpoint?: string }).endpoint === subscription.endpoint);
      await Promise.all(matches.map((s) => pb.collection('push_subscriptions').delete(s.id)));
    } catch (err) {
      console.warn('PocketBase unsubscribe cleanup warning:', err);
    }
  }
  try {
    await subscription.unsubscribe();
  } catch (swErr) {
    console.warn('Local push unregistration warning:', swErr);
  }
}

/**
 * Unsubscribe this device's Web Push endpoint WITHOUT touching the server.
 * Runs on every sign-out (see auth-store-sync.ts) — including ones where the
 * token is already dead and the `push_subscriptions` row can't be deleted.
 * The dead endpoint makes the push service answer 404/410, and the server
 * prunes the row on its next send.
 */
export async function unsubscribeDevicePushLocally(): Promise<void> {
  if (typeof window === 'undefined' || !('serviceWorker' in navigator)) return;
  // getRegistration, not `ready`: never register or wait on a worker here.
  const registration = await navigator.serviceWorker.getRegistration().catch(() => undefined);
  if (!registration) return;
  const notifs = await registration.getNotifications().catch(() => []);
  notifs.forEach((n) => n.close());
  const subscription = await registration.pushManager.getSubscription().catch(() => null);
  if (!subscription) return;
  try {
    await subscription.unsubscribe();
  } catch (err) {
    console.warn('Local push unregistration warning:', err);
  }
}

/** Cache entry the service worker writes on `pushsubscriptionchange` (sw.ts). */
const PUSH_RESYNC_CACHE = 'vectra-push-state';
const PUSH_RESYNC_KEY = '/__push-resync';

/**
 * Store the endpoint the browser rotated to while the app was closed. The
 * service worker re-subscribes on `pushsubscriptionchange` but has no auth
 * token, so it only leaves a marker (with the old endpoint); the next open
 * with a session swaps the old row for the new endpoint here.
 */
export async function resyncRotatedPushSubscription(): Promise<void> {
  if (typeof window === 'undefined' || !('serviceWorker' in navigator) || !('caches' in window)) return;
  const userId = getSessionUserId();
  if (!userId) return;
  try {
    const cache = await caches.open(PUSH_RESYNC_CACHE);
    const marker = await cache.match(PUSH_RESYNC_KEY);
    if (!marker) return;
    const { oldEndpoint } = (await marker.json().catch(() => ({}))) as { oldEndpoint?: string };

    const registration = await navigator.serviceWorker.getRegistration();
    const subscription = await registration?.pushManager.getSubscription();
    if (subscription) {
      const subs = await pb.collection('push_subscriptions').getFullList({
        filter: pb.filter('user = {:u}', { u: userId }),
      });
      const endpointOf = (s: RecordModel) => (s.subscription as { endpoint?: string }).endpoint;
      const stale = subs.filter((s) => oldEndpoint && endpointOf(s) === oldEndpoint);
      // Only adopt the rotated endpoint when this user had push on for it —
      // never opt a device in that the user didn't.
      if (stale.length > 0 && !subs.some((s) => endpointOf(s) === subscription.endpoint)) {
        await pb.collection('push_subscriptions').create({
          user: userId,
          subscription: subscription.toJSON() as unknown as Json,
          transport: 'webpush',
          device_name: navigator.userAgent.includes('Mobile') ? 'Mobile' : 'Desktop',
        });
      }
      await Promise.all(stale.map((s) => pb.collection('push_subscriptions').delete(s.id)));
    }
    await cache.delete(PUSH_RESYNC_KEY);
  } catch (err) {
    console.warn('Push subscription resync warning:', err);
  }
}

export function usePushSubscription() {
  const [isSubscribed, setIsSubscribed] = useState(false);
  const isSecure = typeof window !== 'undefined' ? window.isSecureContext : true;
  const iosState = getIosState();
  const isIOS = !!iosState;
  const isIOSStandalone = iosState?.isStandalone ?? false;
  // iPadOS desktop mode has no parseable version — assume the latest supported iOS.
  const isIOSVersionSupported = iosState ? (iosState.version ?? 999) >= 16.4 : true;

  // Web Push on iOS only works inside an installed PWA on iOS 16.4+. In Safari
  // tabs, PushManager doesn't exist at all, so feature-detection is not enough.
  const isSupported =
    typeof window !== 'undefined' &&
    'serviceWorker' in navigator &&
    'PushManager' in window &&
    'Notification' in window &&
    isSecure &&
    (!isIOS || (isIOSStandalone && isIOSVersionSupported));
  const queryClient = useQueryClient();

  const subscribeMutation = useMutation({
    mutationFn: async () => {
      if (!isSupported) {
        throw new Error(
          isIOS && !isIOSStandalone
            ? 'On iOS, push notifications only work after installing the app: tap Share → Add to Home Screen, then open Vectra from your Home Screen.'
            : isIOS && !isIOSVersionSupported
            ? 'Push notifications on iOS require iOS 16.4 or later.'
            : 'Push notifications not supported in this browser'
        );
      }

      // 1. Request permission
      if (typeof window !== 'undefined' && 'Notification' in window) {
        if (Notification.permission === 'denied') {
          throw new Error('Notifications are blocked by your browser. Tap the lock/tune icon in your address bar to Allow Notifications.');
        }
        const permission = await Notification.requestPermission();
        if (permission !== 'granted') {
          throw new Error('Notification permission was denied. Please allow notifications in site settings.');
        }
      }

      // 2. Ensure Service Worker registration is ready
      const registration = await getReadyServiceWorkerWithTimeout(5000);

      // 3. VAPID Public Key check
      const vapidPublicKey = import.meta.env.VITE_VAPID_PUBLIC_KEY;
      if (!vapidPublicKey) {
        throw new Error('VAPID public key not configured in .env.local');
      }

      // 4. Subscribe to PushManager (auto-recovering if key mismatch / stale subscription exists)
      let subscription = await registration.pushManager.getSubscription();
      // A subscription made under a different VAPID key is rejected (403) by
      // the push service on every send, so drop it and subscribe afresh.
      if (subscription && !subscriptionMatchesKey(subscription, vapidPublicKey)) {
        await subscription.unsubscribe().catch(() => {});
        subscription = null;
      }
      try {
        if (!subscription) {
          subscription = await registration.pushManager.subscribe({
            userVisibleOnly: true,
            applicationServerKey: urlBase64ToUint8Array(vapidPublicKey) as BufferSource,
          });
        }
      } catch {
        // If stale subscription had a different key, unsubscribe and re-subscribe cleanly
        if (subscription) {
          await subscription.unsubscribe().catch(() => {});
        }
        subscription = await registration.pushManager.subscribe({
          userVisibleOnly: true,
          applicationServerKey: urlBase64ToUint8Array(vapidPublicKey) as BufferSource,
        });
      }

      if (!subscription) {
        throw new Error('Failed to create push subscription on this device');
      }

      // 5. Store in PocketBase — clean any previous row for the SAME endpoint on this device to refresh keys
      const userId = requireSession().record!.id;

      const endpoint = subscription.endpoint;
      try {
        // PocketBase can't filter into JSON — fetch the user's rows and match in JS.
        const subs = await pb.collection('push_subscriptions').getFullList({
          filter: pb.filter('user = {:u}', { u: userId }),
        });
        const matches = subs.filter((s) => (s.subscription as { endpoint?: string }).endpoint === endpoint);
        await Promise.all(matches.map((s) => pb.collection('push_subscriptions').delete(s.id)));
      } catch (delErr) {
        console.warn('Previous endpoint cleanup warning:', delErr);
      }

      await pb.collection('push_subscriptions').create({
        user: userId,
        subscription: subscription.toJSON() as unknown as Json,
        transport: 'webpush',
        device_name: navigator.userAgent.includes('Mobile') ? 'Mobile' : 'Desktop',
      });

      setIsSubscribed(true);
      return subscription;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.pushSubscriptions });
    },
  });

  const unsubscribeMutation = useMutation({
    mutationFn: async () => {
      if (!isSupported) return;
      await cleanupDevicePushSubscription();
      setIsSubscribed(false);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.pushSubscriptions });
    },
  });

  const checkSubscription = useCallback(async () => {
    void cleanupDuplicatePushSubscriptions();
    if (!isSupported) {
      setIsSubscribed(false);
      return;
    }
    try {
      const registration = await navigator.serviceWorker.getRegistration();
      if (!registration) {
        setIsSubscribed(false);
        return;
      }
      const subscription = await registration.pushManager.getSubscription();
      const vapidKey = import.meta.env.VITE_VAPID_PUBLIC_KEY;
      if (!subscription || (vapidKey && !subscriptionMatchesKey(subscription, vapidKey))) {
        setIsSubscribed(false);
        return;
      }

      // Verify active subscription row exists in PocketBase for THIS specific device endpoint
      const userId = getSessionUserId();
      if (userId) {
        // PocketBase can't filter into JSON — match the endpoint in JS.
        const subs = await pb.collection('push_subscriptions').getFullList({
          filter: pb.filter('user = {:u}', { u: userId }),
        });
        setIsSubscribed(
          subs.some((s) => (s.subscription as { endpoint?: string }).endpoint === subscription.endpoint)
        );
      } else {
        setIsSubscribed(true);
      }
    } catch {
      setIsSubscribed(false);
    }
  }, [isSupported]);

  return {
    isSupported,
    isSecure,
    isIOS,
    isIOSStandalone,
    isIOSVersionSupported,
    iosVersion: iosState?.version ?? null,
    isSubscribed,
    subscribe: subscribeMutation.mutateAsync,
    unsubscribe: unsubscribeMutation.mutateAsync,
    checkSubscription,
    isSubscribing: subscribeMutation.isPending,
    subscribeError: subscribeMutation.error,
  };
}

/** True when the subscription was created with the given VAPID public key. */
function subscriptionMatchesKey(subscription: PushSubscription, vapidPublicKey: string): boolean {
  const current = subscription.options?.applicationServerKey;
  if (!current) return true; // unknown — can't tell, keep it
  const a = new Uint8Array(current);
  const b = urlBase64ToUint8Array(vapidPublicKey);
  return a.length === b.length && a.every((v, i) => v === b[i]);
}

export function urlBase64ToUint8Array(base64String: string): Uint8Array {
  const cleanBase64 = base64String.trim();
  const padding = '='.repeat((4 - (cleanBase64.length % 4)) % 4);
  const base64 = (cleanBase64 + padding).replace(/-/g, '+').replace(/_/g, '/');
  const decode = typeof window !== 'undefined' ? window.atob.bind(window) : globalThis.atob;
  const rawData = decode(base64);
  const outputArray = new Uint8Array(rawData.length);
  for (let i = 0; i < rawData.length; ++i) {
    outputArray[i] = rawData.charCodeAt(i);
  }
  return outputArray;
}
