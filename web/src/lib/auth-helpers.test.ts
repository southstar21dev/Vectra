import { describe, expect, it, vi, beforeEach } from 'vitest';

/**
 * The auth store is a mutable object so each test can simulate a signed-in
 * (record + valid token) or signed-out client without touching PocketBase.
 */
const authStore = vi.hoisted(() => ({
  isValid: false,
  token: '',
  record: null as { id: string } | null,
  clear: vi.fn(),
}));

vi.mock('@/lib/pocketbase', () => ({
  pb: { authStore },
}));

import { getSessionUserId, getStoredUserId, requireSession } from './auth-helpers';

beforeEach(() => {
  authStore.isValid = false;
  authStore.token = '';
  authStore.record = null;
  authStore.clear.mockClear();
});

describe('getSessionUserId', () => {
  it('returns the auth record id synchronously', () => {
    authStore.isValid = true;
    authStore.token = 'tok';
    authStore.record = { id: 'user-1' };
    expect(getSessionUserId()).toBe('user-1');
  });

  it('returns null when no record is present', () => {
    expect(getSessionUserId()).toBeNull();
  });
});

describe('getStoredUserId', () => {
  it('returns the auth record id synchronously', () => {
    authStore.record = { id: 'user-2' };
    expect(getStoredUserId()).toBe('user-2');
  });

  it('returns null when no record is present', () => {
    expect(getStoredUserId()).toBeNull();
  });
});

describe('requireSession', () => {
  it('returns the auth store when valid with a record', () => {
    authStore.isValid = true;
    authStore.token = 'tok';
    authStore.record = { id: 'user-9' };
    expect(requireSession()).toBe(authStore);
  });

  it('throws when the store is not valid', () => {
    authStore.isValid = false;
    authStore.record = { id: 'user-9' };
    expect(() => requireSession()).toThrow('Not authenticated');
  });

  it('throws when there is no record', () => {
    authStore.isValid = true;
    authStore.token = 'tok';
    authStore.record = null;
    expect(() => requireSession()).toThrow('Not authenticated');
  });
});
