import { useState, useEffect, useCallback } from 'react';
import { RiNotification3Line, RiNotificationOffLine, RiLoader4Line, RiSendPlaneLine, RiBroadcastLine, RiSmartphoneLine, RiCheckboxCircleLine, RiShieldCheckLine, RiMacbookLine, RiQuestionLine } from '@remixicon/react';
import { usePushSubscription } from './use-push-subscription';
import { SettingsRow } from '@/features/settings/settings-section';
import { Button } from '@/components/ui/button';
import { Toggle } from '@/components/ui/toggle';
import { sendServerTestNotification, sendSystemNotification, getEffectiveNotificationPermission } from './push-manager';
import { getErrorMessage } from '@/lib/error-utils';

/** Reads the effective permission, degrading to 'default' on any failure. */
async function readEffectivePermission(): Promise<NotificationPermission> {
  try {
    return await getEffectiveNotificationPermission();
  } catch {
    return 'default';
  }
}

/** The Notification API itself is missing (some in-app browsers, older iOS
 *  Safari tabs) — distinct from the user having denied permission. */
function hasNotificationApi(): boolean {
  return typeof window !== 'undefined' && 'Notification' in window;
}

export function NotificationSettings() {
  const {
    isSupported,
    isSecure,
    isIOS,
    isIOSStandalone,
    isIOSVersionSupported,
    iosVersion,
    isSubscribed,
    subscribe,
    unsubscribe,
    checkSubscription,
    isSubscribing,
  } = usePushSubscription();

  const isMac = typeof navigator !== 'undefined' && /macintosh|mac os x/i.test(navigator.userAgent);

  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const [testSending, setTestSending] = useState(false);
  const [testSent, setTestSent] = useState(false);
  const [showMacTips, setShowMacTips] = useState(false);
  const [permission, setPermission] = useState<NotificationPermission>('default');
  const notificationApi = hasNotificationApi();

  const refreshPermission = useCallback(() => {
    void readEffectivePermission().then(setPermission);
  }, []);

  useEffect(() => {
    checkSubscription();
    void readEffectivePermission().then(setPermission);
  }, [checkSubscription]);

  const handleToggle = async (checked: boolean) => {
    setErrorMessage(null);
    setSuccessMessage(null);
    try {
      if (!checked && isSubscribed) {
        await unsubscribe();
        setSuccessMessage('Push notifications disabled');
      } else if (checked && !isSubscribed) {
        await subscribe();
        setSuccessMessage('Push notifications enabled and synced');
      }
      void refreshPermission();
    } catch (err) {
      setErrorMessage(getErrorMessage(err, 'Failed to toggle push notifications'));
      void refreshPermission();
    }
  };

  const handleSendTestNotification = async () => {
    setErrorMessage(null);
    setSuccessMessage(null);
    setTestSending(true);
    setTestSent(false);

    try {
      if (isSubscribed) {
        // Real End-to-End server push via the PocketBase backend
        const result = await sendServerTestNotification();
        setTestSent(true);
        setSuccessMessage(`Server test push delivered to ${result.sentCount} device(s)!`);
        setTimeout(() => setTestSent(false), 4000);
      } else {
        // Fallback local test notification
        const shown = await sendSystemNotification(
          'Vectra Local Alert 🚀',
          'Local notifications are active. Enable Push Notifications below for background alerts.'
        );
        if (!shown) {
          setErrorMessage(
            notificationApi
              ? 'The browser could not display the notification. Check your system notification settings.'
              : 'Notifications are not supported in this browser.'
          );
          void refreshPermission();
          return;
        }
        setTestSent(true);
        setSuccessMessage('Local notification displayed. Enable push below to receive background alerts.');
        setTimeout(() => setTestSent(false), 4000);
      }
      void refreshPermission();
    } catch (err) {
      setErrorMessage(getErrorMessage(err, 'Failed to send test notification'));
      void refreshPermission();
    } finally {
      setTestSending(false);
    }
  };

  return (
    <>
        {errorMessage && (
          <div className="mx-4 my-3 p-3.5 rounded-2xl bg-red-500/10 border border-tint-red text-tint-red text-xs flex items-center gap-2 shadow-xs">
            <span>⚠️</span>
            <span>{errorMessage}</span>
          </div>
        )}

        {successMessage && (
          <div className="mx-4 my-3 p-3.5 rounded-2xl bg-emerald-500/10 border border-emerald-500/25 text-tint-emerald text-xs flex items-center gap-2 shadow-xs">
            <RiCheckboxCircleLine className="w-4 h-4 shrink-0" />
            <span>{successMessage}</span>
          </div>
        )}

        {isIOS && !isIOSStandalone && (
          <div className="mx-4 my-3 p-4 rounded-2xl bg-tint-blue border-tint-blue text-tint-blue text-xs space-y-2 shadow-xs">
            <p className="font-bold flex items-center gap-2">
              <RiSmartphoneLine className="w-3.5 h-3.5 text-tint-blue" />
              Enable Push on iPhone / iPad
            </p>
            <ol className="list-decimal pl-4 text-[11px] leading-relaxed text-tint-blue/90 space-y-1">
              <li>Tap the <strong className="text-tint-blue">Share</strong> button in Safari</li>
              <li>Tap <strong className="text-tint-blue">Add to Home Screen</strong></li>
              <li>Open Vectra from your Home Screen</li>
              <li>Return here and toggle push notifications on</li>
            </ol>
            <p className="text-[10px] text-tint-blue/70 pt-1 border-t border-blue-500/20">
              Apple only allows push notifications for installed web apps (iOS 16.4+).
            </p>
          </div>
        )}

        {/* Push Notifications Row */}
        <SettingsRow
          icon={isSubscribed ? RiNotification3Line : RiNotificationOffLine}
          iconClass={isSubscribed ? 'bg-tint-emerald text-tint-emerald' : 'bg-surface-overlay text-muted'}
          title="Push notifications"
          subtitle={
            !isSecure
            ? 'Push notifications require HTTPS (Enable SSL on Coolify)'
            : isIOS && !isIOSStandalone
            ? 'Install the app first: Share → Add to Home Screen, then open Vectra from your Home Screen to enable push.'
            : isIOS && !isIOSVersionSupported
            ? `Push on iOS requires iOS 16.4 or later (you're on ${iosVersion}).`
            : !isSupported
            ? 'Not supported in this browser'
            : isSubscribed
            ? 'Active — Desktop & mobile push alerts enabled'
            : 'Enable push alerts for your Vectra notifications'
          }
          control={
            <Toggle
              checked={isSubscribed}
              onChange={handleToggle}
              disabled={!isSupported || isSubscribing}
              loading={isSubscribing}
              aria-label="Toggle push notifications"
            />
          }
        />

        {/* Notification Testing Suite */}
        <SettingsRow
          icon={RiBroadcastLine}
          iconClass="bg-tint-blue text-tint-blue"
          title={isSubscribed ? 'Test server push' : 'Test local notification'}
          subtitle={isSubscribed ? 'Sends a real push through the server' : 'Checks browser permission before enabling push'}
          control={
              <Button
                onClick={handleSendTestNotification}
                disabled={testSending || !notificationApi}
                variant="outline"
                size="sm"
                className="shrink-0 gap-1.5 text-xs font-bold rounded-full"
              >
                {testSending ? (
                  <RiLoader4Line className="w-3.5 h-3.5 animate-spin" />
                ) : testSent ? (
                  <>
                    <RiCheckboxCircleLine className="w-4 h-4 text-tint-emerald" />
                    <span>Sent!</span>
                  </>
                ) : (
                  <>
                    <RiSendPlaneLine className="w-3.5 h-3.5" />
                    <span>Test alert</span>
                  </>
                )}
              </Button>
          }
        />

        {/* System Diagnostics & Mac Tips */}
        <div className="px-4 py-3 space-y-2">
          <div className="flex items-center justify-between text-xs text-secondary px-1">
            <span className="flex items-center gap-1.5">
              <RiShieldCheckLine className="w-3.5 h-3.5 text-tint-emerald" />
              Permission: <strong className="text-primary capitalize">{notificationApi ? permission : 'unsupported'}</strong>
            </span>
            <button
              onClick={() => setShowMacTips(!showMacTips)}
              className="text-xs text-tint-blue hover:underline flex items-center gap-1 cursor-pointer"
            >
              <RiQuestionLine className="w-3.5 h-3.5" />
              <span>Troubleshooting</span>
            </button>
          </div>

          {showMacTips && (
            <div className="p-3 rounded-xl bg-surface-overlay border border-subtle text-xs text-secondary space-y-2 mt-2">
              {isMac ? (
                <div className="space-y-1.5">
                  <p className="font-bold text-primary flex items-center gap-1.5">
                    <RiMacbookLine className="w-3.5 h-3.5 text-tint-blue" />
                    macOS Notification Checklist
                  </p>
                  <ul className="list-disc pl-4 text-[11px] space-y-1">
                    <li>Open <strong>System Settings → Notifications → Google Chrome / Safari</strong> and ensure notifications are Allowed.</li>
                    <li>Check that <strong>Focus Mode / Do Not Disturb</strong> is not silencing alert banners.</li>
                    <li>Ensure alerts are set to <strong>Banners</strong> or <strong>Alerts</strong> in macOS settings.</li>
                  </ul>
                </div>
              ) : (
                <div className="space-y-1.5">
                  <p className="font-bold text-primary">Notification Troubleshooting</p>
                  <ul className="list-disc pl-4 text-[11px] space-y-1">
                    <li>Check browser site settings by clicking the lock/tune icon in the URL bar.</li>
                    <li>Ensure Do Not Disturb / Battery Saver is not suppressing notifications.</li>
                    <li>If notifications stop arriving, toggle Push Notifications off and on above to re-sync.</li>
                  </ul>
                </div>
              )}
            </div>
          )}
        </div>
    </>
  );
}
