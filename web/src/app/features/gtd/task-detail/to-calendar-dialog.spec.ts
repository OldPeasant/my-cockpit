import { TestbedHarnessEnvironment } from '@angular/cdk/testing/testbed';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { MatSelectHarness } from '@angular/material/select/testing';
import { Subject } from 'rxjs';
import { CalendarResponse, ConvertToEventRequest, Settings } from '../../../api/generated';
import { CalendarStore } from '../../calendar/views/calendar-store';
import { fakeCalendar, fakeEvent } from '../../calendar/views/testing';
import { SettingsService } from '../../settings/settings.service';
import {
  askForCalendar,
  browserTimeZone,
  convertRequest,
  endsAfterStart,
  eventStartDay,
  ToCalendarData,
  ToCalendarDialog,
  ToCalendarForm,
} from './to-calendar-dialog';

const settle = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

/** Wednesday 2026-10-21, 15:20 in Zurich (UTC+2), 09:20 in New York (UTC-4). */
const NOW = '2026-10-21T13:20:00Z';

const CALENDARS: readonly CalendarResponse[] = [
  fakeCalendar('feed', { name: 'Holidays', readOnly: true, sourceType: 'ICS_FEED' }),
  fakeCalendar('work', { name: 'Work' }),
  fakeCalendar('family', { name: 'Family', isDefault: true }),
];

/** A promise the test settles. */
function deferred<T = void>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe('ToCalendarDialog', () => {
  let fixture: ComponentFixture<ToCalendarDialog>;
  let ref: { close: ReturnType<typeof vi.fn> };
  let settings: {
    settings: ReturnType<typeof signal<Settings | null>>;
    load: ReturnType<typeof vi.fn>;
  };
  let calendars: {
    calendars: ReturnType<typeof signal<readonly CalendarResponse[]>>;
    loaded: ReturnType<typeof signal<boolean>>;
    error: ReturnType<typeof signal<unknown>>;
    load: ReturnType<typeof vi.fn>;
  };

  const el = () => fixture.nativeElement as HTMLElement;
  const input = (name: string) =>
    el().querySelector<HTMLInputElement>(`[formcontrolname="${name}"]`);
  const button = (text: string) =>
    [...el().querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent!.trim() === text,
    );
  const submitButton = () => el().querySelector<HTMLButtonElement>('button[type="submit"]')!;
  const alerts = () =>
    [...el().querySelectorAll<HTMLElement>('[role="alert"]')].map((a) => a.textContent!.trim());
  const text = () => el().textContent ?? '';

  /** The settings in Zurich, the calendars loaded, unless a test sets them up otherwise. */
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(NOW));
    settings = {
      settings: signal<Settings | null>({ timeZone: 'Europe/Zurich' } as Settings),
      load: vi.fn(() => Promise.resolve()),
    };
    calendars = {
      calendars: signal<readonly CalendarResponse[]>(CALENDARS),
      loaded: signal(true),
      error: signal<unknown>(null),
      load: vi.fn(() => Promise.resolve()),
    };
  });

  afterEach(() => vi.useRealTimers());

  async function open(data: ToCalendarData = { title: 'Call the plumber' }): Promise<void> {
    ref = { close: vi.fn() };
    TestBed.configureTestingModule({
      imports: [ToCalendarDialog],
      providers: [
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: ref },
        { provide: SettingsService, useValue: settings },
        { provide: CalendarStore, useValue: calendars },
      ],
    });
    fixture = TestBed.createComponent(ToCalendarDialog);
    await stable();
  }

  async function stable(): Promise<void> {
    await settle();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function type(name: string, value: string): void {
    const field = input(name)!;
    field.value = value;
    field.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  async function submit(): Promise<void> {
    submitButton().click();
    await stable();
  }

  async function calendarSelect(): Promise<MatSelectHarness> {
    return TestbedHarnessEnvironment.loader(fixture).getHarness(MatSelectHarness);
  }

  async function checkAllDay(): Promise<void> {
    el().querySelector<HTMLInputElement>('input[type="checkbox"]')!.click();
    await stable();
  }

  it('names the task and preselects the default calendar among the writable ones', async () => {
    await open();

    expect(el().querySelector('h2')!.textContent!.trim()).toBe('Move to the calendar');
    expect(text()).toContain('“Call the plumber” becomes an event; the task goes to the Trash.');
    expect(text()).not.toContain('Your unsaved changes are discarded');
    const select = await calendarSelect();
    expect(await select.getValueText()).toBe('Family');
    await select.open();
    // A read-only calendar (a subscribed feed) can't take the event.
    expect(await Promise.all((await select.getOptions()).map((o) => o.getText()))).toEqual([
      'Work',
      'Family',
    ]);
    // Everything was there already: nothing is loaded again.
    expect(settings.load).not.toHaveBeenCalled();
    expect(calendars.load).not.toHaveBeenCalled();
  });

  it('preselects the first writable calendar when none is the default', async () => {
    calendars.calendars.set([
      fakeCalendar('feed', { name: 'Holidays', readOnly: true, isDefault: true }),
      fakeCalendar('work', { name: 'Work' }),
      fakeCalendar('club', { name: 'Club' }),
    ]);
    await open();
    await submit();
    expect(ref.close.mock.calls[0][0]).toMatchObject({ calendarId: 'work' });
  });

  it('warns that unsaved changes are not part of the event', async () => {
    await open({ title: 'Call the plumber', unsaved: true });
    expect(text()).toContain(
      'Your unsaved changes are discarded: the event gets the saved version.',
    );
  });

  it('starts at the next full hour for one hour in the zone of the settings', async () => {
    await open();

    expect(input('startDate')!.value).toBe('2026-10-21');
    expect(input('startTime')!.value).toBe('16:00');
    expect(input('endDate')!.value).toBe('2026-10-21');
    expect(input('endTime')!.value).toBe('17:00');
    expect(text()).toContain('Times in Europe/Zurich');

    await submit();

    expect(ref.close).toHaveBeenCalledTimes(1);
    expect(ref.close).toHaveBeenCalledWith({
      calendarId: 'family',
      allDay: false,
      start: '2026-10-21T14:00:00.000Z',
      end: '2026-10-21T15:00:00.000Z',
      timeZone: 'Europe/Zurich',
    });
  });

  it('moves the default to the next day after 23:00', async () => {
    // 23:30 in Zurich.
    vi.setSystemTime(new Date('2026-10-21T21:30:00Z'));
    await open();
    expect(input('startDate')!.value).toBe('2026-10-22');
    expect(input('startTime')!.value).toBe('00:00');
    expect(input('endDate')!.value).toBe('2026-10-22');
    expect(input('endTime')!.value).toBe('01:00');
  });

  it('converts the chosen calendar and typed times', async () => {
    await open();
    const select = await calendarSelect();
    await select.open();
    await select.clickOptions({ text: 'Work' });
    type('startDate', '2026-10-23');
    type('startTime', '09:30');
    type('endDate', '2026-10-23');
    type('endTime', '10:15');

    await submit();

    expect(ref.close).toHaveBeenCalledWith({
      calendarId: 'work',
      allDay: false,
      start: '2026-10-23T07:30:00.000Z',
      end: '2026-10-23T08:15:00.000Z',
      timeZone: 'Europe/Zurich',
    });
  });

  it('loads missing settings and shows the defaults in their zone', async () => {
    const answer = deferred();
    settings.settings.set(null);
    settings.load.mockImplementation(() =>
      answer.promise.then(() =>
        settings.settings.set({ timeZone: 'America/New_York' } as Settings),
      ),
    );
    await open();
    // Until the settings are there, the browser's zone is used.
    const browser = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
    expect(text()).toContain(`Times in ${browser}`);

    answer.resolve();
    await stable();

    expect(settings.load).toHaveBeenCalledTimes(1);
    expect(text()).toContain('Times in America/New_York');
    expect(input('startTime')!.value).toBe('10:00');
    expect(input('endTime')!.value).toBe('11:00');
    await submit();
    expect(ref.close).toHaveBeenCalledWith(
      expect.objectContaining({
        start: '2026-10-21T14:00:00.000Z',
        end: '2026-10-21T15:00:00.000Z',
        timeZone: 'America/New_York',
      }),
    );
  });

  it('keeps times the user typed when the settings answer late', async () => {
    const answer = deferred();
    settings.settings.set(null);
    settings.load.mockImplementation(() =>
      answer.promise.then(() =>
        settings.settings.set({ timeZone: 'America/New_York' } as Settings),
      ),
    );
    await open();
    type('startDate', '2026-10-24');
    type('startTime', '08:00');
    type('endDate', '2026-10-24');
    type('endTime', '08:45');

    answer.resolve();
    await stable();

    expect(input('startDate')!.value).toBe('2026-10-24');
    expect(input('startTime')!.value).toBe('08:00');
    expect(input('endTime')!.value).toBe('08:45');
  });

  it('still converts in the browser zone when the settings cannot be loaded', async () => {
    settings.settings.set(null);
    settings.load.mockRejectedValue(new Error('offline'));
    await open();

    await submit();

    const browser = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
    expect(ref.close).toHaveBeenCalledWith(
      expect.objectContaining({ calendarId: 'family', allDay: false, timeZone: browser }),
    );
  });

  it('converts an all-day event with an exclusive end date and no times', async () => {
    await open();
    await checkAllDay();

    expect(input('startTime')).toBeNull();
    expect(input('endTime')).toBeNull();
    expect(text()).not.toContain('Times in');
    type('startDate', '2026-11-03');
    type('endDate', '2026-11-04');
    await submit();

    expect(ref.close).toHaveBeenCalledWith({
      calendarId: 'family',
      allDay: true,
      startDate: '2026-11-03',
      endDate: '2026-11-05',
    });
  });

  it('converts a one-day all-day event (end on the start day)', async () => {
    await open();
    await checkAllDay();
    type('startDate', '2026-12-31');
    type('endDate', '2026-12-31');
    await submit();

    expect(ref.close).toHaveBeenCalledWith({
      calendarId: 'family',
      allDay: true,
      startDate: '2026-12-31',
      endDate: '2027-01-01',
    });
  });

  it('makes the default times spanning midnight a one-day event when ticking all day', async () => {
    // 22:30 in Zurich: the default is 23:00 until 00:00 the next day.
    vi.setSystemTime(new Date('2026-10-21T20:30:00Z'));
    await open();
    expect(input('endDate')!.value).toBe('2026-10-22');

    await checkAllDay();
    expect(input('startDate')!.value).toBe('2026-10-21');
    expect(input('endDate')!.value).toBe('2026-10-21');

    // Back to timed: the default end again.
    await checkAllDay();
    expect(input('startTime')!.value).toBe('23:00');
    expect(input('endDate')!.value).toBe('2026-10-22');
    expect(input('endTime')!.value).toBe('00:00');

    await checkAllDay();
    await submit();
    expect(ref.close).toHaveBeenCalledWith({
      calendarId: 'family',
      allDay: true,
      startDate: '2026-10-21',
      endDate: '2026-10-22',
    });
  });

  it('keeps the one-day range when the settings answer late while all day is ticked', async () => {
    // 22:30 in Zurich, 16:30 in New York: the browser default may span midnight.
    vi.setSystemTime(new Date('2026-10-21T20:30:00Z'));
    const answer = deferred();
    settings.settings.set(null);
    settings.load.mockImplementation(() =>
      answer.promise.then(() => settings.settings.set({ timeZone: 'Europe/Zurich' } as Settings)),
    );
    await open();
    await checkAllDay();

    answer.resolve();
    await stable();

    // The late answer resets the untouched times in Zurich: 23:00-00:00, all-day the 21st only.
    expect(input('startDate')!.value).toBe('2026-10-21');
    expect(input('endDate')!.value).toBe('2026-10-21');
    await submit();
    expect(ref.close).toHaveBeenCalledWith({
      calendarId: 'family',
      allDay: true,
      startDate: '2026-10-21',
      endDate: '2026-10-22',
    });
  });

  it('keeps dates the user typed when ticking all day', async () => {
    vi.setSystemTime(new Date('2026-10-21T20:30:00Z'));
    await open();
    type('endDate', '2026-10-23');

    await checkAllDay();

    expect(input('startDate')!.value).toBe('2026-10-21');
    expect(input('endDate')!.value).toBe('2026-10-23');
  });

  it('refuses an end before the start, and the error goes away on an edit', async () => {
    await open();
    type('startTime', '10:00');
    type('endTime', '09:00');

    await submit();

    expect(ref.close).not.toHaveBeenCalled();
    expect(alerts()).toContain('The event must end after it starts.');

    type('endTime', '11:00');
    await stable();
    expect(alerts()).not.toContain('The event must end after it starts.');
    await submit();
    expect(ref.close).toHaveBeenCalledTimes(1);
  });

  it('refuses an event that ends when it starts', async () => {
    await open();
    type('startTime', '10:00');
    type('endTime', '10:00');
    await submit();
    expect(ref.close).not.toHaveBeenCalled();
    expect(alerts()).toContain('The event must end after it starts.');
  });

  it('refuses an all-day event ending on an earlier day', async () => {
    await open();
    await checkAllDay();
    type('startDate', '2026-11-03');
    type('endDate', '2026-11-02');
    await submit();
    expect(ref.close).not.toHaveBeenCalled();
    expect(alerts()).toContain('The event must end after it starts.');
  });

  it('does not convert a timed event without its times or a date', async () => {
    await open();
    type('startTime', '');
    await submit();
    expect(ref.close).not.toHaveBeenCalled();

    type('startTime', '10:00');
    type('endDate', '');
    await submit();
    expect(ref.close).not.toHaveBeenCalled();
  });

  it('closes without a request on Cancel', async () => {
    await open();
    type('startTime', '08:00');
    button('Cancel')!.click();
    await stable();
    expect(ref.close).toHaveBeenCalledTimes(1);
    expect(ref.close.mock.calls[0][0]).toBeUndefined();
  });

  it('says so and cannot convert when there is no writable calendar', async () => {
    calendars.calendars.set([fakeCalendar('feed', { name: 'Holidays', readOnly: true })]);
    await open();

    expect(alerts()).toContain('There is no calendar to add events to.');
    expect(el().querySelector('mat-select')).toBeNull();
    expect(submitButton().disabled).toBe(true);
    submitButton().click();
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    await stable();
    expect(ref.close).not.toHaveBeenCalled();
  });

  it('loads the calendars when they are not loaded yet, then preselects the default', async () => {
    const answer = deferred();
    calendars.calendars.set([]);
    calendars.loaded.set(false);
    calendars.load.mockImplementation(() =>
      answer.promise.then(() => {
        calendars.calendars.set(CALENDARS);
        calendars.loaded.set(true);
      }),
    );
    await open();

    expect(calendars.load).toHaveBeenCalledTimes(1);
    expect(text()).toContain('Loading the calendars…');
    expect(submitButton().disabled).toBe(true);

    answer.resolve();
    await stable();

    expect(text()).not.toContain('Loading the calendars…');
    expect(await (await calendarSelect()).getValueText()).toBe('Family');
    await submit();
    expect(ref.close).toHaveBeenCalledWith(expect.objectContaining({ calendarId: 'family' }));
  });

  it('offers a retry when the calendars cannot be loaded', async () => {
    calendars.calendars.set([]);
    calendars.loaded.set(false);
    calendars.load.mockImplementationOnce(() => {
      calendars.error.set(new Error('offline'));
      return Promise.resolve();
    });
    await open();

    expect(alerts().join(' ')).toContain('The calendars could not be loaded.');
    expect(submitButton().disabled).toBe(true);

    calendars.load.mockImplementationOnce(() => {
      calendars.error.set(null);
      calendars.calendars.set(CALENDARS);
      calendars.loaded.set(true);
      return Promise.resolve();
    });
    button('Try again')!.click();
    await stable();

    expect(calendars.load).toHaveBeenCalledTimes(2);
    expect(alerts().join(' ')).not.toContain('The calendars could not be loaded.');
    expect(await (await calendarSelect()).getValueText()).toBe('Family');
    await submit();
    expect(ref.close).toHaveBeenCalledWith(expect.objectContaining({ calendarId: 'family' }));
  });
});

describe('endsAfterStart', () => {
  const timed: ToCalendarForm = {
    calendarId: 'work',
    allDay: false,
    startDate: '2026-10-21',
    startTime: '10:00',
    endDate: '2026-10-21',
    endTime: '11:00',
    timeZone: 'Europe/Zurich',
  };

  it('needs a timed event to end after it starts', () => {
    expect(endsAfterStart(timed)).toBe(true);
    expect(endsAfterStart({ ...timed, endTime: '10:00' })).toBe(false);
    expect(endsAfterStart({ ...timed, endTime: '09:59' })).toBe(false);
    // An earlier time on a later day is later.
    expect(endsAfterStart({ ...timed, endDate: '2026-10-22', endTime: '01:00' })).toBe(true);
  });

  it('lets an all-day event end on its start day, not before', () => {
    const allDay = { ...timed, allDay: true, startTime: '', endTime: '' };
    expect(endsAfterStart(allDay)).toBe(true);
    expect(endsAfterStart({ ...allDay, endDate: '2026-10-20' })).toBe(false);
  });
});

describe('convertRequest', () => {
  it('turns wall-clock times into instants of the zone', () => {
    expect(
      convertRequest({
        calendarId: 'work',
        allDay: false,
        startDate: '2026-03-29',
        startTime: '01:30',
        endDate: '2026-03-29',
        endTime: '03:30',
        timeZone: 'Europe/Zurich',
      }),
    ).toEqual({
      calendarId: 'work',
      allDay: false,
      // Before and after the switch to summer time.
      start: '2026-03-29T00:30:00.000Z',
      end: '2026-03-29T01:30:00.000Z',
      timeZone: 'Europe/Zurich',
    });
  });

  it('makes the end date of an all-day event exclusive and drops times', () => {
    expect(
      convertRequest({
        calendarId: 'work',
        allDay: true,
        startDate: '2026-02-28',
        startTime: '10:00',
        endDate: '2026-02-28',
        endTime: '11:00',
        timeZone: 'Europe/Zurich',
      }),
    ).toEqual({ calendarId: 'work', allDay: true, startDate: '2026-02-28', endDate: '2026-03-01' });
  });
});

describe('eventStartDay', () => {
  afterEach(() => vi.useRealTimers());

  it('gives the wall-clock day of the start in the zone of the event', () => {
    // 23:30 UTC on the 21st is the 22nd in Zurich and still the 21st in New York.
    const start = '2026-10-21T23:30:00Z';
    expect(eventStartDay(fakeEvent('a', { start, timeZone: 'Europe/Zurich' }))).toBe('2026-10-22');
    expect(eventStartDay(fakeEvent('b', { start, timeZone: 'America/New_York' }))).toBe(
      '2026-10-21',
    );
  });

  it('uses the zone of the browser when the event has none', () => {
    const start = '2026-10-21T23:30:00Z';
    const expected = eventStartDay(fakeEvent('a', { start, timeZone: browserTimeZone() }));
    expect(eventStartDay(fakeEvent('b', { start, timeZone: undefined }))).toBe(expected);
  });

  it('gives the start date of an all-day event, ignoring any instant', () => {
    const event = fakeEvent('a', {
      allDay: true,
      startDate: '2026-12-24',
      endDate: '2026-12-25',
      start: '2026-12-23T23:00:00Z',
    });
    expect(eventStartDay(event)).toBe('2026-12-24');
  });

  it('gives the start date of an event without a start instant', () => {
    expect(eventStartDay(fakeEvent('a', { start: undefined, startDate: '2026-11-02' }))).toBe(
      '2026-11-02',
    );
  });

  it('falls back to today when an all-day event has no start date', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 9, 21, 12, 0));
    expect(eventStartDay(fakeEvent('a', { allDay: true, startDate: undefined }))).toBe(
      '2026-10-21',
    );
  });
});

describe('browserTimeZone', () => {
  it('gives the resolved zone of the browser', () => {
    expect(browserTimeZone()).toBe(Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC');
  });
});

describe('askForCalendar', () => {
  it('opens the dialog with the data and resolves with its result', async () => {
    const closed = new Subject<ConvertToEventRequest | undefined>();
    const open = vi.fn(() => ({ afterClosed: () => closed }));
    const dialog = { open } as unknown as MatDialog;
    const request: ConvertToEventRequest = {
      calendarId: 'work',
      allDay: true,
      startDate: '2026-11-03',
      endDate: '2026-11-04',
    };

    const answer = askForCalendar(dialog, { title: 'Call the plumber', unsaved: true });
    closed.next(request);
    closed.complete();

    expect(await answer).toBe(request);
    expect(open).toHaveBeenCalledWith(
      ToCalendarDialog,
      expect.objectContaining({ data: { title: 'Call the plumber', unsaved: true } }),
    );
  });

  it('resolves with undefined when the dialog is cancelled', async () => {
    const closed = new Subject<ConvertToEventRequest | undefined>();
    const dialog = { open: () => ({ afterClosed: () => closed }) } as unknown as MatDialog;
    const answer = askForCalendar(dialog, { title: 'Call the plumber' });
    closed.next(undefined);
    closed.complete();
    expect(await answer).toBeUndefined();
  });
});
