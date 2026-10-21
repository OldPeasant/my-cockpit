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
import { addDays, localDate } from '../data';

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

/**
 * Whether an all-day occurrence falls on the ISO date `date` (`endDate` is exclusive). The server
 * picks all-day occurrences by the calendar settings' time zone, so when the browser's zone differs
 * it can also return the neighbouring days' ones. Timed occurrences always pass.
 */
export function isOnDate(occurrence: OccurrenceResponse, date: string): boolean {
  if (!occurrence.allDay || !occurrence.startDate) return true;
  const end = occurrence.endDate ?? addDays(occurrence.startDate, 1);
  return occurrence.startDate <= date && date < end;
}

/**
 * How `occurrence` covers `range`: all day (also a timed event covering the whole range), inside
 * it, or crossing its start or end.
 */
export function eventSpan(occurrence: OccurrenceResponse, range: OccurrenceRange): EventSpan {
  if (occurrence.allDay || !occurrence.start || !occurrence.end) return 'allDay';
  const start = Date.parse(occurrence.start);
  const end = Date.parse(occurrence.end);
  const from = Date.parse(range.from);
  const to = Date.parse(range.to);
  if (start <= from && end >= to) return 'allDay';
  if (start < from) return 'until';
  if (end > to) return 'from';
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
  private readonly now = new Date();
  private readonly range = todayRange(this.now);
  private readonly today = localDate(this.now);

  /** The visible calendar ids as one comparable value, so the effect only reruns on a change. */
  private readonly calendarIds = computed(() => [...this.calendars.visibleIds()].sort().join(','));

  protected readonly events = computed((): readonly TodayEvent[] => {
    const visible = this.calendars.visibleIds();
    const byId = this.calendars.byId();
    return this.occurrences
      .items()
      .filter(
        (occurrence) => visible.has(occurrence.calendarId) && isOnDate(occurrence, this.today),
      )
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
