import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { authGuard, loginGuard, safeReturnUrl, setupGuard } from './auth.guards';
import { AuthService } from './auth.service';

@Component({ template: 'page' })
class Page {}

describe('auth guards', () => {
  let setupRequired: boolean;
  let loggedIn: boolean;
  let checkSession: ReturnType<typeof vi.fn>;
  let harness: RouterTestingHarness;
  let router: Router;

  beforeEach(async () => {
    setupRequired = false;
    loggedIn = false;
    checkSession = vi.fn(() => Promise.resolve(loggedIn));
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'setup', canActivate: [setupGuard], component: Page },
          { path: 'login', canActivate: [loginGuard], component: Page },
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
          useValue: { setupRequired: () => Promise.resolve(setupRequired), checkSession },
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
    ])('%s -> %s', (input, expected) => {
      expect(safeReturnUrl(input)).toBe(expected);
    });
  });
});
