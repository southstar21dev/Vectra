import { describe, expect, it, vi } from 'vitest';

// decodeRow decrypts the payload; the real decryptField issues a fetch to the
// crypto proxy. Strip the `vc2:` marker locally so the mapping is tested
// without touching the network.
vi.mock('@/lib/encryption', () => ({
  isCiphertext: (value: unknown) => typeof value === 'string' && /^vc[23]:/.test(value),
  encryptField: async (value: string | null | undefined) => value,
  decryptField: async (value: string | null | undefined) =>
    typeof value === 'string' && value.startsWith('vc2:') ? value.slice(4) : value,
}));

import { createEmptyScheduleItemsState, enqueueScheduleItemUpsert, type StoredScheduleItem } from './schedule-items-store';
import { decodeRow, reconcileQueuedItemConflicts } from './use-schedule-items';
import type { ScheduleEvent } from './use-schedule';
import type { ScheduleItemsResponse } from '@/types/pocketbase-types';

const item: StoredScheduleItem = {
  id: '11111111-1111-4111-8111-111111111111',
  type: 'shift',
  title: 'Work',
  date: '2026-09-07',
  startTime: '09:00',
  endTime: '12:00',
  acknowledgedConflictKeys: [],
  updatedAt: null,
  pendingSync: true,
};

const event: ScheduleEvent = {
  id: '22222222-2222-4222-8222-222222222222',
  user_id: 'user-1',
  course_name: 'Seminar',
  course_code: 'CS101-G01',
  instructor: '',
  location: '',
  day_of_week: 1,
  start_time: '10:00',
  end_time: '11:00',
  color: '#000000',
  semester: '2026/27/1',
  semester_start: '2026-09-07',
  semester_end: '2026-12-14',
  event_type: 'class',
  attendance_type: null,
  csv_metadata: {},
  created_at: '',
  updated_at: '',
  source: 'neptun',
};

describe('schedule item reconnect conflict revalidation', () => {
  it('keeps a newly discovered overlap unacknowledged and pending upload', () => {
    const queued = enqueueScheduleItemUpsert(createEmptyScheduleItemsState(), item, null);
    const result = reconcileQueuedItemConflicts(queued, [event], '2026-09-07');
    expect(result.queue).toHaveLength(1);
    expect(result.queue[0].kind).toBe('upsert');
    if (result.queue[0].kind === 'upsert') {
      expect(result.queue[0].item.acknowledgedConflictKeys).toEqual([]);
    }
    expect(result.items[0].acknowledgedConflictKeys).toEqual([]);
  });

  it('does not change the queued item or acknowledgement keys when no conflict is found', () => {
    const acknowledged = { ...item, acknowledgedConflictKeys: ['prior-conflict'] };
    const queued = enqueueScheduleItemUpsert(createEmptyScheduleItemsState(), acknowledged, null);
    const result = reconcileQueuedItemConflicts(queued, [], '2026-09-07');
    expect(result).toEqual(queued);
  });
});

describe('schedule item row decoding (client_id ↔ app id)', () => {
  const cleartext = JSON.stringify({
    type: 'shift',
    title: 'Work',
    date: '2026-09-07',
    startTime: '09:00',
    endTime: '12:00',
    acknowledgedConflictKeys: [],
  });

  function row(overrides: Partial<ScheduleItemsResponse> = {}): ScheduleItemsResponse {
    return {
      id: 'pb15charrecord',
      client_id: '11111111-1111-4111-8111-111111111111',
      payload: `vc2:${cleartext}`,
      user: 'user-1',
      created_at: '2026-09-01 00:00:00.000Z',
      updated_at: '2026-09-02 00:00:00.000Z',
      collectionId: 'schedule_items',
      collectionName: 'schedule_items',
      ...overrides,
    } as ScheduleItemsResponse;
  }

  it('keys the decoded item on client_id, not the PocketBase record id', async () => {
    const decoded = await decodeRow(row());
    expect(decoded?.id).toBe('11111111-1111-4111-8111-111111111111');
    expect(decoded?.updatedAt).toBe('2026-09-02 00:00:00.000Z');
    expect(decoded?.pendingSync).toBe(false);
  });

  it('falls back to the PocketBase record id for legacy rows without a client_id', async () => {
    const legacyId = '33333333-3333-4333-8333-333333333333';
    const decoded = await decodeRow(row({ client_id: '', id: legacyId }));
    expect(decoded?.id).toBe(legacyId);
  });

  it('ignores rows whose payload is not an encrypted vc2 value', async () => {
    expect(await decodeRow(row({ payload: 'plaintext' }))).toBeNull();
  });
});
