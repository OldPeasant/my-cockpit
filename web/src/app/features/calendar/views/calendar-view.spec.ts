import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';
import { DatesSetInfo, EventInput, FullCalendarComponent } from '@fullcalendar/angular';
import { filter, Subject } from 'rxjs';
import {
  Api,
  ChangeEvent,
  listCalendars,
  listEventOccurrences,
  Settings,
  updateCalendar,
} from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { NotificationService } from '../../../core/http';
import { SettingsService } from '../../settings/settings.service';
import { fakeSettings } from '../../settings/testing';
import calendarRoutes from '../calendar.routes';
import CalendarView from './calendar-view';
import { fakeCalendar, fakeOccurrence } from './testing';

interface Call {
  fn: unknown;
  params: Record<string, unknown>;
  resolve: (value: unknown) => void;
  reject: (error: unknown) => void;
}

const settle = () => new Promise((resolve) => setTimeout(resolve));

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
});

describe('CalendarView', () => {
  let calls: Call[];
  let resyncs: Subject<void>;
  let changes: Subject<ChangeEvent>;
  let settings: ReturnType<typeof signal<Settings | null>>;
  let settingsLoad: ReturnType<typeof vi.fn>;
  let navigate: ReturnType<typeof vi.fn>;
  let notifications: { error: ReturnType<typeof vi.fn> };
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

    const checkboxes = [...root.querySelectorAll<HTMLInputElement>('input[type="checkbox"]')];
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
    const box = root.querySelector<HTMLInputElement>('input[type="checkbox"]')!;
    box.click();
    await settle();
    callsOf(updateCalendar)[0].reject(new Error('412'));
    await settle();
    await fixture.whenStable();
    expect(notifications.error).toHaveBeenCalledWith('The calendar "Work" could not be changed.');
    expect(root.querySelector<HTMLInputElement>('input[type="checkbox"]')!.checked).toBe(true);
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
    root.querySelector<HTMLButtonElement>('.calendar-panel button')!.click();
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
    const box = root.querySelector<HTMLInputElement>('input[type="checkbox"]')!;
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
    const labels = [...root.querySelectorAll<HTMLElement>('.calendar-toggle')];
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
    expect(root.querySelectorAll('input[type="checkbox"]')).toHaveLength(1);
  });
});
