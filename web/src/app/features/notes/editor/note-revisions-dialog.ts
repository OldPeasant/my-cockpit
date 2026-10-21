import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { NoteRevisionResponse } from '../../../api/generated';
import { ApiError } from '../../../core/http';
import { NoteEditorService } from './note-editor.service';
import { NoteMarkdownView } from './note-markdown-view';

export interface NoteRevisionsDialogData {
  readonly noteId: string;
}

/**
 * The kept earlier versions of a note (SPEC.md §8.1, the last 20): pick one to see it, restore it.
 * Closes with the number of the revision to restore, or nothing.
 */
@Component({
  selector: 'app-note-revisions-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, MatButtonModule, MatDialogModule, NoteMarkdownView],
  template: `
    <h2 mat-dialog-title i18n="@@notes.revisions.title">Earlier versions</h2>
    <mat-dialog-content class="content">
      @if (error(); as error) {
        <p class="error" role="alert" i18n="@@notes.revisions.loadFailed">
          The earlier versions could not be loaded: {{ error }}
        </p>
      } @else if (revisions(); as list) {
        @if (list.length) {
          <div class="layout">
            <ul class="list" i18n-aria-label="@@notes.revisions.list" aria-label="Versions">
              @for (revision of list; track revision.number) {
                <li>
                  <button
                    type="button"
                    class="revision"
                    [attr.aria-pressed]="revision.number === selected()?.number"
                    (click)="select(revision)"
                  >
                    <span class="when">{{ revision.savedAt | date: 'medium' }}</span>
                    <span class="what">{{ summary(revision) }}</span>
                  </button>
                </li>
              }
            </ul>
            <article
              class="preview"
              i18n-aria-label="@@notes.revisions.preview"
              aria-label="Version"
            >
              @if (selected(); as revision) {
                @if (revision.title) {
                  <h3 class="preview-title">{{ revision.title }}</h3>
                }
                <app-note-markdown-view [source]="revision.bodyMd" [readonly]="true" />
              }
            </article>
          </div>
        } @else {
          <p class="empty" i18n="@@notes.revisions.empty">
            No earlier versions yet. Each save keeps the version before it.
          </p>
        }
      } @else {
        <p class="hint" role="status" i18n="@@notes.revisions.loading">Loading versions…</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close i18n="@@notes.revisions.close">
        Close
      </button>
      <button
        mat-flat-button
        type="button"
        class="restore"
        [disabled]="!selected()"
        (click)="restore()"
        i18n="@@notes.revisions.restore"
      >
        Restore this version
      </button>
    </mat-dialog-actions>
  `,
  styleUrl: './note-revisions-dialog.scss',
})
export class NoteRevisionsDialog implements OnInit {
  private readonly data = inject<NoteRevisionsDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<NoteRevisionsDialog, number>>(MatDialogRef);
  private readonly service = inject(NoteEditorService);

  protected readonly revisions = signal<NoteRevisionResponse[] | null>(null);
  protected readonly selected = signal<NoteRevisionResponse | null>(null);
  protected readonly error = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    try {
      const revisions = await this.service.revisions(this.data.noteId);
      revisions.sort((a, b) => b.number - a.number);
      this.revisions.set(revisions);
      this.selected.set(revisions[0] ?? null);
    } catch (error) {
      this.error.set(
        error instanceof ApiError
          ? error.userMessage
          : $localize`:@@notes.error:Something went wrong.`,
      );
    }
  }

  protected select(revision: NoteRevisionResponse): void {
    this.selected.set(revision);
  }

  protected summary(revision: NoteRevisionResponse): string {
    const text = revision.title?.trim() || revision.bodyMd.trim().split('\n')[0];
    return text || $localize`:@@notes.revisions.emptyNote:Empty note`;
  }

  protected restore(): void {
    const revision = this.selected();
    if (revision) this.ref.close(revision.number);
  }
}
