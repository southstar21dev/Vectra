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

import { syncTrackerDataWithCloud, trackerDataDiffers, uploadTrackerData } from './course-tracker-upload';
import {
  isCloudReadable,
  markCloudReadable,
  noteHeldBackWrite,
  resetCourseTrackerSyncForTests,
  takeHeldBackWrite,
} from './course-tracker-sync';
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

function doc(trackers: Record<string, ReturnType<typeof tracker>>): CourseTrackerData {
  return { version: 1, trackers };
}

function lastUpload(): CourseTrackerData {
  const calls = cloud.upsert.mock.calls;
  return parseTrackerData(calls[calls.length - 1][1] as string)!;
}

describe('collapsed uploads (05 #7)', () => {
  beforeEach(() => {
    resetCourseTrackerSyncForTests();
    cloud.fetch.mockReset();
    cloud.upsert.mockReset();
    cloud.fetch.mockResolvedValue(null);
    markCloudReadable('u1');
  });

  it('a plain save that supersedes a Neptun upload still drops what the sync removed', async () => {
    const legacy = tracker('Legacy key', '2026-10-01T00:00:00.000Z');
    const kept = tracker('Kept', '2026-10-02T00:00:00.000Z');
    cloud.fetch.mockResolvedValue(serializeTrackerData(doc({ legacy, kept })));
    let release!: () => void;
    cloud.upsert.mockImplementationOnce(() => new Promise<void>((resolve) => { release = resolve; }));
    cloud.upsert.mockResolvedValue(undefined);
    const inFlight = uploadTrackerData('u1', doc({ legacy, kept }));
    await new Promise((resolve) => setTimeout(resolve, 0));
    const neptun = uploadTrackerData('u1', doc({ kept }), { dropKeys: ['legacy'] });
    const edit = uploadTrackerData('u1', doc({ kept: tracker('Kept, edited', '2026-10-03T00:00:00.000Z') }));
    release();
    await Promise.all([inFlight, neptun, edit]);
    expect(cloud.upsert).toHaveBeenCalledTimes(2);
    expect(Object.keys(lastUpload().trackers)).toEqual(['kept']);
    expect(lastUpload().trackers.kept.courseName).toBe('Kept, edited');
  });

  it('never drops a tracker the newest snapshot holds', async () => {
    let release!: () => void;
    cloud.upsert.mockImplementationOnce(() => new Promise<void>((resolve) => { release = resolve; }));
    cloud.upsert.mockResolvedValue(undefined);
    const inFlight = uploadTrackerData('u1', doc({}));
    await new Promise((resolve) => setTimeout(resolve, 0));
    const neptun = uploadTrackerData('u1', doc({}), { dropKeys: ['exam'] });
    const recreated = uploadTrackerData('u1', doc({ exam: tracker('Exam Skills', '2026-10-03T00:00:00.000Z') }));
    release();
    await Promise.all([inFlight, neptun, recreated]);
    expect(Object.keys(lastUpload().trackers)).toEqual(['exam']);
  });
});

describe('syncTrackerDataWithCloud (05 #2)', () => {
  const saved: CourseTrackerData[] = [];
  const save = (data: CourseTrackerData) => { saved.push(data); };

  beforeEach(() => {
    resetCourseTrackerSyncForTests();
    saved.length = 0;
    cloud.fetch.mockReset();
    cloud.upsert.mockReset();
    cloud.upsert.mockResolvedValue(undefined);
  });

  it('uploads an edit the cloud does not have, with no held-back flag (a failed upload or a reload)', async () => {
    const stale = tracker('Calculus', '2026-10-01T00:00:00.000Z');
    const edited = tracker('Calculus', '2026-10-05T00:00:00.000Z');
    edited.courseKey = stale.courseKey;
    edited.id = stale.id;
    cloud.fetch.mockResolvedValue(serializeTrackerData(doc({ calc: stale })));
    const result = await syncTrackerDataWithCloud('u1', () => doc({ calc: edited }), save);
    expect(result.trackers.calc.updatedAt).toBe('2026-10-05T00:00:00.000Z');
    expect(cloud.upsert).toHaveBeenCalledTimes(1);
    expect(lastUpload().trackers.calc.updatedAt).toBe('2026-10-05T00:00:00.000Z');
    expect(saved).toHaveLength(1);
  });

  it('uploads nothing when the local copy matches the cloud', async () => {
    const same = doc({ calc: tracker('Calculus', '2026-10-01T00:00:00.000Z') });
    cloud.fetch.mockResolvedValue(serializeTrackerData(same));
    const local = parseTrackerData(serializeTrackerData(same))!;
    await syncTrackerDataWithCloud('u1', () => local, save);
    expect(cloud.upsert).not.toHaveBeenCalled();
    expect(isCloudReadable('u1')).toBe(true);
  });

  it('a held-back edit goes through the merging upload, keeping what another device added meanwhile', async () => {
    const mine = tracker('Mine', '2026-10-01T00:00:00.000Z');
    noteHeldBackWrite('u1');
    cloud.fetch
      .mockResolvedValueOnce(serializeTrackerData(doc({ mine })))
      .mockResolvedValueOnce(serializeTrackerData(doc({ mine, theirs: tracker('Theirs', '2026-10-02T00:00:00.000Z') })));
    await syncTrackerDataWithCloud('u1', () => doc({ mine }), save);
    expect(Object.keys(lastUpload().trackers).sort()).toEqual(['mine', 'theirs']);
    expect(takeHeldBackWrite('u1')).toBe(false);
  });

  it('seeds a missing cloud row through the merging upload', async () => {
    const mine = tracker('Mine', '2026-10-01T00:00:00.000Z');
    cloud.fetch
      .mockResolvedValueOnce(null)
      .mockResolvedValueOnce(serializeTrackerData(doc({ theirs: tracker('Theirs', '2026-10-02T00:00:00.000Z') })));
    await syncTrackerDataWithCloud('u1', () => doc({ mine }), save);
    expect(Object.keys(lastUpload().trackers).sort()).toEqual(['mine', 'theirs']);
    expect(saved).toHaveLength(0);
  });

  it('keeps the local copy and the gate when the read fails, and remembers a failed upload', async () => {
    const mine = doc({ mine: tracker('Mine', '2026-10-01T00:00:00.000Z') });
    cloud.fetch.mockRejectedValueOnce(new Error('offline'));
    expect(await syncTrackerDataWithCloud('u1', () => mine, save)).toBe(mine);
    expect(isCloudReadable('u1')).toBe(false);
    expect(cloud.upsert).not.toHaveBeenCalled();

    cloud.fetch.mockResolvedValue(serializeTrackerData(doc({})));
    cloud.upsert.mockRejectedValueOnce(new Error('offline'));
    await syncTrackerDataWithCloud('u1', () => mine, save);
    expect(takeHeldBackWrite('u1')).toBe(true);
  });

  it('compares content, not key order or parse defaults', () => {
    const t = tracker('Calculus', '2026-10-01T00:00:00.000Z');
    const reordered = Object.fromEntries(Object.entries(t).reverse()) as typeof t;
    expect(trackerDataDiffers(doc({ calc: reordered }), parseTrackerData(serializeTrackerData(doc({ calc: t })))!)).toBe(false);
    expect(trackerDataDiffers(doc({ calc: t }), doc({}))).toBe(true);
  });
});
