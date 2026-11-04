import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query-keys';
import { decryptField, encryptField, isCiphertext } from '@/lib/encryption';
import { getSessionUserId, getStoredUserId } from '@/lib/auth-helpers';
import { useOnlineStatus } from '@/hooks/use-online-status';
import { nowInBudapest } from '@/lib/date-utils';
import { ClientResponseError } from 'pocketbase';
import { pb } from '@/lib/pocketbase';
import type { ScheduleItemsResponse } from '@/types/pocketbase-types';
import { fetchScheduleEventsFromCloud, type ScheduleEvent } from './use-schedule';
import {
  getScheduleConflicts,
  getItemIntervals,
  type ScheduleConflict,
  type ScheduleItem,
} from './schedule-conflicts';
import {
  createEmptyScheduleItemsState,
  enqueueScheduleItemDelete,
  enqueueScheduleItemUpsert,
  markScheduleItemSyncConflict,
  mergeScheduleItemsState,
  readScheduleItemsState,
  removeQueuedScheduleItemMutation,
  resolveScheduleItemSyncConflict,
  stripScheduleItemMetadata,
  validateScheduleItem,
  writeScheduleItemsState,
  type ScheduleItemsState,
  type StoredScheduleItem,
} from './schedule-items-store';

type ScheduleItemRow = ScheduleItemsResponse;

/** PocketBase wraps 404s in a ClientResponseError; a missing row is a conflict, not a failure. */
function isNotFoundError(error: unknown): boolean {
  return error instanceof ClientResponseError && error.status === 404;
}

export class ScheduleItemConflictError extends Error {
  readonly conflicts: ScheduleConflict[];

  constructor(conflicts: ScheduleConflict[]) {
    super('Acknowledge the current schedule conflicts before saving.');
    this.name = 'ScheduleItemConflictError';
    this.conflicts = conflicts;
  }
}

function getPayload(item: ScheduleItem): Omit<ScheduleItem, 'id'> {
  const { id: _id, ...payload } = stripScheduleItemMetadata(item as StoredScheduleItem);
  return payload;
}

export async function decodeRow(row: ScheduleItemRow): Promise<StoredScheduleItem | null> {
  if (!isCiphertext(row.payload)) return null;

  // The app item id is the client-generated UUID the offline queue keys on;
  // PocketBase's 15-char record id only exists as a transport handle, so fall
  // back to it solely for legacy rows that predate the client_id column.
  const appId = row.client_id || row.id;

  const cleartext = await decryptField(row.payload);
  if (typeof cleartext !== 'string' || isCiphertext(cleartext)) {
    throw new Error(`Unable to decrypt schedule item ${appId}.`);
  }

  try {
    const payload = JSON.parse(cleartext) as Record<string, unknown>;
    const item = validateScheduleItem({
      ...payload,
      id: appId,
      acknowledgedConflictKeys: Array.isArray(payload.acknowledgedConflictKeys)
        ? payload.acknowledgedConflictKeys.filter((key): key is string => typeof key === 'string')
        : [],
    } as ScheduleItem);
    if (!item) return null;
    return { ...item, updatedAt: row.updated_at, pendingSync: false };
  } catch (error) {
    console.warn(`Skipping invalid schedule item ${appId}:`, error);
    return null;
  }
}

async function fetchCloudItems(userId: string): Promise<StoredScheduleItem[]> {
  const rows = await pb.collection<ScheduleItemRow>('schedule_items').getFullList({
    filter: pb.filter('user = {:u}', { u: userId }),
  });
  const decoded = await Promise.all(rows.map(decodeRow));
  return decoded.filter((item): item is StoredScheduleItem => item !== null);
}

function saveLocalState(userId: string, state: ScheduleItemsState): void {
  if (!writeScheduleItemsState(state, userId)) {
    throw new Error('Unable to save this change on this device. Check available storage and try again.');
  }
}

function getConflictsForItem(
  item: ScheduleItem,
  otherItems: ScheduleItem[],
  events: ScheduleEvent[],
  referenceDate = nowInBudapest().ymd
): ScheduleConflict[] {
  const intervals = getItemIntervals(item);
  const dates = Array.from(new Set(intervals.map((interval) => interval.date)));
  const conflicts = getScheduleConflicts(
    [...otherItems.filter((other) => other.id !== item.id), item],
    events,
    referenceDate,
    dates
  ).filter((conflict) => conflict.itemId === item.id || conflict.otherItemId === item.id);
  const acknowledgedKeys = new Set(item.acknowledgedConflictKeys);
  return conflicts.map((conflict) => ({
    ...conflict,
    needsAcknowledgment: conflict.needsAcknowledgment && !acknowledgedKeys.has(conflict.key),
  }));
}

function throwForUnacknowledgedConflicts(conflicts: ScheduleConflict[], acknowledgedKeys: readonly string[]): void {
  const acknowledged = new Set(acknowledgedKeys);
  const unacknowledged = conflicts.filter(
    (conflict) => conflict.needsAcknowledgment && !acknowledged.has(conflict.key)
  );
  if (unacknowledged.length > 0) throw new ScheduleItemConflictError(unacknowledged);
}

function sameQueuedMutation(a: ScheduleItemsState['queue'][number] | undefined, b: ScheduleItemsState['queue'][number]): boolean {
  return !!a && JSON.stringify(a) === JSON.stringify(b);
}

function sameItemPayload(a: ScheduleItem, b: ScheduleItem): boolean {
  return JSON.stringify(getPayload(a)) === JSON.stringify(getPayload(b));
}

export function reconcileQueuedItemConflicts(
  state: ScheduleItemsState,
  events: ScheduleEvent[],
  referenceDate = nowInBudapest().ymd
): ScheduleItemsState {
  // Never manufacture an acknowledgment during background reconciliation.
  // Recalculate to validate every queued interval; current unacknowledged keys
  // stay absent from the stored item and remain visible to the editor.
  for (const mutation of state.queue) {
    if (mutation.kind === 'upsert') {
      getConflictsForItem(
        mutation.item,
        state.items.map(stripScheduleItemMetadata),
        events,
        referenceDate
      );
    }
  }
  return state;
}

export function useScheduleItems(events: ScheduleEvent[] = []) {
  const queryClient = useQueryClient();
  const online = useOnlineStatus();
  const [userId, setUserId] = useState(() => getStoredUserId());
  const authUpdateVersion = useRef(0);
  const queryKey = queryKeys.scheduleItems(userId);
  const [syncError, setSyncError] = useState<string | null>(null);
  const [syncRetryCount, setSyncRetryCount] = useState(0);
  const syncPromise = useRef<Promise<void> | null>(null);
  const syncRef = useRef<() => Promise<void>>(async () => undefined);

  useEffect(() => {
    const unsubscribe = pb.authStore.onChange((_token, record) => {
      authUpdateVersion.current += 1;
      setUserId(record?.id ?? null);
    });
    const version = authUpdateVersion.current;
    const activeUserId = getSessionUserId();
    if (version === authUpdateVersion.current) setUserId(activeUserId);
    return () => unsubscribe();
  }, []);

  const { data: state = createEmptyScheduleItemsState(), isLoading } = useQuery({
    queryKey,
    queryFn: async (): Promise<ScheduleItemsState> => {
      const activeUserId = getSessionUserId();
      if (!activeUserId || activeUserId !== userId) return createEmptyScheduleItemsState();
      const local = readScheduleItemsState(activeUserId);
      if (!online) return local;
      try {
        const [cloud, latestEvents] = await Promise.all([
          fetchCloudItems(activeUserId),
          fetchScheduleEventsFromCloud(activeUserId),
        ]);
        queryClient.setQueryData(['schedule'], latestEvents);
        const merged = mergeScheduleItemsState(local, cloud);
        const revalidated = reconcileQueuedItemConflicts(merged, latestEvents);
        if (!writeScheduleItemsState(revalidated, activeUserId)) {
          console.warn('Unable to refresh the local schedule-item cache; preserving the in-memory result.');
        }
        return revalidated;
      } catch (error) {
        console.warn('Failed to load schedule items; showing the local cache:', error);
        return local;
      }
    },
    placeholderData: () => userId ? readScheduleItemsState(userId) : createEmptyScheduleItemsState(),
    staleTime: 60_000,
  });

  const items = state.items;
  const conflicts = useMemo(
    () => getScheduleConflicts(items, events, nowInBudapest().ymd),
    [items, events]
  );

  const persistLocalState = useCallback((next: ScheduleItemsState) => {
    if (!userId) throw new Error('Sign in to save schedule items.');
    saveLocalState(userId, next);
    queryClient.setQueryData<ScheduleItemsState>(queryKey, next);
    setSyncError(null);
    if (typeof window !== 'undefined') window.dispatchEvent(new Event('vectra_schedule_items_updated'));
  }, [queryClient, queryKey, userId]);

  const executeSync = useCallback(async () => {
    if (!online || !userId || getSessionUserId() !== userId) return;
    if (syncPromise.current) return syncPromise.current;
    let hasNewerMutationToSync = false;

    const operation = (async () => {
      let current = readScheduleItemsState(userId);
      if (current.queue.length === 0) {
        setSyncError(null);
        setSyncRetryCount(0);
        return;
      }

      try {
        // Refresh classes before replaying offline writes so newly imported classes
        // cannot silently invalidate an old offline acknowledgment.
        await queryClient.invalidateQueries({ queryKey: queryKeys.schedule, exact: true });
        const latestEvents = await fetchScheduleEventsFromCloud(userId);
        if (getSessionUserId() !== userId) return;
        queryClient.setQueryData(['schedule'], latestEvents);

        const cloudItems = await fetchCloudItems(userId);
        if (getSessionUserId() !== userId) return;
        current = mergeScheduleItemsState(current, cloudItems);
        saveLocalState(userId, current);
        queryClient.setQueryData<ScheduleItemsState>(queryKey, current);

        for (const queued of [...current.queue]) {
          const latestState = readScheduleItemsState(userId);
          const latestQueued = latestState.queue.find((entry) => entry.id === queued.id);
          if (!sameQueuedMutation(latestQueued, queued)) continue;

          const serverItem = cloudItems.find((item) => item.id === queued.id) || null;
          // A create can reach the server just before the browser closes, leaving
          // its local queue entry behind. Treat the identical row as an already
          // completed idempotent create; different content is a real conflict.
          if (queued.kind === 'upsert' && queued.baseUpdatedAt === null && serverItem) {
            if (sameItemPayload(serverItem, queued.item)) {
              const next = removeQueuedScheduleItemMutation(latestState, queued.id, serverItem);
              saveLocalState(userId, next);
              queryClient.setQueryData<ScheduleItemsState>(queryKey, next);
              continue;
            }
            const conflicted = markScheduleItemSyncConflict(latestState, queued.id, serverItem, serverItem.updatedAt);
            saveLocalState(userId, conflicted);
            queryClient.setQueryData<ScheduleItemsState>(queryKey, conflicted);
            continue;
          }

          const expectedVersion = queued.baseUpdatedAt;
          const versionMatches = expectedVersion === null
            ? serverItem === null
            : serverItem?.updatedAt === expectedVersion;

          if (!versionMatches && queued.kind === 'delete' && serverItem === null) {
            const next = removeQueuedScheduleItemMutation(latestState, queued.id);
            saveLocalState(userId, next);
            queryClient.setQueryData<ScheduleItemsState>(queryKey, next);
            continue;
          }

          if (!versionMatches) {
            const conflicted = markScheduleItemSyncConflict(latestState, queued.id, serverItem, serverItem?.updatedAt ?? null);
            saveLocalState(userId, conflicted);
            queryClient.setQueryData<ScheduleItemsState>(queryKey, conflicted);
            continue;
          }

          if (queued.kind === 'upsert') {
            const itemConflicts = getConflictsForItem(
              queued.item,
              latestState.items.map(stripScheduleItemMetadata),
              latestEvents
            );
            if (itemConflicts.some((conflict) => conflict.needsAcknowledgment)) {
              continue;
            }

            const encryptedPayload = await encryptField(JSON.stringify(getPayload(queued.item)));
            if (!isCiphertext(encryptedPayload)) {
              throw new Error('Schedule item encryption did not produce an encrypted payload.');
            }

            let savedRow: ScheduleItemRow | null = null;
            if (queued.baseUpdatedAt === null) {
              // Send the client UUID as client_id, never as the PocketBase
              // record id: PB ids are 15 chars matching ^[a-z0-9]+$ and would
              // reject a UUID, so the app id lives in its own column.
              savedRow = await pb.collection<ScheduleItemRow>('schedule_items').create({
                user: userId,
                payload: encryptedPayload,
                client_id: queued.id,
              });
            } else {
              // PocketBase has no conditional write, so read the row and compare
              // the version ourselves: a mismatch (or a row deleted underneath
              // us) leaves savedRow null, exactly like the old 0-row update.
              // The row is resolved by client_id because PB cannot filter by the
              // app's UUID through the record id.
              let currentRow: ScheduleItemRow | null = null;
              try {
                currentRow = await pb.collection<ScheduleItemRow>('schedule_items').getFirstListItem(
                  pb.filter('user = {:u} && client_id = {:c}', { u: userId, c: queued.id })
                );
              } catch (error) {
                if (!isNotFoundError(error)) throw error;
              }
              if (currentRow && currentRow.updated_at === queued.baseUpdatedAt) {
                savedRow = await pb.collection<ScheduleItemRow>('schedule_items').update(currentRow.id, {
                  payload: encryptedPayload,
                });
              }
            }

            if (!savedRow) {
              const refreshed = await fetchCloudItems(userId);
              const latestServer = refreshed.find((item) => item.id === queued.id) || null;
              const conflicted = markScheduleItemSyncConflict(latestState, queued.id, latestServer, latestServer?.updatedAt ?? null);
              saveLocalState(userId, conflicted);
              queryClient.setQueryData<ScheduleItemsState>(queryKey, conflicted);
              continue;
            }

          const savedItem = await decodeRow(savedRow) || {
            ...queued.item,
            updatedAt: savedRow.updated_at,
            pendingSync: false,
            syncConflict: undefined,
          };
          const newestLocal = readScheduleItemsState(userId);
          const newestMutation = newestLocal.queue.find((entry) => entry.id === queued.id);
          if (sameQueuedMutation(newestMutation, queued)) {
            const next = removeQueuedScheduleItemMutation(newestLocal, queued.id, savedItem);
            saveLocalState(userId, next);
            queryClient.setQueryData<ScheduleItemsState>(queryKey, next);
          } else if (newestMutation?.kind === 'upsert') {
            // If another tab edited while this write was in flight, its pending
            // payload is now based on the version we just committed.
            const rebasedItem = { ...newestMutation.item, updatedAt: savedRow.updated_at, pendingSync: true };
            const rebasedMutation = { ...newestMutation, item: rebasedItem, baseUpdatedAt: savedRow.updated_at };
            const next: ScheduleItemsState = {
              ...newestLocal,
              items: newestLocal.items.map((item) => item.id === queued.id ? rebasedItem : item),
              queue: newestLocal.queue.map((entry) => entry.id === queued.id ? rebasedMutation : entry),
            };
            saveLocalState(userId, next);
            queryClient.setQueryData<ScheduleItemsState>(queryKey, next);
            hasNewerMutationToSync = true;
          } else if (newestMutation?.kind === 'delete') {
            const rebasedMutation = { ...newestMutation, baseUpdatedAt: savedRow.updated_at };
            const next: ScheduleItemsState = {
              ...newestLocal,
              queue: newestLocal.queue.map((entry) => entry.id === queued.id ? rebasedMutation : entry),
            };
            saveLocalState(userId, next);
            queryClient.setQueryData<ScheduleItemsState>(queryKey, next);
            hasNewerMutationToSync = true;
          } else if (!newestLocal.items.some((item) => item.id === queued.id)) {
            // A queued create may have been cancelled locally while its request
            // was in flight. Delete the newly committed row on the next pass.
            const next = enqueueScheduleItemDelete(newestLocal, queued.id, savedItem.updatedAt);
            saveLocalState(userId, next);
            queryClient.setQueryData<ScheduleItemsState>(queryKey, next);
            hasNewerMutationToSync = true;
          } else {
            const next = removeQueuedScheduleItemMutation(newestLocal, queued.id, savedItem);
            saveLocalState(userId, next);
            queryClient.setQueryData<ScheduleItemsState>(queryKey, next);
          }
          const index = cloudItems.findIndex((item) => item.id === queued.id);
          if (index >= 0) cloudItems[index] = savedItem;
          else cloudItems.push(savedItem);
          } else {
            // Mirror the conditional delete: read first and only delete when the
            // version still matches, so a concurrent edit surfaces as a conflict.
            let deleted = false;
            let currentRow: ScheduleItemRow | null = null;
            try {
              currentRow = await pb.collection<ScheduleItemRow>('schedule_items').getFirstListItem(
                pb.filter('user = {:u} && client_id = {:c}', { u: userId, c: queued.id })
              );
            } catch (error) {
              if (!isNotFoundError(error)) throw error;
            }
            if (currentRow && currentRow.updated_at === queued.baseUpdatedAt) {
              await pb.collection('schedule_items').delete(currentRow.id);
              deleted = true;
            }
            if (!deleted) {
              const refreshed = await fetchCloudItems(userId);
              const latestServer = refreshed.find((item) => item.id === queued.id) || null;
              const conflicted = markScheduleItemSyncConflict(readScheduleItemsState(userId), queued.id, latestServer, latestServer?.updatedAt ?? null);
              saveLocalState(userId, conflicted);
              queryClient.setQueryData<ScheduleItemsState>(queryKey, conflicted);
              continue;
            }
            const newestLocal = readScheduleItemsState(userId);
            if (sameQueuedMutation(newestLocal.queue.find((entry) => entry.id === queued.id), queued)) {
              const next = removeQueuedScheduleItemMutation(newestLocal, queued.id);
              saveLocalState(userId, next);
              queryClient.setQueryData<ScheduleItemsState>(queryKey, next);
            }
          }
        }
        // The refresh and queue pass succeeded even if an item is intentionally
        // paused for user acknowledgment; only transient failures should retry.
        setSyncError(null);
        setSyncRetryCount(0);
      } catch (error) {
        const message = error instanceof Error ? error.message : 'Schedule changes are waiting to sync.';
        setSyncError(message);
        setSyncRetryCount((count) => count + 1);
        console.warn('Schedule item sync deferred:', error);
      }
    })().finally(() => {
      syncPromise.current = null;
      if (hasNewerMutationToSync) queueMicrotask(() => { void syncRef.current(); });
    });

    syncPromise.current = operation;
    return operation;
  }, [online, queryClient, queryKey, userId]);

  useEffect(() => {
    syncRef.current = executeSync;
  }, [executeSync]);

  const saveMutation = useMutation({
    mutationFn: async (candidate: ScheduleItem) => {
      if (!userId) throw new Error('Sign in to save schedule items.');
      const valid = validateScheduleItem(candidate);
      if (!valid) throw new Error('Enter a title, valid date, and a start/end time. Tasks cannot run overnight.');
      let current = readScheduleItemsState(userId);
      let validationEvents = events;
      let validationItems = current.items;
      if (online) {
        try {
          const [latestEvents, cloudItems] = await Promise.all([
            fetchScheduleEventsFromCloud(userId),
            fetchCloudItems(userId),
          ]);
          validationEvents = latestEvents;
          queryClient.setQueryData(['schedule'], latestEvents);
          current = mergeScheduleItemsState(current, cloudItems);
          validationItems = current.items;
          saveLocalState(userId, current);
          queryClient.setQueryData<ScheduleItemsState>(queryKey, current);
        } catch {
          throw new Error('Could not check your latest schedule. Please try again or save while offline.');
        }
      }
      const currentItem = validationItems.find((item) => item.id === valid.id);
      const acknowledgedKeys = valid.acknowledgedConflictKeys;
      const itemConflicts = getConflictsForItem(valid, validationItems.map(stripScheduleItemMetadata), validationEvents);
      throwForUnacknowledgedConflicts(itemConflicts, acknowledgedKeys);
      const stored: StoredScheduleItem = {
        ...valid,
        acknowledgedConflictKeys: acknowledgedKeys,
        updatedAt: currentItem?.updatedAt ?? null,
        pendingSync: true,
      };
      const next = enqueueScheduleItemUpsert(current, stored, currentItem?.updatedAt ?? null);
      persistLocalState(next);
      await executeSync();
      return stored;
    },
  });

  const deleteMutation = useMutation({
    mutationFn: async (id: string) => {
      if (!userId) throw new Error('Sign in to save schedule items.');
      const current = readScheduleItemsState(userId);
      const item = current.items.find((entry) => entry.id === id);
      const next = enqueueScheduleItemDelete(current, id, item?.updatedAt ?? null);
      persistLocalState(next);
      await executeSync();
    },
  });

  const toggleTaskMutation = useMutation({
    mutationFn: async (item: ScheduleItem) => {
      if (item.type !== 'task') return item;
      const completed = !item.completed;
      return saveMutation.mutateAsync({
        ...item,
        completed,
        ...(completed ? {} : { acknowledgedConflictKeys: [] }),
      });
    },
  });

  useEffect(() => {
    if (online && state.queue.length > 0 && !syncError) void syncRef.current();
  }, [online, userId, state.queue.length, syncError]);

  useEffect(() => {
    if (!online || state.queue.length === 0 || !syncError) return;
    const delay = Math.min(60_000, 2_000 * 2 ** Math.min(syncRetryCount, 5));
    const timeout = window.setTimeout(() => void syncRef.current(), delay);
    return () => window.clearTimeout(timeout);
  }, [online, state.queue.length, syncError, syncRetryCount]);

  useEffect(() => {
    if (typeof window === 'undefined') return;
    const refreshAndSync = () => {
      void queryClient.invalidateQueries({ queryKey });
      if (online) void syncRef.current();
    };
    window.addEventListener('online', refreshAndSync);
    window.addEventListener('vectra_neptun_sync_completed', refreshAndSync);
    window.addEventListener('vectra_schedule_items_updated', refreshAndSync);
    return () => {
      window.removeEventListener('online', refreshAndSync);
      window.removeEventListener('vectra_neptun_sync_completed', refreshAndSync);
      window.removeEventListener('vectra_schedule_items_updated', refreshAndSync);
    };
  }, [online, queryClient, queryKey]);

  const conflictsByItemId = useMemo(() => {
    const grouped = new Map<string, ScheduleConflict[]>();
    for (const conflict of conflicts) {
      for (const id of [conflict.itemId, conflict.otherItemId].filter((value): value is string => !!value)) {
        grouped.set(id, [...(grouped.get(id) || []), conflict]);
        const dateKey = `${conflict.date}:${id}`;
        grouped.set(dateKey, [...(grouped.get(dateKey) || []), conflict]);
      }
    }
    return grouped;
  }, [conflicts]);

  const conflictsByEventId = useMemo(() => {
    const grouped = new Map<string, ScheduleConflict[]>();
    for (const conflict of conflicts) {
      if (!conflict.classId) continue;
      grouped.set(conflict.classId, [...(grouped.get(conflict.classId) || []), conflict]);
      const dateKey = `${conflict.date}:${conflict.classId}`;
      grouped.set(dateKey, [...(grouped.get(dateKey) || []), conflict]);
    }
    return grouped;
  }, [conflicts]);

  return {
    items,
    conflicts,
    conflictsByItemId,
    conflictsByEventId,
    needsAcknowledgmentIds: new Set(conflicts.filter((conflict) => conflict.needsAcknowledgment).flatMap((conflict) => [conflict.itemId, conflict.otherItemId].filter((id): id is string => !!id))),
    syncConflictIds: new Set(items.filter((item) => !!item.syncConflict).map((item) => item.id)),
    isLoading,
    isOffline: !online,
    syncStatus: state.queue.length > 0 ? 'pending' as const : 'synced' as const,
    syncError,
    resolveSyncConflict: (id: string, resolution: 'server' | 'local') => {
      const current = readScheduleItemsState(userId);
      const next = resolveScheduleItemSyncConflict(current, id, resolution);
      persistLocalState(next);
      if (online) void executeSync();
    },
    createItem: saveMutation.mutateAsync,
    updateItem: saveMutation.mutateAsync,
    deleteItem: deleteMutation.mutateAsync,
    toggleTaskCompleted: toggleTaskMutation.mutateAsync,
    isSaving: saveMutation.isPending || deleteMutation.isPending,
  };
}

