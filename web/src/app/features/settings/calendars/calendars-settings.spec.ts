import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { CalendarResponse, CalendarSourceResponse } from '../../../api/generated';
import { NotificationService, provideHttp } from '../../../core/http';
import { BROWSER_NAVIGATE } from './calendar-sources.service';
import CalendarsSettings from './calendars-settings';
import { fakeCalendar, fakeSource, fakeSyncedSource, PROVIDERS } from './testing';

const CALENDARS = '/api/v1/calendar/calendars';
const SOURCES = '/api/v1/calendar/sources';
const PROVIDERS_URL = '/api/v1/oauth/providers';

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

const problem = (status: number, title: string, errors: unknown[] = []) => ({
  status,
  statusText: title,
  headers: { 'Content-Type': 'application/problem+json' },
  body: { status, title, errors },
});

describe('CalendarsSettings', () => {
  let fixture: ComponentFixture<CalendarsSettings>;
  let root: HTMLElement;
  let http: HttpTestingController;
  let navigate: ReturnType<typeof vi.fn>;

  const work = fakeCalendar({
    id: 'c2',
    sourceId: 's1',
    sourceType: 'CALDAV',
    name: 'Work',
    color: '#aa0000',
    isDefault: true,
    version: 3,
  });
  const holidays = fakeCalendar({
    id: 'c3',
    sourceId: 's2',
    sourceType: 'ICS_FEED',
    name: 'Holidays',
    readOnly: true,
    visible: false,
  });

  async function setup(
    calendars: CalendarResponse[] = [fakeCalendar(), work, holidays],
    sources: CalendarSourceResponse[] = [
      fakeSource(),
      fakeSyncedSource(),
      fakeSyncedSource({ id: 's2', type: 'ICS_FEED', name: 'Swiss holidays' }),
    ],
  ): Promise<void> {
    navigate = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: { error: vi.fn() } },
        { provide: BROWSER_NAVIGATE, useValue: navigate },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(CalendarsSettings);
    root = fixture.nativeElement as HTMLElement;
    await settle();
    http.expectOne(CALENDARS).flush({ items: calendars });
    http.expectOne(SOURCES).flush({ items: sources });
    http.expectOne(PROVIDERS_URL).flush(PROVIDERS);
    await settle();
  }

  async function settle(): Promise<void> {
    await tick();
    await fixture.whenStable();
    await tick();
    await fixture.whenStable();
  }

  function flushReload(calendars: CalendarResponse[], sources: CalendarSourceResponse[]): void {
    http.expectOne(CALENDARS).flush({ items: calendars });
    http.expectOne(SOURCES).flush({ items: sources });
  }

  afterEach(() => http.verify());

  function groups(): { source: string; calendars: string[] }[] {
    return [...root.querySelectorAll('.group')].map((group) => ({
      source: group.querySelector('.source')?.textContent?.trim() ?? '',
      calendars: [...group.querySelectorAll('.list .name')].map(
        (el) => el.firstChild?.textContent?.trim() ?? '',
      ),
    }));
  }

  function button(text: string): HTMLButtonElement {
    return [...root.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === text,
    )!;
  }

  async function pickKind(label: string): Promise<void> {
    button(label).click();
    await settle();
  }

  async function type(label: string, value: string): Promise<void> {
    const field = [...root.querySelectorAll('mat-form-field')].find(
      (f) => f.querySelector('mat-label')?.textContent?.trim() === label,
    )!;
    const input = field.querySelector('input') as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await settle();
  }

  it('lists the calendars by source with color, read-only, visibility and the default', async () => {
    await setup();

    expect(groups()).toEqual([
      { source: 'In the cockpit', calendars: ['Personal'] },
      { source: 'dav.example.test', calendars: ['Work'] },
      { source: 'Swiss holidays', calendars: ['Holidays'] },
    ]);
    const swatch = root.querySelector<HTMLInputElement>('input[aria-label="Color of Work"]')!;
    expect(swatch.value).toBe('#aa0000');
    expect(root.textContent).toContain('read-only');
    expect(root.textContent).toContain('Default for new events');
    // The default and read-only calendars cannot become the default.
    expect(root.querySelector('button[aria-label="Use Work for new events"]')).toBeNull();
    expect(root.querySelector('button[aria-label="Use Holidays for new events"]')).toBeNull();
    expect(root.querySelector('button[aria-label="Use Personal for new events"]')).not.toBeNull();
    const hidden = root.querySelector<HTMLInputElement>(
      '[aria-label="Show Holidays in the calendar"]',
    );
    expect(hidden?.getAttribute('aria-checked') ?? String(hidden?.checked)).toBe('false');
  });

  it('shows a load error with a retry', async () => {
    navigate = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: { error: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(CalendarsSettings);
    root = fixture.nativeElement as HTMLElement;
    await settle();
    const { body, ...options } = problem(500, 'Server error');
    http.expectOne(CALENDARS).flush(body, options);
    http.expectOne(SOURCES).flush({ items: [] });
    http.expectOne(PROVIDERS_URL).flush([]);
    await settle();

    expect(root.querySelector('[role="alert"]')?.textContent).toContain('could not be loaded');
    button('Try again').click();
    await settle();
    http.expectOne(CALENDARS).flush({ items: [fakeCalendar()] });
    http.expectOne(SOURCES).flush({ items: [fakeSource()] });
    http.expectOne(PROVIDERS_URL).flush([]);
    await settle();
    expect(groups()).toEqual([{ source: 'In the cockpit', calendars: ['Personal'] }]);
  });

  it('changes the color with If-Match', async () => {
    await setup();
    const swatch = root.querySelector<HTMLInputElement>('input[aria-label="Color of Work"]')!;
    swatch.value = '#00aa00';
    swatch.dispatchEvent(new Event('change'));
    await settle();

    const req = http.expectOne({ method: 'PATCH', url: `${CALENDARS}/c2` });
    expect(req.request.headers.get('If-Match')).toBe('"3"');
    expect(req.request.body).toEqual({ color: '#00aa00' });
    req.flush({ ...work, color: '#00aa00', version: 4 });
    await settle();
    expect(root.querySelector<HTMLInputElement>('input[aria-label="Color of Work"]')!.value).toBe(
      '#00aa00',
    );
  });

  it('shows or hides a calendar', async () => {
    await setup();
    const toggle = root.querySelector<HTMLButtonElement>(
      'button[aria-label="Show Holidays in the calendar"], [aria-label="Show Holidays in the calendar"] button',
    )!;
    toggle.click();
    await settle();

    const req = http.expectOne({ method: 'PATCH', url: `${CALENDARS}/c3` });
    expect(req.request.body).toEqual({ visible: true });
    req.flush({ ...holidays, visible: true, version: 1 });
    await settle();
  });

  it('makes a calendar the default and reloads, since the old default changed too', async () => {
    await setup();
    root
      .querySelector<HTMLButtonElement>('button[aria-label="Use Personal for new events"]')!
      .click();
    await settle();

    const req = http.expectOne({ method: 'PATCH', url: `${CALENDARS}/c1` });
    expect(req.request.body).toEqual({ isDefault: true });
    req.flush({ ...fakeCalendar(), isDefault: true, version: 1 });
    await settle();
    flushReload(
      [
        fakeCalendar({ isDefault: true, version: 1 }),
        { ...work, isDefault: false, version: 4 },
        holidays,
      ],
      [fakeSource(), fakeSyncedSource(), fakeSyncedSource({ id: 's2', name: 'Swiss holidays' })],
    );
    await settle();

    expect(root.querySelector('button[aria-label="Use Work for new events"]')).not.toBeNull();
    expect(root.querySelector('button[aria-label="Use Personal for new events"]')).toBeNull();
  });

  it('explains a concurrent change and shows the current state', async () => {
    await setup();
    const swatch = root.querySelector<HTMLInputElement>('input[aria-label="Color of Work"]')!;
    swatch.value = '#00aa00';
    swatch.dispatchEvent(new Event('change'));
    await settle();
    const { body, ...options } = problem(412, 'Precondition Failed');
    http.expectOne({ method: 'PATCH', url: `${CALENDARS}/c2` }).flush(body, options);
    await settle();
    flushReload(
      [fakeCalendar(), { ...work, color: '#123456', version: 5 }, holidays],
      [fakeSource()],
    );
    await settle();

    expect(root.querySelector('[role="alert"]')?.textContent).toContain(
      'Work could not be changed: It was changed elsewhere',
    );
    expect(root.querySelector<HTMLInputElement>('input[aria-label="Color of Work"]')!.value).toBe(
      '#123456',
    );
  });

  it('adds a local calendar', async () => {
    await setup();
    button('Add calendar').click();
    await settle();
    expect(root.textContent).toContain('Enter a name.');
    http.expectNone({ method: 'POST' });

    await type('Name', ' Family ');
    button('Add calendar').click();
    await settle();
    const req = http.expectOne({ method: 'POST', url: CALENDARS });
    expect(req.request.body).toEqual({ name: 'Family', color: '#3f7fbf' });
    req.flush(fakeCalendar({ id: 'c4', name: 'Family' }), { status: 201, statusText: 'Created' });
    await settle();
    flushReload([fakeCalendar(), fakeCalendar({ id: 'c4', name: 'Family' })], [fakeSource()]);
    await settle();

    expect(root.querySelector('[role="status"]')?.textContent).toContain('Family added.');
    expect(groups()[0].calendars).toEqual(['Personal', 'Family']);
  });

  it('connects a CalDAV account, then fetches its calendars', async () => {
    await setup();
    await pickKind('CalDAV');
    button('Connect CalDAV').click();
    await settle();
    expect(root.textContent).toContain('Enter an http or https URL.');
    expect(root.textContent).toContain('Enter the user name.');
    expect(root.textContent).toContain('Enter the password.');

    await type('Server URL', 'https://cloud.example.test/remote.php/dav');
    await type('User name', 'jane');
    await type('App password', 'fictitious-app-pw');
    button('Connect CalDAV').click();
    await settle();
    const req = http.expectOne({ method: 'POST', url: SOURCES });
    expect(req.request.body).toEqual({
      type: 'CALDAV',
      url: 'https://cloud.example.test/remote.php/dav',
      username: 'jane',
      password: 'fictitious-app-pw',
      name: undefined,
    });
    const added = fakeSyncedSource({ id: 's9', name: 'cloud.example.test' });
    req.flush(added, { status: 201, statusText: 'Created' });
    await settle();
    expect(root.querySelector('[role="status"]')?.textContent).toContain('Fetching its calendars');
    http.expectOne({ method: 'POST', url: `${SOURCES}/s9/sync` }).flush(added);
    await settle();
    flushReload([fakeCalendar({ id: 'c9', sourceId: 's9', name: 'Team' })], [fakeSource(), added]);
    await settle();

    expect(root.querySelector('[role="status"]')?.textContent).toContain(
      'cloud.example.test added.',
    );
    expect(groups()).toContainEqual({ source: 'cloud.example.test', calendars: ['Team'] });
    // The form is empty again: the password does not linger.
    expect(root.querySelector<HTMLInputElement>('input[type="password"]')!.value).toBe('');
  });

  it('reports a CalDAV account whose first sync failed', async () => {
    await setup();
    await pickKind('CalDAV');
    await type('Server URL', 'https://cloud.example.test');
    await type('User name', 'jane');
    await type('App password', 'wrong');
    button('Connect CalDAV').click();
    await settle();
    const added = fakeSyncedSource({ id: 's9', name: 'cloud.example.test' });
    http
      .expectOne({ method: 'POST', url: SOURCES })
      .flush(added, { status: 201, statusText: 'Created' });
    await settle();
    http.expectOne({ method: 'POST', url: `${SOURCES}/s9/sync` }).flush({
      ...added,
      sync: {
        ...added.sync!,
        state: 'ERROR',
        lastError: 'the CalDAV server denied access (HTTP 401)',
      },
    });
    await settle();
    flushReload([], [fakeSource(), added]);
    await settle();

    expect(root.querySelector('[role="alert"]')?.textContent).toContain(
      'cloud.example.test was added, but its calendars could not be fetched: the CalDAV server denied access (HTTP 401)',
    );
  });

  it('subscribes to an ICS feed and shows field errors of the server', async () => {
    await setup();
    await pickKind('ICS feed');
    await type('Feed URL', 'ftp://nope');
    button('Subscribe').click();
    await settle();
    expect(root.textContent).toContain('Enter an http, https or webcal URL.');
    http.expectNone({ method: 'POST', url: SOURCES });

    await type('Feed URL', 'webcal://feeds.example.test/holidays.ics');
    await type('Name (optional)', 'Holidays');
    button('Subscribe').click();
    await settle();
    const req = http.expectOne({ method: 'POST', url: SOURCES });
    expect(req.request.body).toEqual({
      type: 'ICS_FEED',
      url: 'webcal://feeds.example.test/holidays.ics',
      name: 'Holidays',
    });
    const { body, ...options } = problem(400, 'Bad Request', [
      { field: 'url', message: 'must be an absolute URL' },
    ]);
    req.flush(body, options);
    await settle();

    expect(root.querySelector('[role="alert"]')?.textContent).toContain(
      'url: must be an absolute URL',
    );
  });

  it('connects Google through the OAuth redirect, and explains an unconfigured Microsoft', async () => {
    await setup();
    await pickKind('Google');
    button('Connect Google account').click();
    expect(navigate).toHaveBeenCalledWith('/api/v1/oauth/google/start?purpose=CALENDAR');

    await pickKind('Microsoft');
    expect(button('Connect Microsoft account')).toBeUndefined();
    expect(root.textContent).toContain('Microsoft is not set up on this server');
  });
});
