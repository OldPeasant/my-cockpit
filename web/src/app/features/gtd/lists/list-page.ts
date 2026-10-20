import { Directive, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { TaskResponse } from '../../../api/generated';
import { ContextStore, localDate, ProjectStore, TaskQuery, TaskStore } from '../data';

/**
 * What the GTD list views share: the task store of their list (each view provides its own
 * `TaskStore`), contexts and projects for the rows, opening and completing a task, and the
 * tasks whose action is running (their buttons are disabled meanwhile).
 */
@Directive()
export abstract class ListPage {
  protected readonly tasks = inject(TaskStore);
  protected readonly contexts = inject(ContextStore);
  protected readonly projects = inject(ProjectStore);
  private readonly router = inject(Router);

  protected readonly today = signal(localDate());
  /** Ids of the tasks whose action is running. */
  protected readonly busy = signal<ReadonlySet<string>>(new Set());

  constructor() {
    void this.tasks.load(this.query());
    void this.contexts.ensureLoaded();
    void this.projects.ensureLoaded();
  }

  /** The tasks this view lists. */
  protected abstract query(): TaskQuery;

  /** Fetches the list again after a failed load. */
  protected retry(): void {
    void this.tasks.refresh();
  }

  protected open(task: TaskResponse): void {
    void this.router.navigate(['/tasks', task.id]);
  }

  /** The row checkbox: completes an open task, reopens a done one. */
  protected toggleDone(task: TaskResponse): void {
    const change = task.completedAt ? this.tasks.uncomplete(task) : this.tasks.complete(task);
    change.catch(ignore);
  }

  /**
   * Runs `action` for `task` unless one is already running for it. Failures were rolled back by
   * the store and shown by the HTTP layer; resolves to whether the action succeeded.
   */
  protected async run(task: TaskResponse, action: () => Promise<unknown>): Promise<boolean> {
    if (this.busy().has(task.id)) return false;
    this.busy.update((ids) => new Set(ids).add(task.id));
    try {
      await action();
      return true;
    } catch {
      return false;
    } finally {
      this.busy.update((ids) => {
        const rest = new Set(ids);
        rest.delete(task.id);
        return rest;
      });
    }
  }
}

function ignore(): void {
  // The store rolled the change back; the HTTP layer shows the error.
}
