import { pb } from '@/lib/pocketbase';
import { requireSession } from '@/lib/auth-helpers';
import type { Profile } from './auth.queries';
import type { LoginCredentials, SignupCredentials } from './types';
import { cleanupDevicePushSubscription } from '@/features/notifications/use-push-subscription';
import { SIGN_OUT_TIMEOUT_MS, withTimeout } from './session-utils';
import { markExplicitSignOut } from './sign-out-intent';

/**
 * The auth mutation bodies live here (separate from `useAuth`) so they can be
 * unit-tested directly without a React/QueryClient harness. `useAuth` wires
 * them into `useMutation` with its invalidation side effects.
 */

/** Register a users auth record, then sign in. */
export async function signupWithPassword({ email, password, fullName }: SignupCredentials) {
  await pb.collection('users').create({
    email,
    password,
    passwordConfirm: password,
    full_name: fullName,
  });
  return await pb.collection('users').authWithPassword(email, password);
}

/** Sign in with email + password. */
export async function loginWithPassword({ email, password }: LoginCredentials) {
  return await pb.collection('users').authWithPassword(email, password);
}

/** Update the signed-in user's own auth record. */
export async function updateUserProfile(updates: Partial<Profile>) {
  const session = requireSession();
  return await pb.collection('users').update(session.record!.id, updates);
}

/** Verify the current password, then switch the caller's email instantly. */
export async function changeEmail({
  currentPassword,
  newEmail,
}: {
  currentPassword: string;
  newEmail: string;
}) {
  requireSession();
  // The backend verifies the current password and switches the email instantly
  // (no SMTP confirmation). The new address is stored unverified, since nothing
  // has proven the user owns it.
  await pb.send('/api/vectra/account/email', {
    method: 'POST',
    body: { currentPassword, newEmail },
  });
  // PocketBase rotates the auth token key whenever an auth record's email
  // changes, which invalidates the current token. Re-authenticate with the new
  // address and the password we just verified so the user stays signed in.
  try {
    await pb.collection('users').authWithPassword(newEmail, currentPassword);
  } catch {
    // The change committed server-side but the old token is now dead. Clear it
    // so the app signs out cleanly instead of looping on 401s, and report the
    // truth: the change succeeded, the user just has to sign in again.
    pb.authStore.clear();
    throw new Error('Email updated, but your session ended — please sign in again with your new address');
  }
  return { pendingConfirmation: false };
}

/** Verify the current password, then set a new one. */
export async function changePassword({
  currentPassword,
  newPassword,
}: {
  currentPassword: string;
  newPassword: string;
}) {
  const session = requireSession();
  const currentEmail = session.record?.email;
  if (!currentEmail) throw new Error('Not authenticated');
  try {
    await pb.collection('users').authWithPassword(currentEmail, currentPassword);
  } catch (err) {
    // Only a rejected login means a wrong password; offline or rate-limited is
    // reported as what it is.
    const status = (err as { status?: number } | null)?.status;
    if (status === 400 || status === 401) throw new Error('Current password is incorrect');
    if (status === 429) throw new Error('Too many attempts — please wait a moment and try again');
    throw err;
  }
  // PocketBase requires oldPassword whenever a user changes their own password.
  await pb.collection('users').update(session.record!.id, {
    oldPassword: currentPassword,
    password: newPassword,
    passwordConfirm: newPassword,
  });
  // A password change also rotates the token key; re-auth with the new password
  // so the user stays signed in instead of being bounced to /login.
  try {
    await pb.collection('users').authWithPassword(currentEmail, newPassword);
  } catch {
    pb.authStore.clear();
    throw new Error('Password updated, but your session ended — please sign in again');
  }
}

/**
 * Delete the caller's account server-side, then clear the dead local session.
 * The server re-checks the current password, so a stolen token alone cannot
 * delete the account.
 */
export async function deleteUserAccount(currentPassword: string) {
  requireSession();
  await pb.send('/api/vectra/account/delete', { method: 'POST', body: { currentPassword } });
  // The server wiped the auth record; the local session is dead. Clearing the
  // store fires `onChange` in useAuth, which drops cached user data, the
  // offline queue included (the account no longer exists).
  markExplicitSignOut();
  pb.authStore.clear();
}

/**
 * Explicit sign-out. Unregisters this device's push subscription BEFORE the
 * session is wiped: deleting the row needs the owner's auth context, and a
 * previous account must not keep receiving pushes on a shared device. The
 * cleanup is bounded so a hung service worker or network can never trap the
 * user signed in; the session is cleared regardless. Clearing fires the
 * auth-store sync (auth-store-sync.ts) → offline data + query cache cleared,
 * local push endpoint unsubscribed.
 */
export async function signOut(): Promise<void> {
  try {
    await withTimeout(cleanupDevicePushSubscription(), SIGN_OUT_TIMEOUT_MS, 'Push cleanup timed out');
  } catch (err) {
    console.warn('Push cleanup on sign-out skipped:', err);
  } finally {
    // The user chose to sign out: the offline queue goes too. Callers flush it
    // first and warn when writes are left (see settings-page.tsx).
    markExplicitSignOut();
    pb.authStore.clear();
  }
}
