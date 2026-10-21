import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { AuthService } from '../../core/auth';
import { NotificationService, provideHttp } from '../../core/http';
import { BreakpointObserver } from '@angular/cdk/layout';
import { of } from 'rxjs';
import { provideFakeSession } from '../../core/auth/testing';
import { Shell } from '../../shell/shell';
import { CAPTURE_DIALOG, THEME_PREFERENCE } from '../../shell/shell-tokens';
import { ThemeService } from '../../shell/theme.service';
import { provideSettingsSync } from './settings.providers';
import settingsRoutes from './settings.routes';
import { fakeSettings } from './testing';

const PATH = '/api/v1/settings';
const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

describe('settings routes', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'settings', children: settingsRoutes }]),
        { provide: NotificationService, useValue: { error: vi.fn() } },
      ],
    });
  });

  it('opens General at /settings', async () => {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/settings');
    TestBed.inject(HttpTestingController).expectOne(PATH).flush(fakeSettings());

    expect(TestBed.inject(Router).url).toBe('/settings');
    expect(harness.routeNativeElement?.querySelector('app-general-settings')).not.toBeNull();
    expect(harness.routeNativeElement?.querySelector('a.active')?.textContent?.trim()).toBe(
      'General',
    );
  });

  it('opens Security at /settings/security', async () => {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/settings/security');
    TestBed.inject(HttpTestingController).expectOne('/api/v1/auth/devices').flush([]);

    expect(harness.routeNativeElement?.querySelector('app-security-settings')).not.toBeNull();
    expect(harness.routeNativeElement?.querySelector('a.active')?.textContent?.trim()).toBe(
      'Security',
    );
  });

  it('opens Tasks & projects at /settings/gtd', async () => {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/settings/gtd');
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/contexts').flush({ items: [] });
    http.expectOne('/api/v1/areas').flush({ items: [] });

    expect(harness.routeNativeElement?.querySelector('app-gtd-settings')).not.toBeNull();
    expect(harness.routeNativeElement?.querySelector('a.active')?.textContent?.trim()).toBe(
      'Tasks & projects',
    );
  });

  it.each([
    ['calendars', 'Calendars'],
    ['integrations', 'Integrations'],
    ['mail', 'Mail'],
    ['backup', 'Backup & restore'],
  ])('has a placeholder for %s', async (path, title) => {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(`/settings/${path}`);
    const root = harness.routeNativeElement as HTMLElement;

    expect(root.querySelector('app-coming-soon h1')?.textContent?.trim()).toBe(title);
    expect(root.querySelector('a.active')?.getAttribute('aria-current')).toBe('page');
  });
});

describe('provideSettingsSync', () => {
  it('loads the settings on login, so the server theme wins over the local one', async () => {
    localStorage.setItem('cockpit.theme', 'dark');
    const user = signal<{ username: string } | null>(null);
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: { error: vi.fn() } },
        { provide: AuthService, useValue: { user } },
        provideSettingsSync(),
      ],
    });
    const http = TestBed.inject(HttpTestingController);
    const theme = TestBed.inject(ThemeService);
    TestBed.tick();
    http.expectNone(PATH);
    expect(theme.mode()).toBe('dark');

    user.set({ username: 'demo' });
    TestBed.tick();
    http.expectOne(PATH).flush(fakeSettings({ theme: 'light' }));
    await tick();

    expect(theme.mode()).toBe('light');
    http.verify();
  });

  it('loads again when another user logs in after a logout', async () => {
    const user = signal<{ username: string } | null>({ username: 'demo' });
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: { error: vi.fn() } },
        { provide: AuthService, useValue: { user } },
        provideSettingsSync(),
      ],
    });
    const http = TestBed.inject(HttpTestingController);
    const theme = TestBed.inject(ThemeService);
    TestBed.tick();
    http.expectOne(PATH).flush(fakeSettings({ theme: 'dark' }));
    await tick();

    user.set(null);
    TestBed.tick();
    http.expectNone(PATH);

    user.set({ username: 'other-demo' });
    TestBed.tick();
    http.expectOne(PATH).flush(fakeSettings({ theme: 'system' }));
    await tick();

    expect(theme.mode()).toBe('system');
    http.verify();
  });

  it('keeps the local theme without a toast when loading fails', async () => {
    localStorage.setItem('cockpit.theme', 'dark');
    const notifications = { error: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: notifications },
        { provide: AuthService, useValue: { user: signal({ username: 'demo' }) } },
        provideSettingsSync(),
      ],
    });
    const http = TestBed.inject(HttpTestingController);
    TestBed.tick();
    http
      .expectOne(PATH)
      .flush({ status: 500, title: 'Error' }, { status: 500, statusText: 'Server Error' });
    await tick();

    expect(TestBed.inject(ThemeService).mode()).toBe('dark');
    expect(notifications.error).not.toHaveBeenCalled();
  });
});

describe('provideSettingsSync THEME_PREFERENCE', () => {
  let notifications: { error: ReturnType<typeof vi.fn> };
  let user: ReturnType<typeof signal<{ username: string } | null>>;

  function configure(): { http: HttpTestingController; theme: ThemeService } {
    notifications = { error: vi.fn() };
    user = signal<{ username: string } | null>({ username: 'demo' });
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: notifications },
        { provide: AuthService, useValue: { user } },
        provideSettingsSync(),
      ],
    });
    return { http: TestBed.inject(HttpTestingController), theme: TestBed.inject(ThemeService) };
  }

  afterEach(() => localStorage.clear());

  it('applies the theme at once and saves it as the setting with the loaded ETag', async () => {
    const { http, theme } = configure();
    TestBed.tick();
    http.expectOne(PATH).flush(fakeSettings({ theme: 'light' }), { headers: { ETag: '"1"' } });
    await tick();

    const result = TestBed.inject(THEME_PREFERENCE).set('dark');
    await tick();

    expect(result).toBeUndefined();
    expect(theme.mode()).toBe('dark');
    const patch = http.expectOne({ method: 'PATCH', url: PATH });
    expect(patch.request.headers.get('If-Match')).toBe('"1"');
    expect(patch.request.body).toEqual({ theme: 'dark' });
    patch.flush(fakeSettings({ theme: 'dark' }), { headers: { ETag: '"2"' } });
    await tick();

    expect(theme.mode()).toBe('dark');
    expect(localStorage.getItem('cockpit.theme')).toBe('dark');
    http.verify();
  });

  it('keeps the saved theme when the settings are loaded again after the next login', async () => {
    const { http, theme } = configure();
    TestBed.tick();
    http.expectOne(PATH).flush(fakeSettings({ theme: 'system' }), { headers: { ETag: '"1"' } });
    await tick();
    TestBed.inject(THEME_PREFERENCE).set('dark');
    await tick();
    http
      .expectOne({ method: 'PATCH', url: PATH })
      .flush(fakeSettings({ theme: 'dark' }), { headers: { ETag: '"2"' } });
    await tick();

    user.set(null);
    TestBed.tick();
    user.set({ username: 'demo' });
    TestBed.tick();
    http.expectOne({ method: 'GET', url: PATH }).flush(fakeSettings({ theme: 'dark' }));
    await tick();

    expect(theme.mode()).toBe('dark');
    http.verify();
  });

  it('rolls the theme back and shows the error without an unhandled rejection when saving fails', async () => {
    const { http, theme } = configure();
    TestBed.tick();
    http.expectOne(PATH).flush(fakeSettings({ theme: 'light' }), { headers: { ETag: '"1"' } });
    await tick();

    TestBed.inject(THEME_PREFERENCE).set('dark');
    await tick();
    expect(theme.mode()).toBe('dark');
    http.expectOne({ method: 'PATCH', url: PATH }).flush(
      { status: 400, title: 'Bad Request' },
      {
        status: 400,
        statusText: 'Bad Request',
        headers: { 'Content-Type': 'application/problem+json' },
      },
    );
    await tick();

    expect(theme.mode()).toBe('light');
    expect(localStorage.getItem('cockpit.theme')).toBe('light');
    expect(notifications.error).toHaveBeenCalledTimes(1);
    http.verify();
  });

  it('keeps the chosen theme when the login load answers after the change was sent', async () => {
    localStorage.setItem('cockpit.theme', 'light');
    const { http, theme } = configure();
    TestBed.tick();
    const loginLoad = http.expectOne(PATH);

    TestBed.inject(THEME_PREFERENCE).set('dark');
    await tick();
    // The save loads the settings itself first, because none are cached yet.
    const ownLoad = http.match({ method: 'GET', url: PATH });
    expect(ownLoad).toHaveLength(1);
    ownLoad[0].flush(fakeSettings({ theme: 'light' }), { headers: { ETag: '"1"' } });
    await tick();
    const patch = http.expectOne({ method: 'PATCH', url: PATH });
    expect(theme.mode()).toBe('dark');

    loginLoad.flush(fakeSettings({ theme: 'light' }), { headers: { ETag: '"1"' } });
    await tick();
    expect(theme.mode()).toBe('dark');

    patch.flush(fakeSettings({ theme: 'dark' }), { headers: { ETag: '"2"' } });
    await tick();
    expect(theme.mode()).toBe('dark');
    http.verify();
  });

  it('saves the theme picked in the shell menu when the shell route provides the sync', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideFakeSession('demo'),
        { provide: NotificationService, useValue: { error: vi.fn() } },
        {
          provide: BreakpointObserver,
          useValue: { observe: () => of({ matches: false, breakpoints: {} }) },
        },
        provideRouter([
          {
            path: '',
            component: Shell,
            providers: [
              provideSettingsSync(),
              { provide: CAPTURE_DIALOG, useValue: { open: vi.fn() } },
            ],
          },
        ]),
      ],
    });
    const http = TestBed.inject(HttpTestingController);
    const harness = await RouterTestingHarness.create('/');
    TestBed.tick();
    http.expectOne(PATH).flush(fakeSettings({ theme: 'system' }), { headers: { ETag: '"1"' } });
    await tick();
    const root = harness.routeNativeElement as HTMLElement;

    root.querySelector<HTMLButtonElement>('button.theme')!.click();
    await harness.fixture.whenStable();
    document.querySelectorAll<HTMLButtonElement>('[role="menuitemradio"]')[1].click();
    await tick();

    const patch = http.expectOne({ method: 'PATCH', url: PATH });
    expect(patch.request.body).toEqual({ theme: 'dark' });
    patch.flush(fakeSettings({ theme: 'dark' }), { headers: { ETag: '"2"' } });
    await tick();
    expect(TestBed.inject(ThemeService).mode()).toBe('dark');
    http.verify();
  });
});
