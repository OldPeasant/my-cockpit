import { DestroyRef, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { merge } from 'rxjs';
import { Api, listEventOccurrences, OccurrenceResponse } from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';

/** A time range of a view: `from` inclusive, `to` exclusive (ISO-8601 instants). */
export interface OccurrenceRange {
  readonly from: string;
  readonly to: string;
}

interface Query {
  readonly range: OccurrenceRange;
  readonly calendarIds: readonly string[];
}

/** The `ChangeEvent.type` of calendar events. */
const EVENT_TYPE = 'EVENT';

/**
 * The event occurrences of the visible range of a calendar view (`GET /calendar/events`, the
 * server expands recurrences).
 *
 * - Only the answer of the newest request is shown: navigating fast never lets a slow answer for
 *   an earlier range replace the current one.
 * - Event change notifications and a SSE resync refetch the range (they carry no contents).
 * - The previous range's occurrences stay shown until the new answer arrives, so navigating
 *   doesn't flash an empty grid.
 */
@Injectable()
export class OccurrenceStore {
  private readonly api = inject(Api);

  private readonly itemsState = signal<readonly OccurrenceResponse[]>([]);
  private readonly loadingState = signal(false);
  private readonly loadedState = signal(false);
  private readonly errorState = signal<unknown>(null);
  private query: Query | undefined;
  private requestSeq = 0;

  /** The occurrences of the current range, ordered by start. */
  readonly items = this.itemsState.asReadonly();
  readonly loading = this.loadingState.asReadonly();
  /** Whether an answer arrived (stays true across ranges). */
  readonly loaded = this.loadedState.asReadonly();
  /** The error of the newest request, `null` after a successful one. */
  readonly error = this.errorState.asReadonly();

  constructor() {
    const events = inject(ChangeEventsService);
    merge(events.resync$, events.events$(EVENT_TYPE))
      .pipe(takeUntilDestroyed(inject(DestroyRef)))
      .subscribe(() => void this.refresh());
  }

  /** Shows the occurrences of `calendarIds` in `range` once they are fetched. */
  load(range: OccurrenceRange, calendarIds: readonly string[]): Promise<void> {
    this.query = { range, calendarIds };
    return this.refresh();
  }

  /** Fetches the current range again; does nothing before the first {@link load}. */
  async refresh(): Promise<void> {
    const query = this.query;
    if (!query) return;
    const seq = ++this.requestSeq;
    if (query.calendarIds.length === 0) {
      // No calendars: nothing to ask (an empty filter would mean "the visible ones").
      this.accept([]);
      this.loadingState.set(false);
      return;
    }
    this.loadingState.set(true);
    try {
      const { items } = await this.api.invoke(listEventOccurrences, {
        from: query.range.from,
        to: query.range.to,
        calendarIds: [...query.calendarIds],
      });
      if (seq === this.requestSeq) this.accept(items);
    } catch (error) {
      if (seq === this.requestSeq) this.errorState.set(error);
    } finally {
      if (seq === this.requestSeq) this.loadingState.set(false);
    }
  }

  private accept(items: readonly OccurrenceResponse[]): void {
    this.itemsState.set(items);
    this.errorState.set(null);
    this.loadedState.set(true);
  }
}
