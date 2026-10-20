import { TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { Api, listCalendars, updateCalendar } from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { calendarColor, CalendarStore, DEFAULT_CALENDAR_COLOR } from './calendar-store';
import { fakeCalendar } from './testing';

interface Call {
  fn: unknown;
  params: Record<string, unknown>;
  resolve: (value: unknown) => void;
  reject: (error: unknown) => void;
}

/** Lets pending promise callbacks run. */
const settle = () => new Promise((resolve) => setTimeout(resolve));

describe('CalendarStore', () => {
  let calls: Call[];
  let resyncs: Subject<void>;
  let store: CalendarStore;

  const callsOf = (fn: unknown) => calls.filter((call) => call.fn === fn);
  const visible = () => store.calendars().map((c) => `${c.id}:${c.visible}`);

  beforeEach(() => {
    calls = [];
    resyncs = new Subject();
    const api = {
      invoke: (fn: unknown, params: Record<string, unknown> = {}) =>
        new Promise((resolve, reject) => calls.push({ fn, params, resolve, reject })),
    };
    TestBed.configureTestingModule({
      providers: [
        { provide: Api, useValue: api },
        { provide: ChangeEventsService, useValue: { resync$: resyncs.asObservable() } },
      ],
    });
    store = TestBed.inject(CalendarStore);
  });

  async function loaded(...items: ReturnType<typeof fakeCalendar>[]): Promise<void> {
    const done = store.load();
    callsOf(listCalendars).at(-1)!.resolve({ items });
    await done;
  }

  it('loads the calendars with their visibility', async () => {
    await loaded(fakeCalendar('family'), fakeCalendar('work', { visible: false }));
    expect(store.loaded()).toBe(true);
    expect(visible()).toEqual(['family:true', 'work:false']);
    expect([...store.visibleIds()]).toEqual(['family']);
    expect(store.byId().get('work')?.name).toBe('Calendar work');
  });

  it('drops an older list answer that arrives after a newer one', async () => {
    const first = store.load();
    const second = store.load();
    calls[1].resolve({ items: [fakeCalendar('new')] });
    await second;
    calls[0].resolve({ items: [fakeCalendar('old')] });
    await first;
    expect(visible()).toEqual(['new:true']);
  });

  it('keeps the error of a failed load and clears it after a successful one', async () => {
    const failed = store.load();
    calls[0].reject(new Error('offline'));
    await failed;
    expect(store.error()).toBeInstanceOf(Error);
    expect(store.loaded()).toBe(false);

    await loaded(fakeCalendar('family'));
    expect(store.error()).toBeNull();
  });

  it('shows a toggle at once and saves it with If-Match', async () => {
    await loaded(fakeCalendar('family', { version: 3 }));
    const done = store.setVisible(store.calendars()[0], false);
    expect(visible()).toEqual(['family:false']);
    await settle();

    const patch = callsOf(updateCalendar)[0];
    expect(patch.params).toEqual({ id: 'family', 'If-Match': '"3"', body: { visible: false } });
    patch.resolve(fakeCalendar('family', { version: 4, visible: false }));
    await done;
    expect(visible()).toEqual(['family:false']);
    expect(store.calendars()[0].version).toBe(4);
  });

  it('rolls a refused toggle back and rethrows', async () => {
    await loaded(fakeCalendar('family'));
    const done = store.setVisible(store.calendars()[0], false);
    await settle();
    callsOf(updateCalendar)[0].reject(new Error('412'));
    await expect(done).rejects.toThrow('412');
    expect(visible()).toEqual(['family:true']);
  });

  it('sends quick toggles one after the other with the latest version', async () => {
    await loaded(fakeCalendar('family', { version: 1 }));
    const off = store.setVisible(store.calendars()[0], false);
    const on = store.setVisible(store.calendars()[0], true);
    expect(visible()).toEqual(['family:true']);
    await settle();
    expect(callsOf(updateCalendar)).toHaveLength(1);

    callsOf(updateCalendar)[0].resolve(fakeCalendar('family', { version: 2, visible: false }));
    await off;
    // The later toggle still shows while its save runs.
    expect(visible()).toEqual(['family:true']);
    await settle();
    const second = callsOf(updateCalendar)[1];
    expect(second.params['If-Match']).toBe('"2"');
    second.resolve(fakeCalendar('family', { version: 3, visible: true }));
    await on;
    expect(visible()).toEqual(['family:true']);
  });

  it('ignores a list answer requested before a saved toggle', async () => {
    await loaded(fakeCalendar('family'));
    const reload = store.load();
    const done = store.setVisible(store.calendars()[0], false);
    await settle();
    callsOf(updateCalendar)[0].resolve(fakeCalendar('family', { version: 2, visible: false }));
    await done;
    callsOf(listCalendars)
      .at(-1)!
      .resolve({ items: [fakeCalendar('family')] });
    await reload;
    expect(visible()).toEqual(['family:false']);
  });

  it('ignores the error of an older list request after a newer one succeeded', async () => {
    const first = store.load();
    const second = store.load();
    calls[1].resolve({ items: [fakeCalendar('family')] });
    await second;
    calls[0].reject(new Error('offline'));
    await first;
    expect(store.error()).toBeNull();
    expect(visible()).toEqual(['family:true']);
  });

  it('keeps a later toggle shown when an earlier save of the same calendar fails', async () => {
    await loaded(fakeCalendar('family', { version: 1 }));
    const off = store.setVisible(store.calendars()[0], false);
    const on = store.setVisible(store.calendars()[0], true);
    await settle();
    callsOf(updateCalendar)[0].reject(new Error('412'));
    await expect(off).rejects.toThrow('412');
    // The rollback of the failed save must not undo the newer toggle.
    expect(visible()).toEqual(['family:true']);

    await settle();
    const second = callsOf(updateCalendar)[1];
    // Nothing was saved: the second save still sends the version the list returned.
    expect(second.params).toEqual({ id: 'family', 'If-Match': '"1"', body: { visible: true } });
    second.resolve(fakeCalendar('family', { version: 2, visible: true }));
    await on;
    expect(visible()).toEqual(['family:true']);
  });

  it('rolls back to the saved state when the later of two toggles fails', async () => {
    await loaded(fakeCalendar('family', { version: 1 }));
    const off = store.setVisible(store.calendars()[0], false);
    const on = store.setVisible(store.calendars()[0], true);
    await settle();
    callsOf(updateCalendar)[0].resolve(fakeCalendar('family', { version: 2, visible: false }));
    await off;
    await settle();
    callsOf(updateCalendar)[1].reject(new Error('412'));
    await expect(on).rejects.toThrow('412');
    expect(visible()).toEqual(['family:false']);
  });

  it('saves toggles of different calendars one after the other', async () => {
    await loaded(fakeCalendar('family'), fakeCalendar('work', { version: 7 }));
    const [family, work] = store.calendars();
    const a = store.setVisible(family, false);
    const b = store.setVisible(work, false);
    expect(visible()).toEqual(['family:false', 'work:false']);
    await settle();
    expect(callsOf(updateCalendar)).toHaveLength(1);
    callsOf(updateCalendar)[0].resolve(fakeCalendar('family', { version: 2, visible: false }));
    await a;
    await settle();
    expect(callsOf(updateCalendar)[1].params).toEqual({
      id: 'work',
      'If-Match': '"7"',
      body: { visible: false },
    });
    callsOf(updateCalendar)[1].resolve(fakeCalendar('work', { version: 8, visible: false }));
    await b;
    expect(visible()).toEqual(['family:false', 'work:false']);
  });

  it('keeps an unsaved toggle shown over a list reloaded by a resync', async () => {
    await loaded(fakeCalendar('family'));
    const done = store.setVisible(store.calendars()[0], false);
    resyncs.next();
    callsOf(listCalendars)
      .at(-1)!
      .resolve({ items: [fakeCalendar('family'), fakeCalendar('school')] });
    await settle();
    expect(visible()).toEqual(['family:false', 'school:true']);

    callsOf(updateCalendar)[0].resolve(fakeCalendar('family', { version: 2, visible: false }));
    await done;
    expect(visible()).toEqual(['family:false', 'school:true']);
  });

  it('saves a queued toggle with its own version when a resync dropped the calendar', async () => {
    await loaded(fakeCalendar('family'), fakeCalendar('work', { version: 5 }));
    const [family, work] = store.calendars();
    const first = store.setVisible(family, false);
    const second = store.setVisible(work, false);
    resyncs.next();
    callsOf(listCalendars)
      .at(-1)!
      .resolve({ items: [fakeCalendar('family')] });
    await settle();
    callsOf(updateCalendar)[0].resolve(fakeCalendar('family', { version: 2, visible: false }));
    await first;
    await settle();
    expect(callsOf(updateCalendar)[1].params).toEqual({
      id: 'work',
      'If-Match': '"5"',
      body: { visible: false },
    });
    callsOf(updateCalendar)[1].reject(new Error('404'));
    await expect(second).rejects.toThrow('404');
    expect(visible()).toEqual(['family:false']);
  });

  it('reloads after a SSE resync', async () => {
    await loaded(fakeCalendar('family'));
    resyncs.next();
    expect(callsOf(listCalendars)).toHaveLength(2);
  });

  it('falls back to the theme color for calendars without one', () => {
    expect(calendarColor(fakeCalendar('a', { color: '#123456' }))).toBe('#123456');
    expect(calendarColor(fakeCalendar('a', { color: undefined }))).toBe(DEFAULT_CALENDAR_COLOR);
    expect(calendarColor(undefined)).toBe(DEFAULT_CALENDAR_COLOR);
  });
});
