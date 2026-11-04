import { beforeEach, describe, expect, it, vi } from 'vitest';

const cloud = vi.hoisted(() => ({
  fetch: vi.fn(),
  upsert: vi.fn(),
}));

vi.mock('./course-tracker-cloud', () => ({
  fetchCourseTrackerData: cloud.fetch,
  upsertCourseTrackerData: cloud.upsert,
}));

vi.mock('@/lib/encryption', () => ({
  encryptField: async (value: string | null | undefined) => value,
  decryptField: async (value: string | null | undefined) => value,
  isCiphertext: (value: unknown) => typeof value === 'string' && value.startsWith('vc2:'),
}));

import { uploadTrackerData } from './course-tracker-upload';
import { markCloudReadable, resetCourseTrackerSyncForTests, takeHeldBackWrite } from './course-tracker-sync';
import { createDefaultTracker, parseTrackerData, serializeTrackerData, type CourseTrackerData } from './tracker-types';

function tracker(name: string, updatedAt: string) {
  const t = createDefaultTracker({ courseName: name, semester: '2026/27/1' });
  t.updatedAt = updatedAt;
  return t;
}

describe('uploadTrackerData', () => {
  beforeEach(() => {
    resetCourseTrackerSyncForTests();
    cloud.fetch.mockReset();
    cloud.upsert.mockReset();
    cloud.upsert.mockResolvedValue(undefined);
  });

  it('holds the write back until the cloud copy has been read', async () => {
    const result = await uploadTrackerData('u1', { version: 1, trackers: {} });
    expect(result).toBe('held-back');
    expect(cloud.upsert).not.toHaveBeenCalled();
    expect(takeHeldBackWrite('u1')).toBe(true);
  });

  it('merges the latest cloud copy (newest tracker wins) before uploading', async () => {
    markCloudReadable('u1');
    const remote: CourseTrackerData = {
      version: 1,
      trackers: {
        a: tracker('A remote newer', '2026-10-02T00:00:00.000Z'),
        b: tracker('B remote only', '2026-10-01T00:00:00.000Z'),
        gone: tracker('Dropped', '2026-10-01T00:00:00.000Z'),
      },
    };
    cloud.fetch.mockResolvedValue(serializeTrackerData(remote));
    const local: CourseTrackerData = {
      version: 1,
      trackers: {
        a: tracker('A local older', '2026-10-01T00:00:00.000Z'),
        c: tracker('C local only', '2026-10-01T00:00:00.000Z'),
      },
    };

    await uploadTrackerData('u1', local, { dropKeys: ['gone'] });

    const uploaded = parseTrackerData(cloud.upsert.mock.calls[0][1] as string)!;
    expect(Object.keys(uploaded.trackers).sort()).toEqual(['a', 'b', 'c']);
    expect(uploaded.trackers.a.courseName).toBe('A remote newer');
  });

  it('refuses to overwrite an unreadable cloud copy', async () => {
    markCloudReadable('u1');
    cloud.fetch.mockResolvedValue('vc2:undecryptable');
    await expect(uploadTrackerData('u1', { version: 1, trackers: {} })).rejects.toThrow('unreadable');
    expect(cloud.upsert).not.toHaveBeenCalled();
  });
});
