import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule } from '@angular/material/dialog';
import { SHELL_SHORTCUTS } from './hotkeys';

/** The `?` overview of keyboard shortcuts. */
@Component({
  selector: 'app-shortcuts-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatDialogModule, MatButtonModule],
  template: `
    <h2 mat-dialog-title i18n="@@shortcuts.title">Keyboard shortcuts</h2>
    <mat-dialog-content>
      <dl class="shortcuts">
        @for (shortcut of shortcuts; track shortcut.description) {
          <dt>
            @for (key of shortcut.keys; track key; let last = $last) {
              <kbd>{{ key }}</kbd>
              @if (!last) {
                <span class="or" i18n="@@shortcuts.or">or</span>
              }
            }
          </dt>
          <dd>{{ shortcut.description }}</dd>
        }
      </dl>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close i18n="@@shortcuts.close">Close</button>
    </mat-dialog-actions>
  `,
  styles: `
    .shortcuts {
      display: grid;
      grid-template-columns: max-content 1fr;
      gap: var(--app-space-2) var(--app-space-4);
      margin: 0;
    }
    dt {
      display: flex;
      align-items: center;
      gap: var(--app-space-1);
    }
    dd {
      margin: 0;
      align-self: center;
    }
    .or {
      color: var(--app-fg-muted);
    }
  `,
})
export class ShortcutsDialog {
  protected readonly shortcuts = SHELL_SHORTCUTS;
}
