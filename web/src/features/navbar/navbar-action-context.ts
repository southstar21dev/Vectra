import { createContext, useContext, useEffect } from 'react';
import type { ComponentType } from 'react';

export interface NavbarActionItem {
  id: string;
  label: string;
  icon: ComponentType<{ className?: string }>;
  variant?: 'primary' | 'emerald' | 'blue' | 'gray' | 'secondary';
  disabled?: boolean;
  onClick: () => void;
}

export interface NavbarActionConfig {
  icon?: ComponentType<{ className?: string }>;
  actions: NavbarActionItem[];
}

export interface NavbarActionContextValue {
  config: NavbarActionConfig | null;
  setConfig: (config: NavbarActionConfig | null) => void;
  isActionRoute: (path: string) => boolean;
  executeAction: (id: string) => void;
}

export const ACTION_ROUTES = new Set(['/transit', '/schedule']);

export const NavbarActionContext = createContext<NavbarActionContextValue | null>(null);

export function useNavbarActions() {
  const ctx = useContext(NavbarActionContext);
  if (!ctx) {
    throw new Error('useNavbarActions must be used within NavbarActionProvider');
  }
  return ctx;
}

/**
 * Hook for pages to register their actions with the navbar.
 * Structural change detection prevents infinite re-render loops.
 */
export function useSetNavbarActions(config: NavbarActionConfig | null) {
  const { setConfig } = useNavbarActions();

  // Register/refresh on config changes: callers often rebuild the config every
  // render (memoized callbacks can depend on unstable values), which is fine —
  // the provider dedupes by structure and just refreshes the action callbacks.
  useEffect(() => {
    setConfig(config);
  }, [config, setConfig]);

  // Clear only on unmount, and only when the destination is not another action
  // route (navigating between them should not flicker the action bubble).
  // This must live in its own mount-scoped effect: when it was the registration
  // effect's cleanup, any re-render cleared and immediately re-registered the
  // config, looping once window.location had already moved to the new route.
  useEffect(() => {
    return () => {
      const nextPath = typeof window !== 'undefined' ? window.location.pathname : '';
      if (!ACTION_ROUTES.has(nextPath)) {
        setConfig(null);
      }
    };
  }, [setConfig]);
}
