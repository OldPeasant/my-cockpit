import { HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import {
  Api,
  createEvent,
  CreateEventRequest,
  deleteEvent,
  EventPatchRequest,
  EventResponse,
  getEvent,
  OccurrenceResponse,
  updateEvent,
} from '../../../api/generated';
import { ApiError, SILENT_ERRORS } from '../../../core/http';

/** Which occurrences of a series a change applies to (SPEC.md §6.2). */
export type EditScope = 'THIS' | 'FOLLOWING' | 'ALL';

/** What a change addresses: the event id, its version and, for a series, scope and occurrence. */
interface Target {
  readonly id: string;
  readonly version: number;
  readonly scope?: EditScope;
  readonly occurrence?: string;
}

/**
 * Whether `error` means the event changed since it was shown: a stale ETag (412), or a conflict
 * (409), which includes "changed at the provider" (the server fetched the new version, SPEC.md §6.2).
 */
export function isConflict(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 409 || error.status === 412);
}

/** Whether `occurrence` belongs to a recurring series (a change needs a scope). */
export function isSeries(occurrence: OccurrenceResponse): boolean {
  return !!occurrence.seriesId && !!occurrence.occurrence;
}

/**
 * The event requests of the calendar editor. Errors are silent (no toast): the dialog shows them
 * next to the form, the views as a message with a reload action.
 *
 * A change of an occurrence goes to the event that holds it: a single event or an override
 * directly (`scope=THIS`), every other scope to the series master with `?scope=&occurrence=`.
 */
@Injectable({ providedIn: 'root' })
export class EventEditService {
  private readonly api = inject(Api);

  get(id: string): Promise<EventResponse> {
    return this.api.invoke(getEvent, { id }, silent());
  }

  create(body: CreateEventRequest): Promise<EventResponse> {
    return this.api.invoke(createEvent, { body }, silent());
  }

  /**
   * Changes `occurrence` (and, by `scope`, other occurrences of its series). `masterVersion` is the
   * version of the series master the user saw; without it the master is fetched.
   */
  async update(
    occurrence: OccurrenceResponse,
    scope: EditScope | undefined,
    body: EventPatchRequest,
    masterVersion?: number,
  ): Promise<EventResponse> {
    const target = await this.target(occurrence, scope, masterVersion);
    return this.api.invoke(
      updateEvent,
      {
        id: target.id,
        scope: target.scope,
        occurrence: target.occurrence,
        'If-Match': etag(target.version),
        body,
      },
      silent(),
    );
  }

  /** Deletes `occurrence` (and, by `scope`, other occurrences of its series). */
  async delete(
    occurrence: OccurrenceResponse,
    scope: EditScope | undefined,
    masterVersion?: number,
  ): Promise<void> {
    const target = await this.target(occurrence, scope, masterVersion);
    await this.api.invoke(
      deleteEvent,
      {
        id: target.id,
        scope: target.scope,
        occurrence: target.occurrence,
        'If-Match': etag(target.version),
      },
      silent(),
    );
  }

  private async target(
    occurrence: OccurrenceResponse,
    scope: EditScope | undefined,
    masterVersion: number | undefined,
  ): Promise<Target> {
    if (!isSeries(occurrence)) return { id: occurrence.eventId, version: occurrence.version };
    const seriesId = occurrence.seriesId!;
    const isOverride = occurrence.eventId !== seriesId;
    const effective = scope ?? 'THIS';
    // An override stands for its occurrence.
    if (isOverride && effective === 'THIS') {
      return { id: occurrence.eventId, version: occurrence.version, scope: 'THIS' };
    }
    const version = !isOverride
      ? occurrence.version
      : (masterVersion ?? (await this.get(seriesId)).version);
    return { id: seriesId, version, scope: effective, occurrence: occurrence.occurrence };
  }
}

function etag(version: number): string {
  return `"${version}"`;
}

function silent(): HttpContext {
  return new HttpContext().set(SILENT_ERRORS, true);
}
