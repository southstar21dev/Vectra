import { describe, expect, it, vi, beforeEach } from 'vitest';

/**
 * Mutable PocketBase auth-store fake plus a stubbed `users` collection, so the
 * query functions can be exercised without touching PocketBase.
 */
const authStore = vi.hoisted(() => ({
  isValid: false,
  token: '',
  record: null as { id: string } | null,
}));

const getOne = vi.hoisted(() => vi.fn());

vi.mock('@/lib/pocketbase', () => ({
  pb: {
    authStore,
    collection: vi.fn(() => ({ getOne })),
  },
}));

import { ClientResponseError } from 'pocketbase';
import { profileQueryOptions, sessionQueryOptions } from './auth.queries';

beforeEach(() => {
  authStore.isValid = false;
  authStore.token = '';
  authStore.record = null;
  getOne.mockReset();
});

describe('sessionQueryOptions', () => {
  it('returns null when the auth store has no valid session', async () => {
    const result = await (sessionQueryOptions.queryFn as any)();
    expect(result).toBeNull();
  });

  it('returns the auth store when a valid session is present', async () => {
    authStore.isValid = true;
    authStore.token = 'tok';
    authStore.record = { id: 'user-1' };
    const result = await (sessionQueryOptions.queryFn as any)();
    expect(result).toBe(authStore);
  });
});

describe('profileQueryOptions', () => {
  it('returns null without fetching when there is no auth record', async () => {
    const result = await (profileQueryOptions.queryFn as any)();
    expect(result).toBeNull();
    expect(getOne).not.toHaveBeenCalled();
  });

  it('fetches and returns the users record for the signed-in id', async () => {
    authStore.record = { id: 'user-123' };
    const mockProfile = { id: 'user-123', full_name: 'Alex' };
    getOne.mockResolvedValue(mockProfile);

    const result = await (profileQueryOptions.queryFn as any)();
    expect(result).toEqual(mockProfile);
    expect(getOne).toHaveBeenCalledWith('user-123');
  });

  it('returns null and warns when the record is missing (404)', async () => {
    authStore.record = { id: 'user-123' };
    getOne.mockRejectedValue(new ClientResponseError({ status: 404 }));
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});

    const result = await (profileQueryOptions.queryFn as any)();
    expect(result).toBeNull();
    expect(warn).toHaveBeenCalled();

    warn.mockRestore();
  });

  it('rethrows non-404 failures so they surface as a query error', async () => {
    authStore.record = { id: 'user-123' };
    getOne.mockRejectedValue(new ClientResponseError({ status: 500 }));
    await expect((profileQueryOptions.queryFn as any)()).rejects.toBeInstanceOf(ClientResponseError);

    const offline = new Error('Failed to fetch');
    getOne.mockRejectedValue(offline);
    await expect((profileQueryOptions.queryFn as any)()).rejects.toBe(offline);
  });
});
