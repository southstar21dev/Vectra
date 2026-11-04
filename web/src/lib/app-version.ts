/**
 * Injected by Vite at build time (see vite.config.ts `define`): the version
 * from package.json, or VITE_APP_VERSION set at build time.
 */
declare const __APP_VERSION__: string;

export interface AppVersionInfo {
  /** The web app (PWA) version baked into this build. */
  appVersion: string;
}

/**
 * App version info for Settings → About. The version is baked in at
 * build time (package.json by default).
 */
export function useAppVersion(): AppVersionInfo {
  return { appVersion: __APP_VERSION__ };
}
