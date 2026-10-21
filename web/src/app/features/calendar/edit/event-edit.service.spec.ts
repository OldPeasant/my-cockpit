import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ApiError, NotificationService, provideHttp, SILENT_ERRORS } from '../../../core/http';
import { fakeEvent, fakeOccurrence } from '../views/testing';
import {
  EditScope,
  EventEditService,
  isConflict,
  isGenerated,
  isSeries,
} from './event-edit.service';

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

const OCCURRENCE = '2026-03-30T07:00:00Z';

/** A single (not repeating) event. */
const single = () => fakeOccurrence('Dentist', { eventId: 'e1', version: 3 });
/** An occurrence the series master `m1` generates. */
const generated = () =>
  fakeOccurrence('Swim', { eventId: 'm1', seriesId: 'm1', occurrence: OCCURRENCE, version: 5 });
/** An occurrence an override `o1` of the series `m1` replaces. */
const override = () =>
  fakeOccurrence('Swim', { eventId: 'o1', seriesId: 'm1', occurrence: OCCURRENCE, version: 2 });

describe('EventEditService', () => {
  let service: EventEditService;
  let http: HttpTestingController;
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
    service = TestBed.inject(EventEditService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  /** The one PATCH sent: its event id, scope, occurrence and If-Match. */
  function expectPatch(id: string) {
    const req = http.expectOne(
      (r) => r.method === 'PATCH' && r.url === `/api/v1/calendar/events/${id}`,
    );
    return {
      req,
      scope: req.request.params.get('scope'),
      occurrence: req.request.params.get('occurrence'),
      ifMatch: req.request.headers.get('If-Match'),
    };
  }

  it('gets an event without toasting errors', async () => {
    const result = service.get('e1');
    const req = http.expectOne((r) => r.method === 'GET' && r.url === '/api/v1/calendar/events/e1');
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush(fakeEvent('e1'));
    await expect(result).resolves.toEqual(fakeEvent('e1'));
  });

  it('creates an event', async () => {
    const body = {
      title: 'Dentist',
      calendarId: 'family',
      allDay: false,
      start: '2026-03-23T08:00:00Z',
      end: '2026-03-23T09:00:00Z',
      timeZone: 'Europe/Zurich',
    };
    const result = service.create(body);
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/calendar/events');
    expect(req.request.body).toEqual(body);
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush(fakeEvent('e1'));
    await expect(result).resolves.toMatchObject({ id: 'e1' });
  });

  it('changes a single event directly, with its version as If-Match', async () => {
    const result = service.update(single(), undefined, { title: 'Dentist (moved)' });
    await tick();
    const patch = expectPatch('e1');
    expect(patch).toMatchObject({ scope: null, occurrence: null, ifMatch: '"3"' });
    expect(patch.req.request.body).toEqual({ title: 'Dentist (moved)' });
    expect(patch.req.request.context.get(SILENT_ERRORS)).toBe(true);
    patch.req.flush(fakeEvent('e1', { version: 4 }));
    await expect(result).resolves.toMatchObject({ version: 4 });
  });

  for (const scope of ['THIS', 'FOLLOWING', 'ALL'] as EditScope[]) {
    it(`changes a generated occurrence through its master with scope ${scope}`, async () => {
      void service.update(generated(), scope, { title: 'Swim' });
      await tick();
      const patch = expectPatch('m1');
      expect(patch).toMatchObject({ scope, occurrence: OCCURRENCE, ifMatch: '"5"' });
      patch.req.flush(fakeEvent('m1'));
    });
  }

  it('changes only this generated occurrence when no scope is given', async () => {
    void service.update(generated(), undefined, { title: 'Swim' });
    await tick();
    expect(expectPatch('m1')).toMatchObject({ scope: 'THIS', occurrence: OCCURRENCE });
    http.match(() => true).forEach((req) => req.flush({}));
  });

  for (const scope of ['THIS', 'FOLLOWING', 'ALL'] as EditScope[]) {
    it(`changes an override with scope ${scope} on the override itself, with its own version`, async () => {
      void service.update(override(), scope, { title: 'Swim' });
      await tick();
      const patch = expectPatch('o1');
      // The override stands for its occurrence: no occurrence parameter, no extra request.
      expect(patch).toMatchObject({ scope, occurrence: null, ifMatch: '"2"' });
      http.expectNone((r) => r.method === 'GET');
      patch.req.flush(fakeEvent('o1'));
    });
  }

  describe('reread', () => {
    const occurrencesUrl = '/api/v1/calendar/events';

    it('finds a generated occurrence again in a range around its original start', async () => {
      const result = service.reread(generated());
      const req = http.expectOne((r) => r.method === 'GET' && r.url === occurrencesUrl);
      expect(req.request.params.get('from')).toBe('2026-03-29T07:00:00.000Z');
      expect(req.request.params.get('to')).toBe('2026-03-31T07:00:00.000Z');
      expect(req.request.params.getAll('calendarIds')).toEqual(['work']);
      expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
      const moved = { ...generated(), start: '2026-03-30T09:00:00Z', version: 6 };
      req.flush({
        items: [
          fakeOccurrence('Other', { seriesId: 'm2', occurrence: OCCURRENCE }),
          { ...generated(), occurrence: '2026-04-06T07:00:00Z' },
          moved,
        ],
      });
      await expect(result).resolves.toEqual(moved);
    });

    it('covers the days around the date of an all-day occurrence', async () => {
      void service.reread(
        fakeOccurrence('Holiday', {
          eventId: 'm1',
          seriesId: 'm1',
          occurrence: '2026-04-06',
          allDay: true,
        }),
      );
      const req = http.expectOne((r) => r.method === 'GET' && r.url === occurrencesUrl);
      expect(req.request.params.get('from')).toBe('2026-04-05T00:00:00.000Z');
      expect(req.request.params.get('to')).toBe('2026-04-08T00:00:00.000Z');
      req.flush({ items: [] });
    });

    it('is undefined when the series no longer has the occurrence', async () => {
      const result = service.reread(generated());
      http.expectOne((r) => r.method === 'GET' && r.url === occurrencesUrl).flush({ items: [] });
      await expect(result).resolves.toBeUndefined();
    });
  });

  it('reports a stale version as a conflict, silently', async () => {
    const result = service.update(single(), undefined, { title: 'Dentist' });
    await tick();
    expectPatch('e1').req.flush(
      { status: 412, title: 'Precondition Failed' },
      { status: 412, statusText: 'Precondition Failed' },
    );
    const error = await result.catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(isConflict(error)).toBe(true);
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('deletes a single event directly and an occurrence by scope', async () => {
    const one = service.delete(single(), undefined);
    await tick();
    const first = http.expectOne((r) => r.method === 'DELETE');
    expect(first.request.url).toBe('/api/v1/calendar/events/e1');
    expect(first.request.headers.get('If-Match')).toBe('"3"');
    expect(first.request.params.has('scope')).toBe(false);
    expect(first.request.context.get(SILENT_ERRORS)).toBe(true);
    first.flush(null, { status: 204, statusText: 'No Content' });
    await expect(one).resolves.toBeUndefined();

    void service.delete(generated(), 'FOLLOWING');
    await tick();
    const second = http.expectOne((r) => r.method === 'DELETE');
    expect(second.request.url).toBe('/api/v1/calendar/events/m1');
    expect(second.request.params.get('scope')).toBe('FOLLOWING');
    expect(second.request.params.get('occurrence')).toBe(OCCURRENCE);
    expect(second.request.headers.get('If-Match')).toBe('"5"');
    second.flush(null, { status: 204, statusText: 'No Content' });

    void service.delete(override(), 'THIS');
    await tick();
    const third = http.expectOne((r) => r.method === 'DELETE');
    expect(third.request.url).toBe('/api/v1/calendar/events/o1');
    expect(third.request.headers.get('If-Match')).toBe('"2"');
    third.flush(null, { status: 204, statusText: 'No Content' });

    void service.delete(override(), 'ALL');
    await tick();
    const fourth = http.expectOne((r) => r.method === 'DELETE');
    expect(fourth.request.url).toBe('/api/v1/calendar/events/o1');
    expect(fourth.request.params.get('scope')).toBe('ALL');
    expect(fourth.request.params.has('occurrence')).toBe(false);
    expect(fourth.request.headers.get('If-Match')).toBe('"2"');
    fourth.flush(null, { status: 204, statusText: 'No Content' });
  });
});

describe('isConflict', () => {
  it('is a 409 or 412 API error', () => {
    expect(isConflict(new ApiError(409, 'Conflict'))).toBe(true);
    expect(isConflict(new ApiError(412, 'Precondition Failed'))).toBe(true);
    expect(isConflict(new ApiError(400, 'Bad Request'))).toBe(false);
    expect(isConflict(new ApiError(404, 'Not Found'))).toBe(false);
    expect(isConflict(new ApiError(0, 'Offline'))).toBe(false);
    expect(isConflict({ status: 409 })).toBe(false);
    expect(isConflict(new Error('409'))).toBe(false);
    expect(isConflict(undefined)).toBe(false);
  });
});

describe('isGenerated', () => {
  it('is an occurrence of a series that no override replaces', () => {
    expect(isGenerated(generated())).toBe(true);
    expect(isGenerated(override())).toBe(false);
    expect(isGenerated(single())).toBe(false);
  });
});

describe('isSeries', () => {
  it('needs both the series and the original start of the occurrence', () => {
    expect(isSeries(generated())).toBe(true);
    expect(isSeries(override())).toBe(true);
    expect(isSeries(single())).toBe(false);
    expect(isSeries(fakeOccurrence('Swim', { seriesId: 'm1' }))).toBe(false);
    expect(isSeries(fakeOccurrence('Swim', { occurrence: OCCURRENCE }))).toBe(false);
  });
});
