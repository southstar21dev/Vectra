import { describe, expect, it, vi, beforeEach } from 'vitest';

/** Mutable PocketBase auth store fake, driven per-test. */
const authStore = vi.hoisted(() => ({
  isValid: false,
  token: '',
  record: null as { id: string } | null,
  clear: vi.fn(),
}));

vi.mock('@/lib/pocketbase', () => ({
  pb: { authStore },
}));

import {
  clearStoredSession,
  clearStoredSessionIfExpired,
  isStoredSessionExpired,
  withTimeout,
} from './session-utils';

/** Minimal in-memory Storage so the helpers can be tested without jsdom. */
function makeStorage(initial: Record<string, string> = {}): Storage {
  const map = new Map(Object.entries(initial));
  return {
    get length() {
      return map.size;
    },
    key: (i: number) => [...map.keys()][i] ?? null,
    getItem: (k: string) => map.get(k) ?? null,
    setItem: (k: string, v: string) => {
      map.set(k, v);
    },
    removeItem: (k: string) => {
      map.delete(k);
    },
    clear: () => {
      map.clear();
    },
  } as unknown as Storage;
}

beforeEach(() => {
  authStore.isValid = false;
  authStore.token = '';
  authStore.record = null;
  authStore.clear.mockClear();
});

describe('withTimeout', () => {
  it('rejects when the promise does not settle in time', async () => {
    await expect(withTimeout(new Promise(() => {}), 20, 'too slow')).rejects.toThrow('too slow');
  });

  it('resolves with the value when it settles in time', async () => {
    await expect(withTimeout(Promise.resolve(42), 1000, 'n/a')).resolves.toBe(42);
  });

  it('propagates the original rejection', async () => {
    await expect(withTimeout(Promise.reject(new Error('boom')), 1000, 'n/a')).rejects.toThrow('boom');
  });
});

describe('clearStoredSession', () => {
  it('removes only the pocketbase_auth key and clears the auth store', () => {
    const storage = makeStorage({
      pocketbase_auth: JSON.stringify({ token: 't', record: { id: 'u1' } }),
      'vectra.appearance': '{"theme":"dark"}',
      'sb-old-auth-token': '{"access_token":"legacy"}',
    });
    clearStoredSession(storage);
    expect(storage.getItem('pocketbase_auth')).toBeNull();
    expect(storage.getItem('vectra.appearance')).toBeTruthy();
    expect(storage.getItem('sb-old-auth-token')).toBeTruthy();
    expect(authStore.clear).toHaveBeenCalledTimes(1);
  });
});

describe('isStoredSessionExpired', () => {
  it('is true when no token is stored', () => {
    authStore.token = '';
    expect(isStoredSessionExpired()).toBe(true);
  });

  it('is true when a token is present but invalid', () => {
    authStore.token = 'tok';
    authStore.isValid = false;
    expect(isStoredSessionExpired()).toBe(true);
  });

  it('is false when a valid token is present', () => {
    authStore.token = 'tok';
    authStore.isValid = true;
    expect(isStoredSessionExpired()).toBe(false);
  });
});

describe('clearStoredSessionIfExpired', () => {
  it('clears when a token is present but invalid', () => {
    authStore.token = 'tok';
    authStore.isValid = false;
    expect(clearStoredSessionIfExpired()).toBe(true);
    expect(authStore.clear).toHaveBeenCalledTimes(1);
  });

  it('does not clear when the token is still valid', () => {
    authStore.token = 'tok';
    authStore.isValid = true;
    expect(clearStoredSessionIfExpired()).toBe(false);
    expect(authStore.clear).not.toHaveBeenCalled();
  });

  it('does not clear when there is no token', () => {
    authStore.token = '';
    authStore.isValid = false;
    expect(clearStoredSessionIfExpired()).toBe(false);
    expect(authStore.clear).not.toHaveBeenCalled();
  });
});
