import type { AuthRecord } from 'pocketbase';
import { pb } from '@/lib/pocketbase';
import { getErrorMessage } from '@/lib/error-utils';

export interface PasskeyRecord {
  id: string;
  friendlyName?: string;
  createdAt: string;
  lastUsedAt?: string;
}

export type PasskeyErrorCode =
  | 'passkey_disabled'
  | 'too_many_passkeys'
  | 'webauthn_credential_exists'
  | 'webauthn_credential_not_found'
  | 'webauthn_challenge_expired'
  | 'webauthn_verification_failed'
  | 'not_supported'
  | 'wrong_password'
  | 'rate_limited'
  | 'cancelled'
  | 'unknown';

export class PasskeyError extends Error {
  readonly code: PasskeyErrorCode;
  readonly cancelled: boolean;

  constructor(code: PasskeyErrorCode, message: string, cancelled = false) {
    super(message);
    this.name = 'PasskeyError';
    this.code = code;
    this.cancelled = cancelled;
  }
}

export async function isPasskeySupported(): Promise<boolean> {
  try {
    return typeof PublicKeyCredential !== 'undefined' && window.isSecureContext;
  } catch {
    return false;
  }
}

interface PasskeyApiItem {
  id: string;
  friendly_name?: string;
  created_at: string;
  last_used_at?: string;
}

function toRecord(item: PasskeyApiItem): PasskeyRecord {
  return {
    id: item.id,
    friendlyName: item.friendly_name,
    createdAt: item.created_at,
    lastUsedAt: item.last_used_at,
  };
}

/** `POST …/register/start` and `…/login/start` both answer `{ challenge_id, options }`. */
interface PasskeyStartResponse<TOptions> {
  challenge_id: string;
  options: TOptions;
}

/** `POST …/login/verify` answers the PocketBase auth payload. */
interface PasskeyAuthResponse {
  token: string;
  record: AuthRecord;
}

const KNOWN_CODES: Record<string, PasskeyErrorCode> = {
  passkey_disabled: 'passkey_disabled',
  too_many_passkeys: 'too_many_passkeys',
  webauthn_credential_exists: 'webauthn_credential_exists',
  webauthn_credential_not_found: 'webauthn_credential_not_found',
  webauthn_challenge_expired: 'webauthn_challenge_expired',
  webauthn_verification_failed: 'webauthn_verification_failed',
};

/** Normalize any thrown value into a PasskeyError (cancellation marked separately). */
function mapError(err: unknown): PasskeyError {
  if (err instanceof PasskeyError) return err;
  const code = (err as { code?: string } | null)?.code;
  const message = getErrorMessage(err, String(err));
  if (code === 'ERROR_CEREMONY_ABORTED' || /not allowed|cancelled|cancel/i.test(message)) {
    return new PasskeyError('cancelled', message, true);
  }
  if (code && KNOWN_CODES[code]) {
    return new PasskeyError(KNOWN_CODES[code], message);
  }
  const status = (err as { status?: number } | null)?.status;
  if (status === 400 && /current password is incorrect/i.test(message)) {
    return new PasskeyError('wrong_password', message);
  }
  if (status === 429) return new PasskeyError('rate_limited', message);
  return new PasskeyError('unknown', message);
}

// --- WebAuthn web-ceremony helpers -------------------------------------------
//
// The server speaks the standard WebAuthn JSON serialization (base64url strings),
// while `navigator.credentials` needs `ArrayBuffer`s. Prefer the browser's
// standards-based converters (`PublicKeyCredential.parse*OptionsFromJSON` and
// `credential.toJSON()`); fall back to an inline base64url conversion on the
// browsers that predate them.

/** Decode an unpadded base64url string into an ArrayBuffer. */
function base64urlToBuffer(value: string): ArrayBuffer {
  const base64 = value.replace(/-/g, '+').replace(/_/g, '/');
  const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4);
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

/** Encode an ArrayBuffer as an unpadded base64url string. */
function bufferToBase64url(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  for (let i = 0; i < bytes.length; i += 1) binary += String.fromCharCode(bytes[i]);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** Manual `parseCreationOptionsFromJSON` for browsers without the helper. */
function creationOptionsFromJSON(
  options: PublicKeyCredentialCreationOptionsJSON,
): PublicKeyCredentialCreationOptions {
  return {
    attestation: options.attestation as AttestationConveyancePreference | undefined,
    authenticatorSelection: options.authenticatorSelection,
    challenge: base64urlToBuffer(options.challenge),
    excludeCredentials: options.excludeCredentials?.map((descriptor) => ({
      id: base64urlToBuffer(descriptor.id),
      type: descriptor.type as PublicKeyCredentialType,
      transports: descriptor.transports as AuthenticatorTransport[] | undefined,
    })),
    extensions: options.extensions as AuthenticationExtensionsClientInputs | undefined,
    pubKeyCredParams: options.pubKeyCredParams,
    rp: options.rp,
    timeout: options.timeout,
    user: {
      displayName: options.user.displayName,
      id: base64urlToBuffer(options.user.id),
      name: options.user.name,
    },
  };
}

/** Manual `parseRequestOptionsFromJSON` for browsers without the helper. */
function requestOptionsFromJSON(
  options: PublicKeyCredentialRequestOptionsJSON,
): PublicKeyCredentialRequestOptions {
  return {
    allowCredentials: options.allowCredentials?.map((descriptor) => ({
      id: base64urlToBuffer(descriptor.id),
      type: descriptor.type as PublicKeyCredentialType,
      transports: descriptor.transports as AuthenticatorTransport[] | undefined,
    })),
    challenge: base64urlToBuffer(options.challenge),
    extensions: options.extensions as AuthenticationExtensionsClientInputs | undefined,
    rpId: options.rpId,
    timeout: options.timeout,
    userVerification: options.userVerification as UserVerificationRequirement | undefined,
  };
}

/** Manual `PublicKeyCredential.toJSON()` for registration responses. */
function registrationJSON(credential: PublicKeyCredential): RegistrationResponseJSON {
  const response = credential.response as AuthenticatorAttestationResponse;
  const publicKey = response.getPublicKey();
  return {
    authenticatorAttachment: credential.authenticatorAttachment ?? undefined,
    clientExtensionResults:
      credential.getClientExtensionResults() as AuthenticationExtensionsClientOutputsJSON,
    id: credential.id,
    rawId: bufferToBase64url(credential.rawId),
    response: {
      attestationObject: bufferToBase64url(response.attestationObject),
      authenticatorData: bufferToBase64url(response.getAuthenticatorData()),
      clientDataJSON: bufferToBase64url(response.clientDataJSON),
      publicKey: publicKey ? bufferToBase64url(publicKey) : undefined,
      publicKeyAlgorithm: response.getPublicKeyAlgorithm(),
      transports: response.getTransports(),
    },
    type: credential.type,
  };
}

/** Manual `PublicKeyCredential.toJSON()` for assertion responses. */
function authenticationJSON(credential: PublicKeyCredential): AuthenticationResponseJSON {
  const response = credential.response as AuthenticatorAssertionResponse;
  return {
    authenticatorAttachment: credential.authenticatorAttachment ?? undefined,
    clientExtensionResults:
      credential.getClientExtensionResults() as AuthenticationExtensionsClientOutputsJSON,
    id: credential.id,
    rawId: bufferToBase64url(credential.rawId),
    response: {
      authenticatorData: bufferToBase64url(response.authenticatorData),
      clientDataJSON: bufferToBase64url(response.clientDataJSON),
      signature: bufferToBase64url(response.signature),
      userHandle: response.userHandle ? bufferToBase64url(response.userHandle) : undefined,
    },
    type: credential.type,
  };
}

/** Run `navigator.credentials.create` and return the credential's JSON form. */
async function webCreate(
  options: PublicKeyCredentialCreationOptionsJSON,
): Promise<RegistrationResponseJSON> {
  const publicKey =
    typeof PublicKeyCredential !== 'undefined' &&
    typeof PublicKeyCredential.parseCreationOptionsFromJSON === 'function'
      ? PublicKeyCredential.parseCreationOptionsFromJSON(options)
      : creationOptionsFromJSON(options);
  const credential = (await navigator.credentials.create({
    publicKey,
  })) as PublicKeyCredential | null;
  if (!credential) throw new Error('No passkey credential was returned');
  return typeof credential.toJSON === 'function'
    ? (credential.toJSON() as RegistrationResponseJSON)
    : registrationJSON(credential);
}

/** Run `navigator.credentials.get` and return the credential's JSON form. */
async function webGet(
  options: PublicKeyCredentialRequestOptionsJSON,
): Promise<AuthenticationResponseJSON> {
  const publicKey =
    typeof PublicKeyCredential !== 'undefined' &&
    typeof PublicKeyCredential.parseRequestOptionsFromJSON === 'function'
      ? PublicKeyCredential.parseRequestOptionsFromJSON(options)
      : requestOptionsFromJSON(options);
  const credential = (await navigator.credentials.get({
    publicKey,
  })) as PublicKeyCredential | null;
  if (!credential) throw new Error('No passkey credential was returned');
  return typeof credential.toJSON === 'function'
    ? (credential.toJSON() as AuthenticationResponseJSON)
    : authenticationJSON(credential);
}

// --- Public API --------------------------------------------------------------

/**
 * Enrol this device. The server re-checks the account's current password first,
 * so a stolen session token alone cannot add a passkey.
 */
export async function registerPasskey(currentPassword: string): Promise<PasskeyRecord> {
  if (!(await isPasskeySupported())) {
    throw new PasskeyError('not_supported', 'Biometric sign-in is not supported on this device');
  }
  try {
    const start = await pb.send<PasskeyStartResponse<PublicKeyCredentialCreationOptionsJSON>>(
      '/api/vectra/passkeys/register/start',
      { method: 'POST', body: { currentPassword } },
    );
    const credential = await webCreate(start.options);
    const verified = await pb.send<PasskeyApiItem>('/api/vectra/passkeys/register/verify', {
      method: 'POST',
      body: { challenge_id: start.challenge_id, credential },
    });
    return toRecord(verified);
  } catch (err) {
    throw mapError(err);
  }
}

export async function signInWithPasskey(): Promise<{ user: { id: string; email?: string } | null }> {
  if (!(await isPasskeySupported())) {
    throw new PasskeyError('not_supported', 'Biometric sign-in is not supported on this device');
  }
  try {
    const start = await pb.send<PasskeyStartResponse<PublicKeyCredentialRequestOptionsJSON>>(
      '/api/vectra/passkeys/login/start',
      { method: 'POST' },
    );
    const credential = await webGet(start.options);
    const auth = await pb.send<PasskeyAuthResponse>('/api/vectra/passkeys/login/verify', {
      method: 'POST',
      body: { challenge_id: start.challenge_id, credential },
    });
    // The server mints a normal PocketBase auth token; keep it in the single
    // session store so `authStore` stays the one source of truth.
    pb.authStore.save(auth.token, auth.record);
    return { user: auth.record ?? null };
  } catch (err) {
    throw mapError(err);
  }
}

export async function listPasskeys(): Promise<PasskeyRecord[]> {
  const items = await pb.send<PasskeyApiItem[]>('/api/vectra/passkeys', { method: 'GET' });
  return (items ?? []).map(toRecord);
}

export async function deletePasskey(passkeyId: string): Promise<void> {
  await pb.send(`/api/vectra/passkeys/${encodeURIComponent(passkeyId)}`, { method: 'DELETE' });
}

export function passkeyErrorMessage(code: PasskeyErrorCode): string {
  switch (code) {
    case 'passkey_disabled':
      return 'Biometric sign-in is not available yet.';
    case 'webauthn_credential_not_found':
      return 'No passkey found for this account on this device.';
    case 'too_many_passkeys':
      return 'Too many passkeys — remove one in Settings first.';
    case 'webauthn_credential_exists':
      return 'This device already has a passkey for your account.';
    case 'webauthn_challenge_expired':
    case 'webauthn_verification_failed':
      return 'Something went wrong — please try again.';
    case 'not_supported':
      return 'Biometric sign-in is not supported on this device.';
    case 'wrong_password':
      return 'Current password is incorrect.';
    case 'rate_limited':
      return 'Too many attempts — please wait a while and try again.';
    case 'cancelled':
      return '';
    case 'unknown':
      return 'Biometric sign-in failed — please try again.';
  }
}
