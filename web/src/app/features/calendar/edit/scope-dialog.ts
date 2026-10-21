import { ChangeDetectionStrategy, Component, inject, Injectable } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';
import { EditScope } from './event-edit.service';

export interface ScopeDialogData {
  /** `change` for an edit or move, `delete` for a delete. */
  readonly action: 'change' | 'delete';
  /** The scopes to offer (e.g. no THIS when the repeat rule changes). */
  readonly scopes: readonly EditScope[];
}

/** Asks which occurrences of a recurring event a change or delete applies to (SPEC.md §6.2). */
@Component({
  selector: 'app-calendar-scope-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDialogModule],
  template: `
    <h2 mat-dialog-title>
      @if (data.action === 'delete') {
        <ng-container i18n="@@calendar.scope.deleteTitle">Delete a repeating event</ng-container>
      } @else {
        <ng-container i18n="@@calendar.scope.changeTitle">Change a repeating event</ng-container>
      }
    </h2>
    <mat-dialog-content>
      <p id="calendar-scope-question" class="question" i18n="@@calendar.scope.question">
        Which events of the series should this apply to?
      </p>
      <div class="options" role="group" aria-labelledby="calendar-scope-question">
        @for (scope of data.scopes; track scope) {
          <button
            mat-stroked-button
            type="button"
            class="option"
            [attr.data-scope]="scope"
            [mat-dialog-close]="scope"
          >
            @switch (scope) {
              @case ('THIS') {
                <ng-container i18n="@@calendar.scope.this">Only this event</ng-container>
              }
              @case ('FOLLOWING') {
                <ng-container i18n="@@calendar.scope.following"
                  >This and following events</ng-container
                >
              }
              @case ('ALL') {
                <ng-container i18n="@@calendar.scope.all">All events in the series</ng-container>
              }
            }
          </button>
        }
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" [mat-dialog-close]="undefined" i18n="@@calendar.cancel">
        Cancel
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .question {
      margin: 0 0 var(--app-space-3);
    }
    .options {
      display: grid;
      gap: var(--app-space-2);
      min-width: min(20rem, 100%);
    }
    .option {
      justify-content: flex-start;
    }
  `,
})
export class ScopeDialog {
  protected readonly data = inject<ScopeDialogData>(MAT_DIALOG_DATA);
}

/** Opens the {@link ScopeDialog}. */
@Injectable({ providedIn: 'root' })
export class ScopePrompt {
  private readonly dialog = inject(MatDialog);

  /** The chosen scope, `undefined` when the user cancels. */
  ask(
    action: ScopeDialogData['action'],
    scopes: readonly EditScope[] = ['THIS', 'FOLLOWING', 'ALL'],
  ): Promise<EditScope | undefined> {
    const ref = this.dialog.open<ScopeDialog, ScopeDialogData, EditScope>(ScopeDialog, {
      data: { action, scopes },
      autoFocus: 'first-tabbable',
    });
    return firstValueFrom(ref.afterClosed());
  }
}
