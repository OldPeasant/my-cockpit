import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ApiError, NotificationService, provideHttp } from '../http';
import { AuthService } from './auth.service';

describe('AuthService', () => {
  let auth: AuthService;
  let backend: HttpTestingController;
  let notifications: { error: ReturnType<typeof vi.fn>; info: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    notifications = { error: vi.fn(), info: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: notifications },
      ],
    });
    auth = TestBed.inject(AuthService);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  /** Lets the Api's promise chain send the request before the backend is inspected. */
  const tick = () => new Promise((resolve) => setTimeout(resolve));

  it('asks for the setup status once and caches it', async () => {
    const first = auth.setupRequired();
    await tick();
    backend.expectOne('/api/v1/setup/status').flush({ setupRequired: true });
    expect(await first).toBe(true);
    expect(await auth.setupRequired()).toBe(true);
  });

  it('knows the user after /me answers 200', async () => {
    const result = auth.checkSession();
    await tick();
    backend.expectOne('/api/v1/auth/me').flush({ username: 'demo' });
    expect(await result).toBe(true);
    expect(auth.user()).toEqual({ username: 'demo' });
    expect(await auth.checkSession()).toBe(true);
  });

  it('treats a 401 of /me as "not logged in", without a toast', async () => {
    const result = auth.checkSession();
    await tick();
    backend
      .expectOne('/api/v1/auth/me')
      .flush({ status: 401, title: 'Unauthorized' }, { status: 401, statusText: 'Unauthorized' });
    expect(await result).toBe(false);
    expect(auth.loggedIn()).toBe(false);
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('asks /me again on refresh', async () => {
    const first = auth.checkSession();
    await tick();
    backend.expectOne('/api/v1/auth/me').flush({ username: 'demo' });
    await first;
    const second = auth.checkSession(true);
    await tick();
    backend.expectOne('/api/v1/auth/me').flush(null, { status: 401, statusText: 'Unauthorized' });
    expect(await second).toBe(false);
  });

  it('logs in and keeps the user', async () => {
    const result = auth.login({ username: 'demo', password: 'pw', totp: '123456' });
    await tick();
    const req = backend.expectOne('/api/v1/auth/login');
    expect(req.request.body).toEqual({ username: 'demo', password: 'pw', totp: '123456' });
    req.flush({ username: 'demo' });
    await result;
    expect(auth.loggedIn()).toBe(true);
  });

  it('rejects a throttled login with retryAfter and no toast', async () => {
    const result = auth.login({ username: 'demo', password: 'pw', totp: '123456' });
    const failure = result.catch((error: unknown) => error);
    await tick();
    backend
      .expectOne('/api/v1/auth/login')
      .flush(
        { status: 429, title: 'Too Many Requests' },
        { status: 429, statusText: 'Too Many Requests', headers: { 'Retry-After': '42' } },
      );
    const error = await failure;
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).retryAfter).toBe(42);
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('forgets the user on logout, also when the server call fails', async () => {
    const login = auth.login({ username: 'demo', password: 'pw', totp: '123456' });
    await tick();
    backend.expectOne('/api/v1/auth/login').flush({ username: 'demo' });
    await login;
    const logout = auth.logout().catch(() => undefined);
    await tick();
    backend.expectOne('/api/v1/auth/logout').flush(null, { status: 500, statusText: 'Error' });
    await logout;
    expect(auth.loggedIn()).toBe(false);
  });

  it('marks setup as done after the TOTP confirmation', async () => {
    const status = auth.setupRequired();
    await tick();
    backend.expectOne('/api/v1/setup/status').flush({ setupRequired: true });
    await status;
    const confirm = auth.confirmSetup('123456');
    await tick();
    const req = backend.expectOne('/api/v1/setup/confirm-totp');
    expect(req.request.body).toEqual({ code: '123456' });
    req.flush(null, { status: 204, statusText: 'No Content' });
    await confirm;
    expect(await auth.setupRequired()).toBe(false);
  });
});
