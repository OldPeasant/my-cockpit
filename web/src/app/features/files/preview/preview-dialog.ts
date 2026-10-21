import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  Injector,
  signal,
  untracked,
} from '@angular/core';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { FileResponse } from '../../../api/generated';
import { renderMarkdown } from '../../gtd/projects/markdown';
import { PdfViewer } from './pdf-viewer';
import { previewKind } from './preview-kind';
import { PreviewService, TextPreview } from './preview.service';

export interface PreviewDialogData {
  /** The files to page through (those without a preview are skipped). */
  files: readonly FileResponse[];
  /** The id of the file shown first. */
  id: string;
}

/**
 * A full-window preview of a file (SPEC.md §8.2): images as a lightbox, PDFs with pdf.js,
 * text and Markdown, audio and video with the browser's player. ←/→ and the side buttons move to
 * the previous/next previewable file of the folder; Esc closes.
 */
@Component({
  selector: 'app-preview-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PdfViewer],
  templateUrl: './preview-dialog.html',
  styleUrl: './preview-dialog.scss',
  host: { '(keydown)': 'onKeydown($event)' },
})
export class PreviewDialog {
  private readonly data = inject<PreviewDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<PreviewDialog>);
  private readonly previews = inject(PreviewService);

  protected readonly files = this.data.files.filter((file) => previewKind(file.mime, file.name));
  protected readonly index = signal(
    Math.max(
      0,
      this.files.findIndex((file) => file.id === this.data.id),
    ),
  );
  protected readonly file = computed(() => this.files[this.index()]);
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
    // Keys typed into the player's controls stay there.
    if ((event.target as HTMLElement | null)?.closest('audio, video')) return;
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

/** Opens the preview of `id`, paging through the previewable ones of `files`. */
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
