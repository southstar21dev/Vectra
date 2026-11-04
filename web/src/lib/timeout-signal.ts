/**
 * AbortSignal that fires after `ms`. `AbortSignal.timeout` throws a TypeError
 * before iOS 16, which would fail the request it was meant to bound.
 */
export function timeoutSignal(ms: number): AbortSignal {
  if (typeof AbortSignal.timeout === 'function') return AbortSignal.timeout(ms);
  const controller = new AbortController();
  setTimeout(() => controller.abort(new DOMException('The operation timed out.', 'TimeoutError')), ms);
  return controller.signal;
}
