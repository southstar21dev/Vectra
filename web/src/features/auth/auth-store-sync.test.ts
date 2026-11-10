import { describe, expect, it, vi, beforeEach } from 'vitest';
import type { QueryClient } from '@tanstack/react-query';

/**
 * The single auth-store listener: a token means refresh the auth caches, an
 * empty token (any sign-out path) means drop user data, reset appearance and
 * kill this device's push endpoint.
 */
type Listener = (token: string, record: { id: string } | null) => void;
const listeners = vi.hoisted(() => new Set<Listener>());
const clearAllOfflineUserData = vi.hoisted(() => vi.fn());
const settleKeptOfflineQueue = vi.hoisted(() => vi.fn());
const unsubscribeDevicePushLocally = vi.hoisted(() => vi.fn(() => Promise.resolve()));
const resetAppearanceForSignOut = vi.hoisted(() => vi.fn());

vi.mock('@/lib/pocketbase', () => ({
  pb: {
    authStore: {
      onChange: (cb: Listener) => {
        listeners.add(cb);
        return () => listeners.delete(cb);
      },
    },
  },
}));
vi.mock('@/lib/offline-cache', () => ({ clearAllOfflineUserData, settleKeptOfflineQueue }));
vi.mock('@/features/notifications/use-push-subscription', () => ({ unsubscribeDevicePushLocally }));
vi.mock('@/hooks/use-appearance', () => ({ resetAppearanceForSignOut }));

import { installAuthStoreSync } from './auth-store-sync';
import { markExplicitSignOut } from './sign-out-intent';

function fakeQueryClient() {
  return {
    invalidateQueries: vi.fn(),
    setQueryData: vi.fn(),
    clear: vi.fn(),
  };
}

const emit = (token: string, userId: string | null = token ? 'user-1' : null) =>
  listeners.forEach((cb) => cb(token, userId ? { id: userId } : null));

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

  it('keeps the offline queue of the account a forced sign-out ends', () => {
    const off = installAuthStoreSync(fakeQueryClient() as unknown as QueryClient);
    emit('token', 'user-1');
    emit('');
    expect(clearAllOfflineUserData).toHaveBeenCalledWith(undefined, 'user-1');
    off();
  });

  it('wipes the offline queue on an explicit sign-out', () => {
    const off = installAuthStoreSync(fakeQueryClient() as unknown as QueryClient);
    emit('token', 'user-1');
    markExplicitSignOut();
    emit('');
    expect(clearAllOfflineUserData).toHaveBeenCalledWith(undefined, null);
    // The flag is one-shot: a later forced sign-out keeps the queue again.
    emit('token', 'user-1');
    emit('');
    expect(clearAllOfflineUserData).toHaveBeenLastCalledWith(undefined, 'user-1');
    off();
  });

  it('settles a kept queue when an account signs in, not on token refreshes', () => {
    const off = installAuthStoreSync(fakeQueryClient() as unknown as QueryClient);
    emit('token', 'user-2');
    expect(settleKeptOfflineQueue).toHaveBeenCalledWith('user-2');
    emit('token2', 'user-2');
    expect(settleKeptOfflineQueue).toHaveBeenCalledTimes(1);
    off();
  });
});
