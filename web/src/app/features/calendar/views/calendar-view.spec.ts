import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';
import { DatesSetInfo, EventInput, FullCalendarComponent } from '@fullcalendar/angular';
import { Subject } from 'rxjs';
import {
  Api,
  listCalendars,
  listEventOccurrences,
  Settings,
  updateCalendar,
} from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { NotificationService } from '../../../core/http';
import { SettingsService } from '../../settings/settings.service';
import { fakeSettings } from '../../settings/testing';
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

describe('CalendarView', () => {
  let calls: Call[];
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
          useValue: { resync$: new Subject<void>(), events$: () => new Subject() },
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
});
