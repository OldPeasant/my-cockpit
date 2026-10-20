import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { merge } from 'rxjs';
import { Api, CalendarResponse, listCalendars, updateCalendar } from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';

/** The color of a calendar without one (a CSS value: the theme's primary color). */
export const DEFAULT_CALENDAR_COLOR = 'var(--mat-sys-primary)';

/** The `ChangeEvent.type` of calendars. */
const CALENDAR_TYPE = 'CALENDAR';

/**
 * The user's calendars (SPEC.md §6.1) with their color and visibility, ordered by name.
 *
 * {@link setVisible} is optimistic: the toggle shows at once, is saved with `PATCH` + `If-Match`
 * and rolled back when the server refuses it. Calendar change notifications and a SSE resync
 * reload the list.
 */
@Injectable({ providedIn: 'root' })
export class CalendarStore {
  private readonly api = inject(Api);

  private readonly server = signal<readonly CalendarResponse[]>([]);
  /** Visibility shown before the server confirmed it, by calendar id. */
  private readonly pendingVisible = signal<ReadonlyMap<string, boolean>>(new Map());
  private readonly loadedState = signal(false);
  private readonly errorState = signal<unknown>(null);
  /** Id of the newest list request; answers of older ones are dropped. */
  private requestSeq = 0;
  private saving: Promise<unknown> = Promise.resolve();

  /** The calendars with optimistic visibility applied. */
  readonly calendars = computed(() => {
    const pending = this.pendingVisible();
    return this.server().map((calendar) =>
      pending.has(calendar.id) ? { ...calendar, visible: pending.get(calendar.id)! } : calendar,
    );
  });
  /** Ids of the visible calendars. */
  readonly visibleIds = computed(
    () =>
      new Set(
        this.calendars()
          .filter((c) => c.visible)
          .map((c) => c.id),
      ),
  );
  /** Each calendar by id. */
  readonly byId = computed(() => new Map(this.calendars().map((c) => [c.id, c])));
  readonly loaded = this.loadedState.asReadonly();
  /** The error of the newest list request, `null` after a successful one. */
  readonly error = this.errorState.asReadonly();

  constructor() {
    const events = inject(ChangeEventsService);
    merge(events.resync$, events.events$(CALENDAR_TYPE))
      .pipe(takeUntilDestroyed(inject(DestroyRef)))
      .subscribe(() => void this.load());
  }

  /** Fetches the calendars. */
  async load(): Promise<void> {
    const seq = ++this.requestSeq;
    try {
      const { items } = await this.api.invoke(listCalendars);
      if (seq !== this.requestSeq) return;
      this.server.update((current) => newest(current, items));
      this.errorState.set(null);
      this.loadedState.set(true);
    } catch (error) {
      if (seq === this.requestSeq) this.errorState.set(error);
    }
  }

  /** Shows or hides `calendar` in the views; rethrows the error after rolling back. */
  setVisible(calendar: CalendarResponse, visible: boolean): Promise<void> {
    this.setPending(calendar.id, visible);
    // Saves run one after the other, so each sends the version the previous one returned.
    const result = this.saving.then(() => this.save(calendar, visible));
    this.saving = result.catch(() => undefined);
    return result;
  }

  private async save(calendar: CalendarResponse, visible: boolean): Promise<void> {
    const current = this.server().find((c) => c.id === calendar.id) ?? calendar;
    try {
      const saved = await this.api.invoke(updateCalendar, {
        id: calendar.id,
        'If-Match': `"${current.version}"`,
        body: { visible },
      });
      // A list answer that arrived first may already hold a newer version of this calendar.
      this.server.update((all) =>
        all.map((c) => (c.id === saved.id && c.version <= saved.version ? saved : c)),
      );
    } finally {
      // A later toggle of the same calendar owns the pending state now.
      if (this.pendingVisible().get(calendar.id) === visible) this.setPending(calendar.id);
    }
  }

  private setPending(id: string, visible?: boolean): void {
    this.pendingVisible.update((pending) => {
      const next = new Map(pending);
      if (visible === undefined) next.delete(id);
      else next.set(id, visible);
      return next;
    });
  }
}

/**
 * The calendars of a list answer, each replaced by the known one when that has a higher version:
 * an answer requested before a save must not bring back the state the save replaced.
 */
function newest(
  known: readonly CalendarResponse[],
  answer: readonly CalendarResponse[],
): readonly CalendarResponse[] {
  const byId = new Map(known.map((c) => [c.id, c]));
  return answer.map((calendar) => {
    const current = byId.get(calendar.id);
    return current && current.version > calendar.version ? current : calendar;
  });
}

/** The display color of `calendar`. */
export function calendarColor(calendar: CalendarResponse | undefined): string {
  return calendar?.color ?? DEFAULT_CALENDAR_COLOR;
}
