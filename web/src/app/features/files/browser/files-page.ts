import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  Injector,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatMenuModule } from '@angular/material/menu';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { debounceTime, filter, firstValueFrom } from 'rxjs';
import { DriveUsageResponse } from '../../../api/generated';
import { ApiError, NotificationService } from '../../../core/http';
import { ItemTypeIcon } from '../../../shared/links';
import { openPreview } from '../preview/preview-dialog';
import { FileThumb } from '../preview/file-thumb';
import { previewKind } from '../preview/preview-kind';
import { FileDrop } from '../upload/file-drop';
import { formatBytes } from '../upload/format-bytes';
import { UploadPanel } from '../upload/upload-panel';
import { UploadService } from '../upload/upload.service';
import { Crumb, DriveEntry, DriveService } from './drive.service';
import { FolderTree } from './folder-tree';
import { MoveDialog, MoveDialogData } from './move-dialog';
import { NameDialog, NameDialogData } from './name-dialog';

/** How the entries of a folder are laid out. */
export type ViewMode = 'list' | 'grid';

/** `localStorage` key of the chosen {@link ViewMode}. */
export const VIEW_MODE_KEY = 'cockpit.files.view';

export { formatBytes };

/** Uploads finishing within this time refresh the listing once. */
export const UPLOAD_REFRESH_MS = 300;

/**
 * The drive (SPEC.md §8.2): the folder tree beside the shown folder's entries as a list or a grid,
 * breadcrumbs, new folder, rename, move, delete (to the Trash), downloads (a folder as a zip) and the
 * disk usage. `/files` shows the drive root, `/files/<folderId>` a folder.
 */
@Component({
  selector: 'app-files-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    FileDrop,
    FileThumb,
    FolderTree,
    ItemTypeIcon,
    MatButtonModule,
    MatMenuModule,
    RouterLink,
    UploadPanel,
  ],
  templateUrl: './files-page.html',
  styleUrl: './files-page.scss',
})
export default class FilesPage {
  private readonly drive = inject(DriveService);
  private readonly dialog = inject(MatDialog);
  private readonly notifications = inject(NotificationService);
  private readonly uploads = inject(UploadService);
  private readonly injector = inject(Injector);

  private readonly tree = viewChild(FolderTree);

  /** The shown folder (`null`: the drive root). */
  protected readonly folderId = signal<string | null>(null);
  protected readonly crumbs = signal<Crumb[]>([]);
  /** Ids of the shown folder and its ancestors, for the tree. */
  protected readonly path = computed(() =>
    this.crumbs().flatMap((crumb) => (crumb.id ? [crumb.id] : [])),
  );
  /** The entries loaded so far; null while the first page loads. */
  protected readonly entries = signal<DriveEntry[] | null>(null);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly loadingMore = signal(false);
  protected readonly moreError = signal<string | null>(null);
  /** Ids of the entries whose delete is still running. */
  protected readonly busy = signal<ReadonlySet<string>>(new Set());
  /** The last failed action, with the name of its entry. */
  protected readonly actionError = signal<{ name: string; message: string } | null>(null);
  protected readonly usage = signal<DriveUsageResponse | null>(null);
  protected readonly view = signal<ViewMode>(storedViewMode());

  /** Share of the disk in use (by everything on it), 0-100. */
  protected readonly usedPercent = computed(() => {
    const usage = this.usage();
    if (!usage?.totalSpaceBytes) return 0;
    const used = usage.totalSpaceBytes - usage.usableSpaceBytes;
    return Math.min(100, Math.max(0, Math.round((used / usage.totalSpaceBytes) * 100)));
  });

  protected readonly formatBytes = formatBytes;

  /** Counts the folder loads: a page that arrives for an earlier load is ignored. */
  private loadSeq = 0;
  /** Counts the usage loads: an answer that arrives for an earlier load is ignored. */
  private usageSeq = 0;

  constructor() {
    inject(ActivatedRoute)
      .paramMap.pipe(takeUntilDestroyed(inject(DestroyRef)))
      .subscribe((params) => {
        this.folderId.set(params.get('folderId'));
        void this.load();
      });
    void this.loadUsage();
    // A file stored into the shown folder appears in the listing.
    this.uploads.uploaded
      .pipe(
        filter((file) => (file.folderId ?? null) === this.folderId()),
        debounceTime(UPLOAD_REFRESH_MS),
        takeUntilDestroyed(),
      )
      .subscribe(() => this.changed());
  }

  /** Uploads files picked or dropped into the shown folder. */
  protected upload(files: FileList | readonly File[] | null): void {
    if (files?.length) this.uploads.upload(Array.from(files), this.folderId());
  }

  protected onPicked(input: HTMLInputElement): void {
    this.upload(input.files);
    // The same file can be picked again.
    input.value = '';
  }

  /** Files open in the preview when there is one for their type, else they download. */
  protected canPreview(entry: DriveEntry): boolean {
    return entry.kind === 'file' && !!previewKind(entry.file.mime, entry.name);
  }

  protected preview(entry: DriveEntry): void {
    const files = (this.entries() ?? []).flatMap((e) => (e.kind === 'file' ? [e.file] : []));
    openPreview(this.injector, files, entry.id);
  }

  protected async load(): Promise<void> {
    const seq = ++this.loadSeq;
    const folderId = this.folderId();
    this.loadError.set(null);
    this.moreError.set(null);
    this.actionError.set(null);
    this.entries.set(null);
    this.nextCursor.set(null);
    try {
      const [crumbs, page] = await Promise.all([
        this.drive.crumbs(folderId),
        this.drive.list(folderId),
      ]);
      if (seq !== this.loadSeq) return;
      this.crumbs.set(crumbs);
      this.entries.set(page.entries);
      this.nextCursor.set(page.nextCursor);
    } catch (error) {
      if (seq === this.loadSeq) this.loadError.set(message(error));
    }
  }

  protected async loadMore(): Promise<void> {
    const cursor = this.nextCursor();
    if (!cursor || this.loadingMore()) return;
    const seq = this.loadSeq;
    this.loadingMore.set(true);
    this.moreError.set(null);
    try {
      const page = await this.drive.list(this.folderId(), cursor);
      if (seq !== this.loadSeq) return;
      const shown = new Set((this.entries() ?? []).map((entry) => entry.id));
      this.entries.update((entries) => [
        ...(entries ?? []),
        ...page.entries.filter((entry) => !shown.has(entry.id)),
      ]);
      this.nextCursor.set(page.nextCursor);
    } catch (error) {
      if (seq === this.loadSeq) this.moreError.set(message(error));
    } finally {
      this.loadingMore.set(false);
    }
  }

  protected setView(view: ViewMode): void {
    this.view.set(view);
    try {
      localStorage.setItem(VIEW_MODE_KEY, view);
    } catch {
      // Storage may be unavailable (private mode): the choice then lasts for this page only.
    }
  }

  protected async newFolder(): Promise<void> {
    const parentId = this.folderId();
    const saved = await this.openName({
      title: $localize`:@@files.newFolder.title:New folder`,
      label: $localize`:@@files.newFolder.label:Folder name`,
      confirm: $localize`:@@files.newFolder.confirm:Create`,
      save: async (name) => {
        await this.drive.createFolder(name, parentId);
      },
    });
    if (saved) this.changed();
  }

  protected async rename(entry: DriveEntry): Promise<void> {
    const saved = await this.openName({
      title: $localize`:@@files.rename.title:Rename`,
      label: $localize`:@@files.rename.label:New name`,
      confirm: $localize`:@@files.rename.confirm:Rename`,
      name: entry.name,
      save: (name) => this.drive.rename(entry, name),
    });
    if (saved) this.changed();
  }

  protected async move(entry: DriveEntry): Promise<void> {
    const target = await firstValueFrom(
      this.dialog
        .open<MoveDialog, MoveDialogData, string | null>(MoveDialog, {
          data: { entry, from: this.folderId() },
        })
        .afterClosed(),
    );
    if (target === undefined) return;
    const name = entry.name;
    this.notifications.info($localize`:@@files.moved:Moved “${name}:name:”`);
    this.changed();
  }

  /** Moves an entry to the Trash; no confirmation, as it can be restored from there. */
  protected async remove(entry: DriveEntry): Promise<void> {
    if (this.busy().has(entry.id)) return;
    this.busy.update((ids) => new Set(ids).add(entry.id));
    this.actionError.set(null);
    const name = entry.name;
    try {
      await this.drive.delete(entry);
      this.notifications.info($localize`:@@files.deleted:Moved “${name}:name:” to the Trash`);
      this.changed();
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) this.changed();
      else this.actionError.set({ name, message: message(error) });
    } finally {
      this.busy.update((ids) => {
        const rest = new Set(ids);
        rest.delete(entry.id);
        return rest;
      });
    }
  }

  protected isBusy(entry: DriveEntry): boolean {
    return this.busy().has(entry.id);
  }

  protected downloadUrl(entry: Pick<DriveEntry, 'kind' | 'id'>): string {
    return this.drive.downloadUrl(entry);
  }

  /** Special folders (Attachments and below) are managed by the app. */
  protected isSpecial(entry: DriveEntry): boolean {
    return entry.kind === 'folder' && !!entry.folder.special;
  }

  protected actionsLabel(entry: DriveEntry): string {
    const name = entry.name;
    return $localize`:@@files.actions:Actions for ${name}:name:`;
  }

  protected openLabel(entry: DriveEntry): string {
    const name = entry.name;
    if (entry.kind === 'folder') return $localize`:@@files.openFolder:Open folder ${name}:name:`;
    return this.canPreview(entry)
      ? $localize`:@@files.previewFile:Preview ${name}:name:`
      : $localize`:@@files.downloadFile:Download ${name}:name:`;
  }

  private async openName(data: NameDialogData): Promise<boolean> {
    return !!(await firstValueFrom(
      this.dialog.open<NameDialog, NameDialogData, boolean>(NameDialog, { data }).afterClosed(),
    ));
  }

  /** Reloads what an operation may have changed: the listing, the tree and the usage. */
  private changed(): void {
    void this.load();
    this.tree()?.refresh();
    void this.loadUsage();
  }

  private async loadUsage(): Promise<void> {
    const seq = ++this.usageSeq;
    try {
      const usage = await this.drive.usage();
      if (seq === this.usageSeq) this.usage.set(usage);
    } catch {
      // The usage is extra information: without it the meter is simply not shown.
    }
  }
}

function storedViewMode(): ViewMode {
  try {
    return localStorage.getItem(VIEW_MODE_KEY) === 'grid' ? 'grid' : 'list';
  } catch {
    return 'list';
  }
}

function message(error: unknown): string {
  return error instanceof ApiError
    ? error.userMessage
    : $localize`:@@files.error:Something went wrong.`;
}
