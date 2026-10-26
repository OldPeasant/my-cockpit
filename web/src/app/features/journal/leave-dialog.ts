import { ChangeDetectionStrategy, Component, inject, Injectable } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';

/** What to do with edits in conflict when leaving the day: save them, drop them or stay. */
export type LeaveChoice = 'keep-mine' | 'discard' | 'stay';

/** Asks before edits in conflict (the entry changed elsewhere) are dropped by leaving the day. */
@Component({
  selector: 'app-journal-leave-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDialogModule],
  template: `
    <h2 mat-dialog-title i18n="@@journal.leave.title">Leave without saving?</h2>
    <mat-dialog-content>
      <p i18n="@@journal.leave.question">
        This entry was changed somewhere else since you opened it, and your edits are not saved yet.
      </p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button
        mat-button
        type="button"
        class="stay"
        [mat-dialog-close]="'stay'"
        i18n="@@journal.leave.stay"
      >
        Stay
      </button>
      <button
        mat-button
        type="button"
        class="discard"
        [mat-dialog-close]="'discard'"
        i18n="@@journal.leave.discard"
      >
        Discard my edits
      </button>
      <button
        mat-flat-button
        type="button"
        class="keep-mine"
        [mat-dialog-close]="'keep-mine'"
        i18n="@@journal.leave.keepMine"
      >
        Save my version
      </button>
    </mat-dialog-actions>
  `,
})
export class LeaveDialog {}

/** Opens the {@link LeaveDialog}. */
@Injectable({ providedIn: 'root' })
export class LeavePrompt {
  private readonly dialog = inject(MatDialog);

  /** The choice; closing the dialog otherwise (Escape, backdrop) stays. */
  async ask(): Promise<LeaveChoice> {
    const ref = this.dialog.open<LeaveDialog, void, LeaveChoice>(LeaveDialog, {
      autoFocus: 'first-tabbable',
    });
    return (await firstValueFrom(ref.afterClosed())) ?? 'stay';
  }
}
