import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';

/** What {@link DeleteProjectDialog} shows: the project's title and its number of open tasks. */
export interface DeleteProjectData {
  readonly title: string;
  readonly openTasks: number;
}

/**
 * Asks before moving a project to the Trash; closes with `true` when confirmed. Says what happens
 * to its open tasks by the server's rule: they stay open and keep the project.
 */
@Component({
  selector: 'app-delete-project-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatDialogModule, MatButtonModule],
  template: `
    <h2 mat-dialog-title i18n="@@gtd.project.delete.title">Delete “{{ data.title }}”?</h2>
    <mat-dialog-content>
      <p i18n="@@gtd.project.delete.text">
        The project moves to the Trash, where you can restore it.
      </p>
      @if (tasksText) {
        <p class="tasks">{{ tasksText }}</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button
        mat-button
        [mat-dialog-close]="false"
        cdkFocusInitial
        i18n="@@gtd.project.form.cancel"
      >
        Cancel
      </button>
      <button
        mat-flat-button
        class="danger"
        [mat-dialog-close]="true"
        i18n="@@gtd.project.delete.confirm"
      >
        Delete project
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    p {
      margin: 0;
      max-width: 44ch;
    }
    .tasks {
      margin-top: var(--app-space-2);
    }
    .danger {
      --mat-button-filled-container-color: var(--app-danger);
    }
  `,
})
export class DeleteProjectDialog {
  protected readonly data = inject<DeleteProjectData>(MAT_DIALOG_DATA);

  /** What happens to the open tasks, if there are any. */
  protected readonly tasksText = tasksText(this.data.openTasks);
}

function tasksText(count: number): string | undefined {
  if (count <= 0) return undefined;
  if (count === 1) {
    return $localize`:@@gtd.project.delete.oneTask:Its open task stays open and keeps the project.`;
  }
  return $localize`:@@gtd.project.delete.tasks:Its ${count}:count: open tasks stay open and keep the project.`;
}
