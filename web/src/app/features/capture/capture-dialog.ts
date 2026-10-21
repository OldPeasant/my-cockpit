import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  Injector,
  signal,
  viewChild,
} from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { catchError, debounceTime, distinctUntilChanged, from, map, of, switchMap } from 'rxjs';
import { CapturePreviewResponse } from '../../api/generated';
import { NotificationService } from '../../core/http';
import { CaptureService } from './capture.service';
import { uuidv7 } from './uuidv7';

/** How long typing must pause before the syntax preview is requested. */
export const PREVIEW_DEBOUNCE_MS = 250;

interface PreviewResult {
  readonly text: string;
  readonly response: CapturePreviewResponse;
}

/**
 * The quick capture dialog (SPEC.md §4.5): one text field with a live preview of the capture syntax,
 * optional notes and a drop/paste area for attachments (not uploaded yet: files come in phase 2).
 * Enter saves to the Inbox and closes, Shift+Enter saves and keeps the dialog open for the next one.
 *
 * Every draft has a client-generated UUIDv7 id: a failed save that is retried reuses it, so the
 * server never creates the item twice. A save that is still running ignores further submits.
 */
@Component({
  selector: 'app-capture-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe],
  templateUrl: './capture-dialog.html',
  styleUrl: './capture-dialog.scss',
})
export class CaptureDialog {
  private readonly captures = inject(CaptureService);
  private readonly notifications = inject(NotificationService);
  private readonly dialogRef = inject(MatDialogRef<CaptureDialog>, { optional: true });
  private readonly textField = viewChild.required<ElementRef<HTMLInputElement>>('textField');

  protected readonly text = signal('');
  protected readonly notes = signal('');
  /** Names of dropped or pasted files: shown, but not saved until files are supported. */
  protected readonly files = signal<readonly string[]>([]);
  protected readonly saving = signal(false);
  protected readonly dragging = signal(false);
  /** Captures saved while the dialog stayed open (Shift+Enter). */
  protected readonly savedCount = signal(0);

  /** The id of the current draft; replaced only after a successful save. */
  private draftId = uuidv7();

  /** The last server reading together with the trimmed text it was computed for. */
  private readonly previewResult = toSignal(
    toObservable(this.text).pipe(
      map((text) => text.trim()),
      debounceTime(PREVIEW_DEBOUNCE_MS),
      distinctUntilChanged(),
      switchMap((text) =>
        text
          ? from(this.captures.preview(text)).pipe(
              map((response): PreviewResult => ({ text, response })),
              catchError(() => of(null)),
            )
          : of(null),
      ),
    ),
    { initialValue: null as PreviewResult | null },
  );

  /**
   * The server's reading of the current text; `null` while empty, while the preview of the current
   * text is pending or when it failed, so a reading of an older text is never shown.
   */
  protected readonly preview = computed(() => {
    const result = this.previewResult();
    return result && result.text === this.text().trim() ? result.response : null;
  });

  protected onTextKeydown(event: KeyboardEvent): void {
    if (event.key !== 'Enter' || event.isComposing) return;
    event.preventDefault();
    void this.submit(event.shiftKey);
  }

  protected onNotesKeydown(event: KeyboardEvent): void {
    // Enter is a new line in the notes; Ctrl/Cmd+Enter saves.
    if (event.key !== 'Enter' || event.isComposing || !(event.ctrlKey || event.metaKey)) return;
    event.preventDefault();
    void this.submit(event.shiftKey);
  }

  protected onPaste(event: ClipboardEvent): void {
    const files = event.clipboardData?.files;
    if (files && files.length > 0) {
      event.preventDefault();
      this.addFiles(files);
    }
  }

  protected onDragOver(event: DragEvent): void {
    if (!event.dataTransfer?.types.includes('Files')) return;
    event.preventDefault();
    this.dragging.set(true);
  }

  protected onDrop(event: DragEvent): void {
    this.dragging.set(false);
    const files = event.dataTransfer?.files;
    if (files && files.length > 0) {
      event.preventDefault();
      this.addFiles(files);
    }
  }

  protected removeFile(index: number): void {
    this.files.update((names) => names.filter((_, i) => i !== index));
  }

  protected cancel(): void {
    this.dialogRef?.close();
  }

  /**
   * Saves the draft to the Inbox; with `keepOpen` the dialog is cleared for the next capture.
   * Returns whether a capture was saved.
   */
  async submit(keepOpen = false): Promise<boolean> {
    const rawText = this.text();
    const text = rawText.trim();
    if (this.saving() || !text) return false;
    this.saving.set(true);
    const rawNotes = this.notes();
    const files = this.files();
    try {
      const notes = rawNotes.trim();
      const task = await this.captures.save({
        id: this.draftId,
        text,
        ...(notes ? { notes } : {}),
      });
      this.draftId = uuidv7();
      this.notifications.info($localize`:@@capture.saved:Captured to Inbox: ${task.title}:title:`);
      if (keepOpen) {
        // Clear only what was saved: input added while the save ran is the next capture.
        if (this.text() === rawText) this.text.set('');
        if (this.notes() === rawNotes) this.notes.set('');
        this.files.update((current) => without(current, files));
        this.savedCount.update((n) => n + 1);
        this.textField().nativeElement.focus();
      } else {
        this.dialogRef?.close(task);
      }
      return true;
    } catch {
      // The error interceptor already told the user; the draft and its id stay for a retry.
      return false;
    } finally {
      this.saving.set(false);
    }
  }

  private addFiles(files: FileList): void {
    const names = Array.from(files, (file) => file.name);
    this.files.update((current) => [...current, ...names]);
  }
}

/** `names` without one entry per name in `removed`. */
function without(names: readonly string[], removed: readonly string[]): readonly string[] {
  const left = [...removed];
  return names.filter((name) => {
    const i = left.indexOf(name);
    if (i >= 0) left.splice(i, 1);
    return i < 0;
  });
}

/** Opens the dialog with the caller's injector, so its content sees route-level providers. */
export function openCaptureDialog(injector: Injector): MatDialogRef<CaptureDialog> {
  return injector.get(MatDialog).open(CaptureDialog, {
    width: '560px',
    maxWidth: '95vw',
    autoFocus: '#capture-text',
    ariaLabelledBy: 'capture-title',
    injector,
  });
}
