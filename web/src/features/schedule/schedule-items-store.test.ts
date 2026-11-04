import { describe, expect, it } from 'vitest';
import {
  createEmptyScheduleItemsState,
  enqueueScheduleItemDelete,
  enqueueScheduleItemUpsert,
  getScheduleItemsStorageKey,
  markScheduleItemSyncConflict,
  mergeScheduleItemsState,
  parseScheduleItemsState,
  readScheduleItemsState,
  removeQueuedScheduleItemMutation,
  writeScheduleItemsState,
  type ScheduleItemsState,
  type StoredScheduleItem,
} from './schedule-items-store';
import type { StorageLike } from '@/lib/storage';

function item(overrides: Partial<StoredScheduleItem> = {}): StoredScheduleItem {
  return {
    id: '11111111-1111-4111-8111-111111111111',
    type: 'shift',
    title: 'Work',
    date: '2026-09-07',
    startTime: '09:00',
    endTime: '12:00',
    acknowledgedConflictKeys: [],
    updatedAt: null,
    ...overrides,
  };
}

function memoryStorage(): StorageLike & { values: Map<string, string> } {
  const values = new Map<string, string>();
  return {
    values,
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: (key) => { values.delete(key); },
  };
}

describe('schedule item local cache', () => {
  it('keeps state strictly scoped to the requested account', () => {
    const storage = memoryStorage();
    const alice: ScheduleItemsState = { ...createEmptyScheduleItemsState(), items: [item()] };
    const bob: ScheduleItemsState = { ...createEmptyScheduleItemsState(), items: [item({ id: '22222222-2222-4222-8222-222222222222', title: 'Other' })] };

    expect(writeScheduleItemsState(alice, 'alice', storage)).toBe(true);
    expect(writeScheduleItemsState(bob, 'bob', storage)).toBe(true);
    expect(readScheduleItemsState('alice', storage).items[0].title).toBe('Work');
    expect(readScheduleItemsState('bob', storage).items[0].title).toBe('Other');
    expect(getScheduleItemsStorageKey('alice')).not.toBe(getScheduleItemsStorageKey('bob'));
  });

  it('rejects malformed cached JSON and reports storage quota errors', () => {
    const storage = memoryStorage();
    storage.values.set(getScheduleItemsStorageKey('alice'), '{bad-json');
    expect(readScheduleItemsState('alice', storage)).toEqual(createEmptyScheduleItemsState());
    const failingStorage: StorageLike = {
      getItem: () => null,
      setItem: () => { throw new Error('quota'); },
      removeItem: () => undefined,
    };
    expect(writeScheduleItemsState(createEmptyScheduleItemsState(), 'alice', failingStorage)).toBe(false);
    expect(parseScheduleItemsState('{bad-json')).toBeNull();
  });

  it('drops only the invalid entries from a partly corrupt cache', () => {
    const good = item();
    const raw = JSON.stringify({
      version: 1,
      items: [good, { id: 'not-a-uuid', title: 42 }],
      queue: [
        { kind: 'upsert', id: good.id, item: good, baseUpdatedAt: null },
        { kind: 'mystery', id: good.id },
      ],
    });
    const parsed = parseScheduleItemsState(raw);
    expect(parsed?.items.map((entry) => entry.id)).toEqual([good.id]);
    expect(parsed?.queue).toHaveLength(1);
  });
});

describe('offline schedule item queue', () => {
  it('coalesces repeated creates and updates by item while retaining the original cloud version', () => {
    const first = item({ updatedAt: '2026-09-01T10:00:00.000Z' });
    const created: ScheduleItemsState = {
      version: 1,
      items: [first],
      queue: [{ kind: 'upsert', id: first.id, item: first, baseUpdatedAt: first.updatedAt }],
    };
    const edited = { ...first, title: 'Updated work', updatedAt: '2026-09-03T10:00:00.000Z' };
    const next = enqueueScheduleItemUpsert(created, edited, '2026-09-03T10:00:00.000Z');

    expect(next.queue).toHaveLength(1);
    expect(next.queue[0]).toMatchObject({ kind: 'upsert', baseUpdatedAt: '2026-09-01T10:00:00.000Z' });
    expect(next.items[0].title).toBe('Updated work');
    expect(next.items[0].pendingSync).toBe(true);
  });

  it('cancels an unsynced create deleted before it reaches the server', () => {
    const created = item();
    const state = enqueueScheduleItemUpsert(createEmptyScheduleItemsState(), created, null);
    const afterDelete = enqueueScheduleItemDelete(state, created.id);
    expect(afterDelete.items).toHaveLength(0);
    expect(afterDelete.queue).toHaveLength(0);
  });

  it('retains a queued server delete until confirmed and keeps the base version', () => {
    const serverItem = item({ updatedAt: '2026-09-01T10:00:00.000Z' });
    const state: ScheduleItemsState = { version: 1, items: [serverItem], queue: [] };
    const queuedDelete = enqueueScheduleItemDelete(state, serverItem.id);
    expect(queuedDelete.queue).toEqual([{ kind: 'delete', id: serverItem.id, baseUpdatedAt: serverItem.updatedAt }]);
    expect(removeQueuedScheduleItemMutation(queuedDelete, serverItem.id).queue).toHaveLength(0);
  });

  it('overlays pending changes over a refreshed cloud snapshot', () => {
    const base = item({ updatedAt: '2026-09-01T10:00:00.000Z' });
    const local = enqueueScheduleItemUpsert({ version: 1, items: [base], queue: [] }, { ...base, title: 'Offline edit' });
    const merged = mergeScheduleItemsState(local, [{ ...base, title: 'Cloud snapshot' }]);
    expect(merged.items[0].title).toBe('Offline edit');
    expect(merged.items[0].pendingSync).toBe(true);
    expect(merged.queue).toHaveLength(1);
  });

  it('marks a local item with the current server version when optimistic concurrency fails', () => {
    const localItem = item({ pendingSync: true });
    const state = enqueueScheduleItemUpsert(createEmptyScheduleItemsState(), localItem, null);
    const cloudItem = item({ title: 'Remote edit', updatedAt: '2026-09-04T10:00:00.000Z' });
    const conflicted = markScheduleItemSyncConflict(state, localItem.id, cloudItem, cloudItem.updatedAt);
    expect(conflicted.items[0].syncConflict).toEqual({
      serverItem: expect.objectContaining({ title: 'Remote edit' }),
      serverUpdatedAt: cloudItem.updatedAt,
    });
    expect(conflicted.queue).toHaveLength(1);
  });
});
