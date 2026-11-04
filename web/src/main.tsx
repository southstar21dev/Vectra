import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '@fontsource-variable/inter';
import '@fontsource-variable/jetbrains-mono';
import '@/styles/globals.css';
import App from './app';
import { estimateScreenCornerRadius } from '@/lib/ios-edge-swipe';
import { queryClient } from '@/lib/query-client';
import { installAuthStoreSync } from '@/features/auth/auth-store-sync';

import { registerSW } from 'virtual:pwa-register';

// One app-wide auth-store listener (cache invalidation + sign-out cleanup),
// installed before the first render so even a sign-out during boot (the
// session watchdog expiring a dead token) runs it.
installAuthStoreSync(queryClient);

// Register PWA Service Worker via VitePWA
registerSW({
  immediate: true,
  onNeedRefresh() {
    console.log('New content available, refresh to update Vectra.');
  },
  onOfflineReady() {
    console.log('Vectra PWA ready for offline use.');
  },
});

// Lock the PWA to portrait on mobile. The web app manifest already declares
// "orientation": "portrait-primary" (honored by Chrome for installed PWAs);
// the Screen Orientation API lock() below covers cases where the manifest is
// not applied (browser tab) — it requires a user gesture, so
// we attempt it on the first tap/click and silently ignore rejection (the
// API needs fullscreen/installed-standalone in some browsers).
const lockPortrait = () => {
  try {
    const so = screen.orientation as ScreenOrientation | undefined;
    so?.lock?.('portrait')?.catch(() => {});
  } catch {
    // Unsupported browser — manifest orientation is the fallback.
  }
  window.removeEventListener('pointerdown', lockPortrait);
};
window.addEventListener('pointerdown', lockPortrait);

// iOS home-screen apps: match the panel corners to the physical display
// radius. Unknown/desktop sizes resolve to 0 and the CSS fallback keeps the
// full-screen panel square.
if (
  typeof navigator !== 'undefined' &&
  (navigator as Navigator & { standalone?: boolean }).standalone === true
) {
  const screenRadius = estimateScreenCornerRadius(window.screen.width, window.screen.height);
  if (screenRadius > 0) {
    document.documentElement.style.setProperty('--screen-radius', `${screenRadius}px`);
  }
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>
);
