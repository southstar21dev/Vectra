/**
 * Push Manager Service
 * Handles service worker registration and system push notification management
 */

import { pb } from '@/lib/pocketbase';
import { requireSession } from '@/lib/auth-helpers';
import { cleanupDuplicatePushSubscriptions } from './use-push-subscription';

export async function registerServiceWorker(): Promise<ServiceWorkerRegistration | null> {
  if (!('serviceWorker' in navigator)) {
    console.warn('Service workers not supported');
    return null;
  }

  try {
    const registration = await navigator.serviceWorker.ready;
    console.log('Service worker ready:', registration.scope);
    return registration;
  } catch (error) {
    console.error('Service worker ready check failed:', error);
    return null;
  }
}

/**
 * Async helper to retrieve the accurate notification permission.
 */
export async function getEffectiveNotificationPermission(): Promise<NotificationPermission> {
  if (!('Notification' in window)) return 'denied';
  return Notification.permission;
}

/**
 * Synchronous best-effort permission check.
 */
export function getNotificationPermission(): NotificationPermission {
  if (!('Notification' in window)) return 'denied';
  return Notification.permission;
}

export async function sendSystemNotification(
  title: string,
  body: string,
  options?: NotificationOptions
): Promise<boolean> {
  if (!('Notification' in window)) {
    console.warn('System notifications not supported in this browser');
    return false;
  }

  let permission = Notification.permission;
  if (permission === 'default') {
    permission = await Notification.requestPermission();
  }

  if (permission !== 'granted') {
    throw new Error(
      permission === 'denied'
        ? 'Notifications are blocked in site settings. Tap the tune/lock icon in your address bar to Allow Notifications.'
        : 'Notification permission was not granted.'
    );
  }

  // Icon is platform-conditional: the `icon` slot renders as the large icon
  // on the RIGHT of Android notifications (an extra icon the user doesn't
  // want), but as the app icon on the LEFT of desktop toasts (which they do
  // want). So Android gets a fully transparent placeholder (deterministic),
  // desktop gets the app owl. Matches the service worker's push handler.
  const isAndroid = typeof navigator !== 'undefined' && /android/i.test(navigator.userAgent);
  const notificationOptions: NotificationOptions & { vibrate?: number[] } = {
    body,
    icon: isAndroid ? '/icons/transparent.png' : '/icons/icon-192.png',
    badge: '/icons/badge-96.png',
    vibrate: [100, 50, 100],
    data: { url: '/' },
    ...options,
  };

  // Primary for Mobile PWA (Android Chrome / iOS PWA)
  if ('serviceWorker' in navigator) {
    try {
      const registration = await navigator.serviceWorker.ready;
      if (registration && 'showNotification' in registration) {
        await registration.showNotification(title, notificationOptions);
        return true;
      }
    } catch (swErr) {
      console.warn('SW showNotification failed, attempting fallback:', swErr);
    }
  }

  // Desktop Browser Fallback
  try {
    new Notification(title, notificationOptions);
    return true;
  } catch (err) {
    console.error('Desktop Notification fallback failed:', err);
  }

  return false;
}

/**
 * Send a real end-to-end server push notification through the PocketBase
 * `POST /api/vectra/push/send` route. Verifies Auth -> route -> Web Push ->
 * Device pipeline.
 */
export async function sendServerTestNotification(): Promise<{ success: boolean; sentCount: number }> {
  requireSession();

  // Purge any duplicate subscription rows for this user before test push
  await cleanupDuplicatePushSubscriptions();

  const data = (await pb.send('/api/vectra/push/send', {
    method: 'POST',
    body: {
      user_id: pb.authStore.record!.id,
      title: 'Vectra Push Alert 🚀',
      body: 'Your push notification pipeline is active and working properly!',
      tag: 'vectra-test-alert',
      url: '/settings',
    },
  })) as {
    sent?: number;
    total?: number;
    message?: string;
    results?: { value?: { success?: boolean; error?: string; statusCode?: number; reason?: string; detail?: string; endpoint?: string } }[];
  };

  if (typeof data?.sent !== 'number' || data.sent === 0) {
    const failures = (data?.results ?? [])
      .map((r) => r.value)
      .filter((v) => v && v.success === false)
      .map((v) => v!.reason ?? [v!.error, v!.statusCode, v!.detail, v!.endpoint && `(${new URL(v!.endpoint).host})`].filter(Boolean).join(' '))
      .filter(Boolean);
    throw new Error(
      failures.length > 0
        ? `Push delivery failed: ${[...new Set(failures)].join('; ')}`
        : (data?.message ??
            'No registered devices received the push. Make sure Push Notifications are toggled ON below.')
    );
  }

  return { success: true, sentCount: data?.sent ?? 1 };
}
