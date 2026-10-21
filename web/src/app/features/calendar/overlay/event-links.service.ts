import { HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import {
  Api,
  createNote,
  createTask,
  EventResponse,
  ItemRefDto,
  NoteResponse,
  OccurrenceResponse,
  TaskResponse,
} from '../../../api/generated';
import { SILENT_ERRORS } from '../../../core/http';
import { LinksService } from '../../../shared/links';
import { wallClockOf } from '../edit/event-times';

/** The item a link to `occurrence` points to: the event that holds its data. */
export function eventRef(occurrence: OccurrenceResponse): ItemRefDto {
  return { type: 'EVENT', id: occurrence.eventId };
}

/**
 * The occurrence the dialog opens for `event` reached by a link (`/calendar/events/{id}`): the event
 * itself (a series as a whole, since a link points to the event, not to one of its occurrences).
 */
export function occurrenceOfEvent(event: EventResponse): OccurrenceResponse {
  return {
    eventId: event.id,
    calendarId: event.calendarId,
    title: event.title,
    allDay: event.allDay,
    start: event.start,
    end: event.end,
    startDate: event.startDate,
    endDate: event.endDate,
    timeZone: event.timeZone,
    location: event.location,
    status: event.status,
    transparency: event.transparency,
    version: event.version,
  };
}

/** The local date (`yyyy-MM-dd`) of `occurrence` in `timeZone`. */
export function occurrenceDate(occurrence: OccurrenceResponse, timeZone: string): string {
  if (occurrence.allDay) return occurrence.startDate ?? '';
  return occurrence.start ? wallClockOf(occurrence.start, timeZone).date : '';
}

/**
 * A linked item was created, but the link failed: the item exists (in the Inbox or the notes)
 * without the link.
 */
export class LinkFailedError extends Error {
  constructor(readonly item: ItemRefDto) {
    super('The item was created, but it could not be linked.');
  }
}

/**
 * Creates items linked to an event (SPEC.md §6.3): a follow-up task in the Inbox, or a note for
 * the meeting. Each is created first and then linked to the event; errors are silent (the event
 * dialog shows them).
 */
@Injectable({ providedIn: 'root' })
export class EventLinksService {
  private readonly api = inject(Api);
  private readonly links = inject(LinksService);

  /** A new Inbox task titled with the event and its date, linked to it. */
  async createTask(occurrence: OccurrenceResponse, timeZone: string): Promise<TaskResponse> {
    const title = datedTitle(occurrence, timeZone);
    const task = await this.api.invoke(createTask, { body: { title, list: 'INBOX' } }, silent());
    await this.link({ type: 'TASK', id: task.id }, occurrence);
    return task;
  }

  /** A new note titled with the event and its date, linked to it. */
  async createNote(occurrence: OccurrenceResponse, timeZone: string): Promise<NoteResponse> {
    const title = datedTitle(occurrence, timeZone);
    const note = await this.api.invoke(createNote, { body: { title } }, silent());
    await this.link({ type: 'NOTE', id: note.id }, occurrence);
    return note;
  }

  private async link(item: ItemRefDto, occurrence: OccurrenceResponse): Promise<void> {
    try {
      await this.links.create(item, eventRef(occurrence));
    } catch {
      throw new LinkFailedError(item);
    }
  }
}

/** `<event title> (<yyyy-MM-dd>)`, or the plain title when the occurrence has no date. */
function datedTitle(occurrence: OccurrenceResponse, timeZone: string): string {
  const date = occurrenceDate(occurrence, timeZone);
  return date ? `${occurrence.title} (${date})` : occurrence.title;
}

function silent(): HttpContext {
  return new HttpContext().set(SILENT_ERRORS, true);
}
