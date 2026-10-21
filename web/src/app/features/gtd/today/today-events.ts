import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  untracked,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { OccurrenceResponse } from '../../../api/generated';
import { CalendarStore, calendarColor } from '../../calendar/views/calendar-store';
import { OccurrenceRange, OccurrenceStore } from '../../calendar/views/occurrence-store';

/** How an occurrence relates to today: its times only show for the part inside today. */
export type EventSpan = 'allDay' | 'timed' | 'from' | 'until';

/** One row of the events section. */
export interface TodayEvent {
  readonly key: string;
  readonly occurrence: OccurrenceResponse;
  readonly span: EventSpan;
  readonly color: string;
  readonly calendarName: string | undefined;
}

/** Today in the browser's time zone: local midnight to the next local midnight. */
export function todayRange(now = new Date()): OccurrenceRange {
  const from = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const to = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1);
  return { from: from.toISOString(), to: to.toISOString() };
}

/** How `occurrence` covers `range`: all day, inside it, or crossing its start or end. */
export function eventSpan(occurrence: OccurrenceResponse, range: OccurrenceRange): EventSpan {
  if (occurrence.allDay || !occurrence.start || !occurrence.end) return 'allDay';
  const startsBefore = Date.parse(occurrence.start) < Date.parse(range.from);
  const endsAfter = Date.parse(occurrence.end) > Date.parse(range.to);
  if (startsBefore && endsAfter) return 'allDay';
  if (startsBefore) return 'until';
  if (endsAfter) return 'from';
  return 'timed';
}

/** All-day (and day-spanning) events first, then by start time, then by title. */
export function compareEvents(a: TodayEvent, b: TodayEvent): number {
  const allDay = Number(b.span === 'allDay') - Number(a.span === 'allDay');
  if (allDay !== 0) return allDay;
  const start = (event: TodayEvent) =>
    event.span === 'allDay' || event.span === 'until' ? 0 : Date.parse(event.occurrence.start!);
  return start(a) - start(b) || a.occurrence.title.localeCompare(b.occurrence.title);
}

/**
 * Today's calendar events (SPEC.md §5.3) at the top of the Today view: the occurrences of the
 * visible calendars between local midnight and the next one, time-ordered.
 */
@Component({
  selector: 'app-today-events',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, MatButtonModule, RouterLink],
  providers: [OccurrenceStore],
  templateUrl: './today-events.html',
  styleUrl: './today-events.scss',
})
export class TodayEvents {
  private readonly calendars = inject(CalendarStore);
  private readonly occurrences = inject(OccurrenceStore);
  private readonly range = todayRange();

  /** The visible calendar ids as one comparable value, so the effect only reruns on a change. */
  private readonly calendarIds = computed(() => [...this.calendars.visibleIds()].sort().join(','));

  protected readonly events = computed((): readonly TodayEvent[] => {
    const visible = this.calendars.visibleIds();
    const byId = this.calendars.byId();
    return this.occurrences
      .items()
      .filter((occurrence) => visible.has(occurrence.calendarId))
      .map((occurrence) => {
        const calendar = byId.get(occurrence.calendarId);
        return {
          key: `${occurrence.eventId}@${occurrence.occurrence ?? ''}`,
          occurrence,
          span: eventSpan(occurrence, this.range),
          color: calendarColor(calendar),
          calendarName: calendar?.name,
        };
      })
      .sort(compareEvents);
  });
  protected readonly loaded = computed(() => this.calendars.loaded() && this.occurrences.loaded());
  protected readonly failed = computed(
    () =>
      (!this.calendars.loaded() && this.calendars.error() !== null) ||
      (!this.occurrences.loaded() && this.occurrences.error() !== null),
  );

  constructor() {
    void this.calendars.load();
    effect(() => {
      const ids = this.calendarIds();
      if (!this.calendars.loaded()) return;
      untracked(() => void this.occurrences.load(this.range, ids ? ids.split(',') : []));
    });
  }

  protected retry(): void {
    if (!this.calendars.loaded()) void this.calendars.load();
    else void this.occurrences.refresh();
  }
}
