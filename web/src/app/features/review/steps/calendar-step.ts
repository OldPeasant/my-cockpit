import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  OnInit,
  signal,
} from '@angular/core';
import {
  Api,
  createTask,
  getReviewPastEvents,
  getReviewUpcomingEvents,
  OccurrenceResponse,
} from '../../../api/generated';
import { localDate } from '../../gtd/data';

/** The occurrences of one day. */
export interface EventDay {
  /** `yyyy-MM-dd` */
  readonly date: string;
  readonly events: readonly OccurrenceResponse[];
}

/**
 * Steps 2 and 3: the calendar of the last 7 or the next 14 days, with a quick-add field that
 * captures what comes to mind into the Inbox.
 */
@Component({
  selector: 'app-review-calendar-step',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe],
  styleUrl: './steps.scss',
  template: `
    <form class="capture" (submit)="capture($event)">
      <input
        type="text"
        aria-label="Capture a task"
        i18n-aria-label="@@review.calendar.captureLabel"
        placeholder="Anything to do? Capture it here"
        i18n-placeholder="@@review.calendar.capturePlaceholder"
        [value]="draft()"
        (input)="draft.set($any($event.target).value)"
      />
      <button
        type="submit"
        class="choice"
        [disabled]="saving() || !draft().trim()"
        i18n="@@review.calendar.capture"
      >
        Add to inbox
      </button>
    </form>
    <p class="status" role="status">{{ status() }}</p>

    @if (days(); as days) {
      @for (day of days; track day.date) {
        <section class="day" [attr.aria-label]="day.date | date: 'fullDate'">
          <h3>{{ day.date | date: 'EEEE, MMM d' }}</h3>
          <ul class="events">
            @for (event of day.events; track event.eventId + (event.occurrence ?? '')) {
              <li>
                <span class="time">
                  @if (event.allDay) {
                    <ng-container i18n="@@review.calendar.allDay">All day</ng-container>
                  } @else {
                    {{ event.start | date: 'HH:mm' }}
                  }
                </span>
                <span class="title">{{ event.title }}</span>
              </li>
            }
          </ul>
        </section>
      } @empty {
        <p class="empty">
          @if (range() === 'past') {
            <ng-container i18n="@@review.calendar.pastEmpty"
              >No events in the last 7 days.</ng-container
            >
          } @else {
            <ng-container i18n="@@review.calendar.upcomingEmpty"
              >No events in the next 14 days.</ng-container
            >
          }
        </p>
      }
    } @else if (failed()) {
      <div role="alert">
        <p class="empty" i18n="@@review.loadError">This step could not be loaded.</p>
        <button type="button" class="choice" (click)="load()" i18n="@@review.retry">
          Try again
        </button>
      </div>
    } @else {
      <p class="empty" i18n="@@review.loading">Loading…</p>
    }
  `,
})
export class CalendarStep implements OnInit {
  private readonly api = inject(Api);

  readonly range = input.required<'past' | 'upcoming'>();

  private readonly events = signal<readonly OccurrenceResponse[] | null>(null);
  protected readonly failed = signal(false);
  protected readonly draft = signal('');
  protected readonly saving = signal(false);
  protected readonly status = signal('');

  protected readonly days = computed(() => {
    const events = this.events();
    return events ? groupByDay(events) : null;
  });

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.failed.set(false);
    try {
      const fetch = this.range() === 'past' ? getReviewPastEvents : getReviewUpcomingEvents;
      this.events.set((await this.api.invoke(fetch)).items);
    } catch {
      this.failed.set(true);
    }
  }

  protected async capture(event: Event): Promise<void> {
    event.preventDefault();
    const title = this.draft().trim();
    if (!title || this.saving()) return;
    this.saving.set(true);
    try {
      await this.api.invoke(createTask, { body: { title } });
      this.draft.set('');
      this.status.set($localize`:@@review.calendar.captured:Added “${title}:title:” to the inbox.`);
    } catch {
      // The HTTP layer shows the error; the text stays to try again.
    } finally {
      this.saving.set(false);
    }
  }
}

/** The local day an occurrence starts on. */
export function eventDay(event: OccurrenceResponse): string {
  if (event.allDay && event.startDate) return event.startDate;
  return localDate(new Date(event.start ?? event.startDate ?? 0));
}

/** Occurrences grouped by their start day, in the order given (the server sorts them by start). */
export function groupByDay(events: readonly OccurrenceResponse[]): EventDay[] {
  const days = new Map<string, OccurrenceResponse[]>();
  for (const event of events) {
    const day = eventDay(event);
    days.set(day, [...(days.get(day) ?? []), event]);
  }
  return [...days.entries()].map(([date, items]) => ({ date, events: items }));
}
