import { z } from 'zod';
import type { ScheduleItem } from './schedule-conflicts';
import { resolveStorage, type StorageLike } from '@/lib/storage';

export interface ScheduleItemSyncConflict {
  serverItem: ScheduleItem | null;
  serverUpdatedAt: string | null;
}

export interface StoredScheduleItem extends ScheduleItem {
  updatedAt: string | null;
  pendingSync?: boolean;
  syncConflict?: ScheduleItemSyncConflict;
}

export type ScheduleItemMutation =
  | {
      kind: 'upsert';
      id: string;
      item: StoredScheduleItem;
      baseUpdatedAt: string | null;
      syncConflict?: ScheduleItemSyncConflict;
    }
  | {
      kind: 'delete';
      id: string;
      baseUpdatedAt: string;
      syncConflict?: ScheduleItemSyncConflict;
    };

export interface ScheduleItemsState {
  version: 1;
  items: StoredScheduleItem[];
  queue: ScheduleItemMutation[];
}

const payloadSchema = z.object({
  id: z.string().uuid(),
  type: z.enum(['shift', 'task']),
  title: z.string().min(1).max(200),
  date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  startTime: z.string().regex(/^\d{2}:\d{2}$/),
  endTime: z.string().regex(/^\d{2}:\d{2}$/),
  location: z.string().max(200).optional(),
  completed: z.boolean().optional(),
  acknowledgedConflictKeys: z.array(z.string()).default([]),
  continuation: z.boolean().optional(),
});

const syncConflictSchema = z.object({
  serverItem: payloadSchema.nullable(),
  serverUpdatedAt: z.string().nullable(),
});

const storedItemSchema = payloadSchema.extend({
  updatedAt: z.string().nullable().default(null),
  pendingSync: z.boolean().optional(),
  syncConflict: syncConflictSchema.optional(),
});

const mutationSchema = z.discriminatedUnion('kind', [
  z.object({
    kind: z.literal('upsert'),
    id: z.string().uuid(),
    item: storedItemSchema,
    baseUpdatedAt: z.string().nullable(),
    syncConflict: syncConflictSchema.optional(),
  }),
  z.object({
    kind: z.literal('delete'),
    id: z.string().uuid(),
    baseUpdatedAt: z.string(),
    syncConflict: syncConflictSchema.optional(),
  }),
]);

const stateSchema = z.object({
  version: z.literal(1),
  items: z.array(storedItemSchema),
  queue: z.array(mutationSchema),
});

const envelopeSchema = z.object({
  version: z.literal(1),
  items: z.array(z.unknown()),
  queue: z.array(z.unknown()),
});

export const SCHEDULE_ITEMS_CACHE_BASE_KEY = 'vectra_schedule_items';

export function getScheduleItemsStorageKey(userId?: string | null): string {
  const scope = userId && userId.trim() ? userId.trim() : 'anonymous';
  return `${SCHEDULE_ITEMS_CACHE_BASE_KEY}_${scope}`;
}

export function createEmptyScheduleItemsState(): ScheduleItemsState {
  return { version: 1, items: [], queue: [] };
}

export function parseScheduleItemsState(raw: string | null | undefined): ScheduleItemsState | null {
  if (!raw) return null;
  try {
    // Validate the envelope, then each item and queued change on its own: one
    // entry written by another build must not discard every offline item and
    // unsynced edit along with it.
    const envelope = envelopeSchema.safeParse(JSON.parse(raw));
    if (!envelope.success) return null;
    const validItems = envelope.data.items.flatMap((entry) => {
      const item = storedItemSchema.safeParse(entry);
      return item.success ? [item.data] : [];
    });
    const validQueue = envelope.data.queue.flatMap((entry) => {
      const mutation = mutationSchema.safeParse(entry);
      return mutation.success ? [mutation.data] : [];
    });
    const itemsById = new Map(validItems.map((item) => [item.id, item]));
    const items = Array.from(itemsById.values());
    const validIds = new Set(itemsById.keys());
    const queueById = new Map<string, ScheduleItemMutation>();
    for (const mutation of validQueue) {
      if (mutation.kind === 'upsert' && !validIds.has(mutation.id)) continue;
      queueById.set(mutation.id, mutation as ScheduleItemMutation);
    }
    return { version: 1, items: items as StoredScheduleItem[], queue: Array.from(queueById.values()) };
  } catch {
    return null;
  }
}

export function readScheduleItemsState(userId?: string | null, storage?: StorageLike | null): ScheduleItemsState {
  const resolved = resolveStorage(storage);
  if (!resolved) return createEmptyScheduleItemsState();
  try {
    return parseScheduleItemsState(resolved.getItem(getScheduleItemsStorageKey(userId))) ?? createEmptyScheduleItemsState();
  } catch {
    return createEmptyScheduleItemsState();
  }
}

/** Returns false when the local cache cannot durably accept this state. */
export function writeScheduleItemsState(
  state: ScheduleItemsState,
  userId?: string | null,
  storage?: StorageLike | null
): boolean {
  const resolved = resolveStorage(storage);
  if (!resolved) return false;
  const parsed = stateSchema.safeParse(state);
  if (!parsed.success) return false;
  try {
    resolved.setItem(getScheduleItemsStorageKey(userId), JSON.stringify(parsed.data));
    return true;
  } catch {
    return false;
  }
}

function upsertItem(items: StoredScheduleItem[], item: StoredScheduleItem): StoredScheduleItem[] {
  if (!items.some((current) => current.id === item.id)) return [...items, item];
  return items.map((current) => current.id === item.id ? item : current);
}

/** Coalesce local writes while preserving the server version first read. */
export function enqueueScheduleItemUpsert(
  state: ScheduleItemsState,
  item: StoredScheduleItem,
  baseUpdatedAt?: string | null
): ScheduleItemsState {
  const previous = state.queue.find((operation) => operation.id === item.id);
  const existing = state.items.find((current) => current.id === item.id);
  const base = previous?.baseUpdatedAt ?? baseUpdatedAt ?? existing?.updatedAt ?? item.updatedAt ?? null;
  const nextItem: StoredScheduleItem = { ...item, pendingSync: true, syncConflict: undefined };
  const mutation: ScheduleItemMutation = { kind: 'upsert', id: item.id, item: nextItem, baseUpdatedAt: base };
  return {
    version: 1,
    items: upsertItem(state.items, nextItem),
    queue: [...state.queue.filter((operation) => operation.id !== item.id), mutation],
  };
}

export function enqueueScheduleItemDelete(
  state: ScheduleItemsState,
  id: string,
  baseUpdatedAt?: string | null
): ScheduleItemsState {
  const previous = state.queue.find((operation) => operation.id === id);
  const base = previous?.baseUpdatedAt ?? baseUpdatedAt ?? state.items.find((item) => item.id === id)?.updatedAt ?? null;
  const items = state.items.filter((item) => item.id !== id);
  const queue = state.queue.filter((operation) => operation.id !== id);
  if (base === null) return { version: 1, items, queue };
  return { version: 1, items, queue: [...queue, { kind: 'delete', id, baseUpdatedAt: base }] };
}

export function removeQueuedScheduleItemMutation(
  state: ScheduleItemsState,
  id: string,
  serverItem?: StoredScheduleItem | null
): ScheduleItemsState {
  const queue = state.queue.filter((operation) => operation.id !== id);
  const items = serverItem
    ? upsertItem(state.items, { ...serverItem, pendingSync: false, syncConflict: undefined })
    : state.items;
  return { version: 1, items, queue };
}

export function markScheduleItemSyncConflict(
  state: ScheduleItemsState,
  id: string,
  serverItem: StoredScheduleItem | null,
  serverUpdatedAt: string | null
): ScheduleItemsState {
  const syncConflict = { serverItem: serverItem ? stripStoredMetadata(serverItem) : null, serverUpdatedAt };
  let items = state.items.map((item) => item.id === id ? { ...item, pendingSync: true, syncConflict } : item);
  if (serverItem && !items.some((item) => item.id === id)) {
    items = upsertItem(items, { ...serverItem, pendingSync: true, syncConflict });
  }
  const queue = state.queue.map((operation) => operation.id === id ? { ...operation, syncConflict } : operation);
  return { ...state, items, queue };
}

export function resolveScheduleItemSyncConflict(
  state: ScheduleItemsState,
  id: string,
  resolution: 'server' | 'local'
): ScheduleItemsState {
  const operation = state.queue.find((entry) => entry.id === id);
  const conflict = operation?.syncConflict;
  if (!operation || !conflict) return state;

  if (resolution === 'server' || (operation.kind === 'delete' && conflict.serverItem === null)) {
    const items = conflict.serverItem
      ? upsertItem(state.items, { ...conflict.serverItem, updatedAt: conflict.serverUpdatedAt, pendingSync: false, syncConflict: undefined })
      : state.items.filter((item) => item.id !== id);
    return { ...state, items, queue: state.queue.filter((entry) => entry.id !== id) };
  }

  if (operation.kind === 'delete') {
    const updated: ScheduleItemMutation = { ...operation, baseUpdatedAt: conflict.serverUpdatedAt!, syncConflict: undefined };
    return { ...state, queue: state.queue.map((entry) => entry.id === id ? updated : entry) };
  }

  const localItem: StoredScheduleItem = {
    ...operation.item,
    updatedAt: conflict.serverUpdatedAt,
    pendingSync: true,
    syncConflict: undefined,
  };
  const updated: ScheduleItemMutation = {
    ...operation,
    item: localItem,
    baseUpdatedAt: conflict.serverUpdatedAt,
    syncConflict: undefined,
  };
  return {
    ...state,
    items: upsertItem(state.items, localItem),
    queue: state.queue.map((entry) => entry.id === id ? updated : entry),
  };
}

function stripStoredMetadata(item: StoredScheduleItem): ScheduleItem {
  const { updatedAt: _updatedAt, pendingSync: _pendingSync, syncConflict: _syncConflict, ...payload } = item;
  return payload;
}

/** Overlay pending local intent over the latest server snapshot. */
export function mergeScheduleItemsState(
  local: ScheduleItemsState,
  cloudItems: StoredScheduleItem[]
): ScheduleItemsState {
  const items = new Map<string, StoredScheduleItem>(cloudItems.map((item) => [item.id, { ...item, pendingSync: false }]));
  for (const operation of local.queue) {
    if (operation.kind === 'delete') {
      if (operation.syncConflict?.serverItem) {
        items.set(operation.id, {
          ...operation.syncConflict.serverItem,
          updatedAt: operation.syncConflict.serverUpdatedAt,
          pendingSync: true,
          syncConflict: operation.syncConflict,
        });
      } else {
        items.delete(operation.id);
      }
    } else {
      items.set(operation.id, {
        ...operation.item,
        pendingSync: true,
        syncConflict: operation.syncConflict ?? operation.item.syncConflict,
      });
    }
  }
  return {
    version: 1,
    items: Array.from(items.values()).sort((a, b) => a.date.localeCompare(b.date) || a.startTime.localeCompare(b.startTime)),
    queue: local.queue,
  };
}

export function stripScheduleItemMetadata(item: StoredScheduleItem): ScheduleItem {
  return stripStoredMetadata(item);
}

export function validateScheduleItem(item: ScheduleItem): ScheduleItem | null {
  const parsed = payloadSchema.safeParse(item);
  if (!parsed.success) return null;
  const next = parsed.data as ScheduleItem;
  if (!next.title.trim()) return null;
  const dateParts = next.date.split('-').map(Number);
  const date = new Date(Date.UTC(dateParts[0], dateParts[1] - 1, dateParts[2]));
  if (date.getUTCFullYear() !== dateParts[0] || date.getUTCMonth() !== dateParts[1] - 1 || date.getUTCDate() !== dateParts[2]) return null;
  const validTime = (time: string) => {
    const [hour, minute] = time.split(':').map(Number);
    return hour <= 23 && minute <= 59;
  };
  if (!validTime(next.startTime) || !validTime(next.endTime)) return null;
  if (next.startTime === next.endTime) return null;
  if (next.type === 'task' && next.endTime < next.startTime) return null;
  if (next.type === 'task' && next.location) return null;
  if (next.type === 'shift' && next.completed) return null;
  return next;
}
