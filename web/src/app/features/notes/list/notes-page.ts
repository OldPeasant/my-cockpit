import { DatePipe, NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  OnInit,
  signal,
  viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { NoteResponse, NoteTagResponse } from '../../../api/generated';
import { ApiError, NotificationService } from '../../../core/http';
import { excerpt, filterNotes, parseTags } from './notes-filter';
import { NotesService } from './notes.service';

/** How the notes are laid out. */
export type NotesViewMode = 'grid' | 'list';

/** `localStorage` key of the chosen {@link NotesViewMode}. */
export const NOTES_VIEW_KEY = 'cockpit.notes.view';

/**
 * The notes overview (SPEC.md §8.1): the notes as a masonry grid or a list of cards, pinned first,
 * filtered by tag, archived or not, and searched by text; pin, archive and write a new note. A card
 * opens the note in the editor (`/notes/<id>`).
 */
@Component({
  selector: 'app-notes-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, MatButtonModule, NgTemplateOutlet, RouterLink],
  templateUrl: './notes-page.html',
  styleUrl: './notes-page.scss',
})
export default class NotesPage implements OnInit {
  private readonly service = inject(NotesService);
  private readonly notifications = inject(NotificationService);

  private readonly composerBody = viewChild<ElementRef<HTMLTextAreaElement>>('composerBody');

  /** The notes of the current tag and archive filter; null while they load. */
  protected readonly notes = signal<NoteResponse[] | null>(null);
  protected readonly tags = signal<NoteTagResponse[]>([]);
  protected readonly tag = signal<string | null>(null);
  protected readonly archived = signal(false);
  protected readonly query = signal('');
  protected readonly view = signal<NotesViewMode>(storedViewMode());
  protected readonly loadError = signal<string | null>(null);
  /** Ids of the notes whose pin or archive change is still running. */
  protected readonly busy = signal<ReadonlySet<string>>(new Set());
  /** The last failed action, with the title of its note. */
  protected readonly actionError = signal<{ title: string; message: string } | null>(null);

  protected readonly composing = signal(false);
  protected readonly draftTitle = signal('');
  protected readonly draftBody = signal('');
  protected readonly draftTags = signal('');
  protected readonly saving = signal(false);
  protected readonly createError = signal<string | null>(null);

  /** The notes matching the search, pinned first. */
  protected readonly shown = computed(() => filterNotes(this.notes() ?? [], this.query()));
  protected readonly pinned = computed(() => this.shown().filter((note) => note.pinned));
  protected readonly others = computed(() => this.shown().filter((note) => !note.pinned));
  protected readonly canSave = computed(
    () => !this.saving() && !!(this.draftTitle().trim() || this.draftBody().trim()),
  );

  /** Counts the loads: notes that arrive for an earlier filter are ignored. */
  private loadSeq = 0;

  ngOnInit(): void {
    void this.load();
    void this.loadTags();
  }

  protected async load(): Promise<void> {
    const seq = ++this.loadSeq;
    this.loadError.set(null);
    this.notes.set(null);
    try {
      const notes = await this.service.list({
        tag: this.tag() ?? undefined,
        archived: this.archived(),
      });
      if (seq === this.loadSeq) this.notes.set(notes);
    } catch (error) {
      if (seq === this.loadSeq) this.loadError.set(message(error));
    }
  }

  protected setTag(tag: string | null): void {
    if (tag === this.tag()) tag = null;
    this.tag.set(tag);
    void this.load();
  }

  protected setArchived(archived: boolean): void {
    if (archived === this.archived()) return;
    this.archived.set(archived);
    this.actionError.set(null);
    void this.load();
  }

  protected setView(view: NotesViewMode): void {
    this.view.set(view);
    try {
      localStorage.setItem(NOTES_VIEW_KEY, view);
    } catch {
      // Storage may be unavailable (private mode): the choice then lasts for this page only.
    }
  }

  protected onSearch(event: Event): void {
    this.query.set((event.target as HTMLInputElement).value);
  }

  protected openComposer(): void {
    this.composing.set(true);
    // A note filtered by tag starts with that tag, so it shows up in the current view.
    if (!this.draftTags() && this.tag()) this.draftTags.set(this.tag() ?? '');
    setTimeout(() => this.composerBody()?.nativeElement.focus());
  }

  protected closeComposer(): void {
    this.composing.set(false);
    this.draftTitle.set('');
    this.draftBody.set('');
    this.draftTags.set('');
    this.createError.set(null);
  }

  protected input(target: { set(value: string): void }, event: Event): void {
    target.set((event.target as HTMLInputElement | HTMLTextAreaElement).value);
  }

  protected async create(): Promise<void> {
    if (!this.canSave()) return;
    this.saving.set(true);
    this.createError.set(null);
    try {
      const note = await this.service.create({
        title: this.draftTitle().trim() || undefined,
        bodyMd: this.draftBody(),
        tags: parseTags(this.draftTags()),
      });
      if (this.matchesFilter(note))
        this.notes.update((notes) => (notes ? [note, ...notes] : notes));
      this.closeComposer();
      this.notifications.info($localize`:@@notes.created:Note saved`);
      void this.loadTags();
    } catch (error) {
      this.createError.set(message(error));
    } finally {
      this.saving.set(false);
    }
  }

  protected async togglePin(note: NoteResponse): Promise<void> {
    await this.run(note, async () => {
      const changed = await this.service.setPinned(note, !note.pinned);
      this.replace(changed);
    });
  }

  protected async toggleArchived(note: NoteResponse): Promise<void> {
    await this.run(note, async () => {
      const changed = await this.service.setArchived(note, !note.archived);
      // It now belongs to the other view.
      this.notes.update((notes) => notes?.filter((n) => n.id !== changed.id) ?? null);
      const title = note.displayTitle;
      this.notifications.info(
        changed.archived
          ? $localize`:@@notes.archived:Archived “${title}:title:”`
          : $localize`:@@notes.unarchived:Moved “${title}:title:” back to Notes`,
      );
    });
  }

  protected isBusy(note: NoteResponse): boolean {
    return this.busy().has(note.id);
  }

  protected excerpt(note: NoteResponse): string {
    return excerpt(note);
  }

  protected pinLabel(note: NoteResponse): string {
    const title = note.displayTitle;
    return note.pinned
      ? $localize`:@@notes.unpinAria:Unpin ${title}:title:`
      : $localize`:@@notes.pinAria:Pin ${title}:title:`;
  }

  protected archiveLabel(note: NoteResponse): string {
    const title = note.displayTitle;
    return note.archived
      ? $localize`:@@notes.unarchiveAria:Unarchive ${title}:title:`
      : $localize`:@@notes.archiveAria:Archive ${title}:title:`;
  }

  private async loadTags(): Promise<void> {
    try {
      this.tags.set(await this.service.tags());
    } catch {
      // Without the tags the filter row stays empty; the notes themselves still load.
    }
  }

  private matchesFilter(note: NoteResponse): boolean {
    const tag = this.tag()?.toLocaleLowerCase();
    return (
      note.archived === this.archived() &&
      (!tag || note.tags.some((t) => t.toLocaleLowerCase() === tag))
    );
  }

  private replace(note: NoteResponse): void {
    this.notes.update((notes) => notes?.map((n) => (n.id === note.id ? note : n)) ?? null);
  }

  /** Runs a change of `note`; a note gone meanwhile (404) is dropped from the list. */
  private async run(note: NoteResponse, action: () => Promise<void>): Promise<void> {
    if (this.busy().has(note.id)) return;
    this.busy.update((ids) => new Set(ids).add(note.id));
    this.actionError.set(null);
    try {
      await action();
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        this.notes.update((notes) => notes?.filter((n) => n.id !== note.id) ?? null);
      } else {
        this.actionError.set({ title: note.displayTitle, message: message(error) });
        // Changed elsewhere: show the current versions, so trying again works.
        if (error instanceof ApiError && (error.status === 409 || error.status === 412)) {
          void this.load();
        }
      }
    } finally {
      this.busy.update((ids) => {
        const rest = new Set(ids);
        rest.delete(note.id);
        return rest;
      });
    }
  }
}

function storedViewMode(): NotesViewMode {
  try {
    return localStorage.getItem(NOTES_VIEW_KEY) === 'list' ? 'list' : 'grid';
  } catch {
    return 'grid';
  }
}

function message(error: unknown): string {
  return error instanceof ApiError
    ? error.userMessage
    : $localize`:@@notes.error:Something went wrong.`;
}
