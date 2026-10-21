import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { FolderSummary } from '../../../api/generated';
import { ApiError } from '../../../core/http';
import { ItemTypeIcon } from '../../../shared/links';
import { Crumb, DriveEntry, DriveService } from './drive.service';

export interface MoveDialogData {
  readonly entry: DriveEntry;
  /** The folder the entry is in now (`null`: the drive root); the picker starts there. */
  readonly from: string | null;
}

/**
 * Picks the folder to move an entry to and moves it. The picker browses the folder tree; a moved
 * folder cannot be opened, so it cannot land inside itself. Closes with the target folder id
 * (`null`: the drive root) once moved, without a result on cancel.
 */
@Component({
  selector: 'app-files-move-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ItemTypeIcon, MatButtonModule, MatDialogModule],
  template: `
    <h2 mat-dialog-title i18n="@@files.move.title">Move “{{ data.entry.name }}”</h2>
    <mat-dialog-content>
      <nav class="crumbs" i18n-aria-label="@@files.move.path" aria-label="Folder to move to">
        @for (crumb of crumbs(); track crumb.id; let last = $last) {
          @if (last) {
            <span class="current" aria-current="location">{{ crumb.name }}</span>
          } @else {
            <button type="button" class="crumb" (click)="open(crumb.id)">{{ crumb.name }}</button>
            <span class="sep" aria-hidden="true">/</span>
          }
        }
      </nav>
      @if (loadError(); as message) {
        <p class="error" role="alert">{{ message }}</p>
      } @else if (folders(); as list) {
        @if (list.length) {
          <ul class="folders">
            @for (folder of list; track folder.id) {
              <li>
                <button
                  type="button"
                  class="folder"
                  [disabled]="folder.id === data.entry.id"
                  (click)="open(folder.id)"
                >
                  <app-item-type-icon type="FOLDER" />
                  <span>{{ folder.name }}</span>
                </button>
              </li>
            }
          </ul>
        } @else {
          <p class="note" i18n="@@files.move.noSubfolders">No folders in here.</p>
        }
      } @else {
        <p class="note" role="status" i18n="@@files.move.loading">Loading folders…</p>
      }
      @if (moveError(); as message) {
        <p class="error" role="alert">{{ message }}</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close i18n="@@files.cancel">Cancel</button>
      <button
        mat-flat-button
        type="button"
        [disabled]="moving() || here() === data.from || crumbs().length === 0"
        (click)="move()"
        i18n="@@files.move.confirm"
      >
        Move here
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .crumbs {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--app-space-1);
      margin-bottom: var(--app-space-2);
      font: var(--mat-sys-body-medium);
    }
    .crumb {
      padding: 0;
      border: 0;
      background: none;
      color: var(--app-accent);
      font: inherit;
      cursor: pointer;
      text-decoration: underline;
    }
    .current {
      font-weight: 600;
    }
    .sep,
    .note {
      color: var(--app-fg-muted);
    }
    .folders {
      min-width: min(24rem, 70vw);
      max-height: 18rem;
      margin: 0;
      padding: 0;
      overflow: auto;
      list-style: none;
      border-top: 1px solid var(--app-border);
    }
    .folder {
      display: flex;
      align-items: center;
      gap: var(--app-space-2);
      width: 100%;
      padding: var(--app-space-2) 0;
      border: 0;
      border-bottom: 1px solid var(--app-border);
      background: none;
      color: inherit;
      font: var(--mat-sys-body-large);
      text-align: start;
      cursor: pointer;

      &:disabled {
        opacity: 0.5;
        cursor: default;
      }
    }
    .note,
    .error {
      margin: var(--app-space-2) 0 0;
    }
    .error {
      color: var(--app-danger);
    }
  `,
})
export class MoveDialog implements OnInit {
  protected readonly data = inject<MoveDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<MoveDialog, string | null>>(MatDialogRef);
  private readonly drive = inject(DriveService);

  protected readonly here = signal<string | null>(null);
  protected readonly crumbs = signal<Crumb[]>([]);
  /** Subfolders of the shown folder; null while they load. */
  protected readonly folders = signal<FolderSummary[] | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly moving = signal(false);
  protected readonly moveError = signal<string | null>(null);

  private seq = 0;

  ngOnInit(): void {
    void this.open(this.data.from);
  }

  protected async open(id: string | null): Promise<void> {
    const seq = ++this.seq;
    this.here.set(id);
    this.folders.set(null);
    this.loadError.set(null);
    this.moveError.set(null);
    try {
      const [crumbs, folders] = await Promise.all([
        this.drive.crumbs(id),
        this.drive.subfolders(id),
      ]);
      if (seq !== this.seq) return;
      this.crumbs.set(crumbs);
      this.folders.set(folders);
    } catch (error) {
      if (seq === this.seq) this.loadError.set(message(error));
    }
  }

  protected async move(): Promise<void> {
    if (this.moving()) return;
    const target = this.here();
    this.moving.set(true);
    this.moveError.set(null);
    try {
      await this.drive.move(this.data.entry, target);
      this.ref.close(target);
    } catch (error) {
      this.moveError.set(message(error));
    } finally {
      this.moving.set(false);
    }
  }
}

function message(error: unknown): string {
  return error instanceof ApiError
    ? error.userMessage
    : $localize`:@@files.error:Something went wrong.`;
}
