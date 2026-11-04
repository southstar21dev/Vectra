import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// vi.hoisted: vi.mock factories are hoisted above module-scope declarations.
const { sendMock } = vi.hoisted(() => ({ sendMock: vi.fn() }));

vi.mock('@/lib/pocketbase', () => ({
  pb: { send: sendMock },
}));

import {
  computeKeyedDigest,
  decryptField,
  decryptNeptunUrl,
  decryptRow,
  encryptField,
  encryptNeptunUrl,
  encryptRow,
} from './encryption';
import { pb } from '@/lib/pocketbase';

type CryptoPayload =
  | { op: 'encrypt'; values: string[] }
  | { op: 'decrypt'; values: string[] }
  | { op: 'digest'; parts: string[][] };

let calls: CryptoPayload[] = [];
let failTransport = false;
let badLength = false;

function handle(payload: CryptoPayload): Record<string, unknown> {
  calls.push(payload);
  if (badLength) {
    return { values: [], digests: [] };
  }
  if (payload.op === 'encrypt') {
    return { values: payload.values.map((v) => `vc2:${v}`) };
  }
  if (payload.op === 'decrypt') {
    return { values: payload.values.map((v) => v.replace(/^vc2:/, '')) };
  }
  return { digests: payload.parts.map((p) => `digest(${p.join('|')})`) };
}

beforeEach(() => {
  calls = [];
  failTransport = false;
  badLength = false;

  // Transport is `pb.send('/api/vectra/crypto', { method: 'POST', body })`.
  vi.mocked(pb.send).mockImplementation(async (_path: string, options: { body: CryptoPayload }) => {
    if (failTransport) throw new Error('crypto route down');
    return handle(options.body);
  });
});

afterEach(() => {
  vi.clearAllMocks();
});

describe('encryption batching', () => {
  it('coalesces concurrent encrypt calls into one round trip', async () => {
    const [a, b, c] = await Promise.all([
      encryptField('one'),
      encryptField('two'),
      encryptField('three'),
    ]);
    expect([a, b, c]).toEqual(['vc2:one', 'vc2:two', 'vc2:three']);
    expect(calls).toHaveLength(1);
    expect(calls[0]).toEqual({ op: 'encrypt', values: ['one', 'two', 'three'] });
  });

  it('posts to the PocketBase crypto route', async () => {
    await encryptField('one');
    expect(pb.send).toHaveBeenCalledWith('/api/vectra/crypto', {
      method: 'POST',
      body: { op: 'encrypt', values: ['one'] },
    });
  });

  it('coalesces concurrent digest calls with their parts intact', async () => {
    const [x, y] = await Promise.all([computeKeyedDigest('a', 'b'), computeKeyedDigest('c')]);
    expect([x, y]).toEqual(['digest(a|b)', 'digest(c)']);
    expect(calls).toHaveLength(1);
  });

  it('does not call the service for empty/null fields', async () => {
    expect(await encryptField('')).toBe('');
    expect(await encryptField(null)).toBeNull();
    expect(await encryptField(undefined)).toBeUndefined();
    expect(calls).toHaveLength(0);
  });
});

describe('encryption failure semantics', () => {
  it('REJECTS encrypt when the crypto service is unavailable (never stores plaintext)', async () => {
    failTransport = true;
    await expect(encryptField('secret')).rejects.toThrow();
  });

  it('REJECTS encrypt on a malformed service response', async () => {
    badLength = true;
    await expect(encryptField('secret')).rejects.toThrow(/invalid encrypt response/);
  });

  it('REJECTS digest when the crypto service is unavailable', async () => {
    failTransport = true;
    await expect(computeKeyedDigest('a')).rejects.toThrow();
  });

  it('returns the RAW value from decrypt on service failure (best-effort display)', async () => {
    failTransport = true;
    expect(await decryptField('vc2:whatever')).toBe('vc2:whatever');
  });
});

describe('decrypt passthrough', () => {
  it('returns non-vc2 values untouched without calling the service', async () => {
    expect(await decryptField('plain text')).toBe('plain text');
    expect(await decryptField('')).toBe('');
    expect(calls).toHaveLength(0);
  });

  it('decrypts vc2-prefixed values', async () => {
    expect(await decryptField('vc2:hello')).toBe('hello');
  });

  it('decryptNeptunUrl returns plain HTTP(S) URLs untouched', async () => {
    expect(await decryptNeptunUrl('https://neptun.example/ics?token=x')).toBe(
      'https://neptun.example/ics?token=x'
    );
    expect(await decryptNeptunUrl('http://neptun.example/ics')).toBe('http://neptun.example/ics');
    expect(calls).toHaveLength(0);
  });

  it('decryptNeptunUrl returns the raw value when decryption fails', async () => {
    failTransport = true;
    expect(await decryptNeptunUrl('vc2:broken')).toBe('vc2:broken');
  });

  it('encryptNeptunUrl returns an empty URL untouched', async () => {
    expect(await encryptNeptunUrl('')).toBe('');
    expect(calls).toHaveLength(0);
  });
});

describe('row helpers', () => {
  it('encryptRow encrypts listed fields and leaves nulls alone', async () => {
    const row = { a: 'x', b: null as string | null, c: 'y' };
    const out = await encryptRow(row, ['a', 'b', 'c']);
    expect(out).toEqual({ a: 'vc2:x', b: null, c: 'vc2:y' });
  });

  it('decryptRow decrypts listed fields and passes through plain ones', async () => {
    const row = { a: 'vc2:x', b: 'plain', c: null as string | null };
    const out = await decryptRow(row, ['a', 'b', 'c']);
    expect(out).toEqual({ a: 'x', b: 'plain', c: null });
  });

  it('does not mutate the input row', async () => {
    const row = { a: 'x' };
    const out = await encryptRow(row, ['a']);
    expect(row.a).toBe('x');
    expect(out).not.toBe(row);
  });
});
