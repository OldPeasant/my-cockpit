import type { EventInput } from '@fullcalendar/angular';
import { CalendarResponse, OccurrenceResponse, Settings } from '../../../api/generated';
import { calendarColor } from './calendar-store';

/** The views of SPEC.md §6.3, as named in the URL (`?view=`). */
export type CalendarViewName = 'day' | 'week' | 'month' | 'agenda';

export const DEFAULT_VIEW: CalendarViewName = 'week';

/** FullCalendar's view type of each view. */
export const VIEW_TYPES: Readonly<Record<CalendarViewName, string>> = {
  day: 'timeGridDay',
  week: 'timeGridWeek',
  month: 'dayGridMonth',
  agenda: 'listWeek',
};

/** The view named `value` (a URL parameter), else the default. */
export function parseView(value: string | null | undefined): CalendarViewName {
  return value && Object.hasOwn(VIEW_TYPES, value) ? (value as CalendarViewName) : DEFAULT_VIEW;
}

/** The URL name of a FullCalendar view type. */
export function viewName(type: string): CalendarViewName {
  const found = (Object.keys(VIEW_TYPES) as CalendarViewName[]).find(
    (name) => VIEW_TYPES[name] === type,
  );
  return found ?? DEFAULT_VIEW;
}

/** `value` if it is a valid `yyyy-MM-dd` date (a URL parameter), else `undefined`. */
export function parseDate(value: string | null | undefined): string | undefined {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return undefined;
  const [year, month, day] = value.split('-').map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  return date.getUTCMonth() === month - 1 && date.getUTCDate() === day ? value : undefined;
}

/** The `yyyy-MM-dd` date of `instant` in `timeZone`. */
export function dateIn(instant: Date, timeZone: string): string {
  // en-CA formats dates as yyyy-MM-dd.
  return new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(instant);
}

const WEEK_DAYS: readonly Settings['weekStart'][] = [
  'SUNDAY',
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
];

/** FullCalendar's `firstDay` (0 = Sunday) of the week start setting. */
export function firstDay(weekStart: Settings['weekStart']): number {
  return Math.max(0, WEEK_DAYS.indexOf(weekStart));
}

/** The FullCalendar event of one occurrence, in the color of its calendar. */
export function toEventInput(
  occurrence: OccurrenceResponse,
  calendar: CalendarResponse | undefined,
): EventInput {
  const color = calendarColor(calendar);
  const classNames = ['app-event'];
  if (occurrence.status === 'CANCELLED') classNames.push('app-event-cancelled');
  if (occurrence.status === 'TENTATIVE') classNames.push('app-event-tentative');
  const className = classNames.join(' ');
  return {
    // One series has many occurrences: the original start tells them apart.
    id: occurrence.occurrence ? `${occurrence.eventId}@${occurrence.occurrence}` : occurrence.eventId,
    title: occurrence.title,
    allDay: occurrence.allDay,
    start: occurrence.allDay ? occurrence.startDate : occurrence.start,
    end: occurrence.allDay ? occurrence.endDate : occurrence.end,
    color,
    className,
    extendedProps: { occurrence, calendarName: calendar?.name },
  };
}
