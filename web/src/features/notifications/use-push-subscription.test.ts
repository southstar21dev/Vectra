import { describe, expect, it, vi, beforeEach } from 'vitest';
import { urlBase64ToUint8Array, cleanupDuplicatePushSubscriptions } from './use-push-subscription';
import { getEffectiveNotificationPermission, getNotificationPermission } from './push-manager';

// Shared PocketBase stub: `collection()` always returns the same record service
// so tests can assert against getFullList/create/delete regardless of collection.
const pbMock = vi.hoisted(() => {
  const recordService = {
    getFullList: vi.fn(),
    create: vi.fn(),
    delete: vi.fn(),
  };
  return {
    collection: vi.fn(() => recordService),
    filter: vi.fn((filter: string) => filter),
    authStore: {
      isValid: true,
      record: { id: 'user-abc' } as { id: string } | null,
    },
    recordService,
  };
});

vi.mock('@/lib/pocketbase', () => ({
  pb: {
    collection: pbMock.collection,
    filter: pbMock.filter,
    authStore: pbMock.authStore,
  },
}));

describe('urlBase64ToUint8Array', () => {
  it('converts a standard base64 string to Uint8Array', () => {
    const result = urlBase64ToUint8Array('aGVsbG8=');
    const expected = new Uint8Array([104, 101, 108, 108, 111]);
    expect(result).toEqual(expected);
  });

  it('handles URL-safe base64 with - and _ characters', () => {
    const base64Url = 'a-_b';
    const result = urlBase64ToUint8Array(base64Url);
    expect(result instanceof Uint8Array).toBe(true);
    expect(result.length).toBeGreaterThan(0);
  });

  it('trims extraneous whitespace and handles missing padding', () => {
    const raw = '  aGVsbG8  ';
    const result = urlBase64ToUint8Array(raw);
    const expected = new Uint8Array([104, 101, 108, 108, 111]);
    expect(result).toEqual(expected);
  });
});

describe('Web Notification Permissions', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('returns the browser permission via getEffectiveNotificationPermission', async () => {
    vi.stubGlobal('window', { Notification: { permission: 'granted' } });
    vi.stubGlobal('Notification', { permission: 'granted' });
    const perm = await getEffectiveNotificationPermission();
    expect(perm).toBe('granted');
    vi.unstubAllGlobals();
  });

  it('returns synchronous permission fallback properly', () => {
    vi.stubGlobal('window', { Notification: { permission: 'default' } });
    vi.stubGlobal('Notification', { permission: 'default' });
    expect(getNotificationPermission()).toBe('default');
    vi.unstubAllGlobals();
  });
});

describe('cleanupDuplicatePushSubscriptions', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    pbMock.authStore.isValid = true;
    pbMock.authStore.record = { id: 'user-abc' };
    pbMock.recordService.getFullList.mockResolvedValue([]);
    pbMock.recordService.delete.mockResolvedValue(true);
  });

  it('deletes duplicate web-push-endpoint rows, keeping the newest', async () => {
    pbMock.recordService.getFullList.mockResolvedValue([
      { id: 'new-web', transport: 'webpush', subscription: { endpoint: 'https://push/1' }, created_at: '2026-01-02T00:00:00Z' },
      { id: 'dup-web', transport: 'webpush', subscription: { endpoint: 'https://push/1' }, created_at: '2026-01-01T00:00:00Z' },
      { id: 'keep-web', transport: 'webpush', subscription: { endpoint: 'https://push/2' }, created_at: '2026-01-01T00:00:00Z' },
    ]);

    await cleanupDuplicatePushSubscriptions();

    expect(pbMock.recordService.getFullList).toHaveBeenCalledWith(
      expect.objectContaining({ sort: '-created_at' })
    );
    expect(pbMock.recordService.delete).toHaveBeenCalledTimes(1);
    expect(pbMock.recordService.delete).toHaveBeenCalledWith('dup-web');
  });

  it('does nothing when the user has a single subscription', async () => {
    pbMock.recordService.getFullList.mockResolvedValue([
      { id: 'only', transport: 'webpush', subscription: { endpoint: 'https://push/1' } },
    ]);

    await cleanupDuplicatePushSubscriptions();
    expect(pbMock.recordService.delete).not.toHaveBeenCalled();
  });

  it('does nothing without an active session', async () => {
    pbMock.authStore.record = null;
    await cleanupDuplicatePushSubscriptions();
    expect(pbMock.recordService.getFullList).not.toHaveBeenCalled();
    expect(pbMock.recordService.delete).not.toHaveBeenCalled();
  });
});
