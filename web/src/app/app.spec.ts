import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { App } from './app';
import { routes } from './app.routes';
import { AuthService } from './core/auth';
import { provideFakeSession } from './core/auth/testing';
import { provideSilentEventSource } from './core/events/testing';
import { CaptureDialog } from './features/capture/capture-dialog';
import { CommandsPaletteProvider } from './features/search/commands-palette.provider';
import { LazySearchPaletteProvider } from './features/search/search.providers';
import { CommandPalette } from './shell/command-palette';
import { NAV_SECTIONS } from './shell/nav-sections';
import { NavigationPaletteProvider } from './shell/navigation-palette.provider';
import { PALETTE_PROVIDERS } from './shell/shell-tokens';
import { ShortcutsDialog } from './shell/shortcuts-dialog';

describe('App', () => {
  let harness: RouterTestingHarness;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter(routes), provideFakeSession(), provideSilentEventSource()],
    });
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => {
    TestBed.inject(MatDialog).closeAll();
  });

  function openDialogs(): unknown[] {
    return TestBed.inject(MatDialog).openDialogs.map((ref) => ref.componentInstance);
  }

  function keydown(init: KeyboardEventInit): void {
    document.body.dispatchEvent(
      new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...init }),
    );
  }

  it('should create the app', () => {
    expect(TestBed.createComponent(App).componentInstance).toBeTruthy();
  });

  it('renders the shell with the dashboard placeholder at /', async () => {
    await harness.navigateByUrl('/');
    const root = harness.routeNativeElement as HTMLElement;
    expect(root.querySelector('app-shell .brand')?.textContent?.trim()).toBe('My Cockpit');
    expect(root.querySelector('app-coming-soon h1')?.textContent?.trim()).toBe('Dashboard');
  });

  /** Sections whose feature is built and so no longer shows the placeholder. */
  const BUILT = [
    '/settings',
    '/trash',
    '/inbox',
    '/today',
    '/next',
    '/waiting',
    '/someday',
    '/scheduled',
    '/logbook',
    '/calendar',
    '/projects',
    '/files',
    '/notes',
    '/mail',
    '/habits',
    '/review',
    '/bookmarks',
  ];

  it.each(NAV_SECTIONS.filter((s) => !BUILT.includes(s.path)).map((s) => [s.path, s.label]))(
    'lazy-loads a coming soon page for %s',
    async (path, label) => {
      await harness.navigateByUrl(path);
      const root = harness.routeNativeElement as HTMLElement;
      expect(root.querySelector('app-coming-soon h1')?.textContent?.trim()).toBe(label);
      expect(root.querySelector('app-coming-soon p')?.textContent?.trim()).toBe(
        'This section is coming soon.',
      );
      expect(document.title).toBe(label);
      const active = root.querySelector('a.link.active');
      expect(active?.textContent?.trim()).toBe(label);
      expect(active?.getAttribute('aria-current')).toBe('page');
    },
  );

  it('lazy-loads the settings page at /settings', async () => {
    await harness.navigateByUrl('/settings');
    const root = harness.routeNativeElement as HTMLElement;
    expect(root.querySelector('app-settings-layout h1')?.textContent?.trim()).toBe('Settings');
    expect(document.title).toBe('Settings');
    expect(root.querySelector('a.link.active')?.getAttribute('aria-current')).toBe('page');
  });

  it('lazy-loads the inbox page at /inbox', async () => {
    await harness.navigateByUrl('/inbox');
    const root = harness.routeNativeElement as HTMLElement;
    expect(root.querySelector('app-inbox-page h1')?.textContent?.trim()).toBe('Inbox');
    expect(root.querySelector('app-coming-soon')).toBeNull();
    expect(document.title).toBe('Inbox');
    expect(root.querySelector('a.link.active')?.getAttribute('aria-current')).toBe('page');
  });

  it('lazy-loads the mail list at /mail', async () => {
    await harness.navigateByUrl('/mail');
    const root = harness.routeNativeElement as HTMLElement;
    expect(root.querySelector('app-mail-list-page h1')?.textContent?.trim()).toBe('All inboxes');
    expect(root.querySelector('app-coming-soon')).toBeNull();
    expect(document.title).toBe('Mail');
    expect(root.querySelector('a.link.active')?.getAttribute('aria-current')).toBe('page');
  });

  it('lazy-loads the bookmarks page at /bookmarks', async () => {
    await harness.navigateByUrl('/bookmarks');
    const root = harness.routeNativeElement as HTMLElement;
    expect(root.querySelector('app-bookmarks-page h1')?.textContent?.trim()).toBe('Bookmarks');
    expect(root.querySelector('app-coming-soon')).toBeNull();
    expect(document.title).toBe('Bookmarks');
    expect(root.querySelector('a.link.active')?.getAttribute('aria-current')).toBe('page');
  });

  it('shows a not found page for unknown URLs', async () => {
    await harness.navigateByUrl('/no-such-section');
    const root = harness.routeNativeElement as HTMLElement;
    expect(root.querySelector('app-not-found h1')?.textContent?.trim()).toBe('Page not found');
    expect(TestBed.inject(Router).url).toBe('/no-such-section');
  });

  it('wires the capture dialog into the shell: Q opens its dialog', async () => {
    await harness.navigateByUrl('/');
    keydown({ key: 'q' });
    await vi.waitFor(() => expect(openDialogs()).toHaveLength(1));
    expect(openDialogs()[0]).toBeInstanceOf(CaptureDialog);
  });

  // Regression guard for the route-level providers in app.routes.ts: the palette dialog must see
  // the PALETTE_PROVIDERS registered on the shell route, not only the root injector's empty default.
  it('wires the navigation commands into the palette: Ctrl+K, type, Enter navigates', async () => {
    await harness.navigateByUrl('/');
    keydown({ key: 'k', ctrlKey: true });
    expect(openDialogs()[0]).toBeInstanceOf(CommandPalette);
    const palette = await vi.waitFor(() => {
      const el = document.querySelector<HTMLElement>('app-command-palette');
      expect(el).not.toBeNull();
      return el!;
    });
    const labels = (): string[] =>
      Array.from(palette.querySelectorAll('[role="option"] .label')).map(
        (e) => e.textContent?.trim() ?? '',
      );
    await vi.waitFor(() => {
      TestBed.tick();
      expect(labels()).toEqual([...NAV_SECTIONS.map((s) => s.label), 'New task']);
    });

    const input = palette.querySelector<HTMLInputElement>('input')!;
    input.value = 'trash';
    input.dispatchEvent(new Event('input'));
    await vi.waitFor(() => {
      TestBed.tick();
      expect(labels()).toEqual(['Trash']);
    });
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }));
    await vi.waitFor(() => expect(TestBed.inject(Router).url).toBe('/trash'));
    await harness.fixture.whenStable();
    const root = harness.routeNativeElement as HTMLElement;
    expect(root.querySelector('app-trash-page h1')?.textContent?.trim()).toBe('Trash');
  });

  // CR-1/CR-2 follow-up: every dialog the shell opens (shortcut overview, capture)
  // resolves its providers through the shell route, like the palette.
  it('opens the shortcut overview and the capture dialog with the shell route providers', async () => {
    await harness.navigateByUrl('/');
    const paletteProviders = [
      expect.any(NavigationPaletteProvider),
      expect.any(CommandsPaletteProvider),
      expect.any(LazySearchPaletteProvider),
    ];
    const routeProviders = (index = 0): readonly unknown[] =>
      TestBed.inject(MatDialog).openDialogs[index].componentRef!.injector.get(PALETTE_PROVIDERS);

    keydown({ key: '?', shiftKey: true });
    expect(openDialogs()[0]).toBeInstanceOf(ShortcutsDialog);
    expect(routeProviders()).toEqual(paletteProviders);
    TestBed.inject(MatDialog).closeAll();
    await vi.waitFor(() => expect(openDialogs()).toEqual([]));

    keydown({ key: 'q' });
    await vi.waitFor(() => expect(openDialogs()).toHaveLength(1));
    expect(openDialogs()[0]).toBeInstanceOf(CaptureDialog);
    expect(routeProviders()).toEqual(paletteProviders);
  });
});

// p0-web-auth: the guards are wired into the real app routes (setup, login, every shell route).
describe('App routes with auth guards', () => {
  let harness: RouterTestingHarness;
  let router: Router;
  let state: { setupRequired: boolean; loggedIn: boolean; enrollmentRequired: boolean };
  let auth: {
    setupRequired: ReturnType<typeof vi.fn>;
    checkSession: ReturnType<typeof vi.fn>;
    login: ReturnType<typeof vi.fn>;
    user: () => { username: string } | null;
    loggedIn: () => boolean;
    totpEnrollmentRequired: () => boolean;
    startTotpEnrollment: ReturnType<typeof vi.fn>;
    confirmTotpEnrollment: ReturnType<typeof vi.fn>;
    logout: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    state = { setupRequired: false, loggedIn: false, enrollmentRequired: false };
    auth = {
      setupRequired: vi.fn(() => Promise.resolve(state.setupRequired)),
      checkSession: vi.fn(() => Promise.resolve(state.loggedIn)),
      login: vi.fn(() => {
        state.loggedIn = true;
        return Promise.resolve({
          username: 'demo',
          totpEnrollmentRequired: state.enrollmentRequired,
        });
      }),
      user: () => (state.loggedIn ? { username: 'demo' } : null),
      loggedIn: () => state.loggedIn,
      totpEnrollmentRequired: () => state.loggedIn && state.enrollmentRequired,
      startTotpEnrollment: vi.fn(() =>
        Promise.resolve({
          totpSecret: 'JBSWY3DPEHPK3PXP',
          otpauthUri: 'otpauth://totp/My%20Cockpit:demo?secret=JBSWY3DPEHPK3PXP',
        }),
      ),
      confirmTotpEnrollment: vi.fn(() => {
        state.enrollmentRequired = false;
        return Promise.resolve();
      }),
      logout: vi.fn(() => Promise.resolve()),
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter(routes),
        { provide: AuthService, useValue: auth },
        provideSilentEventSource(),
      ],
    });
    harness = await RouterTestingHarness.create();
    router = TestBed.inject(Router);
  });

  afterEach(() => {
    TestBed.inject(MatDialog).closeAll();
  });

  /** The whole rendered app (the routed element itself is the page component). */
  function page(): HTMLElement {
    return harness.fixture.nativeElement as HTMLElement;
  }

  it('sends every URL to the setup page while setup is needed', async () => {
    state.setupRequired = true;
    for (const url of ['/', '/inbox', '/login', '/no-such-section']) {
      await harness.navigateByUrl(url);
      expect(router.url).toBe('/setup');
    }
    expect(page().querySelector('app-setup h1')?.textContent).toContain('Create your account');
    expect(document.title).toBe('Set up My Cockpit');
  });

  it('closes the setup page once setup is done', async () => {
    await harness.navigateByUrl('/setup');
    expect(router.url).toBe('/login');
    expect(page().querySelector('app-login h1')?.textContent).toContain('Sign in');
  });

  it.each(['/', ...NAV_SECTIONS.map((s) => s.path), '/no-such-section'])(
    'guards the shell route %s: without a session it shows the login page with the return URL',
    async (path) => {
      await harness.navigateByUrl(path);
      expect(router.url).toBe(`/login?returnUrl=${encodeURIComponent(path)}`);
      expect(page().querySelector('app-shell')).toBeNull();
      expect(page().querySelector('app-login')).not.toBeNull();
      expect(document.title).toBe('Sign in');
    },
  );

  it('signs in on /login and returns to the URL that needed the session', async () => {
    await harness.navigateByUrl('/trash');
    expect(router.url).toBe('/login?returnUrl=%2Ftrash');

    const root = page();
    const type = (selector: string, value: string): void => {
      const input = root.querySelector<HTMLInputElement>(selector)!;
      input.value = value;
      input.dispatchEvent(new Event('input'));
    };
    type('input[formControlName=username]', 'demo');
    type('input[formControlName=password]', 'a fictitious password');
    type('input[name=totp]', '123456');
    root.querySelector('form')!.dispatchEvent(new Event('submit'));

    await vi.waitFor(() => expect(router.url).toBe('/trash'));
    await harness.fixture.whenStable();
    expect(auth.login).toHaveBeenCalledTimes(1);
    expect(page().querySelector('app-trash-page h1')?.textContent?.trim()).toBe('Trash');
  });

  it('shows the login form (not a blank page) while the server fails, and recovers afterwards', async () => {
    const outage = () => Promise.reject(new Error('502 Bad Gateway'));
    auth.setupRequired.mockImplementation(outage);
    await harness.navigateByUrl('/inbox');
    expect(router.url).toBe('/login?returnUrl=%2Finbox');
    expect(page().querySelector('app-shell')).toBeNull();
    expect(page().querySelector('app-login form')).not.toBeNull();

    // Server back (the session cookie was valid all along): retrying the section opens it.
    auth.setupRequired.mockImplementation(() => Promise.resolve(false));
    state.loggedIn = true;
    await harness.navigateByUrl('/inbox');
    expect(router.url).toBe('/inbox');
    expect(page().querySelector('app-shell')).not.toBeNull();
  });

  it('sends a logged-in user from /login straight to the return URL', async () => {
    state.loggedIn = true;
    await harness.navigateByUrl('/login?returnUrl=%2Fnotes');
    expect(router.url).toBe('/notes');
    expect(auth.checkSession).toHaveBeenCalledWith(true);
  });

  // web-totp-reenroll: after a restore without secrets the session may only set up TOTP again.
  it('leads a login that needs TOTP enrollment through /enroll-totp back to the return URL', async () => {
    state.enrollmentRequired = true;
    await harness.navigateByUrl('/trash');
    expect(router.url).toBe('/login?returnUrl=%2Ftrash');

    const type = (selector: string, value: string): void => {
      const input = page().querySelector<HTMLInputElement>(selector)!;
      input.value = value;
      input.dispatchEvent(new Event('input'));
    };
    type('input[formControlName=username]', 'demo');
    type('input[formControlName=password]', 'a fictitious password');
    type('input[name=totp]', '123456');
    page().querySelector('form')!.dispatchEvent(new Event('submit'));

    await vi.waitFor(() => expect(router.url).toBe('/enroll-totp?returnUrl=%2Ftrash'));
    await vi.waitFor(() =>
      expect(page().querySelector('app-totp-enrollment .secret')).not.toBeNull(),
    );
    expect(page().querySelector('app-shell')).toBeNull();
    expect(document.title).toBe('Set up your authenticator app');
    expect(auth.startTotpEnrollment).toHaveBeenCalledTimes(1);

    type('input[name=code]', '654321');
    page().querySelector('app-totp-enrollment form')!.dispatchEvent(new Event('submit'));
    await vi.waitFor(() => expect(router.url).toBe('/trash'));
    expect(auth.confirmTotpEnrollment).toHaveBeenCalledWith('654321');
    await harness.fixture.whenStable();
    expect(page().querySelector('app-trash-page h1')?.textContent?.trim()).toBe('Trash');
  });

  it('keeps a session that needs TOTP enrollment out of every shell route', async () => {
    state.loggedIn = true;
    state.enrollmentRequired = true;
    await harness.navigateByUrl('/notes');
    expect(router.url).toBe('/enroll-totp?returnUrl=%2Fnotes');
    expect(page().querySelector('app-shell')).toBeNull();
    expect(page().querySelector('app-totp-enrollment')).not.toBeNull();
  });

  it('sends a normal session away from /enroll-totp', async () => {
    state.loggedIn = true;
    await harness.navigateByUrl('/enroll-totp?returnUrl=%2Fnotes');
    expect(router.url).toBe('/notes');
    expect(auth.startTotpEnrollment).not.toHaveBeenCalled();
  });
});
