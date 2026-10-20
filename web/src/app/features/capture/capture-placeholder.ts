import { ChangeDetectionStrategy, Component, Injector } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';

/** Placeholder content until the quick capture task (web-capture) builds the real dialog. */
@Component({
  selector: 'app-capture-placeholder',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatDialogModule, MatButtonModule],
  template: `
    <h2 mat-dialog-title i18n="@@capture.title">Capture</h2>
    <mat-dialog-content>
      <p i18n="@@capture.comingSoon">Quick capture to the inbox is coming soon.</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close i18n="@@capture.close">Close</button>
    </mat-dialog-actions>
  `,
})
export class CapturePlaceholder {}

export function openCapturePlaceholder(injector: Injector): void {
  injector.get(MatDialog).open(CapturePlaceholder, { width: '480px', maxWidth: '95vw' });
}
