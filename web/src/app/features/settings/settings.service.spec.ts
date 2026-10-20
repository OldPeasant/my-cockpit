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
    expect(theme.mode()).toBe('dark');
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

    const next = service.update({ theme: 'dark' });
    await tick();
    const retry = http.expectOne({ method: 'PATCH', url: PATH });
    expect(retry.request.headers.get('If-Match')).toBe('"9"');
    retry.flush(fakeSettings({ weekStart: 'SATURDAY', theme: 'dark' }), {
      headers: { ETag: '"10"' },
    });
    await next;
  });

  it('keeps the previous values and rethrows the 412 when the reload fails too', async () => {
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
      .flush({ status: 503, title: 'Unavailable' }, { status: 503, statusText: 'Unavailable' });

    await expect(saved).rejects.toMatchObject({ status: 412 });
    expect(service.settings()?.weekStart).toBe('MONDAY');
    expect(theme.mode()).toBe('light');
  });

  it('rolls back on a network failure as well', async () => {
    await load();
    const saved = service.update({ theme: 'dark' });
    await tick();
    expect(theme.mode()).toBe('dark');

    http.expectOne({ method: 'PATCH', url: PATH }).error(new ProgressEvent('error'));

    await expect(saved).rejects.toBeInstanceOf(ApiError);
    expect(service.settings()?.theme).toBe('light');
    expect(theme.mode()).toBe('light');
    http.expectNone({ method: 'GET', url: PATH });
  });

  it('loads the settings first when a change comes before they were loaded', async () => {
    const saved = service.update({ weekStart: 'SUNDAY' });
    await tick();
    http
      .expectOne({ method: 'GET', url: PATH })
      .flush(fakeSettings(), { headers: { ETag: '"4"' } });
    await tick();

    const req = http.expectOne({ method: 'PATCH', url: PATH });
    expect(req.request.headers.get('If-Match')).toBe('"4"');
    expect(service.settings()?.weekStart).toBe('SUNDAY');
    req.flush(fakeSettings({ weekStart: 'SUNDAY' }), { headers: { ETag: '"5"' } });
    expect((await saved).weekStart).toBe('SUNDAY');
  });

  it('sends a null reset as is and shows the default from the server answer', async () => {
    await load(fakeSettings({ energyLabels: { LOW: 'Tired', MEDIUM: 'Okay', HIGH: 'Wired' } }));
    const saved = service.update({ energyLabels: null } as never);
    await tick();

    expect(service.settings()?.energyLabels).toEqual({
      LOW: 'Tired',
      MEDIUM: 'Okay',
      HIGH: 'Wired',
    });
    const req = http.expectOne({ method: 'PATCH', url: PATH });
    expect(req.request.body).toEqual({ energyLabels: null });
    req.flush(fakeSettings(), { headers: { ETag: '"2"' } });

    await saved;
    expect(service.settings()?.energyLabels).toEqual({
      LOW: 'Low',
      MEDIUM: 'Medium',
      HIGH: 'High',
    });
  });

  it('runs overlapping changes one after the other, the second from the rolled-back state', async () => {
    await load();
    const first = service.update({ weekStart: 'SUNDAY' });
    const second = service.update({ timeZone: 'UTC' });
    await tick();

    const firstReq = http.expectOne({ method: 'PATCH', url: PATH });
    expect(firstReq.request.body).toEqual({ weekStart: 'SUNDAY' });
    firstReq.flush(
      { status: 400, title: 'Bad Request' },
      { status: 400, statusText: 'Bad Request' },
    );
    await expect(first).rejects.toBeInstanceOf(ApiError);
    await tick();

    const secondReq = http.expectOne({ method: 'PATCH', url: PATH });
    expect(secondReq.request.headers.get('If-Match')).toBe('"1"');
    expect(secondReq.request.body).toEqual({ timeZone: 'UTC' });
    expect(service.settings()?.weekStart).toBe('MONDAY');
    expect(service.settings()?.timeZone).toBe('UTC');
    secondReq.flush(fakeSettings({ timeZone: 'UTC' }), { headers: { ETag: '"2"' } });
    await second;
    expect(service.settings()?.timeZone).toBe('UTC');
  });

  it('keeps a saved change when an older load answers after it', async () => {
    // The login sync (settings.providers.ts) and the settings page both load; the login one
    // answers last, after the user already saved a change.
    const loginLoad = service.load();
    const pageLoad = service.load();
    const [loginReq, pageReq] = http.match({ method: 'GET', url: PATH });
    pageReq.flush(fakeSettings(), { headers: { ETag: '"1"' } });
    await pageLoad;
    const saved = service.update({ theme: 'dark' });
    await tick();
    http
      .expectOne({ method: 'PATCH', url: PATH })
      .flush(fakeSettings({ theme: 'dark' }), { headers: { ETag: '"2"' } });
    await saved;

    loginReq.flush(fakeSettings({ theme: 'light' }), { headers: { ETag: '"1"' } });
    await loginLoad;

    expect(service.settings()?.theme).toBe('dark');
    expect(theme.mode()).toBe('dark');
  });

  it('keeps the shown change and the ETag when a load answers while the save is in flight', async () => {
    await load();
    const saved = service.update({ theme: 'dark' });
    await tick();
    const patch = http.expectOne({ method: 'PATCH', url: PATH });

    const loaded = service.load();
    http
      .expectOne({ method: 'GET', url: PATH })
      .flush(fakeSettings({ theme: 'light' }), { headers: { ETag: '"1"' } });
    expect((await loaded).theme).toBe('dark');
    expect(theme.mode()).toBe('dark');

    patch.flush(fakeSettings({ theme: 'dark' }), { headers: { ETag: '"2"' } });
    await saved;
    const next = service.update({ timeZone: 'UTC' });
    await tick();
    const second = http.expectOne({ method: 'PATCH', url: PATH });
    expect(second.request.headers.get('If-Match')).toBe('"2"');
    second.flush(fakeSettings({ theme: 'dark', timeZone: 'UTC' }), { headers: { ETag: '"3"' } });
    await next;
  });

  it('still takes a load that starts after the last change', async () => {
    await load();
    const loaded = service.load();
    http
      .expectOne({ method: 'GET', url: PATH })
      .flush(fakeSettings({ theme: 'dark' }), { headers: { ETag: '"5"' } });

    expect((await loaded).theme).toBe('dark');
    expect(service.settings()?.theme).toBe('dark');
    expect(theme.mode()).toBe('dark');
  });
});
