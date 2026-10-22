import { TestBed } from '@angular/core/testing';
import { CreationOptionsJson, RequestOptionsJson } from '../../../api/generated';
import { fromBase64Url, PasskeyError, toBase64Url, WebAuthn } from './webauthn';

/** Bytes of an ArrayBuffer (or view) as a plain array, for readable assertions. */
function bytes(buffer: BufferSource | undefined): number[] {
  if (!buffer) throw new Error('no buffer');
  return [...new Uint8Array(ArrayBuffer.isView(buffer) ? buffer.buffer : buffer)];
}

function buffer(...values: number[]): ArrayBuffer {
  return new Uint8Array(values).buffer;
}

function isArrayBuffer(value: unknown): boolean {
  return Object.prototype.toString.call(value) === '[object ArrayBuffer]';
}

/** Fictitious options as the server sends them for `navigator.credentials.create()`. */
const CREATION: CreationOptionsJson = {
  rp: { id: 'localhost', name: 'My Cockpit' },
  user: { id: 'AQID', name: 'demo', displayName: 'Demo Owner' },
  challenge: '-_8',
  pubKeyCredParams: [
    { type: 'public-key', alg: -7 },
    { type: 'public-key', alg: -257 },
  ],
  timeout: 300_000,
  excludeCredentials: [
    { type: 'public-key', id: 'BAUG', transports: ['internal', 'hybrid'] },
    { type: 'public-key', id: 'Bwg' },
  ],
  authenticatorSelection: {
    requireResidentKey: true,
    residentKey: 'required',
    userVerification: 'required',
  },
  attestation: 'none',
};

/** Fictitious options as the server sends them for `navigator.credentials.get()`. */
const REQUEST: RequestOptionsJson = {
  challenge: 'CQoL',
  rpId: 'localhost',
  timeout: 120_000,
  allowCredentials: [
    { type: 'public-key', id: 'DA0', transports: ['usb'] },
    { type: 'public-key', id: 'Dg' },
  ],
  userVerification: 'required',
};

describe('base64url', () => {
  it('encodes without padding and with the URL-safe alphabet', () => {
    expect(toBase64Url(buffer(0xfb, 0xff))).toBe('-_8');
    expect(toBase64Url(buffer(0xfb, 0xef, 0xbe))).toBe('----');
    expect(toBase64Url(buffer(0xff, 0xff, 0xff))).toBe('____');
    expect(toBase64Url(buffer())).toBe('');
  });

  it('decodes values of every padding length', () => {
    expect(bytes(fromBase64Url(''))).toEqual([]);
    expect(bytes(fromBase64Url('AA'))).toEqual([0]);
    expect(bytes(fromBase64Url('AAE'))).toEqual([0, 1]);
    expect(bytes(fromBase64Url('AAEC'))).toEqual([0, 1, 2]);
    expect(bytes(fromBase64Url('-_8'))).toEqual([0xfb, 0xff]);
  });

  it('accepts padded input as well', () => {
    expect(bytes(fromBase64Url('-_8='))).toEqual([0xfb, 0xff]);
  });

  it('round-trips every byte value', () => {
    const all = Array.from({ length: 256 }, (_, i) => i);
    const encoded = toBase64Url(buffer(...all));
    expect(encoded).toMatch(/^[A-Za-z0-9_-]+$/);
    expect(bytes(fromBase64Url(encoded))).toEqual(all);
  });

  it('returns an ArrayBuffer', () => {
    expect(isArrayBuffer(fromBase64Url('AQID'))).toBe(true);
  });
});

describe('WebAuthn', () => {
  let webAuthn: WebAuthn;
  let create: ReturnType<typeof vi.fn>;
  let get: ReturnType<typeof vi.fn>;

  function attestation(overrides: Record<string, unknown> = {}): unknown {
    return {
      id: 'AQIDBA',
      rawId: buffer(1, 2, 3, 4),
      type: 'public-key',
      authenticatorAttachment: 'platform',
      response: {
        clientDataJSON: buffer(10, 11),
        attestationObject: buffer(12, 13, 14),
        getTransports: () => ['internal', 'hybrid'],
      },
      getClientExtensionResults: () => ({ credProps: { rk: true } }),
      ...overrides,
    };
  }

  function assertion(overrides: Record<string, unknown> = {}): unknown {
    return {
      id: 'AQIDBA',
      rawId: buffer(1, 2, 3, 4),
      type: 'public-key',
      authenticatorAttachment: 'cross-platform',
      response: {
        clientDataJSON: buffer(20),
        authenticatorData: buffer(21, 22),
        signature: buffer(23, 24, 25),
        userHandle: buffer(1, 2, 3),
      },
      getClientExtensionResults: () => ({}),
      ...overrides,
    };
  }

  function supportWebAuthn(): void {
    vi.stubGlobal('PublicKeyCredential', vi.fn());
    Object.defineProperty(navigator, 'credentials', {
      configurable: true,
      value: { create, get },
    });
  }

  beforeEach(() => {
    create = vi.fn(() => Promise.resolve(attestation()));
    get = vi.fn(() => Promise.resolve(assertion()));
    webAuthn = TestBed.inject(WebAuthn);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    delete (navigator as { credentials?: unknown }).credentials;
  });

  describe('supported', () => {
    it('is false without PublicKeyCredential (e.g. jsdom or an old browser)', () => {
      expect(webAuthn.supported()).toBe(false);
    });

    it('is false when PublicKeyCredential exists but navigator.credentials does not', () => {
      vi.stubGlobal('PublicKeyCredential', vi.fn());
      expect(webAuthn.supported()).toBe(false);
    });

    it('is false when navigator.credentials exists but PublicKeyCredential does not', () => {
      Object.defineProperty(navigator, 'credentials', {
        configurable: true,
        value: { create, get },
      });
      expect(webAuthn.supported()).toBe(false);
    });

    it('is true with PublicKeyCredential and navigator.credentials.create', () => {
      supportWebAuthn();
      expect(webAuthn.supported()).toBe(true);
    });
  });

  describe('create', () => {
    it('passes the options with binary challenge, user id and excluded credential ids', async () => {
      supportWebAuthn();

      await webAuthn.create(CREATION);

      expect(create).toHaveBeenCalledTimes(1);
      const { publicKey } = create.mock.calls[0][0] as CredentialCreationOptions;
      expect(publicKey).toBeDefined();
      const options = publicKey!;
      expect(options.rp).toEqual({ id: 'localhost', name: 'My Cockpit' });
      expect(options.user.name).toBe('demo');
      expect(options.user.displayName).toBe('Demo Owner');
      expect(isArrayBuffer(options.user.id)).toBe(true);
      expect(bytes(options.user.id)).toEqual([1, 2, 3]);
      expect(isArrayBuffer(options.challenge)).toBe(true);
      expect(bytes(options.challenge)).toEqual([0xfb, 0xff]);
      expect(options.pubKeyCredParams).toEqual(CREATION.pubKeyCredParams);
      expect(options.timeout).toBe(300_000);
      expect(options.authenticatorSelection).toEqual(CREATION.authenticatorSelection);
      expect(options.attestation).toBe('none');

      const excluded = options.excludeCredentials!;
      expect(excluded).toHaveLength(2);
      expect(excluded[0].type).toBe('public-key');
      expect(isArrayBuffer(excluded[0].id)).toBe(true);
      expect(bytes(excluded[0].id)).toEqual([4, 5, 6]);
      expect(excluded[0].transports).toEqual(['internal', 'hybrid']);
      expect(bytes(excluded[1].id)).toEqual([7, 8]);
      expect('transports' in excluded[1]).toBe(false);
    });

    it('returns the created credential as RegistrationResponseJSON', async () => {
      supportWebAuthn();

      expect(await webAuthn.create(CREATION)).toEqual({
        id: 'AQIDBA',
        rawId: 'AQIDBA',
        type: 'public-key',
        authenticatorAttachment: 'platform',
        response: {
          clientDataJSON: 'Cgs',
          attestationObject: 'DA0O',
          transports: ['internal', 'hybrid'],
        },
        clientExtensionResults: { credProps: { rk: true } },
      });
    });

    it('fills in defaults for browsers without getTransports, extension results or attachment', async () => {
      supportWebAuthn();
      create.mockResolvedValueOnce(
        attestation({
          authenticatorAttachment: undefined,
          getClientExtensionResults: undefined,
          response: { clientDataJSON: buffer(1), attestationObject: buffer(2) },
        }),
      );

      const json = await webAuthn.create(CREATION);

      expect(json['authenticatorAttachment']).toBeNull();
      expect(json['clientExtensionResults']).toEqual({});
      expect((json['response'] as Record<string, unknown>)['transports']).toEqual([]);
    });

    it('rejects as unsupported without calling the browser when WebAuthn is missing', async () => {
      const error = await webAuthn.create(CREATION).catch((e: unknown) => e);

      expect(error).toBeInstanceOf(PasskeyError);
      expect((error as PasskeyError).failure).toBe('unsupported');
      expect(create).not.toHaveBeenCalled();
    });

    it('rejects as cancelled when the browser returns no credential', async () => {
      supportWebAuthn();
      create.mockResolvedValueOnce(null);

      const error = await webAuthn.create(CREATION).catch((e: unknown) => e);

      expect(error).toBeInstanceOf(PasskeyError);
      expect((error as PasskeyError).failure).toBe('cancelled');
    });

    it.each([
      ['NotAllowedError', 'cancelled'],
      ['AbortError', 'cancelled'],
      ['InvalidStateError', 'duplicate'],
      ['NotSupportedError', 'unsupported'],
      ['SecurityError', 'failed'],
      ['ConstraintError', 'failed'],
      ['UnknownError', 'failed'],
    ])('maps a %s of the browser to %s and keeps it as the cause', async (name, failure) => {
      supportWebAuthn();
      const domError = new DOMException('fictitious browser message', name);
      create.mockRejectedValueOnce(domError);

      const error = await webAuthn.create(CREATION).catch((e: unknown) => e);

      expect(error).toBeInstanceOf(PasskeyError);
      expect((error as PasskeyError).name).toBe('PasskeyError');
      expect((error as PasskeyError).failure).toBe(failure);
      expect((error as PasskeyError).cause).toBe(domError);
    });

    it.each([
      ['a TypeError', new TypeError('bad options')],
      ['a string', 'boom'],
      ['null', null],
      ['undefined', undefined],
    ])('maps %s thrown by the browser to failed', async (_label, thrown) => {
      supportWebAuthn();
      create.mockRejectedValueOnce(thrown);

      const error = await webAuthn.create(CREATION).catch((e: unknown) => e);

      expect(error).toBeInstanceOf(PasskeyError);
      expect((error as PasskeyError).failure).toBe('failed');
    });

    it('maps a synchronous throw of navigator.credentials.create too', async () => {
      supportWebAuthn();
      create.mockImplementationOnce(() => {
        throw new DOMException('not allowed', 'NotAllowedError');
      });

      const error = await webAuthn.create(CREATION).catch((e: unknown) => e);

      expect((error as PasskeyError).failure).toBe('cancelled');
    });
  });

  describe('get', () => {
    it('passes the options with a binary challenge and allowed credential ids', async () => {
      supportWebAuthn();

      await webAuthn.get(REQUEST);

      expect(get).toHaveBeenCalledTimes(1);
      const options = (get.mock.calls[0][0] as CredentialRequestOptions).publicKey!;
      expect(isArrayBuffer(options.challenge)).toBe(true);
      expect(bytes(options.challenge)).toEqual([9, 10, 11]);
      expect(options.rpId).toBe('localhost');
      expect(options.timeout).toBe(120_000);
      expect(options.userVerification).toBe('required');
      const allowed = options.allowCredentials!;
      expect(allowed).toHaveLength(2);
      expect(isArrayBuffer(allowed[0].id)).toBe(true);
      expect(bytes(allowed[0].id)).toEqual([12, 13]);
      expect(allowed[0].transports).toEqual(['usb']);
      expect(bytes(allowed[1].id)).toEqual([14]);
      expect('transports' in allowed[1]).toBe(false);
    });

    it('passes an empty allow list for discoverable credentials', async () => {
      supportWebAuthn();

      await webAuthn.get({ ...REQUEST, allowCredentials: [] });

      expect(
        (get.mock.calls[0][0] as CredentialRequestOptions).publicKey!.allowCredentials,
      ).toEqual([]);
    });

    it('returns the assertion as AuthenticationResponseJSON', async () => {
      supportWebAuthn();

      expect(await webAuthn.get(REQUEST)).toEqual({
        id: 'AQIDBA',
        rawId: 'AQIDBA',
        type: 'public-key',
        authenticatorAttachment: 'cross-platform',
        response: {
          clientDataJSON: 'FA',
          authenticatorData: 'FRY',
          signature: 'FxgZ',
          userHandle: 'AQID',
        },
        clientExtensionResults: {},
      });
    });

    it('sends a null user handle when the authenticator has none', async () => {
      supportWebAuthn();
      get.mockResolvedValueOnce(
        assertion({
          response: {
            clientDataJSON: buffer(1),
            authenticatorData: buffer(2),
            signature: buffer(3),
            userHandle: null,
          },
        }),
      );

      const json = await webAuthn.get(REQUEST);

      expect((json['response'] as Record<string, unknown>)['userHandle']).toBeNull();
    });

    it('rejects as unsupported without calling the browser when WebAuthn is missing', async () => {
      const error = await webAuthn.get(REQUEST).catch((e: unknown) => e);

      expect(error).toBeInstanceOf(PasskeyError);
      expect((error as PasskeyError).failure).toBe('unsupported');
      expect(get).not.toHaveBeenCalled();
    });

    it('rejects as cancelled when the browser returns no credential', async () => {
      supportWebAuthn();
      get.mockResolvedValueOnce(null);

      const error = await webAuthn.get(REQUEST).catch((e: unknown) => e);

      expect((error as PasskeyError).failure).toBe('cancelled');
    });

    it.each([
      ['NotAllowedError', 'cancelled'],
      ['AbortError', 'cancelled'],
      ['InvalidStateError', 'duplicate'],
      ['NotSupportedError', 'unsupported'],
      ['SecurityError', 'failed'],
    ])('maps a %s of the browser to %s', async (name, failure) => {
      supportWebAuthn();
      get.mockRejectedValueOnce(new DOMException('fictitious browser message', name));

      const error = await webAuthn.get(REQUEST).catch((e: unknown) => e);

      expect(error).toBeInstanceOf(PasskeyError);
      expect((error as PasskeyError).failure).toBe(failure);
    });
  });
});
