import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';
import {
  CalendarOptions,
  DateSelectInfo,
  DatesSetInfo,
  EventClickInfo,
  EventInput,
  FullCalendarComponent,
} from '@fullcalendar/angular';
import { filter, Subject } from 'rxjs';
import {
  Api,
  ChangeEvent,
  getEvent,
  listCalendars,
  listEventOccurrences,
  OccurrenceResponse,
  listTasks,
  Settings,
  TaskResponse,
  updateCalendar,
} from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { NotificationService } from '../../../core/http';
import { SettingsService } from '../../settings/settings.service';
import { fakeSettings } from '../../settings/testing';
import calendarRoutes from '../calendar.routes';
import { EventEditor } from '../edit/event-editor';
import { wallClockOf } from '../edit/event-times';
import { TASK_OVERLAY_KEY } from '../overlay/task-overlay';
import CalendarView from './calendar-view';
import { fakeCalendar, fakeEvent, fakeOccurrence } from './testing';

interface Call {
  fn: unknown;
  params: Record<string, unknown>;
  resolve: (value: unknown) => void;
  reject: (error: unknown) => void;
}

const settle = () => new Promise((resolve) => setTimeout(resolve));

/** What FullCalendar passes to `eventDrop` and `eventResize`. */
type DropInfo = Parameters<NonNullable<CalendarOptions['eventDrop']>>[0];
type ResizeInfo = Parameters<NonNullable<CalendarOptions['eventResize']>>[0];

/** What FullCalendar passes to `datesSet` for the week of 2026-03-23 in Zurich. */
function week(): DatesSetInfo {
  return {
    start: new Date('2026-03-22T23:00:00Z'),
    end: new Date('2026-03-29T22:00:00Z'),
    view: { type: 'timeGridWeek', currentStart: new Date('2026-03-22T23:00:00Z') },
  } as unknown as DatesSetInfo;
}

/** The week after {@link week} (CEST, after the DST change). */
function nextWeek(): DatesSetInfo {
  return {
    start: new Date('2026-03-29T22:00:00Z'),
    end: new Date('2026-04-05T22:00:00Z'),
    view: { type: 'timeGridWeek', currentStart: new Date('2026-03-29T22:00:00Z') },
  } as unknown as DatesSetInfo;
}

describe('calendar routes', () => {
  it('lazy-loads the calendar view at /calendar', async () => {
    const [route] = calendarRoutes;
    expect(route.path).toBe('');
    expect(route.title).toBe('Calendar');
    expect(await route.loadComponent!()).toMatchObject({ default: CalendarView });
  });

  it('redirects the deep link of an event to the calendar, which opens it', () => {
    const route = calendarRoutes.find((r) => r.path === 'events/:id')!;
    const redirect = route.redirectTo as (snapshot: { params: Record<string, string> }) => string;
    expect(redirect({ params: { id: 'e/1' } })).toBe('/calendar?event=e%2F1');
  });
});

/** A fictitious open task as the server returns it. */
function fakeTask(id: string, fields: Partial<TaskResponse> = {}): TaskResponse {
  return {
    id,
    title: `Task ${id}`,
    list: 'NEXT',
    contextIds: [],
    position: 0,
    createdAt: '2026-03-01T08:00:00Z',
    updatedAt: '2026-03-01T08:00:00Z',
    version: 1,
    ...fields,
  };
}

describe('CalendarView', () => {
  let calls: Call[];
  let resyncs: Subject<void>;
  let changes: Subject<ChangeEvent>;
  let settings: ReturnType<typeof signal<Settings | null>>;
  let settingsLoad: ReturnType<typeof vi.fn>;
  let navigate: ReturnType<typeof vi.fn>;
  let notifications: { error: ReturnType<typeof vi.fn> };
  let editor: {
    create: ReturnType<typeof vi.fn>;
    open: ReturnType<typeof vi.fn>;
    move: ReturnType<typeof vi.fn>;
  };
  let fixture: ComponentFixture<CalendarView>;
  let root: HTMLElement;

  const callsOf = (fn: unknown) => calls.filter((call) => call.fn === fn);
  const fullCalendar = () =>
    fixture.debugElement.query(By.directive(FullCalendarComponent))?.componentInstance as
      FullCalendarComponent | undefined;
  const options = () => fullCalendar()?.options;
  const events = () => (fullCalendar()?.events ?? []) as EventInput[];
  const titles = () => events().map((event) => event.title);

  async function render(query: Record<string, string> = {}): Promise<void> {
    TestBed.configureTestingModule({
      providers: [
        { provide: Api, useValue: api() },
        {
          provide: ChangeEventsService,
          useValue: {
            resync$: resyncs.asObservable(),
            events$: (type: string) => changes.pipe(filter((event) => event.type === type)),
          },
        },
        { provide: SettingsService, useValue: { settings, load: settingsLoad } },
        { provide: NotificationService, useValue: notifications },
        { provide: EventEditor, useValue: editor },
        { provide: Router, useValue: { navigate } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap(query) } },
        },
      ],
    });
    fixture = TestBed.createComponent(CalendarView);
    root = fixture.nativeElement as HTMLElement;
    await fixture.whenStable();
  }

  function api() {
    return {
      invoke: (fn: unknown, params: Record<string, unknown> = {}) =>
        new Promise((resolve, reject) => calls.push({ fn, params, resolve, reject })),
    };
  }

  async function answerCalendars(...items: ReturnType<typeof fakeCalendar>[]): Promise<void> {
    callsOf(listCalendars).at(-1)!.resolve({ items });
    await settle();
    await fixture.whenStable();
  }

  beforeEach(() => {
    calls = [];
    resyncs = new Subject<void>();
    changes = new Subject<ChangeEvent>();
    settings = signal<Settings | null>(
      fakeSettings({ timeZone: 'Europe/Zurich', weekStart: 'MONDAY' }),
    );
    settingsLoad = vi.fn(() => Promise.resolve(settings()));
    navigate = vi.fn(() => Promise.resolve(true));
    notifications = { error: vi.fn() };
    editor = {
      create: vi.fn(() => Promise.resolve(false)),
      open: vi.fn(() => Promise.resolve(false)),
      move: vi.fn(() => Promise.resolve(true)),
    };
  });

  it('shows the week view by default in the time zone and week start of the settings', async () => {
    settings.set(fakeSettings({ timeZone: 'America/New_York', weekStart: 'SUNDAY' }));
    await render();
    const view = options()!;
    expect(view.initialView).toBe('timeGridWeek');
    expect(view.initialDate).toBeUndefined();
    expect(view.timeZone).toBe('America/New_York');
    expect(view.firstDay).toBe(0);
    expect(view.headerToolbar).toMatchObject({
      end: 'timeGridDay,timeGridWeek,dayGridMonth,listWeek',
    });
    expect(settingsLoad).not.toHaveBeenCalled();
  });

  it('opens the view and date of the URL', async () => {
    await render({ view: 'month', date: '2026-03-01' });
    const view = options()!;
    expect(view.initialView).toBe('dayGridMonth');
    expect(view.initialDate).toBe('2026-03-01');
  });

  it('loads the settings first when they are not loaded yet', async () => {
    settings.set(null);
    let resolve!: (value: Settings) => void;
    settingsLoad.mockImplementation(() => new Promise<Settings>((r) => (resolve = r)));
    await render();
    expect(settingsLoad).toHaveBeenCalledOnce();
    expect(fullCalendar()).toBeUndefined();
    expect(root.textContent).toContain('Loading the calendar');

    settings.set(fakeSettings());
    resolve(fakeSettings());
    await fixture.whenStable();
    expect(options()?.timeZone).toBe('Europe/Zurich');
  });

  it('offers a retry when the settings cannot be loaded', async () => {
    settings.set(null);
    settingsLoad.mockRejectedValueOnce(new Error('offline'));
    await render();
    await settle();
    await fixture.whenStable();
    const retry = root.querySelector<HTMLButtonElement>('[role="alert"] button')!;
    expect(root.textContent).toContain('The calendar settings could not be loaded.');
    retry.click();
    expect(settingsLoad).toHaveBeenCalledTimes(2);
  });

  it('loads the occurrences of the visible range for all calendars and puts it in the URL', async () => {
    await render();
    await answerCalendars(fakeCalendar('family'), fakeCalendar('work', { visible: false }));
    options()!.datesSet!(week());
    await fixture.whenStable();

    // FullCalendar reported its own (current) range first; the latest request is for this one.
    const load = callsOf(listEventOccurrences).at(-1)!;
    expect(load.params).toEqual({
      from: '2026-03-22T23:00:00.000Z',
      to: '2026-03-29T22:00:00.000Z',
      calendarIds: ['family', 'work'],
    });
    expect(navigate).toHaveBeenCalledWith(
      [],
      expect.objectContaining({
        queryParams: { view: 'week', date: '2026-03-23' },
        replaceUrl: true,
      }),
    );

    load.resolve({
      items: [
        fakeOccurrence('Swim', { calendarId: 'family' }),
        fakeOccurrence('Standup', { calendarId: 'work' }),
      ],
    });
    await settle();
    await fixture.whenStable();
    // Only the visible calendar's events, in its color.
    expect(titles()).toEqual(['Swim']);
    expect(events()[0].color).toBe('#2e7d32');
  });

  it('waits for the calendars before loading occurrences', async () => {
    await render();
    options()!.datesSet!(week());
    await fixture.whenStable();
    expect(callsOf(listEventOccurrences)).toHaveLength(0);

    await answerCalendars(fakeCalendar('family'));
    expect(callsOf(listEventOccurrences)).toHaveLength(1);
  });

  it('toggles a calendar without refetching its events', async () => {
    await render();
    await answerCalendars(
      fakeCalendar('family', { name: 'Family' }),
      fakeCalendar('work', { name: 'Work' }),
    );
    options()!.datesSet!(week());
    await fixture.whenStable();
    callsOf(listEventOccurrences)
      .at(-1)!
      .resolve({
        items: [
          fakeOccurrence('Swim', { calendarId: 'family' }),
          fakeOccurrence('Standup', { calendarId: 'work' }),
        ],
      });
    await settle();
    await fixture.whenStable();
    expect(titles()).toEqual(['Swim', 'Standup']);
    const loads = callsOf(listEventOccurrences).length;

    const checkboxes = [
      ...root.querySelectorAll<HTMLInputElement>('.calendar-list input[type="checkbox"]'),
    ];
    expect(checkboxes.map((box) => box.closest('label')!.textContent!.trim())).toEqual([
      'Family',
      'Work',
    ]);
    checkboxes[1].click();
    await fixture.whenStable();
    expect(titles()).toEqual(['Swim']);
    await settle();
    expect(callsOf(updateCalendar)[0].params).toMatchObject({
      id: 'work',
      body: { visible: false },
    });
    expect(callsOf(listEventOccurrences)).toHaveLength(loads);
  });

  it('reports a toggle the server refuses and shows the calendar again', async () => {
    await render();
    await answerCalendars(fakeCalendar('work', { name: 'Work' }));
    const box = root.querySelector<HTMLInputElement>('.calendar-list input[type="checkbox"]')!;
    box.click();
    await settle();
    callsOf(updateCalendar)[0].reject(new Error('412'));
    await settle();
    await fixture.whenStable();
    expect(notifications.error).toHaveBeenCalledWith('The calendar "Work" could not be changed.');
    expect(
      root.querySelector<HTMLInputElement>('.calendar-list input[type="checkbox"]')!.checked,
    ).toBe(true);
  });

  it('invites to add a calendar when there is none', async () => {
    await render();
    await answerCalendars();
    expect(root.textContent).toContain('No calendars yet.');
  });

  it('shows an error with a retry when the events cannot be loaded', async () => {
    await render();
    await answerCalendars(fakeCalendar('family'));
    options()!.datesSet!(week());
    await fixture.whenStable();
    callsOf(listEventOccurrences).at(-1)!.reject(new Error('offline'));
    await settle();
    await fixture.whenStable();
    expect(root.textContent).toContain('The events could not be loaded.');

    const loads = callsOf(listEventOccurrences).length;
    root.querySelector<HTMLButtonElement>('.calendar-banner button')!.click();
    expect(callsOf(listEventOccurrences)).toHaveLength(loads + 1);
  });

  it('retries loading the calendars when they failed', async () => {
    await render();
    callsOf(listCalendars)[0].reject(new Error('offline'));
    await settle();
    await fixture.whenStable();
    expect(root.textContent).toContain('The calendars could not be loaded.');
    root.querySelector<HTMLButtonElement>('.calendar-panel .calendar-note button')!.click();
    expect(callsOf(listCalendars)).toHaveLength(2);
  });

  it('falls back to the week view for an invalid URL view and date', async () => {
    await render({ view: 'year', date: '2026-02-30' });
    const view = options()!;
    expect(view.initialView).toBe('timeGridWeek');
    expect(view.initialDate).toBeUndefined();
  });

  it('opens the day and agenda views of the URL', async () => {
    await render({ view: 'day', date: '2026-03-29' });
    expect(options()!.initialView).toBe('timeGridDay');
    expect(options()!.initialDate).toBe('2026-03-29');
    TestBed.resetTestingModule();
    await render({ view: 'agenda' });
    expect(options()!.initialView).toBe('listWeek');
  });

  it('follows changed settings (time zone and week start)', async () => {
    await render();
    expect(options()!.timeZone).toBe('Europe/Zurich');
    expect(options()!.firstDay).toBe(1);
    settings.set(fakeSettings({ timeZone: 'Asia/Tokyo', weekStart: 'SATURDAY' }));
    await fixture.whenStable();
    expect(options()!.timeZone).toBe('Asia/Tokyo');
    expect(options()!.firstDay).toBe(6);
  });

  it('puts the view and the date in the settings time zone into the URL', async () => {
    settings.set(fakeSettings({ timeZone: 'America/New_York', weekStart: 'SUNDAY' }));
    await render();
    // Sunday 2026-03-22 00:00 in New York (EDT, -04:00).
    options()!.datesSet!({
      start: new Date('2026-03-22T04:00:00Z'),
      end: new Date('2026-03-23T04:00:00Z'),
      view: { type: 'timeGridDay', currentStart: new Date('2026-03-22T04:00:00Z') },
    } as unknown as DatesSetInfo);
    expect(navigate).toHaveBeenLastCalledWith(
      [],
      expect.objectContaining({
        queryParams: { view: 'day', date: '2026-03-22' },
        queryParamsHandling: 'merge',
        replaceUrl: true,
      }),
    );
  });

  it('shows the occurrences of the newest range when answers arrive out of order', async () => {
    await render();
    await answerCalendars(fakeCalendar('family'));
    options()!.datesSet!(week());
    await fixture.whenStable();
    const thisWeek = callsOf(listEventOccurrences).at(-1)!;
    options()!.datesSet!(nextWeek());
    await fixture.whenStable();
    const following = callsOf(listEventOccurrences).at(-1)!;
    expect(following.params).toMatchObject({
      from: '2026-03-29T22:00:00.000Z',
      to: '2026-04-05T22:00:00.000Z',
    });

    following.resolve({ items: [fakeOccurrence('Next week', { calendarId: 'family' })] });
    await settle();
    thisWeek.resolve({ items: [fakeOccurrence('This week', { calendarId: 'family' })] });
    await settle();
    await fixture.whenStable();
    expect(titles()).toEqual(['Next week']);
  });

  it('refetches the range on a SSE resync and with the ids of new calendars', async () => {
    await render();
    await answerCalendars(fakeCalendar('family'));
    options()!.datesSet!(week());
    await fixture.whenStable();
    callsOf(listEventOccurrences)
      .at(-1)!
      .resolve({ items: [fakeOccurrence('Swim', { calendarId: 'family' })] });
    await settle();
    const loads = callsOf(listEventOccurrences).length;

    resyncs.next();
    await fixture.whenStable();
    expect(callsOf(listCalendars)).toHaveLength(2);
    expect(callsOf(listEventOccurrences)).toHaveLength(loads + 1);
    const stale = callsOf(listEventOccurrences).at(-1)!;

    // The resync brought a new calendar: its events are fetched too.
    await answerCalendars(fakeCalendar('family'), fakeCalendar('school', { color: '#6a1b9a' }));
    const fresh = callsOf(listEventOccurrences).at(-1)!;
    expect(fresh).not.toBe(stale);
    expect(fresh.params).toMatchObject({ calendarIds: ['family', 'school'] });

    fresh.resolve({
      items: [
        fakeOccurrence('Swim', { calendarId: 'family' }),
        fakeOccurrence('Parents evening', { calendarId: 'school' }),
      ],
    });
    await settle();
    stale.resolve({ items: [fakeOccurrence('Swim', { calendarId: 'family' })] });
    await settle();
    await fixture.whenStable();
    expect(titles()).toEqual(['Swim', 'Parents evening']);
    expect(events()[1].color).toBe('#6a1b9a');
  });

  it('fetches the events of a calendar created elsewhere after its change notification', async () => {
    await render();
    await answerCalendars(fakeCalendar('family'));
    options()!.datesSet!(week());
    await fixture.whenStable();
    callsOf(listEventOccurrences).at(-1)!.resolve({ items: [] });
    await settle();

    changes.next({ type: 'CALENDAR', id: 'school', op: 'CREATED', at: '2026-03-23T08:00:00Z' });
    await fixture.whenStable();
    expect(callsOf(listCalendars)).toHaveLength(2);

    await answerCalendars(fakeCalendar('family'), fakeCalendar('school'));
    expect(callsOf(listEventOccurrences).at(-1)!.params).toMatchObject({
      calendarIds: ['family', 'school'],
    });
  });

  it('hides the events of a calendar hidden elsewhere without a refetch', async () => {
    await render();
    await answerCalendars(fakeCalendar('family'), fakeCalendar('work'));
    options()!.datesSet!(week());
    await fixture.whenStable();
    callsOf(listEventOccurrences)
      .at(-1)!
      .resolve({
        items: [
          fakeOccurrence('Swim', { calendarId: 'family' }),
          fakeOccurrence('Standup', { calendarId: 'work' }),
        ],
      });
    await settle();
    await fixture.whenStable();
    const loads = callsOf(listEventOccurrences).length;

    changes.next({ type: 'CALENDAR', id: 'work', op: 'UPDATED', at: '2026-03-23T08:00:00Z' });
    await answerCalendars(
      fakeCalendar('family'),
      fakeCalendar('work', { version: 2, visible: false }),
    );
    expect(titles()).toEqual(['Swim']);
    const boxes = [
      ...root.querySelectorAll<HTMLInputElement>('.calendar-list input[type="checkbox"]'),
    ];
    expect(boxes.map((box) => box.checked)).toEqual([true, false]);
    expect(callsOf(listEventOccurrences)).toHaveLength(loads);
  });

  it('refetches without a calendar deleted elsewhere and removes its toggle', async () => {
    await render();
    await answerCalendars(fakeCalendar('family'), fakeCalendar('work'));
    options()!.datesSet!(week());
    await fixture.whenStable();
    callsOf(listEventOccurrences)
      .at(-1)!
      .resolve({
        items: [
          fakeOccurrence('Swim', { calendarId: 'family' }),
          fakeOccurrence('Standup', { calendarId: 'work' }),
        ],
      });
    await settle();
    await fixture.whenStable();

    changes.next({ type: 'CALENDAR', id: 'work', op: 'DELETED', at: '2026-03-23T08:00:00Z' });
    await answerCalendars(fakeCalendar('family'));
    expect(titles()).toEqual(['Swim']);
    expect(root.querySelectorAll('.calendar-list input[type="checkbox"]')).toHaveLength(1);
    expect(callsOf(listEventOccurrences).at(-1)!.params).toMatchObject({
      calendarIds: ['family'],
    });
  });

  it('shows an event created elsewhere after its change notification', async () => {
    await render();
    await answerCalendars(fakeCalendar('family'));
    options()!.datesSet!(week());
    await fixture.whenStable();
    callsOf(listEventOccurrences).at(-1)!.resolve({ items: [] });
    await settle();
    await fixture.whenStable();
    expect(titles()).toEqual([]);

    changes.next({ type: 'EVENT', id: 'e1', op: 'CREATED', at: '2026-03-23T08:00:00Z' });
    const refetch = callsOf(listEventOccurrences).at(-1)!;
    expect(refetch.params).toEqual({
      from: '2026-03-22T23:00:00.000Z',
      to: '2026-03-29T22:00:00.000Z',
      calendarIds: ['family'],
    });
    refetch.resolve({ items: [fakeOccurrence('Dentist', { calendarId: 'family' })] });
    await settle();
    await fixture.whenStable();
    expect(titles()).toEqual(['Dentist']);
  });

  it('shows a calendar again and its events when it is checked', async () => {
    await render();
    await answerCalendars(fakeCalendar('family', { visible: false, name: 'Family' }));
    options()!.datesSet!(week());
    await fixture.whenStable();
    callsOf(listEventOccurrences)
      .at(-1)!
      .resolve({ items: [fakeOccurrence('Swim', { calendarId: 'family' })] });
    await settle();
    await fixture.whenStable();
    const box = root.querySelector<HTMLInputElement>('.calendar-list input[type="checkbox"]')!;
    expect(box.checked).toBe(false);
    expect(titles()).toEqual([]);

    box.click();
    await fixture.whenStable();
    expect(titles()).toEqual(['Swim']);
    await settle();
    expect(callsOf(updateCalendar)[0].params).toMatchObject({ body: { visible: true } });
  });

  it('shows each calendar with its color and hides occurrences of unknown calendars', async () => {
    await render();
    await answerCalendars(
      fakeCalendar('family', { color: '#c62828' }),
      fakeCalendar('work', { color: undefined }),
    );
    const labels = [...root.querySelectorAll<HTMLElement>('.calendar-list .calendar-toggle')];
    expect(labels.map((label) => label.style.getPropertyValue('--app-calendar-color'))).toEqual([
      '#c62828',
      'var(--mat-sys-primary)',
    ]);

    options()!.datesSet!(week());
    await fixture.whenStable();
    callsOf(listEventOccurrences)
      .at(-1)!
      .resolve({
        items: [
          fakeOccurrence('Swim', { calendarId: 'family' }),
          fakeOccurrence('Orphan', { calendarId: 'deleted' }),
        ],
      });
    await settle();
    await fixture.whenStable();
    expect(titles()).toEqual(['Swim']);
    expect(events()[0].color).toBe('#c62828');
  });

  it('keeps the calendar list when a reload after a resync fails', async () => {
    await render();
    await answerCalendars(fakeCalendar('family', { name: 'Family' }));
    resyncs.next();
    callsOf(listCalendars).at(-1)!.reject(new Error('offline'));
    await settle();
    await fixture.whenStable();
    expect(root.textContent).not.toContain('The calendars could not be loaded.');
    expect(root.querySelectorAll('.calendar-list input[type="checkbox"]')).toHaveLength(1);
  });

  describe('editing', () => {
    const swim = fakeOccurrence('Swim', {
      eventId: 'm1',
      seriesId: 'm1',
      occurrence: '2026-03-23T08:00:00Z',
      calendarId: 'work',
    });
    const work = fakeCalendar('work', { name: 'Work' });

    /** The week with `swim` shown; resolves with the number of occurrence loads so far. */
    async function showWeek(): Promise<number> {
      await render();
      await answerCalendars(work, fakeCalendar('feed', { readOnly: true }));
      options()!.datesSet!(week());
      await fixture.whenStable();
      callsOf(listEventOccurrences)
        .at(-1)!
        .resolve({ items: [swim] });
      await settle();
      await fixture.whenStable();
      return callsOf(listEventOccurrences).length;
    }

    /** What FullCalendar passes to eventDrop/eventResize after `swim` was dragged an hour later. */
    function dragged(extendedProps: { occurrence?: OccurrenceResponse } = { occurrence: swim }) {
      return {
        event: {
          extendedProps,
          allDay: false,
          start: new Date('2026-03-23T09:00:00Z'),
          end: new Date('2026-03-23T10:00:00Z'),
          startStr: '2026-03-23T10:00:00+01:00',
          endStr: '2026-03-23T11:00:00+01:00',
        },
        revert: vi.fn(),
      };
    }
    const movedTimes = {
      allDay: false,
      start: '2026-03-23T09:00:00.000Z',
      end: '2026-03-23T10:00:00.000Z',
    };

    it('makes events of writable calendars draggable and ranges selectable', async () => {
      await showWeek();
      expect(options()).toMatchObject({ editable: true, selectable: true });
      expect(events()[0].editable).toBe(true);
    });

    it('saves a dropped event through the editor and shows what the server has now', async () => {
      const loads = await showWeek();
      const info = dragged();
      options()!.eventDrop!(info as unknown as DropInfo);
      await settle();
      expect(editor.move).toHaveBeenCalledExactlyOnceWith(swim, movedTimes, expect.any(Function));
      expect(info.revert).not.toHaveBeenCalled();
      expect(callsOf(listEventOccurrences)).toHaveLength(loads + 1);
    });

    it('puts a dropped event back when nothing was saved (scope cancelled, conflict, error)', async () => {
      await showWeek();
      editor.move.mockResolvedValueOnce(false);
      const info = dragged();
      options()!.eventDrop!(info as unknown as DropInfo);
      await settle();
      expect(info.revert).toHaveBeenCalledOnce();
    });

    it('passes a reload to the editor that refetches the range', async () => {
      const loads = await showWeek();
      editor.move.mockResolvedValueOnce(false);
      options()!.eventDrop!(dragged() as unknown as DropInfo);
      await settle();
      const reload = editor.move.mock.calls[0][2] as () => void;
      const before = callsOf(listEventOccurrences).length;
      expect(before).toBe(loads + 1);
      reload();
      expect(callsOf(listEventOccurrences)).toHaveLength(before + 1);
    });

    it('saves a resized event the same way', async () => {
      await showWeek();
      editor.move.mockResolvedValueOnce(false);
      const info = dragged();
      options()!.eventResize!(info as unknown as ResizeInfo);
      await settle();
      expect(editor.move).toHaveBeenCalledWith(swim, movedTimes, expect.any(Function));
      expect(info.revert).toHaveBeenCalledOnce();
    });

    it('puts back a dragged event it cannot map to an occurrence', async () => {
      await showWeek();
      const info = dragged({});
      options()!.eventDrop!(info as unknown as DropInfo);
      await settle();
      expect(editor.move).not.toHaveBeenCalled();
      expect(info.revert).toHaveBeenCalledOnce();
    });

    it('opens a clicked event in the editor and refetches after a change', async () => {
      const loads = await showWeek();
      const jsEvent = { preventDefault: vi.fn() };
      editor.open.mockResolvedValueOnce(false);
      options()!.eventClick!({
        event: { extendedProps: { occurrence: swim } },
        jsEvent,
      } as unknown as EventClickInfo);
      await settle();
      expect(jsEvent.preventDefault).toHaveBeenCalled();
      expect(editor.open).toHaveBeenCalledWith(swim, {
        calendars: [work, fakeCalendar('feed', { readOnly: true })],
        timeZone: 'Europe/Zurich',
      });
      expect(callsOf(listEventOccurrences)).toHaveLength(loads);

      editor.open.mockResolvedValueOnce(true);
      options()!.eventClick!({
        event: { extendedProps: { occurrence: swim } },
        jsEvent,
      } as unknown as EventClickInfo);
      await settle();
      expect(callsOf(listEventOccurrences)).toHaveLength(loads + 1);
    });

    it('creates an event for a selected range', async () => {
      const loads = await showWeek();
      const unselect = vi.fn();
      editor.create.mockResolvedValueOnce(true);
      options()!.select!({
        allDay: true,
        start: new Date('2026-03-24T00:00:00Z'),
        end: new Date('2026-03-26T00:00:00Z'),
        startStr: '2026-03-24',
        endStr: '2026-03-26',
        view: { calendar: { unselect } },
      } as unknown as DateSelectInfo);
      await settle();
      expect(unselect).toHaveBeenCalled();
      expect(editor.create).toHaveBeenCalledWith(
        { allDay: true, startDate: '2026-03-24', endDate: '2026-03-26' },
        expect.objectContaining({ timeZone: 'Europe/Zurich' }),
      );
      expect(callsOf(listEventOccurrences)).toHaveLength(loads + 1);
    });

    it('creates a one-hour event at the next full hour from the New event button', async () => {
      await render();
      const create = () =>
        [...root.querySelectorAll<HTMLButtonElement>('button')].find(
          (b) => b.textContent!.trim() === 'New event',
        )!;
      expect(create().disabled).toBe(true);
      await answerCalendars(work);
      expect(create().disabled).toBe(false);
      create().click();
      await settle();
      const [draft] = editor.create.mock.calls[0] as [{ start: string; end: string }];
      const start = new Date(draft.start);
      expect(start.getMinutes()).toBe(0);
      expect(start.getTime()).toBeGreaterThan(Date.now());
      expect(start.getTime() - Date.now()).toBeLessThanOrEqual(3_600_000);
      expect(new Date(draft.end).getTime() - start.getTime()).toBe(3_600_000);
    });

    it('rounds the New event start to a full hour in the zone of the settings', async () => {
      // Kolkata is UTC+05:30: a full hour there is :30 in UTC (and in most browser zones).
      settings.set(fakeSettings({ timeZone: 'Asia/Kolkata', weekStart: 'MONDAY' }));
      await render();
      await answerCalendars(work);
      [...root.querySelectorAll<HTMLButtonElement>('button')]
        .find((b) => b.textContent!.trim() === 'New event')!
        .click();
      await settle();
      const [draft] = editor.create.mock.calls[0] as [{ start: string; end: string }];
      expect(wallClockOf(draft.start, 'Asia/Kolkata').time).toMatch(/^\d\d:00$/);
      expect(new Date(draft.start).getUTCMinutes()).toBe(30);
      expect(new Date(draft.start).getTime()).toBeGreaterThan(Date.now());
      expect(new Date(draft.start).getTime() - Date.now()).toBeLessThanOrEqual(3_600_000);
    });
  });

  describe('task overlay', () => {
    const work = fakeCalendar('work');
    const rent = fakeTask('rent', { title: 'Pay rent', dueDate: '2026-03-24' });
    const call = fakeTask('call', {
      title: 'Call Ada',
      dueDate: '2026-03-25',
      dueTime: '14:30:00',
    });
    const passport = fakeTask('passport', {
      title: 'Renew passport',
      list: 'SOMEDAY',
      ticklerDate: '2026-03-27',
    });

    const toggle = () => root.querySelector<HTMLInputElement>('.calendar-overlay-toggle input')!;
    const taskEntries = () => events().filter((event) => String(event.id).startsWith('task-'));

    async function showWeek(): Promise<void> {
      await render();
      await answerCalendars(work);
      options()!.datesSet!(week());
      await settle();
      callsOf(listEventOccurrences).at(-1)!.resolve({ items: [] });
      await settle();
      await fixture.whenStable();
    }

    async function answerTasks(...items: TaskResponse[]): Promise<void> {
      callsOf(listTasks).at(-1)!.resolve({ items });
      await settle();
      await fixture.whenStable();
    }

    function check(box: HTMLInputElement, checked: boolean): void {
      box.checked = checked;
      box.dispatchEvent(new Event('change'));
    }

    beforeEach(() => localStorage.removeItem(TASK_OVERLAY_KEY));
    afterEach(() => localStorage.removeItem(TASK_OVERLAY_KEY));

    it('is off by default and loads no tasks', async () => {
      await showWeek();
      expect(toggle().checked).toBe(false);
      expect(callsOf(listTasks)).toHaveLength(0);
      expect(taskEntries()).toEqual([]);
    });

    it('shows the due tasks and ticklers of the shown week once turned on, and hides them again', async () => {
      await showWeek();
      check(toggle(), true);
      await settle();
      expect(callsOf(listTasks)).toHaveLength(1);
      expect(callsOf(listTasks)[0].params).toMatchObject({ from: '2026-03-23', to: '2026-03-29' });
      expect(localStorage.getItem(TASK_OVERLAY_KEY)).toBe('true');

      await answerTasks(rent, call, passport);
      expect(taskEntries()).toHaveLength(3);
      expect(taskEntries()).toEqual(
        expect.arrayContaining([
          expect.objectContaining({
            id: 'task-due-rent',
            title: 'Pay rent',
            allDay: true,
            start: '2026-03-24',
            editable: false,
          }),
          expect.objectContaining({
            id: 'task-due-call',
            allDay: false,
            // 14:30 in Zurich (CET), for half an hour.
            start: '2026-03-25T13:30:00.000Z',
            end: '2026-03-25T14:00:00.000Z',
          }),
          expect.objectContaining({
            id: 'task-tickler-passport',
            allDay: true,
            start: '2026-03-27',
            className: 'app-task-mark app-task-tickler',
          }),
        ]),
      );

      check(toggle(), false);
      await fixture.whenStable();
      expect(taskEntries()).toEqual([]);
      expect(localStorage.getItem(TASK_OVERLAY_KEY)).toBe('false');
    });

    it('remembers the toggle and loads the tasks of each newly shown week', async () => {
      localStorage.setItem(TASK_OVERLAY_KEY, 'true');
      await showWeek();
      expect(toggle().checked).toBe(true);
      expect(callsOf(listTasks).at(-1)!.params).toMatchObject({
        from: '2026-03-23',
        to: '2026-03-29',
      });
      await answerTasks(rent);
      const loads = callsOf(listTasks).length;
      options()!.datesSet!(nextWeek());
      await settle();
      expect(callsOf(listTasks)).toHaveLength(loads + 1);
      expect(callsOf(listTasks).at(-1)!.params).toMatchObject({
        from: '2026-03-30',
        to: '2026-04-05',
      });
      // The same week again: no new request.
      options()!.datesSet!(nextWeek());
      await settle();
      expect(callsOf(listTasks)).toHaveLength(loads + 1);
    });

    it('shows no tasks in the month and agenda views', async () => {
      localStorage.setItem(TASK_OVERLAY_KEY, 'true');
      await render({ view: 'month' });
      await answerCalendars(work);
      options()!.datesSet!({
        start: new Date('2026-02-22T23:00:00Z'),
        end: new Date('2026-04-05T22:00:00Z'),
        view: { type: 'dayGridMonth', currentStart: new Date('2026-02-28T23:00:00Z') },
      } as unknown as DatesSetInfo);
      await settle();
      await fixture.whenStable();
      expect(callsOf(listTasks)).toHaveLength(0);
      expect(taskEntries()).toEqual([]);
      expect(root.textContent).toContain('Shown in the day and week views.');
    });

    it('opens a clicked task instead of the event editor', async () => {
      localStorage.setItem(TASK_OVERLAY_KEY, 'true');
      await showWeek();
      await answerTasks(rent);
      const jsEvent = { preventDefault: vi.fn() };
      options()!.eventClick!({
        event: { extendedProps: taskEntries()[0].extendedProps },
        jsEvent,
      } as unknown as EventClickInfo);
      await settle();
      expect(navigate).toHaveBeenCalledWith(['/tasks', 'rent']);
      expect(editor.open).not.toHaveBeenCalled();
    });

    it('offers a retry when the tasks cannot be loaded', async () => {
      localStorage.setItem(TASK_OVERLAY_KEY, 'true');
      await showWeek();
      const loads = callsOf(listTasks).length;
      callsOf(listTasks).at(-1)!.reject(new Error('offline'));
      await settle();
      await fixture.whenStable();
      const alert = [...root.querySelectorAll<HTMLElement>('[role="alert"]')].find((a) =>
        a.textContent!.includes('The tasks could not be loaded.'),
      )!;
      alert.querySelector('button')!.click();
      await settle();
      expect(callsOf(listTasks)).toHaveLength(loads + 1);
    });

    it('hides the load error of the tasks once the overlay is turned off', async () => {
      localStorage.setItem(TASK_OVERLAY_KEY, 'true');
      await showWeek();
      callsOf(listTasks).at(-1)!.reject(new Error('offline'));
      await settle();
      await fixture.whenStable();
      expect(root.textContent).toContain('The tasks could not be loaded.');

      check(toggle(), false);
      await fixture.whenStable();
      expect(root.textContent).not.toContain('The tasks could not be loaded.');
    });

    it('shows the tasks of the newest week when an older answer arrives last', async () => {
      localStorage.setItem(TASK_OVERLAY_KEY, 'true');
      await showWeek();
      const first = callsOf(listTasks).at(-1)!;
      options()!.datesSet!(nextWeek());
      await settle();
      const second = callsOf(listTasks).at(-1)!;
      expect(second).not.toBe(first);

      const april = fakeTask('april', { title: 'Book dentist', dueDate: '2026-04-01' });
      second.resolve({ items: [april] });
      await settle();
      // The answer for the previous week arrives last: it is dropped, even with a task that would
      // fall into the shown week.
      const stale = fakeTask('stale', { title: 'Old answer', dueDate: '2026-04-02' });
      first.resolve({ items: [rent, stale] });
      await settle();
      await fixture.whenStable();
      expect(taskEntries().map((entry) => entry.id)).toEqual(['task-due-april']);
      expect(root.textContent).not.toContain('The tasks could not be loaded.');
    });

    it('drops an older failed answer arriving after the newest one', async () => {
      localStorage.setItem(TASK_OVERLAY_KEY, 'true');
      await showWeek();
      const first = callsOf(listTasks).at(-1)!;
      options()!.datesSet!(nextWeek());
      await settle();
      const april = fakeTask('april', { title: 'Book dentist', dueDate: '2026-04-01' });
      callsOf(listTasks)
        .at(-1)!
        .resolve({ items: [april] });
      await settle();
      first.reject(new Error('timeout'));
      await settle();
      await fixture.whenStable();
      expect(taskEntries().map((entry) => entry.id)).toEqual(['task-due-april']);
      expect(root.textContent).not.toContain('The tasks could not be loaded.');
    });

    it('loads the tasks of the one shown day in the day view', async () => {
      localStorage.setItem(TASK_OVERLAY_KEY, 'true');
      await render({ view: 'day', date: '2026-03-25' });
      await answerCalendars(work);
      options()!.datesSet!({
        start: new Date('2026-03-24T23:00:00Z'),
        end: new Date('2026-03-25T23:00:00Z'),
        view: { type: 'timeGridDay', currentStart: new Date('2026-03-24T23:00:00Z') },
      } as unknown as DatesSetInfo);
      await settle();
      expect(callsOf(listTasks).at(-1)!.params).toMatchObject({
        from: '2026-03-25',
        to: '2026-03-25',
      });
      await answerTasks(call);
      expect(taskEntries().map((entry) => entry.id)).toEqual(['task-due-call']);
    });

    it('shows the tasks again when switching back from the month to the week view', async () => {
      localStorage.setItem(TASK_OVERLAY_KEY, 'true');
      await showWeek();
      await answerTasks(rent);
      expect(taskEntries()).toHaveLength(1);

      options()!.datesSet!({
        start: new Date('2026-02-22T23:00:00Z'),
        end: new Date('2026-04-05T22:00:00Z'),
        view: { type: 'dayGridMonth', currentStart: new Date('2026-02-28T23:00:00Z') },
      } as unknown as DatesSetInfo);
      await settle();
      await fixture.whenStable();
      expect(taskEntries()).toEqual([]);
      const loads = callsOf(listTasks).length;

      options()!.datesSet!(week());
      await settle();
      await fixture.whenStable();
      // The same week as before: no new request, the loaded tasks show again.
      expect(callsOf(listTasks)).toHaveLength(loads);
      expect(taskEntries().map((entry) => entry.id)).toEqual(['task-due-rent']);
    });
  });

  describe('linked event', () => {
    it('shows the date of the event of the URL and opens it once', async () => {
      await render({ event: 'e1' });
      await answerCalendars(fakeCalendar('work'));
      await settle();
      const gotoDate = vi.spyOn(fullCalendar()!.getApi(), 'gotoDate');
      expect(navigate).toHaveBeenCalledWith(
        [],
        expect.objectContaining({ queryParams: { event: null }, replaceUrl: true }),
      );
      callsOf(getEvent)[0].resolve(fakeEvent('e1', { title: 'Planning' }));
      await settle();
      expect(gotoDate).toHaveBeenCalledWith('2026-03-23');
      expect(editor.open).toHaveBeenCalledOnce();
      expect(editor.open.mock.calls[0][0]).toMatchObject({ eventId: 'e1', title: 'Planning' });
      expect(callsOf(getEvent)).toHaveLength(1);
    });

    it('shows an all-day linked event at its start date and refetches after a change', async () => {
      editor.open.mockResolvedValueOnce(true);
      await render({ event: 'trip' });
      await answerCalendars(fakeCalendar('work'));
      await settle();
      const gotoDate = vi.spyOn(fullCalendar()!.getApi(), 'gotoDate');
      const loads = callsOf(listEventOccurrences).length;
      callsOf(getEvent)[0].resolve(
        fakeEvent('trip', {
          title: 'Ski trip',
          allDay: true,
          start: undefined,
          end: undefined,
          startDate: '2026-04-10',
          endDate: '2026-04-12',
        }),
      );
      await settle();
      expect(gotoDate).toHaveBeenCalledWith('2026-04-10');
      expect(editor.open.mock.calls[0][0]).toMatchObject({ eventId: 'trip', allDay: true });
      // The editor saved a change: the shown range is loaded again.
      expect(callsOf(listEventOccurrences)).toHaveLength(loads + 1);
    });

    it('dates a timed linked event in the settings zone', async () => {
      await render({ event: 'late' });
      await answerCalendars(fakeCalendar('work'));
      await settle();
      const gotoDate = vi.spyOn(fullCalendar()!.getApi(), 'gotoDate');
      // 23:30 UTC on the 23rd is already the 24th in Zurich.
      callsOf(getEvent)[0].resolve(
        fakeEvent('late', {
          title: 'Late call',
          start: '2026-03-23T23:30:00Z',
          end: '2026-03-24T00:00:00Z',
        }),
      );
      await settle();
      expect(gotoDate).toHaveBeenCalledWith('2026-03-24');
    });

    it('says so when the linked event cannot be loaded', async () => {
      await render({ event: 'gone' });
      await answerCalendars(fakeCalendar('work'));
      await settle();
      callsOf(getEvent)[0].reject(new Error('Not Found'));
      await settle();
      expect(notifications.error).toHaveBeenCalledWith(
        'The linked event could not be opened. It may have been deleted.',
      );
      expect(editor.open).not.toHaveBeenCalled();
    });
  });
});
