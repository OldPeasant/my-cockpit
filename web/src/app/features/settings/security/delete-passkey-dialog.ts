import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';

/** Asks before deleting a passkey; closes with `true` when confirmed. */
@Component({
  selector: 'app-delete-passkey-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatDialogModule, MatButtonModule],
  template: `
    <h2 mat-dialog-title i18n="@@security.passkeys.delete.title">Delete {{ data.name }}?</h2>
    <mat-dialog-content>
      <p i18n="@@security.passkeys.delete.text">
        This passkey can no longer be used to sign in. Remove it from the authenticator or password
        manager too.
      </p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button
        mat-button
        [mat-dialog-close]="false"
        cdkFocusInitial
        i18n="@@security.passkeys.delete.cancel"
      >
        Cancel
      </button>
      <button
        mat-flat-button
        class="danger"
        [mat-dialog-close]="true"
        i18n="@@security.passkeys.delete.confirm"
      >
        Delete passkey
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
export class DeletePasskeyDialog {
  protected readonly data = inject<{ name: string }>(MAT_DIALOG_DATA);
}
