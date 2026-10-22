import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RequestOptionsJson } from '../../../api/generated';
import { AuthService } from '../../../core/auth';
import {
  ApiError,
  NotificationService,
  provideHttp,
  SILENT_ERRORS,
  SKIP_LOGIN_REDIRECT,
} from '../../../core/http';
import { PasskeyLogin } from './passkey-login.service';
import { CredentialJson, PasskeyError, WebAuthn } from './webauthn';

const OPTIONS = '/api/v1/auth/passkeys/authentication/options';
const LOGIN = '/api/v1/auth/passkeys/login';
const ME = '/api/v1/auth/me';

const PUBLIC_KEY: RequestOptionsJson = {
  challenge: 'Y2hhbGxlbmdl',
  rpId: 'localhost',
  timeout: 120_000,
  allowCredentials: [],
  userVerification: 'required',
};

/** A fictitious assertion; its content is opaque to the login service. */
const CREDENTIAL: CredentialJson = {
  id: 'AQIDBA',
  rawId: 'AQIDBA',
  type: 'public-key',
  response: { clientDataJSON: 'e30', authenticatorData: 'AA', signature: 'AQ', userHandle: 'AQ' },
  clientExtensionResults: {},
};

describe('PasskeyLogin', () => {
  let service: PasskeyLogin;
  let http: HttpTestingController;
  let notifications: { error: ReturnType<typeof vi.fn>; info: ReturnType<typeof vi.fn> };
  let webAuthn: { supported: ReturnType<typeof vi.fn>; get: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    notifications = { error: vi.fn(), info: vi.fn() };
    webAuthn = {
      supported: vi.fn(() => true),
      get: vi.fn(() => Promise.resolve(CREDENTIAL)),
    };
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: notifications },
        { provide: WebAuthn, useValue: webAuthn },
      ],
    });
    service = TestBed.inject(PasskeyLogin);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  /** Waits until the Api's promise chain has sent the request. */
  async function request(url: string): Promise<TestRequest> {
    let found: TestRequest | undefined;
    await vi.waitFor(() => {
      [found] = http.match(url);
      if (!found) throw new Error(`no request to ${url}`);
    });
    return found as TestRequest;
  }

  function problem(status: number, title: string, headers: Record<string, string> = {}) {
    return [
      { title, status },
      { status, statusText: title, headers },
    ] as const;
  }

  it('reports whether the browser supports passkeys', () => {
    expect(service.supported()).toBe(true);
    webAuthn.supported.mockReturnValue(false);
    expect(service.supported()).toBe(false);
  });

  it('runs the ceremony and refreshes the session', async () => {
    const result = service.login(true);

    const options = await request(OPTIONS);
    expect(options.request.method).toBe('POST');
    expect(options.request.context.get(SILENT_ERRORS)).toBe(true);
    expect(options.request.context.get(SKIP_LOGIN_REDIRECT)).toBe(true);
    options.flush({ ceremonyId: 'ceremony-1', publicKey: PUBLIC_KEY });

    const login = await request(LOGIN);
    expect(webAuthn.get).toHaveBeenCalledWith(PUBLIC_KEY);
    expect(login.request.method).toBe('POST');
    expect(login.request.body).toEqual({
      ceremonyId: 'ceremony-1',
      credential: CREDENTIAL,
      rememberDevice: true,
    });
    expect(login.request.context.get(SILENT_ERRORS)).toBe(true);
    expect(login.request.context.get(SKIP_LOGIN_REDIRECT)).toBe(true);
    login.flush({ username: 'demo' });

    (await request(ME)).flush({ username: 'demo' });

    expect(await result).toEqual({ username: 'demo' });
    expect(TestBed.inject(AuthService).user()).toEqual({ username: 'demo' });
  });

  it('sends rememberDevice false when the box is not ticked', async () => {
    const result = service.login(false);
    (await request(OPTIONS)).flush({ ceremonyId: 'ceremony-2', publicKey: PUBLIC_KEY });
    const login = await request(LOGIN);
    expect(login.request.body.rememberDevice).toBe(false);
    login.flush({ username: 'demo', totpEnrollmentRequired: true });
    (await request(ME)).flush({ username: 'demo', totpEnrollmentRequired: true });

    expect(await result).toEqual({ username: 'demo', totpEnrollmentRequired: true });
  });

  it('rejects with the PasskeyError of the browser and sends no login', async () => {
    const cancelled = new PasskeyError('cancelled');
    webAuthn.get.mockRejectedValueOnce(cancelled);
    const result = service.login(false);
    (await request(OPTIONS)).flush({ ceremonyId: 'ceremony-3', publicKey: PUBLIC_KEY });

    await expect(result).rejects.toBe(cancelled);
    http.expectNone(LOGIN);
    http.expectNone(ME);
  });

  it('rejects with a 401 ApiError when the passkey is not accepted, without a toast or redirect', async () => {
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigateByUrl');
    const result = service.login(false);
    (await request(OPTIONS)).flush({ ceremonyId: 'ceremony-4', publicKey: PUBLIC_KEY });
    (await request(LOGIN)).flush(...problem(401, 'Unauthorized'));

    const error = await result.catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(401);
    http.expectNone(ME);
    expect(notifications.error).not.toHaveBeenCalled();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('rejects with the Retry-After seconds of a throttled login', async () => {
    const result = service.login(false);
    (await request(OPTIONS)).flush({ ceremonyId: 'ceremony-5', publicKey: PUBLIC_KEY });
    (await request(LOGIN)).flush(...problem(429, 'Too Many Requests', { 'Retry-After': '42' }));

    const error = await result.catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(429);
    expect((error as ApiError).retryAfter).toBe(42);
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('does not open the passkey prompt when the options cannot be loaded', async () => {
    const result = service.login(false);
    (await request(OPTIONS)).flush(...problem(500, 'Server error'));

    const error = await result.catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(500);
    expect(webAuthn.get).not.toHaveBeenCalled();
    http.expectNone(LOGIN);
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('rejects with status 0 when the server cannot be reached', async () => {
    const result = service.login(false);
    (await request(OPTIONS)).error(new ProgressEvent('error'), {
      status: 0,
      statusText: 'Unknown Error',
    });

    const error = await result.catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(0);
    expect(webAuthn.get).not.toHaveBeenCalled();
  });
});
