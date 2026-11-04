import { describe, expect, it, vi, beforeEach } from 'vitest';
import type { QueryClient } from '@tanstack/react-query';

/**
 * The single auth-store listener: a token means refresh the auth caches, an
 * empty token (any sign-out path) means drop user data, reset appearance and
 * kill this device's push endpoint.
 */
const listeners = vi.hoisted(() => new Set<(token: string) => void>());
const clearAllOfflineUserData = vi.hoisted(() => vi.fn());
const unsubscribeDevicePushLocally = vi.hoisted(() => vi.fn(() => Promise.resolve()));
const resetAppearanceForSignOut = vi.hoisted(() => vi.fn());

vi.mock('@/lib/pocketbase', () => ({
  pb: {
    authStore: {
      onChange: (cb: (token: string) => void) => {
        listeners.add(cb);
        return () => listeners.delete(cb);
      },
    },
  },
}));
vi.mock('@/lib/offline-cache', () => ({ clearAllOfflineUserData }));
vi.mock('@/features/notifications/use-push-subscription', () => ({ unsubscribeDevicePushLocally }));
vi.mock('@/hooks/use-appearance', () => ({ resetAppearanceForSignOut }));

import { installAuthStoreSync } from './auth-store-sync';

function fakeQueryClient() {
  return {
    invalidateQueries: vi.fn(),
    setQueryData: vi.fn(),
    clear: vi.fn(),
  };
}

const emit = (token: string) => listeners.forEach((cb) => cb(token));

beforeEach(() => {
  listeners.clear();
  vi.clearAllMocks();
});

describe('installAuthStoreSync', () => {
  it('invalidates the auth and profile caches once per save', () => {
    const qc = fakeQueryClient();
    const off = installAuthStoreSync(qc as unknown as QueryClient);
    emit('token');
    expect(qc.invalidateQueries).toHaveBeenCalledTimes(2);
    expect(unsubscribeDevicePushLocally).not.toHaveBeenCalled();
    off();
  });

  it('keeps a single subscription when installed again', () => {
    const qc = fakeQueryClient();
    installAuthStoreSync(qc as unknown as QueryClient);
    const off = installAuthStoreSync(qc as unknown as QueryClient);
    expect(listeners.size).toBe(1);
    off();
    expect(listeners.size).toBe(0);
  });

  it('cleans up user data and the local push endpoint on any sign-out', () => {
    const qc = fakeQueryClient();
    const off = installAuthStoreSync(qc as unknown as QueryClient);
    emit('');
    expect(clearAllOfflineUserData).toHaveBeenCalledTimes(1);
    expect(resetAppearanceForSignOut).toHaveBeenCalledTimes(1);
    expect(unsubscribeDevicePushLocally).toHaveBeenCalledTimes(1);
    expect(qc.setQueryData).toHaveBeenCalledWith(['auth', 'session'], null);
    expect(qc.clear).toHaveBeenCalledTimes(1);
    off();
  });
});
