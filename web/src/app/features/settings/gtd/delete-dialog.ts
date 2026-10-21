import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatRadioModule } from '@angular/material/radio';

/** What is deleted, how many items use it and where else they can go. */
export interface DeleteDialogData {
  readonly kind: 'context' | 'area';
  readonly name: string;
  /** Tasks (context) or projects (area) assigned to it. */
  readonly count: number;
  /** The other contexts or areas the assigned items can move to. */
  readonly others: readonly { readonly id: string; readonly name: string }[];
}

/** The answer: where the assigned items go (`null`: they lose the context or area). */
export interface DeleteDialogResult {
  readonly moveTo: string | null;
}

/**
 * Asks before deleting a context or area and, when tasks or projects use it, what happens to them:
 * they move to another context or area, or simply lose it. Closes without a result on cancel.
 */
@Component({
  selector: 'app-gtd-delete-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MatButtonModule, MatDialogModule, MatRadioModule],
  template: `
    <h2 mat-dialog-title i18n="@@gtdSettings.delete.title">Delete {{ data.name }}?</h2>
    <mat-dialog-content>
      @if (data.count === 0) {
        @if (data.kind === 'context') {
          <p i18n="@@gtdSettings.delete.contextUnused">No task uses this context.</p>
        } @else {
          <p i18n="@@gtdSettings.delete.areaUnused">No project is in this area.</p>
        }
      } @else {
        <p id="delete-question">
          @if (data.kind === 'context') {
            <ng-container i18n="@@gtdSettings.delete.contextUsed"
              >{data.count, plural, =1 {1 task uses} other {{{ data.count }} tasks use}} this
              context. What should happen to them?</ng-container
            >
          } @else {
            <ng-container i18n="@@gtdSettings.delete.areaUsed"
              >{data.count, plural, =1 {1 project is} other {{{ data.count }} projects are}} in this
              area. What should happen to them?</ng-container
            >
          }
        </p>
        <mat-radio-group
          class="choices"
          aria-labelledby="delete-question"
          [ngModel]="mode()"
          (ngModelChange)="mode.set($event)"
        >
          <mat-radio-button value="clear">
            @if (data.kind === 'context') {
              <ng-container i18n="@@gtdSettings.delete.contextClear"
                >Remove {{ data.name }} from them</ng-container
              >
            } @else {
              <ng-container i18n="@@gtdSettings.delete.areaClear"
                >Leave them without an area</ng-container
              >
            }
          </mat-radio-button>
          @if (data.others.length) {
            <mat-radio-button value="move">
              @if (data.kind === 'context') {
                <ng-container i18n="@@gtdSettings.delete.contextMove"
                  >Give them another context instead</ng-container
                >
              } @else {
                <ng-container i18n="@@gtdSettings.delete.areaMove"
                  >Move them to another area</ng-container
                >
              }
            </mat-radio-button>
          }
        </mat-radio-group>
        @if (mode() === 'move') {
          <select
            class="target"
            i18n-aria-label="@@gtdSettings.delete.target"
            aria-label="Move to"
            [ngModel]="target()"
            (ngModelChange)="target.set($event)"
          >
            @for (other of data.others; track other.id) {
              <option [value]="other.id">{{ other.name }}</option>
            }
          </select>
        }
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close cdkFocusInitial i18n="@@gtdSettings.cancel">
        Cancel
      </button>
      <button
        mat-flat-button
        type="button"
        class="danger"
        (click)="confirm()"
        i18n="@@gtdSettings.delete.confirm"
      >
        Delete
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    p {
      margin: 0;
      max-width: 44ch;
    }
    .choices {
      display: flex;
      flex-direction: column;
      margin-top: var(--app-space-2);
    }
    .target {
      display: block;
      margin: var(--app-space-1) 0 0 var(--app-space-5);
      min-width: 12rem;
      padding: var(--app-space-1) var(--app-space-2);
      border: 1px solid var(--app-border);
      border-radius: var(--app-radius);
      background: var(--app-surface-raised);
      color: var(--app-fg);
      font: var(--mat-sys-body-large);
    }
    .target:focus-visible {
      outline: 2px solid var(--app-accent);
      outline-offset: 1px;
    }
    .danger {
      --mat-button-filled-container-color: var(--app-danger);
    }
  `,
})
export class DeleteDialog {
  protected readonly data = inject<DeleteDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<DeleteDialog, DeleteDialogResult>>(MatDialogRef);

  protected readonly mode = signal<'clear' | 'move'>('clear');
  protected readonly target = signal(this.data.others[0]?.id ?? '');

  protected confirm(): void {
    const move = this.data.count > 0 && this.mode() === 'move' && this.target();
    this.ref.close({ moveTo: move ? this.target() : null });
  }
}
