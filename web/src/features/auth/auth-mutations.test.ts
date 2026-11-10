import { describe, expect, it, vi, beforeEach } from 'vitest';

/**
 * The auth mutation bodies are extracted into `auth-mutations.ts` (no React /
 * QueryClient dependency), so they can be driven directly against a mocked
 * `@/lib/pocketbase`. `requireSession` (real) reads this same store.
 */
const authStore = vi.hoisted(() => ({
  isValid: false,
  token: '',
  record: null as { id: string; email?: string } | null,
  clear: vi.fn(),
}));

const authWithPassword = vi.hoisted(() => vi.fn());
const create = vi.hoisted(() => vi.fn());
const update = vi.hoisted(() => vi.fn());
const send = vi.hoisted(() => vi.fn());

vi.mock('@/lib/pocketbase', () => ({
  pb: {
    authStore,
    collection: vi.fn(() => ({ authWithPassword, create, update })),
    send,
  },
}));

const cleanupDevicePushSubscription = vi.hoisted(() => vi.fn());
vi.mock('@/features/notifications/use-push-subscription', () => ({ cleanupDevicePushSubscription }));

import { takeExplicitSignOut } from './sign-out-intent';
import {
  signOut,
  changeEmail,
  changePassword,
  deleteUserAccount,
  loginWithPassword,
  signupWithPassword,
  updateUserProfile,
} from './auth-mutations';

beforeEach(() => {
  authStore.isValid = true;
  authStore.token = 'tok';
  authStore.record = { id: 'user-1', email: 'old@example.com' };
  authStore.clear.mockClear();
  authWithPassword.mockReset();
  create.mockReset();
  update.mockReset();
  send.mockReset();
  takeExplicitSignOut();
});

/** A PocketBase ClientResponseError-shaped rejection. */
const pbError = (status: number, message = 'Failed to authenticate.') =>
  Object.assign(new Error(message), { status });

describe('loginWithPassword', () => {
  it('authenticates with the given email and password', async () => {
    authWithPassword.mockResolvedValue({ token: 't', record: { id: 'user-1' } });

    await loginWithPassword({ email: 'alex@example.com', password: 'secret' });

    expect(authWithPassword).toHaveBeenCalledWith('alex@example.com', 'secret');
  });
});

describe('signupWithPassword', () => {
  it('creates the users record, then signs in', async () => {
    create.mockResolvedValue({ id: 'user-1' });
    authWithPassword.mockResolvedValue({ token: 't', record: { id: 'user-1' } });

    await signupWithPassword({ email: 'alex@example.com', password: 'secret', fullName: 'Alex' });

    expect(create).toHaveBeenCalledWith({
      email: 'alex@example.com',
      password: 'secret',
      passwordConfirm: 'secret',
      full_name: 'Alex',
    });
    expect(authWithPassword).toHaveBeenCalledWith('alex@example.com', 'secret');
  });
});

describe('updateUserProfile', () => {
  it('updates the signed-in user record', async () => {
    update.mockResolvedValue({ id: 'user-1' });

    await updateUserProfile({ full_name: 'Alex' });

    expect(update).toHaveBeenCalledWith('user-1', { full_name: 'Alex' });
  });
});

describe('changeEmail', () => {
  it('calls the backend route, then re-auths with the new email + password', async () => {
    send.mockResolvedValue({ ok: true });
    authWithPassword.mockResolvedValue({ token: 't2', record: { id: 'user-1' } });

    const result = await changeEmail({ currentPassword: 'secret', newEmail: 'new@example.com' });

    expect(send).toHaveBeenCalledWith('/api/vectra/account/email', {
      method: 'POST',
      body: { currentPassword: 'secret', newEmail: 'new@example.com' },
    });
    expect(authWithPassword).toHaveBeenCalledWith('new@example.com', 'secret');
    expect(result).toEqual({ pendingConfirmation: false });
    expect(authStore.clear).not.toHaveBeenCalled();
  });

  it('wrong password: rejects, does not re-auth, keeps the session', async () => {
    send.mockRejectedValue(new Error('Current password is incorrect'));

    await expect(
      changeEmail({ currentPassword: 'wrong', newEmail: 'new@example.com' })
    ).rejects.toThrow('Current password is incorrect');

    expect(authWithPassword).not.toHaveBeenCalled();
    expect(authStore.clear).not.toHaveBeenCalled();
  });

  it('re-auth failure: clears the session and reports the change succeeded', async () => {
    send.mockResolvedValue({ ok: true });
    authWithPassword.mockRejectedValue(new Error('network down'));

    await expect(
      changeEmail({ currentPassword: 'secret', newEmail: 'new@example.com' })
    ).rejects.toThrow(/sign in again/i);

    expect(authStore.clear).toHaveBeenCalledTimes(1);
  });
});

describe('changePassword', () => {
  it('verifies, updates, then re-auths with the new password', async () => {
    authWithPassword.mockResolvedValue({ token: 't', record: { id: 'user-1' } });
    update.mockResolvedValue({ id: 'user-1' });

    await changePassword({ currentPassword: 'old', newPassword: 'newpass' });

    expect(authWithPassword).toHaveBeenNthCalledWith(1, 'old@example.com', 'old');
    // PocketBase rejects a self-service password change without oldPassword.
    expect(update).toHaveBeenCalledWith('user-1', {
      oldPassword: 'old',
      password: 'newpass',
      passwordConfirm: 'newpass',
    });
    expect(authWithPassword).toHaveBeenNthCalledWith(2, 'old@example.com', 'newpass');
    expect(authStore.clear).not.toHaveBeenCalled();
  });

  it.each([400, 401])('wrong current password (%i): throws and never updates', async (status) => {
    authWithPassword.mockRejectedValueOnce(pbError(status));

    await expect(
      changePassword({ currentPassword: 'wrong', newPassword: 'newpass' })
    ).rejects.toThrow('Current password is incorrect');

    expect(update).not.toHaveBeenCalled();
    expect(authStore.clear).not.toHaveBeenCalled();
  });

  it('a failed check that is not a rejected password is not called a wrong password', async () => {
    authWithPassword.mockRejectedValueOnce(pbError(0, 'Failed to fetch'));

    const err = await changePassword({ currentPassword: 'old', newPassword: 'newpass' }).catch((e: Error) => e);
    expect(err).toBeInstanceOf(Error);
    expect((err as Error).message).toBe('Failed to fetch');
    expect(update).not.toHaveBeenCalled();
  });

  it('a rate-limited check says so', async () => {
    authWithPassword.mockRejectedValueOnce(pbError(429, 'Too many requests.'));

    await expect(
      changePassword({ currentPassword: 'old', newPassword: 'newpass' })
    ).rejects.toThrow(/too many attempts/i);

    expect(update).not.toHaveBeenCalled();
    expect(authStore.clear).not.toHaveBeenCalled();
  });

  it('re-auth failure after update: clears the session and reports the change succeeded', async () => {
    authWithPassword
      .mockResolvedValueOnce({ token: 't', record: { id: 'user-1' } }) // verify current
      .mockRejectedValueOnce(new Error('network down')); // re-auth new
    update.mockResolvedValue({ id: 'user-1' });

    await expect(
      changePassword({ currentPassword: 'old', newPassword: 'newpass' })
    ).rejects.toThrow(/sign in again/i);

    expect(authStore.clear).toHaveBeenCalledTimes(1);
  });
});

describe('deleteUserAccount', () => {
  it('sends the current password, then clears the session as an explicit sign-out', async () => {
    send.mockResolvedValue({ deleted: true });

    await deleteUserAccount('secret');

    expect(send).toHaveBeenCalledWith('/api/vectra/account/delete', {
      method: 'POST',
      body: { currentPassword: 'secret' },
    });
    expect(authStore.clear).toHaveBeenCalledTimes(1);
    expect(takeExplicitSignOut()).toBe(true);
  });

  it('a rejected delete keeps the session', async () => {
    send.mockRejectedValue(pbError(400, 'Current password is incorrect'));

    await expect(deleteUserAccount('wrong')).rejects.toThrow('Current password is incorrect');

    expect(authStore.clear).not.toHaveBeenCalled();
    expect(takeExplicitSignOut()).toBe(false);
  });
});

describe('signOut', () => {
  it('removes the device push subscription, then clears the session', async () => {
    cleanupDevicePushSubscription.mockResolvedValue(undefined);
    await signOut();
    expect(cleanupDevicePushSubscription).toHaveBeenCalledTimes(1);
    expect(authStore.clear).toHaveBeenCalledTimes(1);
    // An explicit sign-out: the auth-store sync wipes the offline queue too.
    expect(takeExplicitSignOut()).toBe(true);
  });

  it('still clears the session when the push cleanup fails', async () => {
    cleanupDevicePushSubscription.mockRejectedValue(new Error('sw gone'));
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    await signOut();
    expect(authStore.clear).toHaveBeenCalledTimes(1);
    warn.mockRestore();
  });

  it('gives up on a hung push cleanup after the sign-out timeout', async () => {
    vi.useFakeTimers();
    cleanupDevicePushSubscription.mockReturnValue(new Promise(() => {}));
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const done = signOut();
    await vi.advanceTimersByTimeAsync(5_000);
    await done;
    expect(authStore.clear).toHaveBeenCalledTimes(1);
    warn.mockRestore();
    vi.useRealTimers();
  });
});
