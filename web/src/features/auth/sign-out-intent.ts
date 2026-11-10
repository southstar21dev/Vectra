/**
 * Whether the next session clear is the user's own choice (logout, account
 * deletion) rather than a forced one (expiry, a dead token after an email or
 * password change). The auth-store sync wipes the offline queue only for the
 * former; see auth-store-sync.ts.
 */
let explicitSignOut = false;

/** Call right before `pb.authStore.clear()` on an explicit sign-out. */
export function markExplicitSignOut(): void {
  explicitSignOut = true;
}

/** Reads and resets the flag: true when the clear in progress was explicit. */
export function takeExplicitSignOut(): boolean {
  const explicit = explicitSignOut;
  explicitSignOut = false;
  return explicit;
}
