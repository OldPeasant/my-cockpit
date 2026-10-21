import { DOCUMENT, inject, Injectable, signal } from '@angular/core';
import type { EventInput } from '@fullcalendar/angular';
import { TaskResponse } from '../../../api/generated';
import { TaskStore } from '../../gtd/data';
import { addHours, instantOf } from '../edit/event-times';

/** Where the overlay toggle is remembered (per browser, like the other view preferences). */
export const TASK_OVERLAY_KEY = 'cockpit.calendar.taskOverlay';

/** The days a view shows, as local dates of the settings zone (both inclusive). */
export interface DayRange {
  readonly from: string;
  readonly to: string;
}

/** How a task shows in the overlay: on its due date, or on the day it comes back from a tickler. */
export type TaskMarkKind = 'due' | 'tickler';

/** The `extendedProps` of an overlay entry. */
export interface TaskMark {
  readonly task: TaskResponse;
  readonly kind: TaskMarkKind;
}

/** A due task with a time takes this long in the time grid. */
const DUE_SLOT_HOURS = 0.5;

/**
 * The optional task overlay of the day and week views (SPEC.md §6.3): open tasks due on the shown
 * days (at their due time, else all day) and ticklers coming back on them. Off by default; the
 * toggle is remembered in the browser. The tasks stay current through the {@link TaskStore}
 * (change events, SSE resync).
 *
 * Provided by the calendar view (`providers: [TaskOverlay, TaskStore]`).
 */
@Injectable()
export class TaskOverlay {
  private readonly tasks = inject(TaskStore);
  private readonly storage = inject(DOCUMENT).defaultView?.localStorage;

  private readonly enabledState = signal(this.read());
  private readonly range = signal<DayRange | undefined>(undefined);

  /** Whether the overlay is shown. */
  readonly enabled = this.enabledState.asReadonly();
  /** The error of the last task request, `null` once one succeeded. */
  readonly error = this.tasks.error;

  /** The overlay entries of the loaded tasks (empty while off), in the zone `timeZone`. */
  entries(timeZone: string): EventInput[] {
    if (!this.enabled()) return [];
    const range = this.range();
    if (!range) return [];
    return this.tasks.items().flatMap((task) => taskEvents(task, range, timeZone));
  }

  /** Turns the overlay on or off; on loads the tasks of the shown days. */
  setEnabled(enabled: boolean): void {
    this.enabledState.set(enabled);
    try {
      this.storage?.setItem(TASK_OVERLAY_KEY, String(enabled));
    } catch {
      // Storage full or blocked: the toggle still works for this page.
    }
    if (enabled) void this.fetch();
  }

  /** The view shows `range` now: loads its tasks (when the overlay is on). */
  show(range: DayRange): void {
    const current = this.range();
    if (current && current.from === range.from && current.to === range.to) return;
    this.range.set(range);
    if (this.enabled()) void this.fetch();
  }

  /** Loads the tasks of the shown days again. */
  refresh(): Promise<void> {
    return this.tasks.refresh();
  }

  private fetch(): Promise<void> {
    const range = this.range();
    if (!range) return Promise.resolve();
    // A failed request shows as `error`; the calendar keeps working without the overlay.
    return this.tasks.load({ from: range.from, to: range.to }).catch(() => undefined);
  }

  private read(): boolean {
    try {
      return this.storage?.getItem(TASK_OVERLAY_KEY) === 'true';
    } catch {
      return false;
    }
  }
}

/** The overlay entries of `task` within `range`: its due date and its tickler date, as they apply. */
export function taskEvents(task: TaskResponse, range: DayRange, timeZone: string): EventInput[] {
  if (task.completedAt) return [];
  const entries: EventInput[] = [];
  if (task.dueDate && inRange(task.dueDate, range)) {
    const timed = !!task.dueTime;
    const start = timed ? instantOf(task.dueDate, task.dueTime!, timeZone) : task.dueDate;
    entries.push({
      id: `task-due-${task.id}`,
      title: task.title,
      allDay: !timed,
      start,
      end: timed ? addHours(start, DUE_SLOT_HOURS) : undefined,
      className: 'app-task-mark app-task-due',
      editable: false,
      extendedProps: { task, kind: 'due' } satisfies TaskMark,
    });
  }
  if (task.ticklerDate && inRange(task.ticklerDate, range)) {
    entries.push({
      id: `task-tickler-${task.id}`,
      title: task.title,
      allDay: true,
      start: task.ticklerDate,
      className: 'app-task-mark app-task-tickler',
      editable: false,
      extendedProps: { task, kind: 'tickler' } satisfies TaskMark,
    });
  }
  return entries;
}

/** The overlay mark an entry's `extendedProps` carry, if it is one. */
export function taskMarkOf(props: Record<string, unknown>): TaskMark | undefined {
  const task = props['task'] as TaskResponse | undefined;
  const kind = props['kind'] as TaskMarkKind | undefined;
  return task && kind ? { task, kind } : undefined;
}

function inRange(date: string, range: DayRange): boolean {
  return date >= range.from && date <= range.to;
}
