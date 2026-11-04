import { effectiveTheme, type AppearancePrefs } from './appearance-prefs';

// Fallbacks only; must equal --surface-primary in globals.css (and the
// first-paint values in index.html / manifest.json).
const THEME_COLOR_DARK = '#07080a';
const THEME_COLOR_LIGHT = '#f8fafc';

/** Apply prefs to the DOM: effective theme + mode + accent + background on
 *  <html>, and the browser-chrome color via the theme-color meta tag. */
export function applyAppearanceToDom(prefs: AppearancePrefs): void {
  if (typeof document === 'undefined') return;
  const systemPrefersDark =
    typeof window !== 'undefined' && window.matchMedia
      ? window.matchMedia('(prefers-color-scheme: dark)').matches
      : false;
  const effective = effectiveTheme(prefs.theme, systemPrefersDark);
  const root = document.documentElement;
  root.dataset.theme = effective;
  root.dataset.themeMode = prefs.theme;
  root.dataset.accent = prefs.accent;
  root.dataset.background = prefs.background;
  // The Android status bar paints theme-color; reading the live page
  // background token makes it blend seamlessly into the top of the app.
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) {
    const surface = getComputedStyle(root).getPropertyValue('--surface-primary').trim();
    meta.setAttribute('content', surface || (effective === 'dark' ? THEME_COLOR_DARK : THEME_COLOR_LIGHT));
  }
}
