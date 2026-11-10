import { beforeEach, describe, expect, it } from 'vitest';
import {
  enqueueCloudSave,
  isCloudReadable,
  markCloudReadable,
  noteHeldBackWrite,
  resetCourseTrackerSyncForTests,
  takeHeldBackWrite,
} from './course-tracker-sync';

describe('course tracker cloud write gate', () => {
  beforeEach(() => resetCourseTrackerSyncForTests());

  it('stays closed until a cloud read succeeds for that user', () => {
    expect(isCloudReadable('u1')).toBe(false);
    markCloudReadable('u1');
    expect(isCloudReadable('u1')).toBe(true);
    expect(isCloudReadable('u2')).toBe(false);
  });

  it('reports a held-back write once', () => {
    noteHeldBackWrite('u1');
    expect(takeHeldBackWrite('u1')).toBe(true);
    expect(takeHeldBackWrite('u1')).toBe(false);
  });

  it('serialises uploads and collapses queued ones to the newest', async () => {
    const ran: string[] = [];
    let release!: () => void;
    const first = enqueueCloudSave('u1', () => new Promise<void>((resolve) => {
      ran.push('first');
      release = resolve;
    }));
    // Let the first upload start before queueing more behind it.
    await new Promise((resolve) => setTimeout(resolve, 0));
    const second = enqueueCloudSave('u1', async () => { ran.push('second'); });
    const third = enqueueCloudSave('u1', async () => { ran.push('third'); });
    release();
    await Promise.all([first, second, third]);
    expect(ran).toEqual(['first', 'third']);
  });

  it('hands the upload that runs the dropKeys of every upload it collapsed (05 #7)', async () => {
    const ran: Array<[string, string[]]> = [];
    let release!: () => void;
    const first = enqueueCloudSave('u1', (drop) => new Promise<void>((resolve) => {
      ran.push(['first', drop]);
      release = resolve;
    }), ['a']);
    await new Promise((resolve) => setTimeout(resolve, 0));
    const neptun = enqueueCloudSave('u1', async (drop) => { ran.push(['neptun', drop]); }, ['legacy', 'exam']);
    const plain = enqueueCloudSave('u1', async (drop) => { ran.push(['plain', drop]); });
    release();
    await Promise.all([first, neptun, plain]);
    expect(ran).toEqual([['first', ['a']], ['plain', ['legacy', 'exam']]]);
  });

  it('does not carry dropKeys over to another user', async () => {
    const ran: Array<[string, string[]]> = [];
    let release!: () => void;
    const first = enqueueCloudSave('u1', () => new Promise<void>((resolve) => { release = resolve; }));
    await new Promise((resolve) => setTimeout(resolve, 0));
    const queued = enqueueCloudSave('u1', async (drop) => { ran.push(['u1', drop]); }, ['legacy']);
    const other = enqueueCloudSave('u2', async (drop) => { ran.push(['u2', drop]); });
    release();
    await Promise.all([first, queued, other]);
    expect(ran).toEqual([['u2', []]]);
  });

  it('keeps the queue usable after a failed upload', async () => {
    await expect(enqueueCloudSave('u1', async () => { throw new Error('boom'); })).rejects.toThrow('boom');
    const ran: string[] = [];
    await enqueueCloudSave('u1', async () => { ran.push('ok'); });
    expect(ran).toEqual(['ok']);
  });
});
