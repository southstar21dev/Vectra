/** Order-insensitive equality for mode-filter lists. */
export function sameModeFilter(a: readonly string[], b: readonly string[]): boolean {
  if (a.length !== b.length) return false;
  const set = new Set(a);
  return b.every((mode) => set.has(mode));
}

/**
 * Serializes persisted mode-filter writes. Only one write is in flight; taps
 * that arrive while it is pending only update the desired value, so the queue
 * always persists the latest selection (an out-of-order earlier completion
 * cannot land last). A write is skipped when it would repeat the last value
 * written successfully. Each returned promise rejects only for its own write;
 * the queue keeps accepting later writes after a failure.
 */
export function createModeFilterWriteQueue(): (
  filter: string[],
  write: (filter: string[]) => Promise<unknown>
) => Promise<void> {
  let desired: { filter: string[]; write: (filter: string[]) => Promise<unknown> } | null = null;
  let written: string[] | null = null;
  let tail: Promise<void> = Promise.resolve();

  return (filter, write) => {
    desired = { filter, write };
    const run = tail.then(async () => {
      const next = desired;
      if (next === null || (written !== null && sameModeFilter(next.filter, written))) return;
      await next.write(next.filter);
      written = next.filter;
    });
    tail = run.catch(() => {});
    return run;
  };
}

/** Selected transit modes; an empty list means no mode filter (ALL). Reads the
 *  current list form and the legacy single-string value. */
export function parseModeFilter(
  preferences: Record<string, unknown> | undefined | null
): string[] {
  const raw = preferences?.transit_mode_filter;
  if (Array.isArray(raw)) {
    return raw
      .filter((value): value is string => typeof value === 'string')
      .map((value) => value.toUpperCase())
      .filter((value) => value !== 'ALL');
  }
  if (typeof raw === 'string' && raw && raw.toUpperCase() !== 'ALL') {
    return [raw.toUpperCase()];
  }
  return [];
}

/** Next filter list when `id` is picked from the explorer's single-select
 *  chips: tapping the only active mode clears the filter, any other mode
 *  replaces the selection ('ALL' always clears). Kept single-entry so a
 *  previously persisted mode cannot linger next to the tapped one. */
export function selectSingleModeFilter(current: readonly string[], id: string): string[] {
  const upper = id.toUpperCase();
  if (upper === 'ALL') return [];
  if (current.length === 1 && current[0].toUpperCase() === upper) return [];
  return [upper];
}
