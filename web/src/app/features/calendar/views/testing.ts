import { CalendarResponse, EventResponse, OccurrenceResponse } from '../../../api/generated';

/** A fictitious single timed event (Monday 2026-03-23, 09:00–10:00 Zurich time). */
export function fakeEvent(id: string, fields: Partial<EventResponse> = {}): EventResponse {
  return {
    id,
    calendarId: 'work',
    title: `Event ${id}`,
    allDay: false,
    start: '2026-03-23T08:00:00Z',
    end: '2026-03-23T09:00:00Z',
    timeZone: 'Europe/Zurich',
    status: 'CONFIRMED',
    transparency: 'OPAQUE',
    syncState: 'LOCAL',
    attendees: [],
    exdates: [],
    reminderMinutes: [],
    createdAt: '2026-03-01T08:00:00Z',
    updatedAt: '2026-03-01T08:00:00Z',
    version: 1,
    ...fields,
  };
}

/** A fictitious local calendar as the server returns it. */
export function fakeCalendar(id: string, fields: Partial<CalendarResponse> = {}): CalendarResponse {
  return {
    id,
    name: `Calendar ${id}`,
    color: '#2e7d32',
    isDefault: false,
    readOnly: false,
    visible: true,
    sourceId: 'local',
    sourceType: 'LOCAL',
    createdAt: '2026-03-01T08:00:00Z',
    updatedAt: '2026-03-01T08:00:00Z',
    version: 1,
    ...fields,
  };
}

/** A fictitious timed occurrence (Monday 2026-03-23, 09:00–10:00 Zurich time). */
export function fakeOccurrence(
  title: string,
  fields: Partial<OccurrenceResponse> = {},
): OccurrenceResponse {
  return {
    eventId: `event-${title}`,
    calendarId: 'work',
    title,
    allDay: false,
    start: '2026-03-23T08:00:00Z',
    end: '2026-03-23T09:00:00Z',
    timeZone: 'Europe/Zurich',
    status: 'CONFIRMED',
    transparency: 'OPAQUE',
    version: 1,
    ...fields,
  };
}
