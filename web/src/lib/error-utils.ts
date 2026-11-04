/**
 * Extract a human-readable message from an unknown error value.
 * Falls back to a generic message when the value is not an Error instance.
 */
export function getErrorMessage(err: unknown, fallback = 'Unknown error'): string {
  if (err instanceof Error) return err.message;
  if (typeof err === 'string') return err;
  return fallback;
}
