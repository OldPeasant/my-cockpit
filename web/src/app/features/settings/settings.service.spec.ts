import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ApiError, NotificationService, provideHttp } from '../../core/http';
import { ThemeService } from '../../shell/theme.service';
import { SettingsService } from './settings.service';
import { fakeSettings } from './testing';

const PATH = '/api/v1/settings';

/** Lets pending promise callbacks (and the HTTP calls they start) run. */
const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

describe('SettingsService', () => {
  let service: SettingsService;
  let http: HttpTestingController;
  let theme: ThemeService;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: { error: vi.fn() } },
      ],
    });
    service = TestBed.inject(SettingsService);
    http = TestBed.inject(HttpTestingController);
    theme = TestBed.inject(ThemeService);
  });

  afterEach(() => http.verify());

  /** Loads `settings` with ETag `"1"`. */
  async function load(settings = fakeSettings()): Promise<void> {
    const loaded = service.load();
    http.expectOne({ method: 'GET', url: PATH }).flush(settings, { headers: { ETag: '"1"' } });
    await loaded;
  }

  it('loads the settings and applies the server theme over the local one', async () => {
    theme.setMode('dark');
    await load(fakeSettings({ theme: 'light' }));

    expect(service.settings()?.timeZone).toBe('Europe/Zurich');
    expect(theme.mode()).toBe('light');
    expect(localStorage.getItem('cockpit.theme')).toBe('light');
  });

  it('shows a change at once and keeps the server answer with its ETag', async () => {
    await load();
    const saved = service.update({ weekStart: 'SUNDAY', theme: 'dark' });
    await tick();

    expect(service.settings()?.weekStart).toBe('SUNDAY');
    expect(theme.mode()).toBe('dark');
    const req = http.expectOne({ method: 'PATCH', url: PATH });
    expect(req.request.headers.get('If-Match')).toBe('"1"');
    expect(req.request.body).toEqual({ weekStart: 'SUNDAY', theme: 'dark' });
    req.flush(fakeSettings({ weekStart: 'SUNDAY', theme: 'dark' }), { headers: { ETag: '"2"' } });

    expect((await saved).weekStart).toBe('SUNDAY');
    const next = service.update({ timeZone: 'UTC' });
    await tick();
    const second = http.expectOne({ method: 'PATCH', url: PATH });
    expect(second.request.headers.get('If-Match')).toBe('"2"');
    second.flush(fakeSettings({ timeZone: 'UTC' }), { headers: { ETag: '"3"' } });
    await next;
  });

  it('rolls the values and the theme back when the server refuses the change', async () => {
    await load();
    const saved = service.update({ theme: 'dark', weekStart: 'FRIDAY' });
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

    await expect(saved).rejects.toBeInstanceOf(ApiError);
    expect(service.settings()?.weekStart).toBe('MONDAY');
    expect(theme.mode()).toBe('light');
  });

  it('reloads the server state after a 412, so the next change sends the new ETag', async () => {
    await load();
    const saved = service.update({ weekStart: 'SUNDAY' });
    await tick();
    http
      .expectOne({ method: 'PATCH', url: PATH })
      .flush(
        { status: 412, title: 'Precondition Failed' },
        { status: 412, statusText: 'Precondition Failed' },
      );
    await tick();
    http
      .expectOne({ method: 'GET', url: PATH })
      .flush(fakeSettings({ weekStart: 'SATURDAY' }), { headers: { ETag: '"9"' } });

    await expect(saved).rejects.toMatchObject({ status: 412 });
    expect(service.settings()?.weekStart).toBe('SATURDAY');
  });
});
