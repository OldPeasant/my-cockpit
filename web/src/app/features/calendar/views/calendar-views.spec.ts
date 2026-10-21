import { DEFAULT_CALENDAR_COLOR } from './calendar-store';
import {
  dateIn,
  firstDay,
  parseDate,
  parseView,
  toEventInput,
  VIEW_TYPES,
  viewName,
} from './calendar-views';
import { fakeCalendar, fakeOccurrence } from './testing';

describe('calendar views', () => {
  it('maps URL view names to FullCalendar views and back, week by default', () => {
    expect(parseView('day')).toBe('day');
    expect(parseView('agenda')).toBe('agenda');
    expect(parseView(null)).toBe('week');
    expect(parseView('year')).toBe('week');
    expect(parseView('toString')).toBe('week');
    for (const [name, type] of Object.entries(VIEW_TYPES)) expect(viewName(type)).toBe(name);
    expect(viewName('multiMonthYear')).toBe('week');
  });

  it('accepts only valid yyyy-MM-dd dates from the URL', () => {
    expect(parseDate('2026-03-29')).toBe('2026-03-29');
    expect(parseDate('2026-02-30')).toBeUndefined();
    expect(parseDate('2026-3-29')).toBeUndefined();
    expect(parseDate('yesterday')).toBeUndefined();
    expect(parseDate(undefined)).toBeUndefined();
  });

  it('rejects out-of-range and decorated dates, accepts leap days', () => {
    expect(parseDate('2028-02-29')).toBe('2028-02-29');
    expect(parseDate('2026-02-29')).toBeUndefined();
    expect(parseDate('2026-13-01')).toBeUndefined();
    expect(parseDate('2026-00-10')).toBeUndefined();
    expect(parseDate('2026-04-31')).toBeUndefined();
    expect(parseDate('2026-03-00')).toBeUndefined();
    expect(parseDate('2026-03-29T00:00')).toBeUndefined();
    expect(parseDate(' 2026-03-29')).toBeUndefined();
    expect(parseDate('')).toBeUndefined();
    expect(parseDate(null)).toBeUndefined();
  });

  it('ignores empty and prototype view names from the URL', () => {
    expect(parseView('')).toBe('week');
    expect(parseView('__proto__')).toBe('week');
    expect(parseView('constructor')).toBe('week');
    expect(parseView('WEEK')).toBe('week');
    expect(parseView('month')).toBe('month');
  });

  it('formats the date of an instant across the DST change', () => {
    // 2026-03-29 00:30 CET and 23:30 CEST are both on the 29th in Zurich.
    expect(dateIn(new Date('2026-03-28T23:30:00Z'), 'Europe/Zurich')).toBe('2026-03-29');
    expect(dateIn(new Date('2026-03-29T21:30:00Z'), 'Europe/Zurich')).toBe('2026-03-29');
    expect(dateIn(new Date('2026-03-29T22:30:00Z'), 'Europe/Zurich')).toBe('2026-03-30');
  });

  it('formats the date of an instant in a time zone', () => {
    // 23:30 UTC on the 28th is already the 29th in Zurich, still the 28th in New York.
    const instant = new Date('2026-03-28T23:30:00Z');
    expect(dateIn(instant, 'Europe/Zurich')).toBe('2026-03-29');
    expect(dateIn(instant, 'America/New_York')).toBe('2026-03-28');
  });

  it('maps the week start setting to FullCalendar firstDay', () => {
    expect(firstDay('SUNDAY')).toBe(0);
    expect(firstDay('MONDAY')).toBe(1);
    expect(firstDay('SATURDAY')).toBe(6);
    expect(firstDay('WEDNESDAY')).toBe(3);
    // An unknown value (a newer server) falls back to Sunday instead of an invalid -1.
    expect(firstDay('HOLIDAY' as Parameters<typeof firstDay>[0])).toBe(0);
  });

  it('maps a timed occurrence to an event in its calendar color', () => {
    const input = toEventInput(
      fakeOccurrence('Standup', { calendarId: 'work' }),
      fakeCalendar('work', { color: '#1565c0', name: 'Work' }),
    );
    expect(input).toMatchObject({
      id: 'event-Standup',
      title: 'Standup',
      allDay: false,
      start: '2026-03-23T08:00:00Z',
      end: '2026-03-23T09:00:00Z',
      color: '#1565c0',
      className: 'app-event',
    });
    expect(input.extendedProps?.['calendarName']).toBe('Work');
  });

  it('gives each occurrence of a series its own id', () => {
    const a = toEventInput(
      fakeOccurrence('Swim', { eventId: 's', seriesId: 's', occurrence: '2026-03-23T08:00:00Z' }),
      undefined,
    );
    const b = toEventInput(
      fakeOccurrence('Swim', { eventId: 's', seriesId: 's', occurrence: '2026-03-29T07:00:00Z' }),
      undefined,
    );
    expect(a.id).not.toBe(b.id);
    expect(a.color).toBe(DEFAULT_CALENDAR_COLOR);
  });

  it('maps an all-day occurrence to its dates (exclusive end)', () => {
    const input = toEventInput(
      fakeOccurrence('Holiday', {
        allDay: true,
        start: undefined,
        end: undefined,
        startDate: '2026-03-27',
        endDate: '2026-03-28',
      }),
      undefined,
    );
    expect(input).toMatchObject({ allDay: true, start: '2026-03-27', end: '2026-03-28' });
  });

  it('marks cancelled and tentative occurrences', () => {
    expect(toEventInput(fakeOccurrence('A', { status: 'CANCELLED' }), undefined).className).toBe(
      'app-event app-event-cancelled',
    );
    expect(toEventInput(fakeOccurrence('B', { status: 'TENTATIVE' }), undefined).className).toBe(
      'app-event app-event-tentative',
    );
  });

  it('lets only events of writable calendars that are not cancelled be dragged', () => {
    const writable = fakeCalendar('work');
    const feed = fakeCalendar('feed', { readOnly: true });
    expect(toEventInput(fakeOccurrence('A'), writable).editable).toBe(true);
    expect(toEventInput(fakeOccurrence('A'), undefined).editable).toBe(true);
    expect(toEventInput(fakeOccurrence('B'), feed).editable).toBe(false);
    expect(toEventInput(fakeOccurrence('C', { status: 'CANCELLED' }), writable).editable).toBe(
      false,
    );
  });
});
