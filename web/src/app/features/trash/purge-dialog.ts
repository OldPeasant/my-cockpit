import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';

/** Asks before deleting an item permanently; closes with `true` when confirmed. */
@Component({
  selector: 'app-purge-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatDialogModule, MatButtonModule],
  template: `
    <h2 mat-dialog-title i18n="@@trash.purge.title">Delete “{{ data.title }}” permanently?</h2>
    <mat-dialog-content>
      <p i18n="@@trash.purge.text">
        It is gone for good, together with its links. This cannot be undone.
      </p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button [mat-dialog-close]="false" cdkFocusInitial i18n="@@trash.purge.cancel">
        Cancel
      </button>
      <button mat-flat-button class="danger" [mat-dialog-close]="true" i18n="@@trash.purge.confirm">
        Delete permanently
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    p {
      margin: 0;
      max-width: 44ch;
    }
    .danger {
      --mat-button-filled-container-color: var(--app-danger);
    }
  `,
})
export class PurgeDialog {
  protected readonly data = inject<{ title: string }>(MAT_DIALOG_DATA);
}
