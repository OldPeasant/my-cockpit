import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NoteResponse } from '../../../api/generated';
import { excerpt } from './notes-filter';

/**
 * One note of the overview: its title and text (opening the editor), tags, last change, and the pin
 * and archive buttons.
 */
@Component({
  selector: 'app-note-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, RouterLink],
  host: { '[class.compact]': 'compact()' },
  template: `
    @let n = note();
    <a class="open" [routerLink]="['/notes', n.id]">
      <span class="title">{{ n.displayTitle }}</span>
      @if (text(); as text) {
        <span class="text">{{ text }}</span>
      }
    </a>
    <div class="foot">
      @if (n.tags.length) {
        <ul class="note-tags" i18n-aria-label="@@notes.noteTags" aria-label="Tags">
          @for (t of n.tags; track t) {
            <li>#{{ t }}</li>
          }
        </ul>
      }
      <time class="updated" [attr.datetime]="n.updatedAt">{{
        n.updatedAt | date: 'mediumDate'
      }}</time>
      <div class="actions">
        <button
          type="button"
          class="pin"
          [attr.aria-pressed]="n.pinned"
          [attr.aria-label]="pinLabel()"
          [title]="pinLabel()"
          [disabled]="busy()"
          (click)="pin.emit()"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <path d="M16 3H8v2l1 1v5l-3 3v2h5v5l1 1 1-1v-5h5v-2l-3-3V6l1-1z" />
          </svg>
        </button>
        <button
          type="button"
          class="archive"
          [attr.aria-label]="archiveLabel()"
          [title]="archiveLabel()"
          [disabled]="busy()"
          (click)="archive.emit()"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            @if (n.archived) {
              <path d="M4 4h16v4H4zM5 9h14v11H5zm7 2-4 4h3v3h2v-3h3z" />
            } @else {
              <path d="M4 4h16v4H4zM5 9h14v11H5zm4 2v2h6v-2z" />
            }
          </svg>
        </button>
      </div>
    </div>
  `,
  styleUrl: './note-card.scss',
})
export class NoteCard {
  readonly note = input.required<NoteResponse>();
  /** A pin or archive change of this note is running. */
  readonly busy = input(false);
  /** Fewer lines of text, for the list view. */
  readonly compact = input(false);
  /** The pin button was pressed: pin or unpin the note. */
  readonly pin = output();
  /** The archive button was pressed: archive or unarchive the note. */
  readonly archive = output();

  protected readonly text = computed(() => excerpt(this.note()));

  protected readonly pinLabel = computed(() => {
    const title = this.note().displayTitle;
    return this.note().pinned
      ? $localize`:@@notes.unpinAria:Unpin ${title}:title:`
      : $localize`:@@notes.pinAria:Pin ${title}:title:`;
  });

  protected readonly archiveLabel = computed(() => {
    const title = this.note().displayTitle;
    return this.note().archived
      ? $localize`:@@notes.unarchiveAria:Unarchive ${title}:title:`
      : $localize`:@@notes.archiveAria:Archive ${title}:title:`;
  });
}
