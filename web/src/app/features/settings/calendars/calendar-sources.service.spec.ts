import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { DOCUMENT } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Api } from '../../../api/generated';
import { ApiError, NotificationService, provideHttp } from '../../../core/http';
import { BROWSER_NAVIGATE, CalendarSourcesService } from './calendar-sources.service';
import { fakeCalendar, fakeConnection, fakeSyncedSource, PROVIDERS } from './testing';

const CALENDARS = '/api/v1/calendar/calendars';
const SOURCES = '/api/v1/calendar/sources';

/** Lets pending promise callbacks (and the HTTP calls they start) run. */
const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

describe('CalendarSourcesService', () => {
  let service: CalendarSourcesService;
  let http: HttpTestingController;
  let notify: ReturnType<typeof vi.fn>;
  let navigate: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    notify = vi.fn();
    navigate = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: { error: notify } },
        { provide: BROWSER_NAVIGATE, useValue: navigate },
      ],
    });
    service = TestBed.inject(CalendarSourcesService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists calendars and sources from their item envelopes', async () => {
    const calendars = service.calendars();
    const sources = service.sources();
    await tick();
    http.expectOne({ method: 'GET', url: CALENDARS }).flush({ items: [fakeCalendar()] });
    http.expectOne({ method: 'GET', url: SOURCES }).flush({ items: [fakeSyncedSource()] });

    expect((await calendars).map((c) => c.name)).toEqual(['Personal']);
    expect((await sources).map((s) => s.id)).toEqual(['s1']);
  });

  it('adds a local calendar and an external source with the given body', async () => {
    const local = service.createLocal({ name: 'Family', color: '#3f7fbf' });
    await tick();
    const localReq = http.expectOne({ method: 'POST', url: CALENDARS });
    expect(localReq.request.body).toEqual({ name: 'Family', color: '#3f7fbf' });
    localReq.flush(fakeCalendar({ id: 'c4', name: 'Family' }), {
      status: 201,
      statusText: 'Created',
    });
    expect((await local).id).toBe('c4');

    const source = service.addSource({ type: 'ICS_FEED', url: 'https://feeds.example.test/a.ics' });
    await tick();
    const sourceReq = http.expectOne({ method: 'POST', url: SOURCES });
    expect(sourceReq.request.body).toEqual({
      type: 'ICS_FEED',
      url: 'https://feeds.example.test/a.ics',
    });
    sourceReq.flush(fakeSyncedSource({ id: 's7', type: 'ICS_FEED' }), {
      status: 201,
      statusText: 'Created',
    });
    expect((await source).id).toBe('s7');
  });

  it('changes a calendar at the version it was read with', async () => {
    const saved = service.update(fakeCalendar({ id: 'c2', version: 7 }), { visible: false });
    await tick();

    const req = http.expectOne({ method: 'PATCH', url: `${CALENDARS}/c2` });
    expect(req.request.headers.get('If-Match')).toBe('"7"');
    expect(req.request.body).toEqual({ visible: false });
    req.flush(fakeCalendar({ id: 'c2', visible: false, version: 8 }));
    expect((await saved).version).toBe(8);
  });

  it('syncs a source by id', async () => {
    const synced = service.sync('s1');
    await tick();

    http.expectOne({ method: 'POST', url: `${SOURCES}/s1/sync` }).flush(fakeSyncedSource());
    expect((await synced).sync?.state).toBe('OK');
  });

  it('reads the OAuth providers and connections', async () => {
    const providers = service.providers();
    const connections = service.connections();
    await tick();
    http.expectOne({ method: 'GET', url: '/api/v1/oauth/providers' }).flush(PROVIDERS);
    http.expectOne({ method: 'GET', url: '/api/v1/oauth/connections' }).flush([fakeConnection()]);

    expect((await providers).map((p) => p.slug)).toEqual(['google', 'microsoft']);
    expect((await connections)[0].email).toBe('jane@gmail.example');
  });

  it('rejects with the API error and does not toast it', async () => {
    const added = service.addSource({ type: 'CALDAV', url: 'ftp://nope' });
    const failure = expect(added).rejects.toBeInstanceOf(ApiError);
    await tick();
    http.expectOne({ method: 'POST', url: SOURCES }).flush(
      {
        status: 400,
        title: 'Bad Request',
        errors: [{ field: 'url', message: 'must be an absolute http/https URL' }],
      },
      {
        status: 400,
        statusText: 'Bad Request',
        headers: { 'Content-Type': 'application/problem+json' },
      },
    );

    await failure;
    const error = (await added.catch((e: unknown) => e)) as ApiError;
    expect(error.errors).toEqual([{ field: 'url', message: 'must be an absolute http/https URL' }]);
    const sync = service.sync('s1');
    const syncFailure = expect(sync).rejects.toBeInstanceOf(ApiError);
    await tick();
    http.expectOne({ method: 'POST', url: `${SOURCES}/s1/sync` }).flush(
      { status: 500, title: 'Server error' },
      {
        status: 500,
        statusText: 'Server error',
        headers: { 'Content-Type': 'application/problem+json' },
      },
    );
    await syncFailure;
    expect(notify).not.toHaveBeenCalled();
  });

  it('leaves for the consent screen of Google or Microsoft with purpose CALENDAR', () => {
    service.connect('google');
    service.connect('microsoft');

    expect(navigate.mock.calls).toEqual([
      ['/api/v1/oauth/google/start?purpose=CALENDAR'],
      ['/api/v1/oauth/microsoft/start?purpose=CALENDAR'],
    ]);
    expect(http.match(() => true)).toEqual([]);
  });

  it('remembers a reconnect for its own connection only, once, and forgets it on a new connect', () => {
    service.reconnect('google', 'o1');
    expect(navigate).toHaveBeenCalledWith('/api/v1/oauth/google/start?purpose=CALENDAR');
    expect(service.takeReconnect('o2')).toBe(false);
    expect(service.takeReconnect('o1')).toBe(false);

    service.reconnect('google', 'o1');
    expect(service.takeReconnect('o1')).toBe(true);
    expect(service.takeReconnect('o1')).toBe(false);

    service.reconnect('microsoft', 'o3');
    service.connect('microsoft');
    expect(service.takeReconnect('o3')).toBe(false);
    sessionStorage.clear();
  });

  it('still leaves for the consent screen when the browser blocks sessionStorage', () => {
    const blocked = vi.spyOn(window, 'sessionStorage', 'get').mockImplementation(() => {
      throw new DOMException('The operation is insecure.', 'SecurityError');
    });
    try {
      expect(() => service.reconnect('microsoft', 'o2')).not.toThrow();
      expect(() => service.connect('google')).not.toThrow();
      // Nothing could be remembered, so the callback is treated as a calendar connect.
      expect(service.takeReconnect('o2')).toBe(false);
    } finally {
      blocked.mockRestore();
    }

    expect(navigate.mock.calls).toEqual([
      ['/api/v1/oauth/microsoft/start?purpose=CALENDAR'],
      ['/api/v1/oauth/google/start?purpose=CALENDAR'],
    ]);
  });

  it('puts the API root in front of the consent URL', () => {
    TestBed.inject(Api).rootUrl = 'https://cockpit.example.test';

    service.connect('google');

    expect(navigate).toHaveBeenCalledWith(
      'https://cockpit.example.test/api/v1/oauth/google/start?purpose=CALENDAR',
    );
  });
});

describe('BROWSER_NAVIGATE', () => {
  it('assigns the URL to the document location', () => {
    const assign = vi.fn();
    TestBed.configureTestingModule({
      providers: [{ provide: DOCUMENT, useValue: { location: { assign } } }],
    });

    TestBed.inject(BROWSER_NAVIGATE)('https://accounts.example.test/consent');

    expect(assign).toHaveBeenCalledWith('https://accounts.example.test/consent');
  });
});
