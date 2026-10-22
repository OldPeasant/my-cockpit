import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import {
  authGuard,
  loginGuard,
  safeReturnUrl,
  setupGuard,
  totpEnrollmentGuard,
} from './auth.guards';
import { ApiError } from '../http';
import { AuthService } from './auth.service';

@Component({ template: 'page' })
class Page {}

describe('auth guards', () => {
  let setupRequired: boolean;
  let loggedIn: boolean;
  let enrollmentRequired: boolean;
  let setupStatus: ReturnType<typeof vi.fn>;
  let checkSession: ReturnType<typeof vi.fn>;
  let harness: RouterTestingHarness;
  let router: Router;

  beforeEach(async () => {
    setupRequired = false;
    loggedIn = false;
    enrollmentRequired = false;
    checkSession = vi.fn(() => Promise.resolve(loggedIn));
    setupStatus = vi.fn(() => Promise.resolve(setupRequired));
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'setup', canActivate: [setupGuard], component: Page },
          { path: 'login', canActivate: [loginGuard], component: Page },
          { path: 'enroll-totp', canActivate: [totpEnrollmentGuard], component: Page },
          {
            path: '',
            canActivate: [authGuard],
            children: [
              { path: '', pathMatch: 'full', component: Page },
              { path: 'inbox', component: Page },
            ],
          },
        ]),
        {
          provide: AuthService,
          useValue: {
            setupRequired: setupStatus,
            checkSession,
            totpEnrollmentRequired: () => enrollmentRequired,
          },
        },
      ],
    });
    harness = await RouterTestingHarness.create();
    router = TestBed.inject(Router);
  });

  describe('authGuard (shell routes)', () => {
    it('lets a logged-in user in', async () => {
      loggedIn = true;
      await harness.navigateByUrl('/inbox');
      expect(router.url).toBe('/inbox');
    });

    it('sends a user without a session to /login with the return URL', async () => {
      await harness.navigateByUrl('/inbox?view=all');
      expect(router.url).toBe('/login?returnUrl=%2Finbox%3Fview%3Dall');
    });

    it('sends everyone to /setup while setup is needed', async () => {
      setupRequired = true;
      await harness.navigateByUrl('/inbox');
      expect(router.url).toBe('/setup');
      expect(checkSession).not.toHaveBeenCalled();
    });
  });

  describe('forced TOTP re-enrollment', () => {
    beforeEach(() => {
      loggedIn = true;
      enrollmentRequired = true;
    });

    it('authGuard sends the session to /enroll-totp with the return URL', async () => {
      await harness.navigateByUrl('/inbox?view=all');
      expect(router.url).toBe('/enroll-totp?returnUrl=%2Finbox%3Fview%3Dall');
    });

    it('authGuard sends the dashboard to /enroll-totp without a return URL', async () => {
      await harness.navigateByUrl('/');
      expect(router.url).toBe('/enroll-totp');
    });

    it('loginGuard sends the session to /enroll-totp, keeping the return URL', async () => {
      await harness.navigateByUrl('/login?returnUrl=%2Finbox');
      expect(router.url).toBe('/enroll-totp?returnUrl=%2Finbox');
    });

    it('totpEnrollmentGuard opens the page and asks the server again', async () => {
      await harness.navigateByUrl('/enroll-totp?returnUrl=%2Finbox');
      expect(router.url).toBe('/enroll-totp?returnUrl=%2Finbox');
      expect(checkSession).toHaveBeenCalledWith(true);
    });

    it('totpEnrollmentGuard sends a normal session to the return URL', async () => {
      enrollmentRequired = false;
      await harness.navigateByUrl('/enroll-totp?returnUrl=%2Finbox');
      expect(router.url).toBe('/inbox');
    });

    it('totpEnrollmentGuard sends a user without a session to /login', async () => {
      loggedIn = false;
      await harness.navigateByUrl('/enroll-totp?returnUrl=%2Finbox');
      expect(router.url).toBe('/login?returnUrl=%2Finbox');
    });

    it('totpEnrollmentGuard sends to /setup while setup is needed', async () => {
      setupRequired = true;
      await harness.navigateByUrl('/enroll-totp');
      expect(router.url).toBe('/setup');
    });

    it('loginGuard sends the session to /enroll-totp without a return URL', async () => {
      await harness.navigateByUrl('/login');
      expect(router.url).toBe('/enroll-totp');
    });

    it('loginGuard drops an unsafe return URL on the way to /enroll-totp', async () => {
      await harness.navigateByUrl('/login?returnUrl=%2F%2Fevil.example');
      expect(router.url).toBe('/enroll-totp');
    });

    it('totpEnrollmentGuard sends a normal session to the dashboard for an unsafe return URL', async () => {
      enrollmentRequired = false;
      await harness.navigateByUrl('/enroll-totp?returnUrl=https%3A%2F%2Fevil.example');
      expect(router.url).toBe('/');
    });

    it('totpEnrollmentGuard does not loop when the return URL is the enrollment page', async () => {
      enrollmentRequired = false;
      await harness.navigateByUrl('/enroll-totp?returnUrl=%2Fenroll-totp');
      expect(router.url).toBe('/');
    });

    it('totpEnrollmentGuard sends a user without a session to /login without a return URL', async () => {
      loggedIn = false;
      await harness.navigateByUrl('/enroll-totp');
      expect(router.url).toBe('/login');
    });

    it('totpEnrollmentGuard sends to /login when the setup status fails', async () => {
      setupStatus.mockImplementation(() => Promise.reject(new ApiError(0, 'unreachable')));
      await harness.navigateByUrl('/enroll-totp');
      expect(router.url).toBe('/login');
    });

    it('totpEnrollmentGuard sends to /login when the server fails', async () => {
      checkSession.mockImplementation(() => Promise.reject(new ApiError(502, 'Bad Gateway')));
      await harness.navigateByUrl('/enroll-totp');
      expect(router.url).toBe('/login');
    });
  });

  describe('setupGuard', () => {
    it('opens /setup while setup is needed', async () => {
      setupRequired = true;
      await harness.navigateByUrl('/setup');
      expect(router.url).toBe('/setup');
    });

    it('redirects /setup to /login once setup is done', async () => {
      await harness.navigateByUrl('/setup');
      expect(router.url).toBe('/login');
    });
  });

  describe('loginGuard', () => {
    it('shows the login page without a session', async () => {
      await harness.navigateByUrl('/login');
      expect(router.url).toBe('/login');
    });

    it('asks the server again, as an expired session leads here', async () => {
      await harness.navigateByUrl('/login');
      expect(checkSession).toHaveBeenCalledWith(true);
    });

    it('sends a logged-in user to the return URL', async () => {
      loggedIn = true;
      await harness.navigateByUrl('/login?returnUrl=%2Finbox');
      expect(router.url).toBe('/inbox');
    });

    it('sends a logged-in user without a return URL to the dashboard', async () => {
      loggedIn = true;
      await harness.navigateByUrl('/login');
      expect(router.url).toBe('/');
    });

    it('does not loop when the return URL is the login page itself', async () => {
      loggedIn = true;
      await harness.navigateByUrl('/login?returnUrl=%2Flogin%3FreturnUrl%3D%252Finbox');
      expect(router.url).toBe('/');
    });

    it('ignores a return URL to another site', async () => {
      loggedIn = true;
      await harness.navigateByUrl('/login?returnUrl=%2F%2Fevil.example');
      expect(router.url).toBe('/');
    });

    it('sends to /setup while setup is needed', async () => {
      setupRequired = true;
      await harness.navigateByUrl('/login');
      expect(router.url).toBe('/setup');
    });
  });

  describe('when the server fails (unreachable, 5xx)', () => {
    const outage = () => Promise.reject(new ApiError(502, 'Bad Gateway'));

    it('authGuard sends to /login with the return URL when /setup/status fails', async () => {
      setupStatus.mockImplementation(outage);
      await harness.navigateByUrl('/inbox');
      expect(router.url).toBe('/login?returnUrl=%2Finbox');
    });

    it('authGuard sends to /login with the return URL when /me fails', async () => {
      checkSession.mockImplementation(outage);
      await harness.navigateByUrl('/inbox');
      expect(router.url).toBe('/login?returnUrl=%2Finbox');
    });

    it('loginGuard shows the login page when /setup/status fails', async () => {
      setupStatus.mockImplementation(outage);
      await harness.navigateByUrl('/login');
      expect(router.url).toBe('/login');
    });

    it('loginGuard shows the login page when /me fails', async () => {
      checkSession.mockImplementation(outage);
      await harness.navigateByUrl('/login');
      expect(router.url).toBe('/login');
    });

    it('setupGuard shows /setup when the setup status is unknown', async () => {
      setupStatus.mockImplementation(outage);
      await harness.navigateByUrl('/setup');
      expect(router.url).toBe('/setup');
    });
  });

  describe('safeReturnUrl', () => {
    it.each([
      ['/inbox?x=1', '/inbox?x=1'],
      [null, '/'],
      [undefined, '/'],
      ['inbox', '/'],
      ['/', '/'],
      [
        '/notes/0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b#top',
        '/notes/0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b#top',
      ],
      ['', '/'],
      ['https://evil.example', '/'],
      ['//evil.example', '/'],
      ['/\\evil.example', '/'],
      ['javascript:alert(1)', '/'],
      ['/login', '/'],
      ['/login?returnUrl=%2F', '/'],
      ['/setup', '/'],
      ['/enroll-totp', '/'],
      ['/enroll-totp?returnUrl=%2Finbox', '/'],
      ['/login#x', '/'],
      ['/setup?step=2', '/'],
      ['/enroll-totp#top', '/'],
      ['/enroll-totp-help', '/enroll-totp-help'],
      ['/logins', '/logins'],
    ])('%s -> %s', (input, expected) => {
      expect(safeReturnUrl(input)).toBe(expected);
    });
  });
});
