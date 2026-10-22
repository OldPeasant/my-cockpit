import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule } from '@angular/material/dialog';
import { DriveEntryResponse, FileResponse, FolderSummary } from '../../../api/generated';
import { ApiError } from '../../../core/http';
import { formatBytes } from '../read/format-bytes';
import { MailComposeService } from './mail-compose.service';

/**
 * Picks a file of the drive to attach: browses folders from the root (a path of folders to go back
 * up) and closes with the chosen `FileResponse`, without a result on cancel.
 */
@Component({
  selector: 'app-file-picker-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDialogModule],
  template: `
    <h2 mat-dialog-title i18n="@@mailCompose.pickTitle">Attach from Files</h2>
    <mat-dialog-content>
      <nav class="path" i18n-aria-label="@@mailCompose.pickPath" aria-label="Folder">
        <button type="button" class="crumb" (click)="open(-1)" i18n="@@mailCompose.pickRoot">
          Files
        </button>
        @for (folder of path(); track folder.id; let i = $index) {
          <span aria-hidden="true">/</span>
          <button type="button" class="crumb" (click)="open(i)">{{ folder.name }}</button>
        }
      </nav>
      @if (error(); as message) {
        <p class="note" role="alert">{{ message }}</p>
      } @else if (loading()) {
        <p class="note" role="status" i18n="@@mailCompose.pickLoading">Loading…</p>
      } @else if (entries().length) {
        <ul class="entries">
          @for (entry of entries(); track entry.folder?.id ?? entry.file?.id) {
            <li>
              @if (entry.folder; as folder) {
                <button type="button" class="entry folder" (click)="enter(folder)">
                  <span class="name">{{ folder.name }}/</span>
                </button>
              } @else if (entry.file; as file) {
                <button type="button" class="entry" [mat-dialog-close]="file">
                  <span class="name">{{ file.name }}</span>
                  <span class="size">{{ size(file) }}</span>
                </button>
              }
            </li>
          }
        </ul>
      } @else {
        <p class="note" i18n="@@mailCompose.pickEmpty">This folder is empty.</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" [mat-dialog-close]="undefined" i18n="@@mailCompose.cancel">
        Cancel
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .path {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--app-space-1);
      margin-bottom: var(--app-space-2);
      color: var(--app-fg-muted);
    }
    .crumb,
    .entry {
      border: 0;
      border-radius: var(--app-radius);
      background: none;
      color: inherit;
      font: inherit;
      cursor: pointer;
      &:hover,
      &:focus-visible {
        background: var(--mat-sys-surface-container);
      }
    }
    .crumb {
      padding: var(--app-space-1);
    }
    .entries {
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .entry {
      display: flex;
      justify-content: space-between;
      gap: var(--app-space-3);
      width: 100%;
      padding: var(--app-space-2);
      text-align: start;
    }
    .folder .name {
      font-weight: 500;
    }
    .name {
      overflow-wrap: anywhere;
    }
    .size,
    .note {
      color: var(--app-fg-muted);
    }
  `,
})
export class FilePickerDialog {
  private readonly service = inject(MailComposeService);

  protected readonly path = signal<FolderSummary[]>([]);
  protected readonly entries = signal<DriveEntryResponse[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  /** Counts the loads: only the latest one may show its folder. */
  private seq = 0;

  constructor() {
    void this.load();
  }

  protected enter(folder: FolderSummary): void {
    this.path.update((path) => [...path, folder]);
    void this.load();
  }

  /** Goes back to the folder at `index` of the path (`-1`: the root). */
  protected open(index: number): void {
    this.path.update((path) => path.slice(0, index + 1));
    void this.load();
  }

  protected size(file: FileResponse): string {
    return formatBytes(file.size);
  }

  private async load(): Promise<void> {
    const seq = ++this.seq;
    const folderId = this.path().at(-1)?.id ?? null;
    this.loading.set(true);
    this.error.set(null);
    try {
      const entries = await this.service.files(folderId);
      if (seq !== this.seq) return;
      this.entries.set(entries);
    } catch (error) {
      if (seq !== this.seq) return;
      this.error.set(
        error instanceof ApiError
          ? error.userMessage
          : $localize`:@@mailCompose.unexpected:Something went wrong.`,
      );
    } finally {
      if (seq === this.seq) this.loading.set(false);
    }
  }
}
