import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { AuthService } from '../../core/auth';
import { NotificationService, provideHttp } from '../../core/http';
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

  it.each([
    ['gtd', 'Tasks & projects'],
    ['security', 'Security'],
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
