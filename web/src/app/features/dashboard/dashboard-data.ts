import { HttpContext } from '@angular/common/http';
import { DestroyRef, inject, Injectable, signal, WritableSignal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { debounceTime, merge } from 'rxjs';
import {
  Api,
  BookmarkResponse,
  getWeeklyReviewStatus,
  HabitResponse,
  listBookmarks,
  listEventOccurrences,
  listHabits,
  listMailMessages,
  listNotes,
  listProjects,
  listTasks,
  ListTasks$Params,
  NoteResponse,
  OccurrenceResponse,
  ProjectResponse,
  TaskResponse,
  WeeklyReviewStatusResponse,
} from '../../api/generated';
import { ChangeEventsService } from '../../core/events';
import { SILENT_ERRORS } from '../../core/http';
import { addDays, localDate } from '../gtd/data';
import { WidgetId } from './dashboard-layout';

/** Page size of the lists a widget counts: beyond it, the count shows as "N+". */
export const PAGE = 50;
/** How far ahead the Upcoming events widget looks. */
export const UPCOMING_DAYS = 7;

/** A number that may be cut at a page size (`more`: there are more than `count`). */
export interface Count {
  readonly count: number;
  readonly more: boolean;
}

export interface TodayData {
  /** Overdue and due-today tasks, overdue first. */
  readonly tasks: readonly TaskResponse[];
  readonly events: readonly OccurrenceResponse[];
  /** Habits due today and how many of them are done. */
  readonly habitsDue: number;
  readonly habitsDone: number;
}

export interface InboxData {
  readonly tasks: Count;
  /** Unread messages in the unified Inbox of all mail accounts. */
  readonly unreadMail: Count;
}

/** What each widget shows. */
export interface WidgetData {
  today: TodayData;
  inbox: InboxData;
  upcoming: readonly OccurrenceResponse[];
  waitingOverdue: readonly TaskResponse[];
  stalledProjects: readonly ProjectResponse[];
  weeklyReview: WeeklyReviewStatusResponse;
  readLater: { readonly items: readonly BookmarkResponse[]; readonly more: boolean };
  pinnedNotes: { readonly items: readonly NoteResponse[]; readonly more: boolean };
}

/** A widget's data: loading (the first time), failed, or loaded. */
export type WidgetState<T> =
  | { readonly status: 'loading' }
  | { readonly status: 'error' }
  | { readonly status: 'ready'; readonly value: T };

/** The change event types (`ChangeEvent.type`) after which a widget loads again. */
const SOURCES: Record<WidgetId, readonly string[]> = {
  today: ['TASK', 'EVENT', 'HABIT'],
  inbox: ['TASK', 'MAIL_FOLDER'],
  upcoming: ['EVENT', 'CALENDAR'],
  waitingOverdue: ['TASK'],
  stalledProjects: ['PROJECT', 'TASK'],
  weeklyReview: ['WEEKLY_REVIEW'],
  readLater: ['BOOKMARK'],
  pinnedNotes: ['NOTE'],
};

/**
 * Loads the data of the dashboard widgets (SPEC.md §8.7), each on its own, so a failing module only
 * breaks its widget. Only the widgets that are {@link show}n load; they load again (keeping their
 * data on screen) after a change event of their types and after an SSE resync. Errors are not
 * toasted: the widget shows them.
 */
@Injectable()
export class DashboardData {
  private readonly api = inject(Api);
  private readonly states = new Map<WidgetId, WritableSignal<WidgetState<unknown>>>();
  /** The newest request per widget; older answers are dropped. */
  private readonly seq = new Map<WidgetId, number>();
  private shown = new Set<WidgetId>();

  constructor() {
    const events = inject(ChangeEventsService);
    const destroyRef = inject(DestroyRef);
    for (const [id, types] of Object.entries(SOURCES) as [WidgetId, string[]][]) {
      merge(...types.map((type) => events.events$(type)))
        .pipe(debounceTime(300), takeUntilDestroyed(destroyRef))
        .subscribe(() => this.reload(id));
    }
    events.resync$
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe(() => this.shown.forEach((id) => this.reload(id)));
  }

  /** The state of widget `id`. */
  state<K extends WidgetId>(id: K): WidgetState<WidgetData[K]> {
    return this.signal(id)() as WidgetState<WidgetData[K]>;
  }

  /** Sets the shown widgets; those not loaded yet (or failed) load now. */
  show(ids: readonly WidgetId[]): void {
    this.shown = new Set(ids);
    for (const id of ids) {
      if (this.signal(id)().status !== 'ready') void this.load(id);
    }
  }

  /** Loads widget `id`; `quietly` keeps its data on screen and ignores a failure. */
  async load(id: WidgetId, quietly = false): Promise<void> {
    const seq = (this.seq.get(id) ?? 0) + 1;
    this.seq.set(id, seq);
    const state = this.signal(id);
    if (!quietly) state.set({ status: 'loading' });
    try {
      const value = await this.fetch(id);
      if (this.seq.get(id) === seq) state.set({ status: 'ready', value });
    } catch {
      if (this.seq.get(id) === seq && !quietly) state.set({ status: 'error' });
    }
  }

  private reload(id: WidgetId): void {
    if (!this.shown.has(id)) return;
    void this.load(id, this.signal(id)().status === 'ready');
  }

  private signal(id: WidgetId): WritableSignal<WidgetState<unknown>> {
    let state = this.states.get(id);
    if (!state) {
      state = signal<WidgetState<unknown>>({ status: 'loading' });
      this.states.set(id, state);
    }
    return state;
  }

  private fetch(id: WidgetId): Promise<WidgetData[WidgetId]> {
    switch (id) {
      case 'today':
        return this.today();
      case 'inbox':
        return this.inbox();
      case 'upcoming':
        return this.events(new Date(), UPCOMING_DAYS);
      case 'waitingOverdue':
        return this.tasks({ list: 'WAITING', due: 'overdue' });
      case 'stalledProjects':
        return this.api
          .invoke(listProjects, { status: 'ACTIVE', stalled: true, limit: PAGE }, silent())
          .then((page) => page.items);
      case 'weeklyReview':
        return this.api.invoke(getWeeklyReviewStatus, {}, silent());
      case 'readLater':
        return this.api
          .invoke(listBookmarks, { readLater: true, read: false, limit: PAGE }, silent())
          .then((page) => ({ items: page.items, more: !!page.nextCursor }));
      case 'pinnedNotes':
        return this.api.invoke(listNotes, { pinned: true, limit: PAGE }, silent()).then((page) => ({
          items: page.items,
          more: !!page.nextCursor,
        }));
    }
  }

  private async today(): Promise<TodayData> {
    const midnight = new Date();
    midnight.setHours(0, 0, 0, 0);
    const [overdue, due, events, habits] = await Promise.all([
      this.tasks({ due: 'overdue' }),
      this.tasks({ due: 'today' }),
      this.events(midnight, 1),
      this.api
        .invoke(listHabits, { archived: false }, silent())
        .then((list): HabitResponse[] => list.items),
    ]);
    const ids = new Set<string>();
    const tasks = [...overdue, ...due].filter((task) => !ids.has(task.id) && ids.add(task.id));
    const habitsDue = habits.filter((habit) => habit.stats.dueToday);
    return {
      tasks,
      events,
      habitsDue: habitsDue.length,
      habitsDone: habitsDue.filter((habit) => habit.stats.doneToday).length,
    };
  }

  private async inbox(): Promise<InboxData> {
    const [tasks, mail] = await Promise.all([
      this.api.invoke(listTasks, { list: 'INBOX', limit: PAGE }, silent()),
      this.api.invoke(listMailMessages, { limit: PAGE }, silent()),
    ]);
    return {
      tasks: { count: tasks.items.length, more: !!tasks.nextCursor },
      unreadMail: {
        count: (mail.items ?? []).filter((message) => !message.seen).length,
        more: !!mail.nextCursor,
      },
    };
  }

  private tasks(params: ListTasks$Params): Promise<TaskResponse[]> {
    return this.api
      .invoke(listTasks, { ...params, limit: PAGE }, silent())
      .then((page) => page.items);
  }

  /** The occurrences from `from` for `days` local days (from midnight), cancelled ones left out. */
  private async events(from: Date, days: number): Promise<OccurrenceResponse[]> {
    const end = new Date(`${addDays(localDate(from), days)}T00:00:00`);
    const list = await this.api.invoke(
      listEventOccurrences,
      {
        from: from.toISOString(),
        to: end.toISOString(),
      },
      silent(),
    );
    return list.items.filter((occurrence) => occurrence.status !== 'CANCELLED');
  }
}

function silent(): HttpContext {
  return new HttpContext().set(SILENT_ERRORS, true);
}
