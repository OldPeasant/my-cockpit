import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ItemRefDto, NoteResponse } from '../../../api/generated';
import { ApiError } from '../../../core/http';
import { itemTypeLabel, LinkedItems, LinksService } from '../../../shared/links';
import { LinkOption, MarkdownEditor, withoutUploadPlaceholders } from './markdown-editor';
import { NoteDraft, NoteEditorService } from './note-editor.service';
import { InlineLinkInfo, inlineLinks, toggleChecklistItem } from './note-markdown';
import { NoteMarkdownView } from './note-markdown-view';
import { NoteRevisionsDialog, NoteRevisionsDialogData } from './note-revisions-dialog';

/** Wait after the last keystroke before saving. */
export const AUTOSAVE_DEBOUNCE_MS = 1000;

/** How the editor and the preview share the page. */
export type NoteEditorMode = 'edit' | 'split' | 'preview';

/** `localStorage` key of the chosen {@link NoteEditorMode}. */
export const NOTE_EDITOR_MODE_KEY = 'cockpit.notes.editorMode';

/** Where the edits stand: saved, waiting for the debounce, saving, failed or in conflict. */
export type SaveState = 'saved' | 'pending' | 'saving' | 'error' | 'conflict';

/**
 * The note editor (`/notes/:id`, SPEC.md §8.1): title, a CodeMirror Markdown editor with preview
 * (toggle or split), autosave (debounced, `If-Match`; a note changed elsewhere stops the autosave
 * and asks which version to keep), images by paste or drop, checklists toggled in the preview,
 * `[[` links rendered as chips, earlier versions with restore, and the Linked items panel.
 */
@Component({
  selector: 'app-note-editor-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [LinkedItems, MarkdownEditor, MatButtonModule, NoteMarkdownView, RouterLink],
  host: { '(window:beforeunload)': 'onBeforeUnload($event)' },
  templateUrl: './note-editor-page.html',
  styleUrl: './note-editor-page.scss',
})
export default class NoteEditorPage {
  private readonly service = inject(NoteEditorService);
  private readonly links = inject(LinksService);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);

  /** The note's id, from the route. */
  protected readonly id = signal('');

  private readonly linkedItems = viewChild(LinkedItems);

  /** The note as last saved; null while it loads. */
  protected readonly note = signal<NoteResponse | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly title = signal('');
  protected readonly body = signal('');
  protected readonly mode = signal<NoteEditorMode>(storedMode());
  protected readonly saveState = signal<SaveState>('saved');
  protected readonly saveError = signal<string | null>(null);
  /** What is known about items linked inline, by `TYPE:id`: from the note's links and the autocomplete. */
  protected readonly linkInfo = signal<ReadonlyMap<string, InlineLinkInfo>>(new Map());

  protected readonly ref = computed<ItemRefDto>(() => ({ type: 'NOTE', id: this.id() }));
  protected readonly dirty = computed(() => {
    const note = this.note();
    return !!note && !!changes(note, this.draft());
  });

  /** Uploads an image to the note; tracked, so leaving the note still puts it in place. */
  protected readonly uploadImage = (file: File, placeholder: string): Promise<string> => {
    const upload = this.service.uploadImage(this.id(), file).then((image) => image.markdown);
    const done = upload.catch(() => '');
    this.uploads.set(placeholder, done);
    void done.then(() => this.uploads.delete(placeholder));
    return upload;
  };
  protected readonly searchLinks = (query: string): Promise<LinkOption[]> =>
    this.findLinkTargets(query);

  private readonly draft = computed<NoteDraft>(() => ({
    title: this.title().trim() || null,
    bodyMd: withoutUploadPlaceholders(this.body()),
  }));
  private timer?: ReturnType<typeof setTimeout>;
  private inFlight: Promise<void> | null = null;
  private destroyed = false;
  /** Images still uploading, by their placeholder in the body: their Markdown ('' if failed). */
  private readonly uploads = new Map<string, Promise<string>>();

  /** Counts the loads: a note that arrives after another one was opened is ignored. */
  private loadSeq = 0;
  /** Counts the route changes: while leaving waits, a newer route wins. */
  private openSeq = 0;

  constructor() {
    inject(ActivatedRoute)
      .paramMap.pipe(takeUntilDestroyed())
      .subscribe((params) => void this.open(params.get('id') ?? ''));
    inject(DestroyRef).onDestroy(() => {
      this.destroyed = true;
      void this.leave();
    });
  }

  protected async load(): Promise<void> {
    const seq = ++this.loadSeq;
    this.loadError.set(null);
    try {
      const note = await this.service.get(this.id());
      if (seq !== this.loadSeq) return;
      this.show(note);
      void this.loadLinkInfo();
    } catch (error) {
      if (seq === this.loadSeq) this.loadError.set(message(error));
    }
  }

  /**
   * Shows the note `id`, after saving the edits of the one shown before (a chip to another note).
   * If the save fails (not a conflict: those edits are dropped), it stays on that note.
   */
  private async open(id: string): Promise<void> {
    const seq = ++this.openSeq;
    // Also back to the note shown while leaving it waits: the switch is cancelled, it stays.
    if (id === this.id()) return;
    if (this.note()) await this.leave();
    // The page was left or the route changed again while leaving waited.
    if (this.destroyed || seq !== this.openSeq) return;
    // The edits could not be saved: stay with them and the retry, and put the address back.
    if (this.saveState() === 'error' && this.dirty()) {
      void this.router.navigate(['/notes', this.id()], { replaceUrl: true });
      return;
    }
    this.id.set(id);
    this.note.set(null);
    this.linkInfo.set(new Map());
    await this.load();
  }

  /**
   * Leaving the note (another note or page): saves what is not saved yet (also after a failed save),
   * then waits for the images still uploading and saves them in place of their placeholders.
   */
  private async leave(): Promise<void> {
    await this.flushAll();
    if (!this.uploads.size) return;
    await this.finishUploads();
    await this.flushAll();
  }

  /**
   * Saves until nothing is left, so edits typed while a save runs are not lost; stops on a conflict
   * or a failed save. Each round sends the current draft, and a round with nothing to send ends it.
   */
  private async flushAll(): Promise<void> {
    do await this.flush();
    while (this.dirty() && this.saveState() !== 'conflict' && this.saveState() !== 'error');
  }

  /** Waits for the uploads and puts each result where its placeholder still stands. */
  private async finishUploads(): Promise<void> {
    while (this.uploads.size) {
      const results = await Promise.all(
        [...this.uploads].map(async ([placeholder, done]) => [placeholder, await done] as const),
      );
      for (const [placeholder, markdown] of results) {
        this.uploads.delete(placeholder);
        if (this.body().includes(placeholder)) {
          this.body.update((body) => body.replace(placeholder, () => markdown));
        }
      }
    }
  }

  protected setMode(mode: NoteEditorMode): void {
    this.mode.set(mode);
    try {
      localStorage.setItem(NOTE_EDITOR_MODE_KEY, mode);
    } catch {
      // Storage may be unavailable (private mode): the choice then lasts for this page only.
    }
  }

  protected onTitle(event: Event): void {
    this.title.set((event.target as HTMLInputElement).value);
    this.schedule();
  }

  protected onBody(body: string): void {
    this.body.set(body);
    this.schedule();
  }

  /** A checkbox in the preview: the change is saved right away. */
  protected onToggle(line: number): void {
    const body = toggleChecklistItem(this.body(), line);
    if (body === this.body()) return;
    this.body.set(body);
    this.schedule();
    void this.flush();
  }

  protected onUploadFailed(error: unknown): void {
    this.saveError.set(
      $localize`:@@notes.editor.uploadFailed:The image could not be uploaded: ${message(error)}:message:`,
    );
  }

  /** Saves now what is not saved yet; waits for a save that is running. */
  async flush(): Promise<void> {
    clearTimeout(this.timer);
    this.timer = undefined;
    while (this.inFlight) await this.inFlight;
    const note = this.note();
    if (!note || this.saveState() === 'conflict') return;
    const draft = changes(note, this.draft());
    if (!draft) {
      if (this.saveState() !== 'error') this.saveState.set('saved');
      return;
    }
    this.inFlight = this.persist(note, draft);
    try {
      await this.inFlight;
    } finally {
      this.inFlight = null;
    }
  }

  protected retry(): void {
    this.saveError.set(null);
    this.saveState.set('pending');
    void this.flush();
  }

  /** Conflict: drop the local edits and show the note as it is now. */
  protected async useTheirs(): Promise<void> {
    const seq = this.loadSeq;
    try {
      const note = await this.service.get(this.id());
      // Another note was opened meanwhile.
      if (seq !== this.loadSeq) return;
      this.show(note);
      void this.reloadLinks();
    } catch (error) {
      if (seq === this.loadSeq) this.saveError.set(message(error));
    }
  }

  /** Conflict: save the local edits over the newer version. */
  protected async keepMine(): Promise<void> {
    const seq = this.loadSeq;
    try {
      const note = await this.service.get(this.id());
      // Another note was opened meanwhile: its text must not overwrite this one.
      if (seq !== this.loadSeq) return;
      this.note.set(note);
      this.saveError.set(null);
      this.saveState.set('pending');
      await this.flush();
    } catch (error) {
      if (seq === this.loadSeq) this.saveError.set(message(error));
    }
  }

  protected async openRevisions(): Promise<void> {
    const data: NoteRevisionsDialogData = { noteId: this.id() };
    const number = await new Promise<number | undefined>((resolve) =>
      this.dialog
        .open<NoteRevisionsDialog, NoteRevisionsDialogData, number>(NoteRevisionsDialog, {
          data,
          maxWidth: '960px',
          autoFocus: 'dialog',
        })
        .afterClosed()
        .subscribe(resolve),
    );
    if (number === undefined) return;
    await this.flush();
    const note = this.note();
    if (!note || this.saveState() === 'conflict') return;
    try {
      this.show(await this.service.restore(note, number));
      void this.reloadLinks();
    } catch (error) {
      this.saveError.set(
        $localize`:@@notes.editor.restoreFailed:The version could not be restored: ${message(error)}:message:`,
      );
    }
  }

  protected onBeforeUnload(event: BeforeUnloadEvent): void {
    if (this.dirty() || this.saveState() === 'saving' || this.uploads.size) event.preventDefault();
  }

  private schedule(): void {
    if (this.saveState() === 'conflict') return;
    clearTimeout(this.timer);
    this.saveState.set('pending');
    this.timer = setTimeout(() => {
      this.timer = undefined;
      void this.flush();
    }, AUTOSAVE_DEBOUNCE_MS);
  }

  private async persist(note: NoteResponse, draft: NoteDraft): Promise<void> {
    this.saveState.set('saving');
    this.saveError.set(null);
    try {
      const saved = await this.service.save(note, draft);
      this.note.set(saved);
      if (!sameLinks(note.bodyMd, saved.bodyMd)) void this.reloadLinks();
      // Edits made while saving wait for their own debounce (or are saved now when leaving).
      if (!changes(saved, this.draft())) this.saveState.set('saved');
      else if (this.destroyed) void this.flush();
      else if (this.timer === undefined) this.schedule();
      else this.saveState.set('pending');
    } catch (error) {
      if (error instanceof ApiError && (error.status === 409 || error.status === 412)) {
        clearTimeout(this.timer);
        this.timer = undefined;
        this.saveState.set('conflict');
      } else {
        this.saveState.set('error');
        this.saveError.set(message(error));
      }
    }
  }

  private show(note: NoteResponse): void {
    clearTimeout(this.timer);
    this.timer = undefined;
    this.note.set(note);
    this.title.set(note.title ?? '');
    this.body.set(note.bodyMd);
    this.saveState.set('saved');
    this.saveError.set(null);
  }

  private async reloadLinks(): Promise<void> {
    void this.linkedItems()?.reload();
    await this.loadLinkInfo();
  }

  /** Titles and deep links of the note's linked items, for its chips. */
  private async loadLinkInfo(): Promise<void> {
    try {
      const links = await this.links.list(this.ref());
      this.linkInfo.update((known) => {
        const next = new Map(known);
        for (const link of links) {
          const other = link.direction === 'OUTGOING' ? link.target : link.source;
          next.set(`${other.type}:${other.id}`, {
            title: other.title ?? itemTypeLabel(other.type),
            deepLink: other.available ? other.deepLink : undefined,
          });
        }
        return next;
      });
    } catch {
      // Chips then show the item type only; the Linked items panel shows the error.
    }
  }

  private async findLinkTargets(query: string): Promise<LinkOption[]> {
    const groups = await this.links.search(query);
    const options: LinkOption[] = [];
    const found = new Map<string, InlineLinkInfo>();
    for (const group of groups) {
      for (const hit of group.items) {
        if (hit.type === 'NOTE' && hit.id === this.id()) continue;
        options.push({
          type: hit.type,
          id: hit.id,
          title: hit.title,
          detail: itemTypeLabel(hit.type),
        });
        found.set(`${hit.type}:${hit.id}`, { title: hit.title, deepLink: hit.deepLink });
      }
    }
    this.linkInfo.update((known) => new Map([...known, ...found]));
    return options;
  }
}

/** The draft when it differs from the saved note, else null. */
function changes(note: NoteResponse, draft: NoteDraft): NoteDraft | null {
  return draft.bodyMd !== note.bodyMd || draft.title !== (note.title ?? null) ? draft : null;
}

function sameLinks(before: string, after: string): boolean {
  const key = (body: string) =>
    inlineLinks(body)
      .map((ref) => `${ref.type}:${ref.id}`)
      .join(' ');
  return key(before) === key(after);
}

function storedMode(): NoteEditorMode {
  try {
    const mode = localStorage.getItem(NOTE_EDITOR_MODE_KEY);
    return mode === 'edit' || mode === 'preview' ? mode : 'split';
  } catch {
    return 'split';
  }
}

function message(error: unknown): string {
  return error instanceof ApiError
    ? error.userMessage
    : $localize`:@@notes.error:Something went wrong.`;
}
