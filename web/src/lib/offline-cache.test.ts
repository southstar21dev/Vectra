import { describe, expect, it } from 'vitest';
import {
  createOfflineCache,
  clearAllOfflineUserData,
  KEPT_QUEUE_OWNER_KEY,
  settleKeptOfflineQueue,
} from './offline-cache';

function makeStorage(initial: Record<string, string> = {}): Storage {
  const map = new Map(Object.entries(initial));
  return {
    get length() {
      return map.size;
    },
    key: (i: number) => [...map.keys()][i] ?? null,
    getItem: (k: string) => map.get(k) ?? null,
    setItem: (k: string, v: string) => {
      map.set(k, v);
    },
    removeItem: (k: string) => {
      map.delete(k);
    },
    clear: () => {
      map.clear();
    },
  } as unknown as Storage;
}

describe('createOfflineCache', () => {
  it('isolates cache entries strictly by userId', () => {
    const storage = makeStorage();
    const cache = createOfflineCache<{ score: number }>('test_cache');

    // Mock window.localStorage for this test
    const origWindow = globalThis.window;
    // @ts-ignore
    globalThis.window = { localStorage: storage };

    try {
      cache.write({ score: 100 }, 'user_alice');
      cache.write({ score: 42 }, 'user_bob');

      expect(cache.read('user_alice')).toEqual({ score: 100 });
      expect(cache.read('user_bob')).toEqual({ score: 42 });

      // An unauthenticated request or querying with an unknown user does NOT leak alice's or bob's data
      expect(cache.read(null)).toBeNull();
      expect(cache.read('user_charlie')).toBeNull();
    } finally {
      globalThis.window = origWindow;
    }
  });

  it('does not bleed data into an un-scoped fallback key', () => {
    const storage = makeStorage();
    const cache = createOfflineCache<string[]>('schedule_cache');

    const origWindow = globalThis.window;
    // @ts-ignore
    globalThis.window = { localStorage: storage };

    try {
      cache.write(['Math', 'Physics'], 'user_1');

      // Check raw storage: key is 'schedule_cache_user_1'
      expect(storage.getItem('schedule_cache_user_1')).toBe(JSON.stringify(['Math', 'Physics']));
      // No un-scoped 'schedule_cache' key exists
      expect(storage.getItem('schedule_cache')).toBeNull();

      // Read with different or null userId returns null
      expect(cache.read(null)).toBeNull();
      expect(cache.read('user_2')).toBeNull();
    } finally {
      globalThis.window = origWindow;
    }
  });

  it('clears all offline user data when clearAllOfflineUserData is invoked', () => {
    const storage = makeStorage({
      vectra_schedule_cache_events_user1: '[{"id":"1"}]',
      vectra_schedule_items_user1: '{"items":[],"queue":[]}',
      vectra_transit_trackers_cache_user1: '[]',
      vectra_course_tracker_cache_user1: '{}',
      vectra_neptun_sync_error: 'Sync failed',
      vectra_neptun_sync_timestamps: '[123]',
      'vectra.weather.snapshot': '{"coords":{"latitude":47.5,"longitude":19.04}}',
      'vectra.deviceLocation': '{"lat":47.5,"lon":19.04}',
      'vectra.appearance': '{"theme":"dark"}', // unrelated preference should remain
    });

    clearAllOfflineUserData(storage);

    expect(storage.getItem('vectra_schedule_cache_events_user1')).toBeNull();
    expect(storage.getItem('vectra_schedule_items_user1')).toBeNull();
    expect(storage.getItem('vectra_transit_trackers_cache_user1')).toBeNull();
    expect(storage.getItem('vectra_course_tracker_cache_user1')).toBeNull();
    expect(storage.getItem('vectra_neptun_sync_error')).toBeNull();
    expect(storage.getItem('vectra_neptun_sync_timestamps')).toBeNull();
    expect(storage.getItem('vectra.weather.snapshot')).toBeNull();
    expect(storage.getItem('vectra.deviceLocation')).toBeNull();
    expect(storage.getItem('vectra.appearance')).toBe('{"theme":"dark"}');
  });

  it('keeps one account\'s offline queue on a forced sign-out', () => {
    const storage = makeStorage({
      vectra_schedule_items_user1: '{"queue":[1]}',
      vectra_course_tracker_cache_user1: '{"trackers":{}}',
      vectra_schedule_items_user2: '{"queue":[2]}',
      vectra_schedule_cache_events_user1: '[]',
      vectra_neptun_sync_error: 'x',
    });

    clearAllOfflineUserData(storage, 'user1');

    expect(storage.getItem('vectra_schedule_items_user1')).toBe('{"queue":[1]}');
    expect(storage.getItem('vectra_course_tracker_cache_user1')).toBe('{"trackers":{}}');
    expect(storage.getItem('vectra_schedule_items_user2')).toBeNull();
    expect(storage.getItem('vectra_schedule_cache_events_user1')).toBeNull();
    expect(storage.getItem('vectra_neptun_sync_error')).toBeNull();
    expect(storage.getItem(KEPT_QUEUE_OWNER_KEY)).toBe('user1');

    // An explicit sign-out later wipes the kept queue and its marker.
    clearAllOfflineUserData(storage);
    expect(storage.getItem('vectra_schedule_items_user1')).toBeNull();
    expect(storage.getItem(KEPT_QUEUE_OWNER_KEY)).toBeNull();
  });

  it('replays a kept queue for its own account and drops it for another', () => {
    const kept = () => makeStorage({
      vectra_schedule_items_user1: '{"queue":[1]}',
      vectra_course_tracker_cache_user1: '{}',
      [KEPT_QUEUE_OWNER_KEY]: 'user1',
    });

    const same = kept();
    settleKeptOfflineQueue('user1', same);
    expect(same.getItem('vectra_schedule_items_user1')).toBe('{"queue":[1]}');
    expect(same.getItem(KEPT_QUEUE_OWNER_KEY)).toBeNull();

    const other = kept();
    settleKeptOfflineQueue('user2', other);
    expect(other.getItem('vectra_schedule_items_user1')).toBeNull();
    expect(other.getItem('vectra_course_tracker_cache_user1')).toBeNull();
    expect(other.getItem(KEPT_QUEUE_OWNER_KEY)).toBeNull();
  });
});
