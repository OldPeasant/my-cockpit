import { inject, Injectable } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Api, restoreTrashItem, TaskResponse } from '../../../api/generated';

/** How long the undo snackbar stays (ms). */
export const UNDO_DURATION_MS = 6000;

/**
 * The "Undo" snackbar after completing or deleting a task, in task detail and in the lists:
 * `undo.completed(task, () => store.uncomplete(done))`, `undo.deleted(task)`. Undoing a deletion
 * restores the task from the Trash. A failed undo is shown by the HTTP layer.
 */
@Injectable({ providedIn: 'root' })
export class TaskUndo {
  private readonly snackBar = inject(MatSnackBar);
  private readonly api = inject(Api);

  /** Offers to reopen `task`, which was just completed; `reopen` does it. */
  completed(task: Pick<TaskResponse, 'title'>, reopen: () => Promise<unknown>): Promise<boolean> {
    return this.offer($localize`:@@gtd.undo.completed:Completed “${task.title}:title:”`, reopen);
  }

  /** Offers to restore `task`, which was just moved to the Trash. */
  deleted(task: Pick<TaskResponse, 'id' | 'title'>): Promise<boolean> {
    return this.offer(
      $localize`:@@gtd.undo.deleted:Moved “${task.title}:title:” to the Trash`,
      () => this.api.invoke(restoreTrashItem, { type: 'TASK', id: task.id }),
    );
  }

  /** Shows `message` with "Undo"; resolves to whether the user undid and `undo` succeeded. */
  private async offer(message: string, undo: () => Promise<unknown>): Promise<boolean> {
    const ref = this.snackBar.open(message, $localize`:@@gtd.undo.action:Undo`, {
      duration: UNDO_DURATION_MS,
    });
    const undone = await new Promise<boolean>((resolve) => {
      const action = ref.onAction().subscribe(() => resolve(true));
      ref.afterDismissed().subscribe(() => {
        action.unsubscribe();
        resolve(false);
      });
    });
    if (!undone) return false;
    try {
      await undo();
      return true;
    } catch {
      // The store rolled its change back; the HTTP layer shows the error.
      return false;
    }
  }
}
