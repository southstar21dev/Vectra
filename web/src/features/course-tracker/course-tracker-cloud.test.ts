import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * `course_tracker_data` is a singleton row per user and PocketBase has no
 * upsert, so the cloud layer finds the row then creates or updates it. These
 * tests drive that find-then-write path directly against a stubbed client.
 */
const cloud = vi.hoisted(() => ({
  getFirstListItem: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
}));

vi.mock('@/lib/pocketbase', () => ({
  pb: {
    collection: vi.fn(() => ({
      getFirstListItem: cloud.getFirstListItem,
      create: cloud.create,
      update: cloud.update,
    })),
    filter: vi.fn((filter: string) => filter),
  },
}));

import { fetchCourseTrackerData, upsertCourseTrackerData } from './course-tracker-cloud';

beforeEach(() => {
  cloud.getFirstListItem.mockReset();
  cloud.create.mockReset();
  cloud.update.mockReset();
  cloud.create.mockResolvedValue({ id: 'row-1' });
  cloud.update.mockResolvedValue({ id: 'row-1' });
});

describe('fetchCourseTrackerData', () => {
  it('returns the encrypted blob when the user has a row', async () => {
    cloud.getFirstListItem.mockResolvedValue({ id: 'row-1', data: 'vc2:ciphertext' });
    await expect(fetchCourseTrackerData('user-1')).resolves.toBe('vc2:ciphertext');
  });

  it('returns null when the user has no row yet (404)', async () => {
    cloud.getFirstListItem.mockRejectedValue(Object.assign(new Error('404'), { status: 404 }));
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    await expect(fetchCourseTrackerData('user-1')).resolves.toBeNull();
    // A missing row is expected, not a warning.
    expect(warn).not.toHaveBeenCalled();
    warn.mockRestore();
  });

  it('rethrows a read failure so the caller keeps its offline cache', async () => {
    cloud.getFirstListItem.mockRejectedValue(new Error('network down'));
    await expect(fetchCourseTrackerData('user-1')).rejects.toThrow('network down');
  });
});

describe('upsertCourseTrackerData', () => {
  it('creates the singleton row when the user has none', async () => {
    cloud.getFirstListItem.mockRejectedValue(Object.assign(new Error('404'), { status: 404 }));

    await upsertCourseTrackerData('user-1', 'vc2:ciphertext');

    expect(cloud.create).toHaveBeenCalledTimes(1);
    expect(cloud.update).not.toHaveBeenCalled();
    const payload = cloud.create.mock.calls[0][0] as {
      user: string;
      data: string;
      updated_at: string;
    };
    expect(payload.user).toBe('user-1');
    expect(payload.data).toBe('vc2:ciphertext');
    expect(payload.updated_at).toBeTruthy();
  });

  it('updates the existing row instead of creating a second one', async () => {
    cloud.getFirstListItem.mockResolvedValue({ id: 'row-existing' });

    await upsertCourseTrackerData('user-1', 'vc2:next');

    expect(cloud.update).toHaveBeenCalledTimes(1);
    expect(cloud.update.mock.calls[0][0]).toBe('row-existing');
    expect(cloud.update.mock.calls[0][1]).toMatchObject({ user: 'user-1', data: 'vc2:next' });
    expect(cloud.create).not.toHaveBeenCalled();
  });

  it('rethrows a non-404 lookup failure instead of creating a duplicate', async () => {
    cloud.getFirstListItem.mockRejectedValue(new Error('network down'));

    await expect(upsertCourseTrackerData('user-1', 'vc2:x')).rejects.toThrow('network down');
    expect(cloud.create).not.toHaveBeenCalled();
    expect(cloud.update).not.toHaveBeenCalled();
  });

  it('adopts the existing row when create races with another writer', async () => {
    cloud.getFirstListItem
      .mockRejectedValueOnce(Object.assign(new Error('404'), { status: 404 })) // lookup: no row
      .mockResolvedValueOnce({ id: 'row-raced' }); // re-fetch after the failed create
    cloud.create.mockRejectedValueOnce(new Error('unique constraint'));

    await upsertCourseTrackerData('user-1', 'vc2:raced');

    expect(cloud.create).toHaveBeenCalledTimes(1);
    expect(cloud.update).toHaveBeenCalledTimes(1);
    expect(cloud.update.mock.calls[0][0]).toBe('row-raced');
  });
});
