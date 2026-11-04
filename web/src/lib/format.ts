/**
 * Shared formatting helpers for non-date values. All date, clock, and time
 * formatters live in `lib/date-utils.ts`.
 */

export interface FormatBytesOptions {
  /** Decimal places (default 2). */
  decimals?: number;
  /** 1000 = decimal (MB), 1024 = binary (MiB-style, still labelled MB by callers). */
  base?: 1000 | 1024;
}

/** Human-readable byte size, e.g. `1.46 MB`. */
export function formatBytes(bytes: number, options: FormatBytesOptions = {}): string {
  const { decimals = 2, base = 1024 } = options;
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B';

  const dm = decimals < 0 ? 0 : decimals;
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.min(sizes.length - 1, Math.floor(Math.log(bytes) / Math.log(base)));
  const value = parseFloat((bytes / Math.pow(base, i)).toFixed(dm));
  return `${value} ${sizes[i]}`;
}
