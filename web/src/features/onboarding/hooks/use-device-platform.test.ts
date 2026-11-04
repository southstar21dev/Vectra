import { describe, it, expect } from 'vitest';
import { detectDevicePlatform, isInAppBrowserUa } from './use-device-platform';

describe('detectDevicePlatform', () => {
  const DESKTOP_UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36';
  const IPHONE_UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148';
  const ANDROID_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Mobile Safari/537.36';

  it('identifies desktop browser correctly and does not gate', () => {
    const res = detectDevicePlatform({
      userAgent: DESKTOP_UA,
      innerWidth: 1440,
      isStandalone: false,
    });

    expect(res.isDesktop).toBe(true);
    expect(res.isMobileWebGateActive).toBe(false);
    expect(res.isStandalonePwa).toBe(false);
  });

  it('identifies mobile Safari web browser as active gate', () => {
    const res = detectDevicePlatform({
      userAgent: IPHONE_UA,
      innerWidth: 390,
      isStandalone: false,
    });

    expect(res.isDesktop).toBe(false);
    expect(res.isStandalonePwa).toBe(false);
    expect(res.isMobileWebGateActive).toBe(true);
  });

  it('identifies mobile Chrome web browser as active gate', () => {
    const res = detectDevicePlatform({
      userAgent: ANDROID_UA,
      innerWidth: 412,
      isStandalone: false,
    });

    expect(res.isDesktop).toBe(false);
    expect(res.isStandalonePwa).toBe(false);
    expect(res.isMobileWebGateActive).toBe(true);
  });

  it('deactivates gate when running as standalone PWA on mobile', () => {
    const res = detectDevicePlatform({
      userAgent: IPHONE_UA,
      innerWidth: 390,
      isStandalone: true,
    });

    expect(res.isStandalonePwa).toBe(true);
    expect(res.isMobileWebGateActive).toBe(false);
  });

  it('recognizes fullscreen display-mode as standalone PWA when launched from home screen', () => {
    const originalWindow = (globalThis as unknown as { window?: unknown }).window;
    (globalThis as unknown as { window?: unknown }).window = {
      matchMedia: (query: string) => ({
        matches: query === '(display-mode: fullscreen)',
        media: query,
        onchange: null,
        addListener: () => {},
        removeListener: () => {},
        addEventListener: () => {},
        removeEventListener: () => {},
        dispatchEvent: () => false,
      }),
      innerWidth: 412,
    };

    try {
      const res = detectDevicePlatform({
        userAgent: ANDROID_UA,
        innerWidth: 412,
      });

      expect(res.isStandalonePwa).toBe(true);
      expect(res.isMobileWebGateActive).toBe(false);
    } finally {
      (globalThis as unknown as { window?: unknown }).window = originalWindow;
    }
  });

  it('lets social in-app browsers through the install gate', () => {
    const uas = [
      `${IPHONE_UA} Instagram 300.0.0.0`,
      `${ANDROID_UA} [FBAN/EMA;FBLC/en_US;FBAV/400.0.0]`,
      `${IPHONE_UA} Line/13.0.0`,
      `${ANDROID_UA} musical_ly_2023 BytedanceWebview/d8a21c6`,
    ];
    for (const userAgent of uas) {
      const res = detectDevicePlatform({ userAgent, innerWidth: 390, isStandalone: false });
      expect(res.isInAppBrowser).toBe(true);
      expect(res.isMobileWebGateActive).toBe(false);
    }
    expect(isInAppBrowserUa(IPHONE_UA)).toBe(false);
    expect(isInAppBrowserUa(ANDROID_UA)).toBe(false);
  });
});
