import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NEVER, Subject } from 'rxjs';
import { Api, listCalendars, listEventOccurrences } from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { fakeCalendar, fakeOccurrence } from '../../calendar/views/testing';
import { eventSpan, TodayEvents, todayRange } from './today-events';

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
});

describe('TodayEvents', () => {
  let api: { invoke: ReturnType<typeof vi.fn<Invoke>> };
  let calendars: unknown[];
  let occurrences: unknown[];
  let fixture: ComponentFixture<TodayEvents>;
  let root: HTMLElement;

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 9, 20, 9, 30));
    calendars = [fakeCalendar('work', { name: 'Work' })];
    occurrences = [];
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
          useValue: { resync$: new Subject<void>(), events$: () => NEVER },
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
});
