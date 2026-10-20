import { Injectable } from '@angular/core';
import {
  completeTask,
  CompletedTaskResponse,
  createTask,
  CreateTaskRequest,
  deleteTask,
  listTasks,
  ListTasks$Params,
  moveTask,
  MoveTaskRequest,
  TaskPatchRequest,
  TaskResponse,
  uncompleteTask,
  updateTask,
} from '../../../api/generated';
import { addDays, localDate } from './dates';
import { ListStore, patched } from './list-store';

/** The filters of a task list (`GET /api/v1/tasks` without paging). */
export type TaskQuery = Omit<ListTasks$Params, 'cursor' | 'limit'>;

/** Page size of list requests: the largest the server allows. */
export const TASK_PAGE_SIZE = 200;

/**
 * The tasks of one list (SPEC.md §5.3), e.g. `load({ list: 'NEXT', context: id })`. Each view
 * provides its own instance (`providers: [TaskStore]`). Changes are optimistic (see
 * {@link ListStore}): a task that no longer matches the query (moved, completed, deleted) leaves
 * the list at once and comes back if the server refuses.
 *
 * Open tasks are ordered by position, done ones (`completed: true`, the Logbook) newest first,
 * as the server returns them.
 */
@Injectable()
export class TaskStore extends ListStore<TaskResponse, TaskQuery> {
  constructor() {
    super('TASK');
  }

  /** Creates a task; it shows once the server answered (and if it matches the query). */
  async create(body: CreateTaskRequest): Promise<TaskResponse> {
    const task = await this.api.invoke(createTask, { body });
    this.accept(task);
    return task;
  }

  /** Changes fields of `task` (`null` clears a field). */
  update(task: TaskResponse, patch: TaskPatchRequest): Promise<TaskResponse> {
    return this.mutate(
      task.id,
      (current) => patched(current ?? task, patch),
      () =>
        this.api.invoke(updateTask, {
          id: task.id,
          'If-Match': this.ifMatch(task),
          body: patch,
        }),
    );
  }

  /** Moves `task` to another list (and optionally project), see `MoveTaskRequest`. */
  move(task: TaskResponse, body: MoveTaskRequest): Promise<TaskResponse> {
    return this.mutate(
      task.id,
      (current) => {
        const base = current ?? task;
        return {
          ...base,
          list: body.list,
          projectId: body.projectId ?? base.projectId,
          scheduledDate: body.list === 'SCHEDULED' ? body.scheduledDate : undefined,
          ticklerDate: body.ticklerDate,
          waitingForText: body.list === 'WAITING' ? body.waitingForText : undefined,
          waitingForContactId: body.list === 'WAITING' ? body.waitingForContactId : undefined,
        };
      },
      () =>
        this.api.invoke(moveTask, { id: task.id, 'If-Match': this.ifMatch(task), body }),
    );
  }

  /**
   * Marks `task` done. The answer tells whether its project now needs a new next step
   * (`needsNextStep`).
   */
  async complete(task: TaskResponse): Promise<CompletedTaskResponse> {
    let completed: CompletedTaskResponse | undefined;
    await this.mutate(
      task.id,
      (current) => ({ ...(current ?? task), completedAt: new Date().toISOString() }),
      async () => {
        completed = await this.api.invoke(completeTask, {
          id: task.id,
          'If-Match': this.ifMatch(task),
        });
        const done: Partial<CompletedTaskResponse> = { ...completed };
        delete done.needsNextStep;
        return done as TaskResponse;
      },
    );
    return completed!;
  }

  /** Reopens a done `task`. */
  uncomplete(task: TaskResponse): Promise<TaskResponse> {
    return this.mutate(
      task.id,
      (current) => ({ ...(current ?? task), completedAt: undefined }),
      () =>
        this.api.invoke(uncompleteTask, { id: task.id, 'If-Match': this.ifMatch(task) }),
    );
  }

  /** Moves `task` to the Trash. */
  async remove(task: TaskResponse): Promise<void> {
    await this.mutate(
      task.id,
      () => undefined,
      () =>
        this.api.invoke(deleteTask, { id: task.id, 'If-Match': this.ifMatch(task) }),
    );
  }

  protected async fetch(query: TaskQuery): Promise<TaskResponse[]> {
    const items: TaskResponse[] = [];
    let cursor: string | undefined;
    do {
      const page = await this.api.invoke(listTasks, { ...query, cursor, limit: TASK_PAGE_SIZE });
      items.push(...page.items);
      cursor = page.nextCursor ?? undefined;
    } while (cursor);
    return items;
  }

  protected matches(task: TaskResponse, query: TaskQuery): boolean {
    return matchesTask(task, query);
  }

  protected compare(a: TaskResponse, b: TaskResponse): number {
    if (a.completedAt && b.completedAt) return b.completedAt.localeCompare(a.completedAt);
    return a.position - b.position;
  }
}

/**
 * Whether `task` belongs to the list of `query`, by the server's rules (`TaskService.list`).
 * `due` windows use the browser's date, which may differ from the user's time zone near midnight.
 */
export function matchesTask(task: TaskResponse, query: TaskQuery, today = localDate()): boolean {
  if (Boolean(query.completed) !== Boolean(task.completedAt)) return false;
  if (query.list && task.list !== query.list) return false;
  if (query.projectId && task.projectId !== query.projectId) return false;
  if (query.context && !task.contextIds.includes(query.context)) return false;
  if (query.energy && task.energy !== query.energy) return false;
  if (query.creativity && task.creativity !== query.creativity) return false;
  if (query.maxMinutes !== undefined) {
    if (task.timeEstimateMin == null || task.timeEstimateMin > query.maxMinutes) return false;
  }
  if (query.due) {
    const due = task.dueDate;
    if (!due) return false;
    const window = query.due.toLowerCase();
    if (window === 'overdue' && !(due < today)) return false;
    if (window === 'today' && due !== today) return false;
    if (window === 'week' && !(due >= today && due <= addDays(today, 6))) return false;
  }
  return true;
}
