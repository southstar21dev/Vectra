import { useState, useRef, useMemo, useCallback, type ReactNode } from 'react';
import {
  type NavbarActionConfig,
  ACTION_ROUTES,
  NavbarActionContext,
} from './navbar-action-context';

function serializeConfig(c: NavbarActionConfig | null): string {
  if (!c) return 'null';
  return (
    (c.icon?.name || 'Icon') +
    '|' +
    c.actions.map((a) => `${a.id}:${a.label}:${a.variant || 'primary'}:${Boolean(a.disabled)}`).join(';')
  );
}

export function NavbarActionProvider({ children }: { children: ReactNode }) {
  const [config, setConfigState] = useState<NavbarActionConfig | null>(null);
  const currentKeyRef = useRef<string>('null');
  const callbacksRef = useRef<Record<string, () => void>>({});

  const setConfig = useCallback((nextConfig: NavbarActionConfig | null) => {
    // Always keep action callbacks up to date in ref
    if (nextConfig) {
      const nextMap: Record<string, () => void> = {};
      for (const act of nextConfig.actions) {
        nextMap[act.id] = act.onClick;
      }
      callbacksRef.current = nextMap;
    } else {
      callbacksRef.current = {};
    }

    const nextKey = serializeConfig(nextConfig);
    if (nextKey === currentKeyRef.current) {
      return; // UI structure unchanged, avoid state update and re-render loops
    }

    currentKeyRef.current = nextKey;
    setConfigState(nextConfig);
  }, []);

  const executeAction = useCallback((id: string) => {
    callbacksRef.current[id]?.();
  }, []);

  const isActionRoute = useCallback((path: string) => {
    return ACTION_ROUTES.has(path);
  }, []);

  const value = useMemo(
    () => ({
      config,
      setConfig,
      isActionRoute,
      executeAction,
    }),
    [config, setConfig, isActionRoute, executeAction]
  );

  return <NavbarActionContext.Provider value={value}>{children}</NavbarActionContext.Provider>;
}
