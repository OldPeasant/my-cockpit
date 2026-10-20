import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';

/** Asks before revoking a device; closes with `true` when confirmed. */
@Component({
  selector: 'app-revoke-device-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatDialogModule, MatButtonModule],
  template: `
    <h2 mat-dialog-title i18n="@@security.revoke.title">Revoke {{ data.name }}?</h2>
    <mat-dialog-content>
      <p i18n="@@security.revoke.text">
        The app on this device is signed out at once. To use it again, sign in there with your
        password and a code from your authenticator app.
      </p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button [mat-dialog-close]="false" cdkFocusInitial i18n="@@security.revoke.cancel">
        Cancel
      </button>
      <button
        mat-flat-button
        class="danger"
        [mat-dialog-close]="true"
        i18n="@@security.revoke.confirm"
      >
        Revoke device
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
export class RevokeDeviceDialog {
  protected readonly data = inject<{ name: string }>(MAT_DIALOG_DATA);
}
