import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule } from '@angular/material/dialog';
import { SHELL_SHORTCUTS, ShortcutInfo } from './hotkeys';
import { PageShortcuts } from './page-shortcuts';

/** The `?` overview of keyboard shortcuts: the shell's, then those of the shown page. */
@Component({
  selector: 'app-shortcuts-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatDialogModule, MatButtonModule, NgTemplateOutlet],
  template: `
    <h2 mat-dialog-title i18n="@@shortcuts.title">Keyboard shortcuts</h2>
    <mat-dialog-content>
      <ng-template #list let-shortcuts>
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
      </ng-template>
      <ng-container *ngTemplateOutlet="list; context: { $implicit: shortcuts }" />
      @for (section of sections(); track section.title) {
        <h3 class="section">{{ section.title }}</h3>
        <ng-container *ngTemplateOutlet="list; context: { $implicit: section.shortcuts }" />
      }
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
    .section {
      margin: var(--app-space-4) 0 var(--app-space-2);
      font: var(--mat-sys-title-small);
    }
    .or {
      color: var(--app-fg-muted);
    }
  `,
})
export class ShortcutsDialog {
  protected readonly shortcuts: readonly ShortcutInfo[] = SHELL_SHORTCUTS;
  protected readonly sections = inject(PageShortcuts).sections;
}
