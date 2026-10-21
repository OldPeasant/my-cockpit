import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { folderLabel } from './mail-folder-tree';
import { FolderNode } from './mail-folders-store';

export interface MailMoveDialogData {
  /** The folders the messages can go to (same account, without the one they are in). */
  readonly folders: FolderNode[];
}

/**
 * Picks the folder to move messages to: a filter field and the folders as buttons. `Enter` in the
 * field picks the first match. Closes with the folder id, without a result on cancel.
 */
@Component({
  selector: 'app-mail-move-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDialogModule],
  template: `
    <h2 mat-dialog-title i18n="@@mail.move.title">Move to folder</h2>
    <mat-dialog-content>
      <input
        class="filter"
        type="search"
        [value]="query()"
        (input)="query.set($any($event.target).value)"
        (keydown.enter)="pickFirst($event)"
        i18n-aria-label="@@mail.move.filter"
        aria-label="Filter folders"
        i18n-placeholder="@@mail.move.filterPlaceholder"
        placeholder="Folder name"
      />
      @if (matches().length) {
        <ul class="folders">
          @for (node of matches(); track node.folder.id) {
            <li [style.--depth]="query() ? 0 : node.depth">
              <button type="button" class="folder" (click)="pick(node.folder.id)">
                {{ label(node) }}
              </button>
            </li>
          }
        </ul>
      } @else {
        <p class="note" i18n="@@mail.move.none">No matching folder.</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" [mat-dialog-close]="undefined" i18n="@@mail.move.cancel">
        Cancel
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .filter {
      box-sizing: border-box;
      width: 100%;
      margin-bottom: var(--app-space-2);
      padding: var(--app-space-2);
      border: 1px solid var(--app-border);
      border-radius: var(--app-radius);
      background: transparent;
      color: inherit;
      font: var(--mat-sys-body-large);
    }
    .folders {
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .folder {
      width: 100%;
      padding: var(--app-space-2);
      padding-inline-start: calc(var(--app-space-2) + var(--depth, 0) * 14px);
      border: 0;
      border-radius: var(--app-radius);
      background: none;
      color: inherit;
      font: var(--mat-sys-body-large);
      text-align: start;
      cursor: pointer;
    }
    .folder:hover,
    .folder:focus-visible {
      background: var(--mat-sys-secondary-container);
      color: var(--mat-sys-on-secondary-container);
      outline: none;
    }
    .note {
      color: var(--app-fg-muted);
    }
  `,
})
export class MailMoveDialog {
  protected readonly data = inject<MailMoveDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<MailMoveDialog, string>>(MatDialogRef);

  protected readonly query = signal('');
  protected readonly matches = computed(() => {
    const query = this.query().trim().toLowerCase();
    if (!query) return this.data.folders;
    return this.data.folders.filter((node) =>
      [folderLabel(node.folder), node.folder.path].some((text) =>
        text.toLowerCase().includes(query),
      ),
    );
  });

  protected label(node: FolderNode): string {
    return this.query() ? node.folder.path : folderLabel(node.folder);
  }

  protected pick(folderId: string): void {
    this.ref.close(folderId);
  }

  protected pickFirst(event: Event): void {
    event.preventDefault();
    const first = this.matches()[0];
    if (first) this.pick(first.folder.id);
  }
}
