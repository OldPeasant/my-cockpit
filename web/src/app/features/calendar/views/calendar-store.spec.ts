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
