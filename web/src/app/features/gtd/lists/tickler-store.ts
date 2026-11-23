import { Injectable } from '@angular/core';
import { TaskResponse } from '../../../api/generated';
import { addDays, localDate, TaskQuery, TaskStore } from '../data';

/** How many years ahead the Tickler view looks for tickler dates (one task list request per year). */
export const TICKLER_HORIZON_YEARS = 10;

/**
 * The open tasks still in a tickler (`ticklerDate` after today), soonest first, then in list order.
 * The task list's `from`/`to` filter also returns tasks in a tickler but spans at most a year, so
 * the next {@link TICKLER_HORIZON_YEARS} years are read a year at a time; tasks found only by their
 * due date (no pending tickler) are dropped. Provided as the view's `TaskStore`.
 */
@Injectable()
export class TicklerStore extends TaskStore {
  protected override async fetch(query: TaskQuery): Promise<TaskResponse[]> {
    const today = localDate();
    const found = new Map<string, TaskResponse>();
    for (const [from, to] of ticklerWindows(today)) {
      for (const task of await super.fetch({ ...query, from, to })) found.set(task.id, task);
    }
    return [...found.values()].filter((task) => isInTickler(task, today));
  }

  protected override matches(task: TaskResponse): boolean {
    return isInTickler(task, localDate());
  }

  protected override compare(a: TaskResponse, b: TaskResponse): number {
    return (a.ticklerDate ?? '').localeCompare(b.ticklerDate ?? '') || a.position - b.position;
  }
}

/** Whether `task` is open and its tickler date is after `today`. */
export function isInTickler(task: TaskResponse, today: string): boolean {
  return !task.completedAt && !!task.ticklerDate && task.ticklerDate > today;
}

/**
 * The inclusive `from`/`to` ranges the Tickler view requests: a year each, starting tomorrow,
 * {@link TICKLER_HORIZON_YEARS} of them.
 */
export function ticklerWindows(today: string): [string, string][] {
  const windows: [string, string][] = [];
  let start = addDays(today, 1);
  for (let i = 0; i < TICKLER_HORIZON_YEARS; i++) {
    const next = addYear(start);
    windows.push([start, addDays(next, -1)]);
    start = next;
  }
  return windows;
}

/** The ISO date a year after `date` (29 Feb becomes 1 Mar). */
function addYear(date: string): string {
  const [year, month, day] = date.split('-').map(Number);
  return localDate(new Date(year + 1, month - 1, day));
}
