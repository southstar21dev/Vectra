import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// --- Mocks (vi.hoisted: vi.mock factories are hoisted above declarations) ---

const { sendMock, saveMock } = vi.hoisted(() => ({
  sendMock: vi.fn(),
  saveMock: vi.fn(),
}));

vi.mock('@/lib/pocketbase', () => ({
  pb: { send: sendMock, authStore: { save: saveMock } },
}));

import {
  deletePasskey,
  isPasskeySupported,
  listPasskeys,
  passkeyErrorMessage,
  registerPasskey,
  signInWithPasskey,
} from './passkeys';

const metadata = {
  id: 'passkey-1',
  friendly_name: 'Google Password Manager',
  created_at: '2026-08-11T00:00:00Z',
};
const record = {
  id: 'passkey-1',
  friendlyName: 'Google Password Manager',
  createdAt: '2026-08-11T00:00:00Z',
  lastUsedAt: undefined,
};

/** The registration `options` the server returns from `…/register/start`. */
const creationOptions = {
  challenge: 'Y2hhbGxlbmdl',
  rp: { id: 'vectra.southstar21.dev', name: 'Vectra' },
  user: { id: 'dXNlci0x', name: 'a@b.c', displayName: 'A' },
  pubKeyCredParams: [{ type: 'public-key', alg: -7 }],
  excludeCredentials: [{ id: 'ZXhjbHVkZQ', type: 'public-key', transports: ['internal'] }],
};

/** The assertion `options` the server returns from `…/login/start`. */
const requestOptions = { challenge: 'Y2hhbGxlbmdl', rpId: 'vectra.southstar21.dev' };

const registrationJSON = {
  id: 'cred-1',
  rawId: 'Y3JlZC0x',
  type: 'public-key',
  authenticatorAttachment: 'platform',
  response: { clientDataJSON: 'e30', attestationObject: 'YXR0ZXN0' },
  clientExtensionResults: {},
};

const assertionJSON = {
  id: 'cred-1',
  rawId: 'Y3JlZC0x',
  type: 'public-key',
  response: { clientDataJSON: 'e30', authenticatorData: 'YXV0aA', signature: 'c2ln' },
  clientExtensionResults: {},
};

/** Install the browser globals a web ceremony needs; returns the ceremony spies. */
function installWebCeremony(credential: unknown) {
  const create = vi.fn().mockResolvedValue(credential);
  const get = vi.fn().mockResolvedValue(credential);
  vi.stubGlobal('navigator', { credentials: { create, get } });
  return { create, get };
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.stubGlobal('window', { isSecureContext: true });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('isPasskeySupported', () => {
  it('is true on web with PublicKeyCredential in a secure context', async () => {
    vi.stubGlobal('PublicKeyCredential', class {});
    await expect(isPasskeySupported()).resolves.toBe(true);
  });

  it('is false on web without a secure context', async () => {
    vi.stubGlobal('PublicKeyCredential', class {});
    vi.stubGlobal('window', { isSecureContext: false });
    await expect(isPasskeySupported()).resolves.toBe(false);
  });

  it('is false on web without PublicKeyCredential', async () => {
    vi.stubGlobal('PublicKeyCredential', undefined);
    await expect(isPasskeySupported()).resolves.toBe(false);
  });
});

describe('registerPasskey (web ceremony)', () => {
  it('converts the options, runs navigator.credentials.create, and posts start+verify', async () => {
    const publicKey = { challenge: new ArrayBuffer(8) };
    const parseCreationOptionsFromJSON = vi.fn(() => publicKey);
    vi.stubGlobal(
      'PublicKeyCredential',
      class {
        static parseCreationOptionsFromJSON = parseCreationOptionsFromJSON;
      },
    );
    const { create } = installWebCeremony({ toJSON: () => registrationJSON });
    sendMock
      .mockResolvedValueOnce({ challenge_id: 'c-1', options: creationOptions })
      .mockResolvedValueOnce(metadata);

    await expect(registerPasskey()).resolves.toEqual(record);

    expect(sendMock).toHaveBeenNthCalledWith(1, '/api/vectra/passkeys/register/start', {
      method: 'POST',
    });
    expect(parseCreationOptionsFromJSON).toHaveBeenCalledWith(creationOptions);
    expect(create).toHaveBeenCalledWith({ publicKey });
    expect(sendMock).toHaveBeenNthCalledWith(2, '/api/vectra/passkeys/register/verify', {
      method: 'POST',
      body: { challenge_id: 'c-1', credential: registrationJSON },
    });
  });

  it('falls back to inline base64url conversion when the browser lacks the helpers', async () => {
    const bytes = new Uint8Array([1, 2, 3, 255]).buffer;
    const credential = {
      id: 'cred-1',
      rawId: bytes,
      type: 'public-key',
      authenticatorAttachment: null,
      response: {
        clientDataJSON: bytes,
        attestationObject: bytes,
        getAuthenticatorData: () => bytes,
        getPublicKey: () => null,
        getPublicKeyAlgorithm: () => -7,
        getTransports: () => ['internal'],
      },
      getClientExtensionResults: () => ({}),
    };
    // A bare class: no `parse*OptionsFromJSON` static, no `credential.toJSON`.
    vi.stubGlobal('PublicKeyCredential', class {});
    const { create } = installWebCeremony(credential);
    sendMock
      .mockResolvedValueOnce({ challenge_id: 'c-1', options: creationOptions })
      .mockResolvedValueOnce(metadata);

    await expect(registerPasskey()).resolves.toEqual(record);

    // base64url of [1,2,3,255] is "AQID_w".
    const [{ publicKey }] = create.mock.calls[0];
    expect(publicKey.challenge).toBeInstanceOf(ArrayBuffer);
    expect(publicKey.user.id).toBeInstanceOf(ArrayBuffer);
    expect(publicKey.excludeCredentials[0].id).toBeInstanceOf(ArrayBuffer);
    expect(sendMock).toHaveBeenNthCalledWith(2, '/api/vectra/passkeys/register/verify', {
      method: 'POST',
      body: {
        challenge_id: 'c-1',
        credential: {
          id: 'cred-1',
          rawId: 'AQID_w',
          type: 'public-key',
          authenticatorAttachment: undefined,
          clientExtensionResults: {},
          response: {
            attestationObject: 'AQID_w',
            authenticatorData: 'AQID_w',
            clientDataJSON: 'AQID_w',
            publicKey: undefined,
            publicKeyAlgorithm: -7,
            transports: ['internal'],
          },
        },
      },
    });
  });

  it('throws a mapped PasskeyError on passkey_disabled', async () => {
    vi.stubGlobal('PublicKeyCredential', class {});
    installWebCeremony(undefined);
    sendMock.mockRejectedValue({ code: 'passkey_disabled', message: 'disabled' });

    await expect(registerPasskey()).rejects.toMatchObject({
      name: 'PasskeyError',
      code: 'passkey_disabled',
      cancelled: false,
    });
  });

  it('marks a ceremony abort as a silent cancellation', async () => {
    vi.stubGlobal('PublicKeyCredential', class {});
    installWebCeremony(undefined);
    sendMock.mockRejectedValue({ code: 'ERROR_CEREMONY_ABORTED', message: 'aborted' });

    await expect(registerPasskey()).rejects.toMatchObject({
      name: 'PasskeyError',
      code: 'cancelled',
      cancelled: true,
    });
  });

  it('throws not_supported before any request when unsupported', async () => {
    vi.stubGlobal('PublicKeyCredential', undefined);
    await expect(registerPasskey()).rejects.toMatchObject({ code: 'not_supported' });
    expect(sendMock).not.toHaveBeenCalled();
  });
});

describe('signInWithPasskey (web ceremony)', () => {
  it('runs navigator.credentials.get, verifies, and saves the returned session', async () => {
    const publicKey = { challenge: new ArrayBuffer(8) };
    const parseRequestOptionsFromJSON = vi.fn(() => publicKey);
    vi.stubGlobal(
      'PublicKeyCredential',
      class {
        static parseRequestOptionsFromJSON = parseRequestOptionsFromJSON;
      },
    );
    const { get } = installWebCeremony({ toJSON: () => assertionJSON });
    const authRecord = { id: 'user-1', email: 'a@b.c' };
    sendMock
      .mockResolvedValueOnce({ challenge_id: 'c-2', options: requestOptions })
      .mockResolvedValueOnce({ token: 'tok', record: authRecord });

    await expect(signInWithPasskey()).resolves.toEqual({ user: authRecord });

    expect(sendMock).toHaveBeenNthCalledWith(1, '/api/vectra/passkeys/login/start', {
      method: 'POST',
    });
    expect(parseRequestOptionsFromJSON).toHaveBeenCalledWith(requestOptions);
    expect(get).toHaveBeenCalledWith({ publicKey });
    expect(sendMock).toHaveBeenNthCalledWith(2, '/api/vectra/passkeys/login/verify', {
      method: 'POST',
      body: { challenge_id: 'c-2', credential: assertionJSON },
    });
    expect(saveMock).toHaveBeenCalledWith('tok', authRecord);
  });

  it('falls back to inline base64url conversion when the browser lacks the helpers', async () => {
    const bytes = new Uint8Array([1, 2, 3, 255]).buffer;
    const userHandle = new Uint8Array([9, 9]).buffer;
    const credential = {
      id: 'cred-1',
      rawId: bytes,
      type: 'public-key',
      authenticatorAttachment: null,
      response: {
        clientDataJSON: bytes,
        authenticatorData: bytes,
        signature: bytes,
        userHandle,
      },
      getClientExtensionResults: () => ({}),
    };
    // A bare class: no `parseRequestOptionsFromJSON` static, no `credential.toJSON`.
    vi.stubGlobal('PublicKeyCredential', class {});
    const { get } = installWebCeremony(credential);
    const authRecord = { id: 'user-1', email: 'a@b.c' };
    sendMock
      .mockResolvedValueOnce({ challenge_id: 'c-2', options: requestOptions })
      .mockResolvedValueOnce({ token: 'tok', record: authRecord });

    await expect(signInWithPasskey()).resolves.toEqual({ user: authRecord });

    // base64url of [1,2,3,255] is "AQID_w"; of [9,9] is "CQk".
    const [{ publicKey }] = get.mock.calls[0];
    expect(publicKey.challenge).toBeInstanceOf(ArrayBuffer);
    expect(sendMock).toHaveBeenNthCalledWith(2, '/api/vectra/passkeys/login/verify', {
      method: 'POST',
      body: {
        challenge_id: 'c-2',
        credential: {
          id: 'cred-1',
          rawId: 'AQID_w',
          type: 'public-key',
          authenticatorAttachment: undefined,
          clientExtensionResults: {},
          response: {
            authenticatorData: 'AQID_w',
            clientDataJSON: 'AQID_w',
            signature: 'AQID_w',
            userHandle: 'CQk',
          },
        },
      },
    });
    expect(saveMock).toHaveBeenCalledWith('tok', authRecord);
  });
});

describe('listPasskeys / deletePasskey', () => {
  it('maps list items to PasskeyRecord', async () => {
    sendMock.mockResolvedValue([metadata]);

    await expect(listPasskeys()).resolves.toEqual([record]);
    expect(sendMock).toHaveBeenCalledWith('/api/vectra/passkeys', { method: 'GET' });
  });

  it('hits the delete route with the id', async () => {
    sendMock.mockResolvedValue(undefined);

    await deletePasskey('passkey-1');

    expect(sendMock).toHaveBeenCalledWith('/api/vectra/passkeys/passkey-1', { method: 'DELETE' });
  });
});

describe('passkeyErrorMessage', () => {
  it('maps every non-cancelled code to copy, and cancelled to empty', () => {
    expect(passkeyErrorMessage('passkey_disabled')).toContain('not available');
    expect(passkeyErrorMessage('webauthn_credential_not_found')).toContain('No passkey found');
    expect(passkeyErrorMessage('cancelled')).toBe('');
  });
});
