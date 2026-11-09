import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  Injector,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { NavigationStart, Router } from '@angular/router';
import { filter } from 'rxjs';
import { FileResponse } from '../../../api/generated';
import { LinkedItems } from '../../../shared/links';
import { renderMarkdown } from '../../gtd/projects/markdown';
import { PdfViewer } from './pdf-viewer';
import { previewKind } from './preview-kind';
import { PreviewService, TextPreview } from './preview.service';

export interface PreviewDialogData {
  /**
   * The files to page through (those without a preview are skipped, unless `id` is one of them:
   * then it is shown alone).
   */
  files: readonly FileResponse[];
  /** The id of the file shown first. */
  id: string;
}

/**
 * A full-window preview of a file (SPEC.md §8.2): images as a lightbox, PDFs with pdf.js,
 * text and Markdown, audio and video with the browser's player. ←/→ and the side buttons move to
 * the previous/next previewable file of the folder; Esc closes. "Linked items" shows the file's
 * links (SPEC.md §4.3) beside it; following one closes the preview, so the linked item is seen.
 * A file without a preview (a zip, a Word document) is shown alone with a "no preview" notice, its
 * download and its linked items, like the Android app's "Unsupported" view.
 */
@Component({
  selector: 'app-preview-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [LinkedItems, PdfViewer],
  templateUrl: './preview-dialog.html',
  styleUrl: './preview-dialog.scss',
  host: { '(keydown)': 'onKeydown($event)' },
})
export class PreviewDialog {
  private readonly data = inject<PreviewDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<PreviewDialog>);
  private readonly previews = inject(PreviewService);

  protected readonly files = previewableOrAlone(this.data.files, this.data.id);
  protected readonly index = signal(
    Math.max(
      0,
      this.files.findIndex((file) => file.id === this.data.id),
    ),
  );
  protected readonly file = computed(() => this.files[this.index()]);
  /** Whether the linked-items panel is shown beside the file. */
  protected readonly showLinks = signal(false);
  protected readonly kind = computed(() => {
    const file = this.file();
    return file ? previewKind(file.mime, file.name) : null;
  });
  protected readonly src = computed(() => {
    const file = this.file();
    return file ? this.previews.previewUrl(file.id) : '';
  });
  protected readonly downloadUrl = computed(() => {
    const file = this.file();
    return file ? this.previews.downloadUrl(file.id) : '';
  });

  /** The text of a text or Markdown file; `null` while it loads. */
  protected readonly text = signal<TextPreview | null>(null);
  protected readonly textError = signal(false);
  protected readonly markdown = computed(() => {
    const text = this.text();
    return text && this.kind() === 'markdown' ? renderMarkdown(text.text) : '';
  });
  protected readonly imageError = signal(false);

  constructor() {
    // A router navigation (a linked item followed) does not close a dialog by itself.
    inject(Router)
      .events.pipe(
        filter((event) => event instanceof NavigationStart),
        takeUntilDestroyed(inject(DestroyRef)),
      )
      .subscribe(() => this.dialogRef.close());
    effect(() => {
      const file = this.file();
      const kind = this.kind();
      untracked(() => {
        this.imageError.set(false);
        this.text.set(null);
        this.textError.set(false);
        if (file && (kind === 'text' || kind === 'markdown')) void this.loadText(file.id);
      });
    });
  }

  protected prev(): void {
    if (this.index() > 0) this.index.update((i) => i - 1);
  }

  protected next(): void {
    if (this.index() < this.files.length - 1) this.index.update((i) => i + 1);
  }

  protected close(): void {
    this.dialogRef.close();
  }

  protected onKeydown(event: KeyboardEvent): void {
    // Keys typed into the player's controls or the linked-items panel stay there.
    if ((event.target as HTMLElement | null)?.closest('audio, video, .links')) return;
    if (event.key === 'ArrowLeft') this.prev();
    else if (event.key === 'ArrowRight') this.next();
    else return;
    event.preventDefault();
  }

  private async loadText(id: string): Promise<void> {
    try {
      const text = await this.previews.text(id);
      if (this.file()?.id === id) this.text.set(text);
    } catch {
      if (this.file()?.id === id) this.textError.set(true);
    }
  }
}

/**
 * The previewable ones of `files`; just the file `id` when it has no preview, so it can still be
 * opened (its download and linked items) without paging to files of another kind.
 */
function previewableOrAlone(files: readonly FileResponse[], id: string): readonly FileResponse[] {
  const shown = files.find((file) => file.id === id);
  if (shown && !previewKind(shown.mime, shown.name)) return [shown];
  return files.filter((file) => previewKind(file.mime, file.name));
}

/**
 * Opens the preview of `id`, paging through the previewable ones of `files`; a file without a
 * preview is shown alone (notice, download, linked items).
 */
export function openPreview(
  injector: Injector,
  files: readonly FileResponse[],
  id: string,
): MatDialogRef<PreviewDialog> {
  return injector.get(MatDialog).open<PreviewDialog, PreviewDialogData>(PreviewDialog, {
    data: { files, id },
    width: '100vw',
    height: '100vh',
    maxWidth: '100vw',
    maxHeight: '100vh',
    panelClass: 'app-preview-panel',
    ariaLabelledBy: 'preview-title',
    autoFocus: 'dialog',
    injector,
  });
}
