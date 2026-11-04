/** Pure helpers for the account management forms in Settings. */

/** Basic shape check — must look like `name@domain.tld`. */
export function isValidEmail(email: string): boolean {
  return /^\S+@\S+\.\S+$/.test(email);
}

/** PocketBase's default minimum for auth-record passwords (the users
 *  migration keeps the default). */
export const MIN_PASSWORD_LENGTH = 8;

/** Returns an error message when the password is unusable, else null. */
export function validatePassword(password: string): string | null {
  if (!password) return 'Password is required';
  if (password.length < MIN_PASSWORD_LENGTH) {
    return `Password must be at least ${MIN_PASSWORD_LENGTH} characters`;
  }
  return null;
}

/** Client cap for the display name. The users `full_name` field keeps
 *  PocketBase's generous default text limit; 100 is plenty for a name and
 *  keeps the navbar/greeting layouts sane. */
export const FULL_NAME_MAX_LENGTH = 100;

/** PocketBase answers every bad email/password pair with this generic text. */
const PB_AUTH_FAILED = 'Failed to authenticate.';

/** Human copy for a failed password sign-in. */
export function loginErrorMessage(err: unknown): string {
  const message = err instanceof Error ? err.message : '';
  if (message === PB_AUTH_FAILED) return 'Incorrect email or password.';
  return message || 'Could not sign in. Try again.';
}

const FIELD_LABELS: Record<string, string> = {
  password: 'Password',
  passwordConfirm: 'Password confirmation',
  email: 'Email',
  full_name: 'Nickname',
};

/**
 * The first per-field validation message from a PocketBase request failure
 * (`err.response.data.<field>.message`), labelled with its field — the
 * top-level message is only a generic "Failed to create record.". Null when
 * the failure carries no field errors.
 */
export function pocketbaseFieldErrorMessage(err: unknown): string | null {
  if (!err || typeof err !== 'object') return null;
  const data = (err as { response?: { data?: unknown } }).response?.data;
  if (!data || typeof data !== 'object') return null;
  for (const [field, value] of Object.entries(data as Record<string, unknown>)) {
    const message = (value as { message?: unknown } | null)?.message;
    if (typeof message === 'string' && message) {
      const label = FIELD_LABELS[field];
      return label ? `${label}: ${message}` : message;
    }
  }
  return null;
}

/** The first field PocketBase flagged in a request failure, or null. */
export function pocketbaseErrorField(err: unknown): string | null {
  if (!err || typeof err !== 'object') return null;
  const data = (err as { response?: { data?: unknown } }).response?.data;
  if (!data || typeof data !== 'object') return null;
  for (const [field, value] of Object.entries(data as Record<string, unknown>)) {
    if (typeof (value as { message?: unknown } | null)?.message === 'string') return field;
  }
  return null;
}

/** Returns an error message when the confirmation doesn't match, else null. */
export function validateConfirmPassword(password: string, confirm: string): string | null {
  if (!confirm) return 'Please confirm your new password';
  if (password !== confirm) return 'Passwords do not match';
  return null;
}

/** The exact text a user must type to confirm account deletion. */
export const DELETE_ACCOUNT_CONFIRM_TEXT = 'DELETE';

/** True when the typed text matches the literal delete confirmation. */
export function deleteConfirmationMatches(input: string): boolean {
  return input.trim().toUpperCase() === DELETE_ACCOUNT_CONFIRM_TEXT;
}

/**
 * Extract a readable message from a PocketBase request failure.
 * When the error carries a JSON response body with an `error` field, that
 * message is the useful one. Falls back to the error's own message, or null
 * when nothing is usable.
 */
export async function functionsErrorMessage(err: unknown): Promise<string | null> {
  if (!err || typeof err !== 'object') return null;
  const e = err as { message?: string; context?: Response };
  if (e.context && typeof (e.context as Response).json === 'function') {
    try {
      const body = await (e.context as Response).json();
      if (body && typeof body.error === 'string' && body.error) return body.error;
    } catch {
      // not JSON — fall through to the plain message
    }
  }
  return typeof e.message === 'string' && e.message ? e.message : null;
}