import { CreateTaskRequest, TaskResponse } from '../../../api/generated';
import { addDays, localDate } from '../data';

/** Tasks that share a date: a scheduled date, or the day they were completed. */
export interface DateGroup {
  /** ISO date (`yyyy-MM-dd`). */
  readonly date: string;
  readonly tasks: readonly TaskResponse[];
}

/** Waiting longer than this many days is shown as overdue for a follow-up. */
export const LONG_WAIT_DAYS = 7;

/**
 * Waiting For order (SPEC.md §5.3): longest waiting first, by `waitingSince`; tasks without a date
 * last, ties in list order.
 */
export function byWaitingSince(tasks: readonly TaskResponse[]): TaskResponse[] {
  return [...tasks].sort((a, b) => {
    const since = compareOptional(a.waitingSince, b.waitingSince);
    return since !== 0 ? since : a.position - b.position;
  });
}

/** Whole days between the ISO dates `since` and `today` (never negative). */
export function daysBetween(since: string, today: string): number {
  const [y1, m1, d1] = since.split('-').map(Number);
  const [y2, m2, d2] = today.split('-').map(Number);
  const days = Math.round((Date.UTC(y2, m2 - 1, d2) - Date.UTC(y1, m1 - 1, d1)) / 86_400_000);
  return Math.max(0, days);
}

/**
 * Scheduled tasks grouped by `scheduledDate`, earliest date first; within a date in list order.
 * Tasks without a date (not expected in this list) form a last group with date `''`.
 */
export function groupByScheduledDate(tasks: readonly TaskResponse[]): DateGroup[] {
  const sorted = [...tasks].sort((a, b) => {
    const date = compareOptional(a.scheduledDate, b.scheduledDate);
    return date !== 0 ? date : a.position - b.position;
  });
  return group(sorted, (task) => task.scheduledDate ?? '');
}

/**
 * Done tasks grouped by the local day of `completedAt`, newest day first and newest task first
 * within a day.
 */
export function groupByCompletedDay(tasks: readonly TaskResponse[]): DateGroup[] {
  const done = tasks.filter((task) => task.completedAt);
  const sorted = done.sort((a, b) => b.completedAt!.localeCompare(a.completedAt!));
  return group(sorted, (task) => localDate(new Date(task.completedAt!)));
}

/**
 * The task that "follow up" creates for a waiting item: a next action in the same project with
 * the person waited for in its title.
 */
export function followUpTask(task: TaskResponse): CreateTaskRequest {
  const who = task.waitingForText?.trim();
  const title = who
    ? $localize`:@@gtd.waiting.followUpTitleWho:Follow up with ${who}:who:: ${task.title}:title:`
    : $localize`:@@gtd.waiting.followUpTitle:Follow up: ${task.title}:title:`;
  return { title, list: 'NEXT', projectId: task.projectId };
}

/** How the ISO date `date` relates to `today`, for date headings; `undefined` for other days. */
export function relativeDay(
  date: string,
  today: string,
): 'yesterday' | 'today' | 'tomorrow' | undefined {
  if (date === today) return 'today';
  if (date === addDays(today, 1)) return 'tomorrow';
  if (date === addDays(today, -1)) return 'yesterday';
  return undefined;
}

/** Ascending order of optional ISO strings, missing values last. */
function compareOptional(a: string | undefined, b: string | undefined): number {
  if (a === b) return 0;
  if (a === undefined) return 1;
  if (b === undefined) return -1;
  return a.localeCompare(b);
}

/** Splits sorted `tasks` into runs with the same key. */
function group(tasks: readonly TaskResponse[], key: (task: TaskResponse) => string): DateGroup[] {
  const groups: { date: string; tasks: TaskResponse[] }[] = [];
  for (const task of tasks) {
    const date = key(task);
    const last = groups.at(-1);
    if (last?.date === date) last.tasks.push(task);
    else groups.push({ date, tasks: [task] });
  }
  return groups;
}
