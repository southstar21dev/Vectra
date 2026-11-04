/**
 * Encryption helpers backed by the server-side `POST /api/vectra/crypto` route (Level 1).
 *
 * The key (NEPTUN_ENCRYPTION_KEY) lives ONLY on the server — it never ships in
 * the client bundle. This module POSTs batched requests to the JWT-gated
 * `POST /api/vectra/crypto` PocketBase route, which reads the key from the
 * server environment (dev now runs a local PocketBase).
 *
 * Requests are coalesced on a microtask: all encrypt/decrypt/digest calls made
 * in the same synchronous block (e.g. decrypting every event of a schedule
 * fetch) go out as ONE round-trip.
 *
 * Ciphertext formats: `vc3:` (bound to the owning user) is what the server
 * emits now; legacy `vc2:` + base64(iv[12] || ciphertext) rows still decrypt
 * unchanged. Values without either prefix are treated as plain text
 * (legacy / forward-compat rows).
 *
 * Failure semantics:
 *  - encrypt / digest REJECT — we never silently store plaintext when the
 *    crypto service is unavailable;
 *  - decrypt passes the raw value through on failure (best-effort display);
 *    callers that persist or merge decrypted data must check `isCiphertext`
 *    on the result and treat a hit as a failed read.
 */

import { pb } from './pocketbase';

const CIPHERTEXT_PREFIXES = ['vc2:', 'vc3:'] as const;

/** True when `value` is still server ciphertext (encrypted, or a failed decrypt). */
export function isCiphertext(value: unknown): value is string {
  return typeof value === 'string' && CIPHERTEXT_PREFIXES.some((prefix) => value.startsWith(prefix));
}

// ---- batching ---------------------------------------------------------------

interface Pending<T> {
  resolve: (v: T) => void;
  reject: (e: unknown) => void;
}

interface PendingDigest extends Pending<string> {
  parts: string[];
}

let encryptPending: Array<Pending<string> & { value: string }> = [];
let decryptPending: Array<Pending<string> & { value: string }> = [];
let digestPending: PendingDigest[] = [];
let flushScheduled = false;

function scheduleFlush(): void {
  if (flushScheduled) return;
  flushScheduled = true;
  queueMicrotask(() => {
    flushScheduled = false;
    void flushPending();
  });
}

async function postCrypto(payload: Record<string, unknown>): Promise<any> {
  return pb.send('/api/vectra/crypto', { method: 'POST', body: payload });
}

async function flushPending(): Promise<void> {
  const encrypts = encryptPending;
  const decrypts = decryptPending;
  const digests = digestPending;
  encryptPending = [];
  decryptPending = [];
  digestPending = [];
  if (!encrypts.length && !decrypts.length && !digests.length) return;

  if (encrypts.length) {
    try {
      const { values } = (await postCrypto({
        op: 'encrypt',
        values: encrypts.map((p) => p.value),
      })) as { values?: unknown[] };
      if (!Array.isArray(values) || values.length !== encrypts.length) throw new Error('Crypto service returned an invalid encrypt response');
      encrypts.forEach((p, i) => p.resolve(String(values[i])));
    } catch (e) {
      encrypts.forEach((p) => p.reject(e));
    }
  }

  if (decrypts.length) {
    try {
      const { values } = (await postCrypto({
        op: 'decrypt',
        values: decrypts.map((p) => p.value),
      })) as { values?: unknown[] };
      if (!Array.isArray(values) || values.length !== decrypts.length) throw new Error('Crypto service returned an invalid decrypt response');
      decrypts.forEach((p, i) => p.resolve(String(values[i])));
    } catch (e) {
      decrypts.forEach((p) => p.reject(e));
    }
  }

  if (digests.length) {
    try {
      const { digests: results } = (await postCrypto({
        op: 'digest',
        parts: digests.map((p) => p.parts),
      })) as { digests?: unknown[] };
      if (!Array.isArray(results) || results.length !== digests.length) throw new Error('Crypto service returned an invalid digest response');
      digests.forEach((p, i) => p.resolve(String(results[i])));
    } catch (e) {
      digests.forEach((p) => p.reject(e));
    }
  }
}

function queueEncrypt(value: string): Promise<string> {
  return new Promise((resolve, reject) => {
    encryptPending.push({ value, resolve, reject });
    scheduleFlush();
  });
}

function queueDecrypt(value: string): Promise<string> {
  return new Promise((resolve, reject) => {
    decryptPending.push({ value, resolve, reject });
    scheduleFlush();
  });
}

function queueDigest(parts: string[]): Promise<string> {
  return new Promise((resolve, reject) => {
    digestPending.push({ parts, resolve, reject });
    scheduleFlush();
  });
}

// ---- public API (signatures unchanged from the client-side implementation) ---

/**
 * Encrypts a Neptun URL using AES-256-GCM (server-side key).
 * Throws if the crypto service is unavailable — never stores plaintext.
 */
export async function encryptNeptunUrl(url: string): Promise<string> {
  if (!url) return url;
  return queueEncrypt(url);
}

/**
 * Decrypts an encrypted Neptun URL. Plain HTTP(S) URLs and non-ciphertext values are
 * passed through untouched. On crypto failure the raw value is returned.
 */
export async function decryptNeptunUrl(encryptedUrl: string): Promise<string> {
  if (!encryptedUrl) return encryptedUrl;

  // If it's already a plain HTTP/HTTPS URL, don't try to decrypt it.
  if (encryptedUrl.startsWith('http://') || encryptedUrl.startsWith('https://')) {
    return encryptedUrl;
  }

  try {
    if (isCiphertext(encryptedUrl)) {
      return await queueDecrypt(encryptedUrl);
    }
    // Non-ciphertext value: plain text (legacy ciphertext is no longer decryptable).
    return encryptedUrl;
  } catch (error) {
    console.error('Failed to decrypt Neptun URL:', error);
    return encryptedUrl;
  }
}

/**
 * Encrypts a generic string field. Throws if the crypto service is
 * unavailable — never silently stores plaintext.
 */
export async function encryptField(text: string | null | undefined): Promise<string | null | undefined> {
  if (!text) return text;
  return queueEncrypt(text);
}

/**
 * Keyed fingerprint (hex SHA-256 of the key + parts) for deterministic DB
 * constraints over encrypted-at-rest rows (e.g. unique indexes). Computed
 * server-side; identical to the previous client-side computation.
 */
export async function computeKeyedDigest(...parts: string[]): Promise<string> {
  return queueDigest(parts);
}

/**
 * Decrypts a generic string field.
 * Values without a ciphertext prefix are returned as-is (plain text); on crypto
 * failure the raw value is returned.
 */
export async function decryptField(encryptedText: string | null | undefined): Promise<string | null | undefined> {
  if (!encryptedText) return encryptedText;

  try {
    if (isCiphertext(encryptedText)) {
      return await queueDecrypt(encryptedText);
    }
    return encryptedText;
  } catch {
    return encryptedText;
  }
}

/**
 * Decrypt specified fields on a row object in-place.
 * Non-encrypted values pass through unchanged (decryptField handles that).
 */
export async function decryptRow<T extends Record<string, unknown>>(
  row: T,
  fields: readonly (keyof T)[]
): Promise<T> {
  const out = { ...row };
  await Promise.all(
    fields.map(async (field) => {
      (out as Record<string, unknown>)[field as string] = await decryptField(
        out[field] as string | null | undefined
      );
    })
  );
  return out;
}

/**
 * Encrypt specified fields on a row object in-place.
 * Throws if the crypto service is unavailable (never stores plaintext).
 */
export async function encryptRow<T extends Record<string, unknown>>(
  row: T,
  fields: readonly (keyof T)[]
): Promise<T> {
  const out = { ...row };
  await Promise.all(
    fields.map(async (field) => {
      (out as Record<string, unknown>)[field as string] = await encryptField(
        out[field] as string | null | undefined
      );
    })
  );
  return out;
}
