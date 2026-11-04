import { useState, useEffect } from 'react';

export interface DevicePlatformState {
  isDesktop: boolean;
  isStandalonePwa: boolean;
  isMobileWebGateActive: boolean;
  /** Social-app webviews (Instagram, Facebook, LINE, TikTok) can't install a
   *  PWA, so the install gate would be a dead end there — they pass through. */
  isInAppBrowser: boolean;
}

const IN_APP_BROWSER_UA = /Instagram|FBAN|FBAV|FB_IAB|Line\/|TikTok|musical_ly|BytedanceWebview/;

export function isInAppBrowserUa(ua: string): boolean {
  return IN_APP_BROWSER_UA.test(ua);
}

export function isInstalledDisplayMode(): boolean {
  if (typeof window === 'undefined') return false;
  return (
    window.matchMedia?.('(display-mode: standalone)')?.matches === true ||
    window.matchMedia?.('(display-mode: fullscreen)')?.matches === true ||
    window.matchMedia?.('(display-mode: minimal-ui)')?.matches === true ||
    window.matchMedia?.('(display-mode: window-controls-overlay)')?.matches === true ||
    ('standalone' in navigator && (navigator as Navigator & { standalone?: boolean }).standalone === true)
  );
}

export function detectDevicePlatform(options?: {
  userAgent?: string;
  innerWidth?: number;
  isStandalone?: boolean;
}): DevicePlatformState {
  const ua = options?.userAgent ?? (typeof navigator !== 'undefined' ? navigator.userAgent : '');
  const width = options?.innerWidth ?? (typeof window !== 'undefined' ? window.innerWidth : 1280);

  const isMobileUa = /Mobi|Android|iPhone|iPad|iPod/i.test(ua);
  const isDesktop = !isMobileUa || width >= 1024;

  const isStandalone = options?.isStandalone ?? isInstalledDisplayMode();

  const isInAppBrowser = isInAppBrowserUa(ua);

  const isMobileWebGateActive = !isDesktop && !isStandalone && !isInAppBrowser;

  return {
    isDesktop,
    isStandalonePwa: !!isStandalone,
    isMobileWebGateActive,
    isInAppBrowser,
  };
}

/**
 * Desktop-ness is decided once at mount: re-deriving it from the width on
 * resize meant rotating a tablet below 1024px swapped the running app for the
 * install gate mid-session. Only display-mode changes (the app got installed)
 * update the state, and once the app is showing the gate never comes back.
 */
export function useDevicePlatform(): DevicePlatformState {
  const [state, setState] = useState<DevicePlatformState>(() => detectDevicePlatform());

  useEffect(() => {
    const update = () =>
      setState((prev) => {
        const next = detectDevicePlatform();
        // The mount-time desktop verdict stands; only installing can lift the gate.
        const isMobileWebGateActive = prev.isMobileWebGateActive && !next.isStandalonePwa;
        if (next.isStandalonePwa === prev.isStandalonePwa && isMobileWebGateActive === prev.isMobileWebGateActive) {
          return prev;
        }
        return { ...next, isDesktop: prev.isDesktop, isMobileWebGateActive };
      });

    update();

    const standaloneMq = window.matchMedia?.('(display-mode: standalone)');
    const fullscreenMq = window.matchMedia?.('(display-mode: fullscreen)');
    const minimalUiMq = window.matchMedia?.('(display-mode: minimal-ui)');

    const handleMediaChange = () => update();
    standaloneMq?.addEventListener?.('change', handleMediaChange);
    fullscreenMq?.addEventListener?.('change', handleMediaChange);
    minimalUiMq?.addEventListener?.('change', handleMediaChange);

    return () => {
      standaloneMq?.removeEventListener?.('change', handleMediaChange);
      fullscreenMq?.removeEventListener?.('change', handleMediaChange);
      minimalUiMq?.removeEventListener?.('change', handleMediaChange);
    };
  }, []);

  return state;
}
