import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Api, CalendarResponse, listCalendars, updateCalendar } from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';

/** The color of a calendar without one (a CSS value: the theme's primary color). */
export const DEFAULT_CALENDAR_COLOR = 'var(--mat-sys-primary)';

/**
 * The user's calendars (SPEC.md §6.1) with their color and visibility, ordered by name.
 *
 * {@link setVisible} is optimistic: the toggle shows at once, is saved with `PATCH` + `If-Match`
 * and rolled back when the server refuses it. A SSE resync reloads the list.
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
    () => new Set(this.calendars().filter((c) => c.visible).map((c) => c.id)),
  );
  /** Each calendar by id. */
  readonly byId = computed(() => new Map(this.calendars().map((c) => [c.id, c])));
  readonly loaded = this.loadedState.asReadonly();
  /** The error of the newest list request, `null` after a successful one. */
  readonly error = this.errorState.asReadonly();

  constructor() {
    inject(ChangeEventsService)
      .resync$.pipe(takeUntilDestroyed(inject(DestroyRef)))
      .subscribe(() => void this.load());
  }

  /** Fetches the calendars. */
  async load(): Promise<void> {
    const seq = ++this.requestSeq;
    try {
      const { items } = await this.api.invoke(listCalendars);
      if (seq !== this.requestSeq) return;
      this.server.set(items);
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
      this.server.update((all) => all.map((c) => (c.id === saved.id ? saved : c)));
      this.requestSeq++; // a list answer requested before this change is older than it
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

/** The display color of `calendar`. */
export function calendarColor(calendar: CalendarResponse | undefined): string {
  return calendar?.color ?? DEFAULT_CALENDAR_COLOR;
}
