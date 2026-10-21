import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { filter, Subject } from 'rxjs';
import {
  Api,
  ChangeEvent,
  listCalendars,
  listEventOccurrences,
  OccurrenceResponse,
} from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { fakeCalendar, fakeOccurrence } from '../../calendar/views/testing';
import {
  compareEvents,
  eventSpan,
  isOnDate,
  TodayEvent,
  TodayEvents,
  todayRange,
} from './today-events';

type Invoke = (fn: unknown, params?: Record<string, unknown>) => Promise<unknown>;

/** Lets pending promise callbacks run. */
const settle = () => new Promise((resolve) => setTimeout(resolve));

/** An instant on 2026-10-20 in the browser's time zone. */
const at = (hours: number, minutes = 0, day = 20) =>
  new Date(2026, 9, day, hours, minutes).toISOString();

describe('todayRange', () => {
  it('runs from local midnight to the next local midnight', () => {
    expect(todayRange(new Date(2026, 9, 20, 9, 30))).toEqual({
      from: new Date(2026, 9, 20).toISOString(),
      to: new Date(2026, 9, 21).toISOString(),
    });
  });
});

describe('eventSpan', () => {
  const range = todayRange(new Date(2026, 9, 20, 9, 30));

  it('tells all-day, inside, crossing-start, crossing-end and day-spanning events apart', () => {
    expect(eventSpan(fakeOccurrence('a', { allDay: true }), range)).toBe('allDay');
    expect(eventSpan(fakeOccurrence('b', { start: at(9), end: at(10) }), range)).toBe('timed');
    expect(eventSpan(fakeOccurrence('c', { start: at(22, 0, 19), end: at(2) }), range)).toBe(
      'until',
    );
    expect(eventSpan(fakeOccurrence('d', { start: at(22), end: at(2, 0, 21) }), range)).toBe(
      'from',
    );
    expect(eventSpan(fakeOccurrence('e', { start: at(8, 0, 19), end: at(8, 0, 21) }), range)).toBe(
      'allDay',
    );
  });

  it('treats a timed event covering the whole day exactly as all day', () => {
    expect(eventSpan(fakeOccurrence('a', { start: at(0), end: at(0, 0, 21) }), range)).toBe(
      'allDay',
    );
    expect(eventSpan(fakeOccurrence('b', { start: at(0), end: at(12, 0, 21) }), range)).toBe(
      'allDay',
    );
    expect(eventSpan(fakeOccurrence('c', { start: at(12, 0, 19), end: at(0, 0, 21) }), range)).toBe(
      'allDay',
    );
  });

  it('counts an event starting at midnight but ending before the next one as inside today', () => {
    expect(eventSpan(fakeOccurrence('a', { start: at(0), end: at(23, 59) }), range)).toBe('timed');
    expect(eventSpan(fakeOccurrence('b', { start: at(0, 1), end: at(0, 0, 21) }), range)).toBe(
      'timed',
    );
  });

  it('counts an event crossing only one midnight as crossing it, even by a minute', () => {
    expect(eventSpan(fakeOccurrence('a', { start: at(23, 59, 19), end: at(0, 1) }), range)).toBe(
      'until',
    );
    expect(eventSpan(fakeOccurrence('b', { start: at(23, 59), end: at(0, 1, 21) }), range)).toBe(
      'from',
    );
  });

  it('treats a timed occurrence without start or end as all day', () => {
    expect(eventSpan(fakeOccurrence('a', { start: undefined }), range)).toBe('allDay');
    expect(eventSpan(fakeOccurrence('b', { end: undefined }), range)).toBe('allDay');
  });
});

describe('isOnDate', () => {
  const allDay = (startDate: string, endDate?: string) =>
    fakeOccurrence('a', { allDay: true, start: undefined, end: undefined, startDate, endDate });

  it('keeps all-day occurrences whose dates include the day, the end date being exclusive', () => {
    expect(isOnDate(allDay('2026-10-20', '2026-10-21'), '2026-10-20')).toBe(true);
    expect(isOnDate(allDay('2026-10-18', '2026-10-23'), '2026-10-20')).toBe(true);
    expect(isOnDate(allDay('2026-10-19', '2026-10-20'), '2026-10-20')).toBe(false);
    expect(isOnDate(allDay('2026-10-21', '2026-10-22'), '2026-10-20')).toBe(false);
  });

  it('treats an all-day occurrence without end date as one day long', () => {
    expect(isOnDate(allDay('2026-10-20'), '2026-10-20')).toBe(true);
    expect(isOnDate(allDay('2026-10-19'), '2026-10-20')).toBe(false);
  });

  it('keeps timed occurrences and all-day ones without dates', () => {
    expect(isOnDate(fakeOccurrence('a', { startDate: '2026-10-19' }), '2026-10-20')).toBe(true);
    expect(isOnDate(fakeOccurrence('b', { allDay: true }), '2026-10-20')).toBe(true);
  });
});

describe('compareEvents', () => {
  const range = todayRange(new Date(2026, 9, 20, 9, 30));
  const event = (title: string, fields: Partial<OccurrenceResponse>): TodayEvent => {
    const occurrence = fakeOccurrence(title, fields);
    return {
      key: title,
      occurrence,
      span: eventSpan(occurrence, range),
      color: '#2e7d32',
      calendarName: 'Work',
    };
  };
  const order = (...events: TodayEvent[]) =>
    [...events].sort(compareEvents).map((e) => e.occurrence.title);

  it('orders events with the same start by title', () => {
    expect(
      order(
        event('Review', { start: at(10), end: at(11) }),
        event('Lunch', { start: at(10), end: at(10, 30) }),
      ),
    ).toEqual(['Lunch', 'Review']);
  });

  it('orders all-day and day-spanning events by title', () => {
    expect(
      order(
        event('Trip', { start: at(8, 0, 19), end: at(8, 0, 21) }),
        event('Holiday', { allDay: true }),
      ),
    ).toEqual(['Holiday', 'Trip']);
  });

  it('puts all-day first, then events running since yesterday, then by start', () => {
    expect(
      order(
        event('From tonight', { start: at(22), end: at(2, 0, 21) }),
        event('Midnight', { start: at(0), end: at(1) }),
        event('Overnight', { start: at(22, 0, 19), end: at(2) }),
        event('Holiday', { allDay: true }),
      ),
    ).toEqual(['Holiday', 'Overnight', 'Midnight', 'From tonight']);
  });
});

describe('TodayEvents', () => {
  let api: { invoke: ReturnType<typeof vi.fn<Invoke>> };
  let calendars: unknown[];
  let occurrences: unknown[];
  let fixture: ComponentFixture<TodayEvents>;
  let root: HTMLElement;
  let changes: Subject<ChangeEvent>;
  let resyncs: Subject<void>;

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 9, 20, 9, 30));
    calendars = [fakeCalendar('work', { name: 'Work' })];
    occurrences = [];
    changes = new Subject();
    resyncs = new Subject();
    api = {
      invoke: vi.fn<Invoke>((fn) => {
        if (fn === listCalendars) return Promise.resolve({ items: calendars });
        if (fn === listEventOccurrences) return Promise.resolve({ items: occurrences });
        return Promise.reject(new Error(`unexpected call ${String(fn)}`));
      }),
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: Api, useValue: api },
        {
          provide: ChangeEventsService,
          useValue: {
            resync$: resyncs.asObservable(),
            events$: (type: string) => changes.pipe(filter((event) => event.type === type)),
          },
        },
      ],
    });
  });

  afterEach(() => vi.useRealTimers());

  async function render(): Promise<void> {
    fixture = TestBed.createComponent(TodayEvents);
    root = fixture.nativeElement;
    for (let i = 0; i < 3; i++) {
      await settle();
      fixture.detectChanges();
    }
  }

  const titles = () =>
    [...root.querySelectorAll('.event .title')].map((el) => el.textContent!.trim());
  const text = () => root.textContent!.replace(/\s+/g, ' ');

  it("asks for today's occurrences of the visible calendars", async () => {
    calendars = [fakeCalendar('work'), fakeCalendar('hidden', { visible: false })];
    await render();

    expect(api.invoke).toHaveBeenCalledWith(listEventOccurrences, {
      from: new Date(2026, 9, 20).toISOString(),
      to: new Date(2026, 9, 21).toISOString(),
      calendarIds: ['work'],
    });
  });

  it('lists the events time-ordered, all-day first', async () => {
    occurrences = [
      fakeOccurrence('Dentist', { start: at(14), end: at(15), location: 'Fictitious Street 1' }),
      fakeOccurrence('Standup', { start: at(9), end: at(9, 15) }),
      fakeOccurrence('Holiday', { allDay: true, startDate: '2026-10-20', endDate: '2026-10-21' }),
    ];
    await render();

    expect(titles()).toEqual(['Holiday', 'Standup', 'Dentist']);
    expect(root.querySelector('#today-events')?.textContent).toContain('Calendar');
    expect(root.querySelector('#today-events .count')?.textContent?.trim()).toBe('3');
    expect(text()).toContain('All day');
    expect(text()).toContain('Fictitious Street 1');
    expect(text()).toContain('Work');
  });

  it('says so when there are no events today', async () => {
    await render();
    expect(text()).toContain('No events today.');
  });

  it('offers a retry when the events could not be loaded', async () => {
    api.invoke.mockImplementation((fn) =>
      fn === listCalendars
        ? Promise.resolve({ items: calendars })
        : Promise.reject(new Error('offline')),
    );
    await render();
    expect(root.querySelector('[role="alert"]')?.textContent).toContain(
      "Today's events could not be loaded.",
    );

    occurrences = [fakeOccurrence('Standup', { start: at(9), end: at(9, 15) })];
    api.invoke.mockImplementation((fn) =>
      Promise.resolve({ items: fn === listCalendars ? calendars : occurrences }),
    );
    root.querySelector<HTMLButtonElement>('[role="alert"] button')!.click();
    for (let i = 0; i < 3; i++) {
      await settle();
      fixture.detectChanges();
    }
    expect(titles()).toEqual(['Standup']);
  });

  /** Lets answers arrive and re-renders. */
  async function flush(): Promise<void> {
    for (let i = 0; i < 3; i++) {
      await settle();
      fixture.detectChanges();
    }
  }

  /** The parameters of every occurrence request so far. */
  const occurrenceRequests = () =>
    api.invoke.mock.calls.filter(([fn]) => fn === listEventOccurrences).map(([, params]) => params);
  const timeOf = (title: string) =>
    [...root.querySelectorAll('.event')]
      .find((el) => el.querySelector('.title')?.textContent?.trim() === title)
      ?.querySelector('.time')
      ?.textContent?.replace(/\s+/g, ' ')
      .trim();
  const rowOf = (title: string) =>
    [...root.querySelectorAll<HTMLElement>('.event')].find(
      (el) => el.querySelector('.title')?.textContent?.trim() === title,
    );
  const calendarChanged = () =>
    changes.next({ type: 'CALENDAR', id: 'home', op: 'UPDATED', at: '2026-10-20T07:31:00Z' });

  it('labels the part of an event inside today: start-end, from, until, all day', async () => {
    occurrences = [
      fakeOccurrence('Standup', { start: at(9), end: at(9, 15) }),
      fakeOccurrence('Night train', { start: at(22), end: at(6, 0, 21) }),
      fakeOccurrence('Late party', { start: at(21, 0, 19), end: at(1, 30) }),
      fakeOccurrence('Conference', { start: at(8, 0, 19), end: at(18, 0, 21) }),
    ];
    await render();

    expect(titles()).toEqual(['Conference', 'Late party', 'Standup', 'Night train']);
    expect(timeOf('Standup')).toMatch(/^9:00\sAM – 9:15\sAM$/);
    expect(timeOf('Night train')).toMatch(/^From 10:00\sPM$/);
    expect(timeOf('Late party')).toMatch(/^Until 1:30\sAM$/);
    expect(timeOf('Conference')).toBe('All day');
  });

  it('leaves out all-day events of yesterday and tomorrow the server returns for another zone', async () => {
    occurrences = [
      fakeOccurrence('Yesterday', { allDay: true, startDate: '2026-10-19', endDate: '2026-10-20' }),
      fakeOccurrence('Holiday', { allDay: true, startDate: '2026-10-20', endDate: '2026-10-21' }),
      fakeOccurrence('Tomorrow', { allDay: true, startDate: '2026-10-21', endDate: '2026-10-22' }),
      fakeOccurrence('Standup', { start: at(9), end: at(9, 15) }),
    ];
    await render();

    expect(titles()).toEqual(['Holiday', 'Standup']);
    expect(root.querySelector('#today-events .count')?.textContent?.trim()).toBe('2');
  });

  it('labels a timed event covering the whole day as all day', async () => {
    occurrences = [fakeOccurrence('Offsite', { start: at(0), end: at(0, 0, 21) })];
    await render();

    expect(timeOf('Offsite')).toBe('All day');
  });

  it('marks cancelled and tentative events', async () => {
    occurrences = [
      fakeOccurrence('Cancelled', { start: at(9), end: at(10), status: 'CANCELLED' }),
      fakeOccurrence('Maybe', { start: at(11), end: at(12), status: 'TENTATIVE' }),
      fakeOccurrence('Sure', { start: at(13), end: at(14), status: 'CONFIRMED' }),
    ];
    await render();

    expect(rowOf('Cancelled')!.classList).toContain('cancelled');
    expect(rowOf('Cancelled')!.classList).not.toContain('tentative');
    expect(rowOf('Maybe')!.classList).toContain('tentative');
    expect(rowOf('Maybe')!.classList).not.toContain('cancelled');
    expect(rowOf('Sure')!.classList).not.toContain('cancelled');
    expect(rowOf('Sure')!.classList).not.toContain('tentative');
  });

  it('tells screen readers that an event is cancelled or tentative', async () => {
    occurrences = [
      fakeOccurrence('Cancelled', { start: at(9), end: at(10), status: 'CANCELLED' }),
      fakeOccurrence('Maybe', { start: at(11), end: at(12), status: 'TENTATIVE' }),
      fakeOccurrence('Sure', { start: at(13), end: at(14), status: 'CONFIRMED' }),
    ];
    await render();

    const status = (title: string) =>
      [...rowOf(title)!.querySelectorAll('.visually-hidden')].map((el) => el.textContent!.trim());
    expect(status('Cancelled')).toEqual(['Cancelled']);
    expect(status('Maybe')).toEqual(['Tentative']);
    expect(status('Sure')).toEqual([]);
  });

  it("shows each event in its calendar's color and name", async () => {
    calendars = [
      fakeCalendar('work', { name: 'Work', color: '#1565c0' }),
      fakeCalendar('home', { name: 'Home', color: undefined }),
    ];
    occurrences = [
      fakeOccurrence('Standup', { start: at(9), end: at(9, 15) }),
      fakeOccurrence('Groceries', { calendarId: 'home', start: at(18), end: at(19) }),
    ];
    await render();

    expect(rowOf('Standup')!.style.getPropertyValue('--event-color')).toBe('#1565c0');
    expect(rowOf('Standup')!.querySelector('.calendar')?.textContent).toBe('Work');
    expect(rowOf('Groceries')!.style.getPropertyValue('--event-color')).toBe(
      'var(--mat-sys-primary)',
    );
    expect(rowOf('Groceries')!.querySelector('.calendar')?.textContent).toBe('Home');
    expect(rowOf('Groceries')!.querySelector('.location')).toBeNull();
  });

  it('never shows occurrences of a hidden calendar, even when the server returns them', async () => {
    calendars = [fakeCalendar('work'), fakeCalendar('hidden', { visible: false })];
    occurrences = [
      fakeOccurrence('Standup', { start: at(9), end: at(9, 15) }),
      fakeOccurrence('Secret', { calendarId: 'hidden', start: at(10), end: at(11) }),
      fakeOccurrence('Unknown', { calendarId: 'deleted', start: at(12), end: at(13) }),
    ];
    await render();

    expect(titles()).toEqual(['Standup']);
    expect(root.querySelector('#today-events .count')?.textContent?.trim()).toBe('1');
  });

  it('shows loading until the events arrive', async () => {
    let answer!: (value: unknown) => void;
    api.invoke.mockImplementation((fn) =>
      fn === listCalendars
        ? Promise.resolve({ items: calendars })
        : new Promise((resolve) => (answer = resolve)),
    );
    await render();
    expect(root.querySelector('[role="status"]')?.textContent).toContain('Loading events…');
    expect(root.querySelector('#today-events .count')).toBeNull();

    answer({ items: [fakeOccurrence('Standup', { start: at(9), end: at(9, 15) })] });
    await flush();
    expect(root.querySelector('[role="status"]')).toBeNull();
    expect(titles()).toEqual(['Standup']);
  });

  it('reloads for the new calendars when a calendar is shown or hidden', async () => {
    calendars = [fakeCalendar('work'), fakeCalendar('home', { visible: false })];
    occurrences = [fakeOccurrence('Standup', { start: at(9), end: at(9, 15) })];
    await render();
    expect(occurrenceRequests()).toHaveLength(1);

    calendars = [fakeCalendar('work'), fakeCalendar('home', { visible: true, version: 2 })];
    occurrences = [
      fakeOccurrence('Standup', { start: at(9), end: at(9, 15) }),
      fakeOccurrence('Groceries', { calendarId: 'home', start: at(18), end: at(19) }),
    ];
    calendarChanged();
    await flush();

    expect(occurrenceRequests()).toHaveLength(2);
    expect(occurrenceRequests()[1]).toEqual({
      from: new Date(2026, 9, 20).toISOString(),
      to: new Date(2026, 9, 21).toISOString(),
      calendarIds: ['home', 'work'],
    });
    expect(titles()).toEqual(['Standup', 'Groceries']);
  });

  it('does not reload when the calendars change but the visible ones stay the same', async () => {
    await render();
    calendars = [fakeCalendar('work', { name: 'Work (renamed)', version: 2 })];
    calendarChanged();
    await flush();

    expect(api.invoke.mock.calls.filter(([fn]) => fn === listCalendars)).toHaveLength(2);
    expect(occurrenceRequests()).toHaveLength(1);
  });

  it('shows no events, without asking, when every calendar is hidden', async () => {
    occurrences = [fakeOccurrence('Standup', { start: at(9), end: at(9, 15) })];
    await render();
    expect(titles()).toEqual(['Standup']);

    calendars = [fakeCalendar('work', { visible: false, version: 2 })];
    calendarChanged();
    await flush();

    expect(occurrenceRequests()).toHaveLength(1);
    expect(titles()).toEqual([]);
    expect(text()).toContain('No events today.');
  });

  it('keeps the newest calendars when an older answer arrives last', async () => {
    calendars = [fakeCalendar('work'), fakeCalendar('home', { visible: false })];
    const answers: ((value: unknown) => void)[] = [];
    api.invoke.mockImplementation((fn) =>
      fn === listCalendars
        ? Promise.resolve({ items: calendars })
        : new Promise((resolve) => answers.push(resolve)),
    );
    await render();

    calendars = [fakeCalendar('work'), fakeCalendar('home', { visible: true, version: 2 })];
    calendarChanged();
    await flush();
    expect(answers).toHaveLength(2);

    answers[1]({
      items: [
        fakeOccurrence('Standup', { start: at(9), end: at(9, 15) }),
        fakeOccurrence('Groceries', { calendarId: 'home', start: at(18), end: at(19) }),
      ],
    });
    await flush();
    answers[0]({ items: [fakeOccurrence('Stale', { start: at(8), end: at(9) })] });
    await flush();

    expect(titles()).toEqual(['Standup', 'Groceries']);
  });

  it('refreshes when an event changes or the change stream resyncs', async () => {
    await render();
    expect(text()).toContain('No events today.');

    occurrences = [fakeOccurrence('Standup', { start: at(9), end: at(9, 15) })];
    changes.next({ type: 'EVENT', id: 'event-Standup', op: 'CREATED', at: '2026-10-20T07:31:00Z' });
    await flush();
    expect(titles()).toEqual(['Standup']);

    occurrences = [];
    resyncs.next();
    await flush();
    expect(titles()).toEqual([]);
    expect(occurrenceRequests()).toHaveLength(3);
  });

  it('ignores change notifications of other entity types', async () => {
    await render();
    changes.next({ type: 'TASK', id: 't1', op: 'CREATED', at: '2026-10-20T07:31:00Z' });
    await flush();
    expect(occurrenceRequests()).toHaveLength(1);
  });

  it('offers a retry when the calendars could not be loaded', async () => {
    api.invoke.mockImplementation((fn) =>
      fn === listCalendars
        ? Promise.reject(new Error('offline'))
        : Promise.resolve({ items: occurrences }),
    );
    await render();
    expect(root.querySelector('[role="alert"]')?.textContent).toContain(
      "Today's events could not be loaded.",
    );
    expect(occurrenceRequests()).toHaveLength(0);

    occurrences = [fakeOccurrence('Standup', { start: at(9), end: at(9, 15) })];
    api.invoke.mockImplementation((fn) =>
      Promise.resolve({ items: fn === listCalendars ? calendars : occurrences }),
    );
    root.querySelector<HTMLButtonElement>('[role="alert"] button')!.click();
    await flush();

    expect(root.querySelector('[role="alert"]')).toBeNull();
    expect(occurrenceRequests()).toHaveLength(1);
    expect(titles()).toEqual(['Standup']);
  });

  it('keeps showing the events when a later refresh fails', async () => {
    occurrences = [fakeOccurrence('Standup', { start: at(9), end: at(9, 15) })];
    await render();

    api.invoke.mockImplementation(() => Promise.reject(new Error('offline')));
    changes.next({ type: 'EVENT', id: 'event-Standup', op: 'UPDATED', at: '2026-10-20T07:31:00Z' });
    await flush();

    expect(root.querySelector('[role="alert"]')).toBeNull();
    expect(titles()).toEqual(['Standup']);
  });

  it('links to the calendar', async () => {
    await render();
    expect(root.querySelector('a.open')?.getAttribute('href')).toBe('/calendar');
  });
});
