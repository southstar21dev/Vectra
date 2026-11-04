/**
 * In-app page navigation with native Android tab semantics: history holds at
 * most Home plus one page above it. Switching between non-home pages replaces
 * that entry instead of stacking, so the system back gesture goes to Home and
 * then leaves the app rather than walking every visited tab.
 */

import { DIALOG_HISTORY_KEY } from './dialog-history';

/** Marks a history entry that sits directly above a Home entry. */
export const ABOVE_HOME_KEY = '__vectraAboveHome';

const HOME = '/';
/** Public pages keep their own history; Home is never inserted under them. */
const STANDALONE_PATHS = ['/login', '/signup'];

/** Top-level sections the router renders (sub-paths render the same page). */
const SECTION_PATHS = ['/schedule', '/transit', '/settings'];

/**
 * True when the router has a page for `path`. Anything else is not an app
 * route: the router sends it to Home with a replaced URL, and the link
 * interceptor leaves it to the browser (static files, API routes).
 */
export function isKnownAppPath(path: string): boolean {
  if (path === HOME || STANDALONE_PATHS.includes(path)) return true;
  return SECTION_PATHS.some((section) => path === section || path.startsWith(`${section}/`));
}

export interface HistoryLike {
  readonly state: unknown;
  pushState: (state: unknown, unused: string, url: string) => void;
  replaceState: (state: unknown, unused: string, url: string) => void;
  back: () => void;
}

function readState(history: HistoryLike): Record<string, unknown> {
  const state = history.state;
  return state && typeof state === 'object' ? (state as Record<string, unknown>) : {};
}

/**
 * Navigates from `fromPath` to `url`. Returns true when the caller must render
 * the new path itself; false when it went back in history, in which case the
 * browser's own `popstate` drives the router.
 */
export function navigateInApp(fromPath: string, url: string, history: HistoryLike): boolean {
  const toPath = new URL(url, 'http://x').pathname;
  const state = readState(history);
  const aboveHome = state[ABOVE_HOME_KEY] === true;

  if (toPath === HOME) {
    // An open dialog's trap entry sits on top; back would only pop that.
    if (aboveHome && state[DIALOG_HISTORY_KEY] !== true) {
      history.back();
      return false;
    }
    history.replaceState({}, '', url);
    return true;
  }

  if (fromPath === HOME) {
    history.pushState({ [ABOVE_HOME_KEY]: true }, '', url);
  } else {
    history.replaceState(aboveHome ? { [ABOVE_HOME_KEY]: true } : {}, '', url);
  }
  return true;
}

/**
 * On a cold start straight into a non-home page (deep link, notification,
 * reload), inserts Home underneath it so back lands on Home first.
 */
export function ensureHomeBeneath(path: string, url: string, history: HistoryLike): void {
  if (path === HOME || STANDALONE_PATHS.includes(path)) return;
  if (readState(history)[ABOVE_HOME_KEY] === true) return; // reload: already in place
  history.replaceState({}, '', HOME);
  history.pushState({ [ABOVE_HOME_KEY]: true }, '', url);
}
