/**
 * JSON value type shared by the PocketBase data layers for JSON fields such as
 * `csv_metadata`.
 */
export type Json = string | number | boolean | null | { [key: string]: Json | undefined } | Json[];

/** PocketBase system `id` + custom timestamps + owner relation named `user`. */
export interface PBRecord {
  id: string;
  user?: string;
  created_at?: string;
  updated_at?: string;
}

/**
 * Add `user_id` to a PocketBase row so existing domain types (which read
 * `user_id`) keep working. Call at the data-layer boundary only.
 */
export function withUserId<T extends PBRecord>(row: T): T & { user_id: string } {
  return { ...row, user_id: row.user ?? '' };
}
