import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { CreationOptionsJson } from '../../../api/generated';
import { ApiError, NotificationService, provideHttp, SILENT_ERRORS } from '../../../core/http';
import { CredentialJson, PasskeyError, WebAuthn } from '../../auth/passkey/webauthn';
import { SecurityService } from './security.service';

const PASSKEYS = '/api/v1/auth/passkeys';
const REGISTRATION_OPTIONS = '/api/v1/auth/passkeys/registration/options';
const REGISTRATION = '/api/v1/auth/passkeys/registration';

/** Fictitious creation options; the service hands them to WebAuthn unchanged. */
const CREATION: CreationOptionsJson = {
  rp: { id: 'localhost', name: 'My Cockpit' },
  user: { id: 'AQID', name: 'demo', displayName: 'Demo Owner' },
  challenge: 'Y2hhbGxlbmdl',
  pubKeyCredParams: [{ type: 'public-key', alg: -7 }],
  timeout: 300_000,
  excludeCredentials: [],
  authenticatorSelection: {
    requireResidentKey: true,
    residentKey: 'required',
    userVerification: 'required',
  },
  attestation: 'none',
};

const CREDENTIAL: CredentialJson = {
  id: 'AQIDBA',
  rawId: 'AQIDBA',
  type: 'public-key',
  response: { clientDataJSON: 'e30', attestationObject: 'AA', transports: ['internal'] },
  clientExtensionResults: {},
};

const PASSKEY = {
  id: '0192f0c4-0000-7000-8000-0000000000a1',
  name: 'Laptop',
  createdAt: '2026-10-20T08:00:00Z',
};

/** Lets the Api's promise chain (and the WebAuthn ceremony) run before the backend is inspected. */
const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

describe('SecurityService', () => {
  let service: SecurityService;
  let http: HttpTestingController;
  let notifications: { error: ReturnType<typeof vi.fn> };
  let webAuthn: { supported: ReturnType<typeof vi.fn>; create: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    notifications = { error: vi.fn() };
    webAuthn = { supported: vi.fn(() => true), create: vi.fn(() => Promise.resolve(CREDENTIAL)) };
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: notifications },
        { provide: WebAuthn, useValue: webAuthn },
      ],
    });
    service = TestBed.inject(SecurityService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists the devices without toasting errors', async () => {
    const devices = service.devices();
    const req = http.expectOne({ method: 'GET', url: '/api/v1/auth/devices' });
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush([
      {
        id: 'd1',
        name: 'Pixel 9',
        createdAt: '2026-10-01T08:00:00Z',
        lastSeenAt: '2026-10-19T17:30:00Z',
      },
    ]);

    expect((await devices).map((d) => d.name)).toEqual(['Pixel 9']);
  });

  it('revokes a device with DELETE', async () => {
    const done = service.revoke('0192f0c4-0000-7000-8000-000000000001');
    http
      .expectOne({
        method: 'DELETE',
        url: '/api/v1/auth/devices/0192f0c4-0000-7000-8000-000000000001',
      })
      .flush(null, { status: 204, statusText: 'No Content' });

    await expect(done).resolves.toBeUndefined();
  });

  it('regenerates recovery codes with the TOTP code and returns them', async () => {
    const codes = service.regenerateRecoveryCodes('123456');
    const req = http.expectOne({ method: 'POST', url: '/api/v1/auth/recovery-codes' });
    expect(req.request.body).toEqual({ totp: '123456' });
    req.flush({ recoveryCodes: ['abcde-fghjk'] });

    expect(await codes).toEqual(['abcde-fghjk']);
  });

  it('rejects a failed device list or revoke with an ApiError and does not toast it', async () => {
    const devices = service.devices();
    http
      .expectOne('/api/v1/auth/devices')
      .flush(
        { title: 'Server error', status: 500, detail: 'Database down' },
        { status: 500, statusText: 'Error' },
      );
    const listError = await devices.catch((e: unknown) => e);
    expect(listError).toBeInstanceOf(ApiError);
    expect((listError as ApiError).status).toBe(500);

    const revoked = service.revoke('0192f0c4-0000-7000-8000-000000000002');
    const req = http.expectOne('/api/v1/auth/devices/0192f0c4-0000-7000-8000-000000000002');
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush(
      { title: 'Not Found', status: 404, detail: 'Device not found' },
      { status: 404, statusText: 'Not Found' },
    );
    const revokeError = await revoked.catch((e: unknown) => e);
    expect(revokeError).toBeInstanceOf(ApiError);
    expect((revokeError as ApiError).status).toBe(404);
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('rejects with an ApiError and does not toast it', async () => {
    const codes = service.regenerateRecoveryCodes('000000');
    http
      .expectOne('/api/v1/auth/recovery-codes')
      .flush({ title: 'Bad Request', status: 400 }, { status: 400, statusText: 'Bad Request' });

    const error = await codes.catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(400);
    expect(notifications.error).not.toHaveBeenCalled();
  });

  describe('passkeys', () => {
    it('lists the passkeys without toasting errors', async () => {
      const passkeys = service.passkeys();
      const req = http.expectOne({ method: 'GET', url: PASSKEYS });
      expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
      req.flush([PASSKEY]);

      expect(await passkeys).toEqual([PASSKEY]);
    });

    it('asks WebAuthn whether passkeys are supported', () => {
      expect(service.passkeysSupported()).toBe(true);
      webAuthn.supported.mockReturnValue(false);
      expect(service.passkeysSupported()).toBe(false);
    });

    it('adds a passkey: TOTP starts the ceremony, the credential is stored under the name', async () => {
      const added = service.addPasskey('Laptop', '123456');

      const start = http.expectOne({ method: 'POST', url: REGISTRATION_OPTIONS });
      expect(start.request.body).toEqual({ totp: '123456' });
      expect(start.request.context.get(SILENT_ERRORS)).toBe(true);
      start.flush({ ceremonyId: 'ceremony-1', publicKey: CREATION });
      await tick();

      expect(webAuthn.create).toHaveBeenCalledWith(CREATION);
      const finish = http.expectOne({ method: 'POST', url: REGISTRATION });
      expect(finish.request.body).toEqual({
        ceremonyId: 'ceremony-1',
        credential: CREDENTIAL,
        name: 'Laptop',
      });
      expect(finish.request.context.get(SILENT_ERRORS)).toBe(true);
      finish.flush(PASSKEY, { status: 201, statusText: 'Created' });

      expect(await added).toEqual(PASSKEY);
    });

    it('does not start the ceremony when the TOTP code is wrong (403)', async () => {
      const added = service.addPasskey('Laptop', '000000');
      http
        .expectOne(REGISTRATION_OPTIONS)
        .flush({ title: 'Forbidden', status: 403 }, { status: 403, statusText: 'Forbidden' });

      const error = await added.catch((e: unknown) => e);
      expect(error).toBeInstanceOf(ApiError);
      expect((error as ApiError).status).toBe(403);
      expect(webAuthn.create).not.toHaveBeenCalled();
      http.expectNone(REGISTRATION);
      expect(notifications.error).not.toHaveBeenCalled();
    });

    it('rejects with the PasskeyError of the browser and does not finish the registration', async () => {
      const cancelled = new PasskeyError('cancelled');
      webAuthn.create.mockRejectedValueOnce(cancelled);
      const added = service.addPasskey('Laptop', '123456');
      http.expectOne(REGISTRATION_OPTIONS).flush({ ceremonyId: 'ceremony-2', publicKey: CREATION });

      await expect(added).rejects.toBe(cancelled);
      http.expectNone(REGISTRATION);
    });

    it('rejects with a 409 ApiError when the passkey is already registered', async () => {
      const added = service.addPasskey('Laptop', '123456');
      http.expectOne(REGISTRATION_OPTIONS).flush({ ceremonyId: 'ceremony-3', publicKey: CREATION });
      await tick();
      http
        .expectOne(REGISTRATION)
        .flush({ title: 'Conflict', status: 409 }, { status: 409, statusText: 'Conflict' });

      const error = await added.catch((e: unknown) => e);
      expect(error).toBeInstanceOf(ApiError);
      expect((error as ApiError).status).toBe(409);
      expect(notifications.error).not.toHaveBeenCalled();
    });

    it('deletes a passkey with DELETE and does not toast a 404', async () => {
      const deleted = service.deletePasskey(PASSKEY.id);
      const req = http.expectOne({ method: 'DELETE', url: `${PASSKEYS}/${PASSKEY.id}` });
      expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
      req.flush(null, { status: 204, statusText: 'No Content' });
      await expect(deleted).resolves.toBeUndefined();

      const again = service.deletePasskey(PASSKEY.id);
      http
        .expectOne(`${PASSKEYS}/${PASSKEY.id}`)
        .flush({ title: 'Not Found', status: 404 }, { status: 404, statusText: 'Not Found' });
      const error = await again.catch((e: unknown) => e);
      expect((error as ApiError).status).toBe(404);
      expect(notifications.error).not.toHaveBeenCalled();
    });
  });
});
