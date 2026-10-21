import {
  AttendeeDto,
  CreateEventRequest,
  EventPatchRequest,
  EventResponse,
  OccurrenceResponse,
} from '../../../api/generated';
import { addDays, instantOf, wallClockOf } from './event-times';
import { normalize, Repeat, repeatOf, rruleOf } from './recurrence';

/** The times of a new event, e.g. from a selection in a view. */
export interface EventDraft {
  readonly allDay: boolean;
  /** Timed: instants. */
  readonly start?: string;
  readonly end?: string;
  /** All-day: dates, `endDate` exclusive. */
  readonly startDate?: string;
  readonly endDate?: string;
}

/**
 * What the event dialog edits. Dates and times are wall-clock values in `timeZone`; for all-day
 * events `endDate` is the last day (inclusive, as people read it), not the exclusive API date.
 */
export interface EventFormValue {
  title: string;
  calendarId: string;
  allDay: boolean;
  startDate: string;
  startTime: string;
  endDate: string;
  endTime: string;
  timeZone: string;
  repeat: Repeat;
  /** The rule a `custom` repeat keeps. */
  rrule: string;
  location: string;
  description: string;
  reminderMinutes: number[];
  attendees: AttendeeDto[];
}

/** The form of a new event with `draft`'s times. */
export function newForm(draft: EventDraft, calendarId: string, timeZone: string): EventFormValue {
  return {
    title: '',
    calendarId,
    ...timesOf(draft, timeZone),
    timeZone,
    repeat: 'none',
    rrule: '',
    location: '',
    description: '',
    reminderMinutes: [],
    attendees: [],
  };
}

/**
 * The form of `occurrence`: the data of `event` (the event that provides it), the repeat rule of
 * `master` (the series) and the times of the occurrence.
 */
export function editForm(
  occurrence: OccurrenceResponse,
  event: EventResponse,
  master: EventResponse | undefined,
  defaultTimeZone: string,
): EventFormValue {
  const timeZone = event.timeZone ?? occurrence.timeZone ?? defaultTimeZone;
  const rrule = normalize(master?.rrule ?? event.rrule);
  return {
    title: event.title,
    calendarId: event.calendarId,
    ...timesOf(occurrence, timeZone),
    timeZone,
    repeat: repeatOf(rrule),
    rrule,
    location: event.location ?? '',
    description: event.description ?? '',
    reminderMinutes: [...event.reminderMinutes].sort((a, b) => a - b),
    attendees: event.attendees.map((attendee) => ({ ...attendee })),
  };
}

function timesOf(
  source: EventDraft,
  timeZone: string,
): Pick<EventFormValue, 'allDay' | 'startDate' | 'startTime' | 'endDate' | 'endTime'> {
  if (source.allDay) {
    const startDate = source.startDate!;
    const endDate = source.endDate ? addDays(source.endDate, -1) : startDate;
    return {
      allDay: true,
      startDate,
      endDate: endDate < startDate ? startDate : endDate,
      startTime: '09:00',
      endTime: '10:00',
    };
  }
  const start = wallClockOf(source.start!, timeZone);
  const end = wallClockOf(source.end ?? source.start!, timeZone);
  return {
    allDay: false,
    startDate: start.date,
    startTime: start.time,
    endDate: end.date,
    endTime: end.time,
  };
}

/** Whether the end of `form` is after its start (all-day: not before). */
export function endsAfterStart(form: EventFormValue): boolean {
  if (form.allDay) return form.endDate >= form.startDate;
  return `${form.endDate}T${form.endTime}` > `${form.startDate}T${form.startTime}`;
}

/** The time fields of `form`, as the API takes them. */
function timeFields(
  form: EventFormValue,
): Pick<CreateEventRequest, 'start' | 'end' | 'startDate' | 'endDate'> {
  if (form.allDay) return { startDate: form.startDate, endDate: addDays(form.endDate, 1) };
  return {
    start: instantOf(form.startDate, form.startTime, form.timeZone),
    end: instantOf(form.endDate, form.endTime, form.timeZone),
  };
}

/**
 * The request creating the event of `form`. `attendees` only when `withAttendees` (local
 * calendars: providers manage the guests of synced ones).
 */
export function createRequest(form: EventFormValue, withAttendees: boolean): CreateEventRequest {
  const rrule = rruleOf(form.repeat, form.rrule);
  return {
    title: form.title.trim(),
    calendarId: form.calendarId,
    allDay: form.allDay,
    ...timeFields(form),
    ...(form.allDay ? {} : { timeZone: form.timeZone }),
    ...(rrule ? { rrule } : {}),
    ...(form.location.trim() ? { location: form.location.trim() } : {}),
    ...(form.description.trim() ? { description: form.description } : {}),
    reminderMinutes: [...form.reminderMinutes],
    ...(withAttendees && form.attendees.length ? { attendees: form.attendees } : {}),
  };
}

/**
 * The fields that differ between `initial` and `current` (only those are sent, so a change of one
 * occurrence or of the series touches nothing else). Time fields are sent together when any of
 * them changed. `attendees` only when `withAttendees` (local calendars).
 */
export function patchRequest(
  initial: EventFormValue,
  current: EventFormValue,
  withAttendees: boolean,
): EventPatchRequest {
  const patch: EventPatchRequest = {};
  if (current.title.trim() !== initial.title.trim()) patch.title = current.title.trim();
  if (current.calendarId !== initial.calendarId) patch.calendarId = current.calendarId;
  if (current.location.trim() !== initial.location.trim()) {
    patch.location = current.location.trim() || null;
  }
  if (current.description !== initial.description) {
    patch.description = current.description.trim() ? current.description : null;
  }
  const rrule = rruleOf(current.repeat, current.rrule);
  if (rrule !== rruleOf(initial.repeat, initial.rrule)) patch.rrule = rrule;
  if (!sameNumbers(current.reminderMinutes, initial.reminderMinutes)) {
    patch.reminderMinutes = [...current.reminderMinutes];
  }
  if (withAttendees && !sameAttendees(current.attendees, initial.attendees)) {
    patch.attendees = current.attendees;
  }
  const timesChanged =
    current.allDay !== initial.allDay ||
    current.startDate !== initial.startDate ||
    current.endDate !== initial.endDate ||
    (!current.allDay &&
      (current.startTime !== initial.startTime ||
        current.endTime !== initial.endTime ||
        current.timeZone !== initial.timeZone));
  if (timesChanged) {
    if (current.allDay !== initial.allDay) patch.allDay = current.allDay;
    Object.assign(patch, timeFields(current));
    if (!current.allDay && (current.timeZone !== initial.timeZone || initial.allDay)) {
      patch.timeZone = current.timeZone;
    }
  }
  return patch;
}

function sameNumbers(a: readonly number[], b: readonly number[]): boolean {
  return a.length === b.length && a.every((value, i) => value === b[i]);
}

function sameAttendees(a: readonly AttendeeDto[], b: readonly AttendeeDto[]): boolean {
  return (
    a.length === b.length &&
    a.every(
      (attendee, i) =>
        attendee.email === b[i].email &&
        (attendee.name ?? '') === (b[i].name ?? '') &&
        attendee.status === b[i].status,
    )
  );
}
