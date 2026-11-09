import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { BookmarkResponse, BookmarkTagResponse } from '../../api/generated';
import { ChangeEventsService } from '../../core/events';
import { ApiError, NotificationService } from '../../core/http';
import { uuidv7 } from '../capture/uuidv7';
import { hostOf, inQueue, matchesQuery, normalizeUrl, parseTags } from './bookmark-display';
import { BookmarksService, BookmarkView } from './bookmarks.service';

/** The item type of bookmarks in change events. */
const BOOKMARK_TYPE = 'BOOKMARK';

/** How long the undo snackbar after a delete stays (ms). */
export const UNDO_DURATION_MS = 6000;

/** The unsaved values of the bookmark editor. */
interface Draft {
  id: string;
  title: string;
  tags: string;
  notes: string;
}

/**
 * Bookmarks (SPEC.md §8.4): the read-later queue (`/bookmarks`) and all bookmarks, optionally of
 * one tag (`/bookmarks?view=all&tag=…`). Add a URL, mark bookmarks read, move them into or out of
 * the queue, open their readable snapshot, search the loaded ones, edit title, tags and notes,
 * undo a delete, and turn one into an Inbox task (SPEC.md §4.5). Title, description and icon of a new bookmark arrive
 * from the server in the background and replace it in place.
 */
@Component({
  selector: 'app-bookmarks-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, RouterLink],
  templateUrl: './bookmarks-page.html',
  styleUrls: ['./bookmarks-page.scss', './bookmark-edit.scss'],
})
export default class BookmarksPage {
  private readonly service = inject(BookmarksService);
  private readonly notifications = inject(NotificationService);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly view = signal<BookmarkView>('queue');
  protected readonly tag = signal<string | null>(null);

  /** The bookmarks of the current view, newest first; null while they load. */
  protected readonly bookmarks = signal<BookmarkResponse[] | null>(null);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly loadingMore = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly tags = signal<BookmarkTagResponse[]>([]);

  protected readonly url = signal('');
  protected readonly tagsText = signal('');
  protected readonly readLater = signal(true);
  protected readonly adding = signal(false);
  protected readonly addError = signal<string | null>(null);

  /** Ids of the bookmarks whose change is still running. */
  protected readonly busy = signal<ReadonlySet<string>>(new Set());
  /** The last failed change, by bookmark id. */
  protected readonly rowError = signal<{ id: string; message: string } | null>(null);

  /** The search text: only loaded bookmarks matching every word are shown. */
  protected readonly query = signal('');
  /** The bookmarks of the view that match the search; null while they load. */
  protected readonly shown = computed(() => {
    const items = this.bookmarks();
    const query = this.query();
    return items && query.trim() ? items.filter((b) => matchesQuery(b, query)) : items;
  });

  /** The bookmark being edited, with its unsaved values. */
  protected readonly draft = signal<Draft | null>(null);

  /**
   * The client id of the Inbox task to create for a bookmark, kept until it was created: a retry
   * after a lost answer then returns the same task instead of a second one.
   */
  private readonly taskIds = new Map<string, string>();

  protected readonly hostOf = hostOf;

  /** The query params of the current view, so the reader can lead back to it. */
  protected readonly viewParams = computed(() =>
    this.view() === 'all' ? { view: 'all', tag: this.tag() } : {},
  );

  /** Counts the loads: bookmarks that arrive for an earlier view are ignored. */
  private loadSeq = 0;
  /**
   * Counts the local changes (add, mark read, queue, delete), at their start and end: a quiet reload
   * that overlaps one may have read the bookmarks before it and would undo it on screen.
   */
  private changeSeq = 0;
  /** The `changeSeq` of the last local change, by bookmark id. */
  private readonly changedAt = new Map<string, number>();

  constructor() {
    const destroyRef = inject(DestroyRef);
    inject(ActivatedRoute)
      .queryParamMap.pipe(takeUntilDestroyed(destroyRef))
      .subscribe((params) => {
        const view: BookmarkView = params.get('view') === 'all' ? 'all' : 'queue';
        const tag = view === 'all' ? params.get('tag') || null : null;
        if (this.bookmarks() !== null && view === this.view() && tag === this.tag()) return;
        this.view.set(view);
        this.tag.set(tag);
        this.readLater.set(view === 'queue');
        this.rowError.set(null);
        void this.load();
      });
    const events = inject(ChangeEventsService);
    events
      .events$(BOOKMARK_TYPE)
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe((event) => {
        if (event.op === 'CREATED') return void this.reloadQuietly(event.id);
        if (event.op === 'DELETED') return this.drop(event.id);
        void this.refreshOne(event.id);
      });
    events.resync$.pipe(takeUntilDestroyed(destroyRef)).subscribe(() => void this.reloadQuietly());
    void this.loadTags();
  }

  protected async load(): Promise<void> {
    const seq = ++this.loadSeq;
    this.loadError.set(null);
    this.bookmarks.set(null);
    this.nextCursor.set(null);
    try {
      const page = await this.service.list(this.view(), this.tag());
      if (seq !== this.loadSeq) return;
      this.bookmarks.set(page.items);
      this.nextCursor.set(page.nextCursor ?? null);
    } catch (error) {
      if (seq === this.loadSeq) this.loadError.set(message(error));
    }
  }

  protected async loadMore(): Promise<void> {
    const cursor = this.nextCursor();
    if (!cursor || this.loadingMore()) return;
    const seq = this.loadSeq;
    this.loadingMore.set(true);
    try {
      const page = await this.service.list(this.view(), this.tag(), cursor);
      if (seq !== this.loadSeq) return;
      this.bookmarks.update((current) => {
        const known = new Set(current?.map((b) => b.id));
        return [...(current ?? []), ...page.items.filter((b) => !known.has(b.id))];
      });
      this.nextCursor.set(page.nextCursor ?? null);
    } catch (error) {
      if (seq === this.loadSeq) this.notifications.error(message(error));
    } finally {
      this.loadingMore.set(false);
    }
  }

  protected setUrl(event: Event): void {
    this.url.set((event.target as HTMLInputElement).value);
    this.addError.set(null);
  }

  protected setTagsText(event: Event): void {
    this.tagsText.set((event.target as HTMLInputElement).value);
  }

  protected setQuery(event: Event): void {
    this.query.set((event.target as HTMLInputElement).value);
  }

  protected clearQuery(): void {
    this.query.set('');
  }

  protected setReadLater(event: Event): void {
    this.readLater.set((event.target as HTMLInputElement).checked);
  }

  protected async add(): Promise<void> {
    if (this.adding()) return;
    const url = normalizeUrl(this.url());
    if (!url) {
      this.addError.set(
        $localize`:@@bookmarks.add.invalid:Enter a web address, such as https://example.org/article.`,
      );
      return;
    }
    this.adding.set(true);
    this.addError.set(null);
    this.noteChange();
    try {
      const created = await this.service.create({
        url,
        readLater: this.readLater(),
        tags: parseTags(this.tagsText()),
      });
      this.noteChange(created.id);
      this.url.set('');
      this.tagsText.set('');
      if (this.belongs(created)) {
        this.bookmarks.update((current) =>
          current && !current.some((b) => b.id === created.id) ? [created, ...current] : current,
        );
      }
      this.notifications.info(
        created.readLater
          ? $localize`:@@bookmarks.added.queue:Saved to Read later`
          : $localize`:@@bookmarks.added:Bookmark saved`,
      );
      void this.loadTags();
    } catch (error) {
      this.addError.set(message(error));
    } finally {
      this.noteChange();
      this.adding.set(false);
    }
  }

  /** Marks the bookmark as read, or as unread again. */
  protected async toggleRead(bookmark: BookmarkResponse): Promise<void> {
    const read = !bookmark.readAt;
    await this.change(bookmark, { read }, () => {
      const title = bookmark.displayTitle;
      if (read)
        this.notifications.info(
          $localize`:@@bookmarks.markedRead:Marked “${title}:title:” as read`,
        );
    });
  }

  /** Puts the bookmark into the read-later queue, or takes it out. */
  protected async toggleReadLater(bookmark: BookmarkResponse): Promise<void> {
    await this.change(bookmark, { readLater: !bookmark.readLater });
  }

  protected async remove(bookmark: BookmarkResponse): Promise<void> {
    let removed = false;
    await this.run(bookmark, async () => {
      await this.service.remove(bookmark);
      removed = true;
      this.drop(bookmark.id);
      if (this.draft()?.id === bookmark.id) this.draft.set(null);
      void this.loadTags();
    });
    if (removed) await this.offerUndo(bookmark);
  }

  /** Shows "Undo" after a delete; undoing restores the bookmark from the Trash. */
  private async offerUndo(bookmark: BookmarkResponse): Promise<void> {
    const title = bookmark.displayTitle;
    const ref = this.snackBar.open(
      $localize`:@@bookmarks.trashed:Moved “${title}:title:” to the Trash`,
      $localize`:@@bookmarks.undo:Undo`,
      { duration: UNDO_DURATION_MS },
    );
    const undone = await new Promise<boolean>((resolve) => {
      const action = ref.onAction().subscribe(() => resolve(true));
      ref.afterDismissed().subscribe(() => {
        action.unsubscribe();
        resolve(false);
      });
    });
    if (!undone) return;
    try {
      await this.service.restore(bookmark);
      this.noteChange();
      await this.refreshOne(bookmark.id);
      void this.loadTags();
    } catch (error) {
      this.notifications.error(
        $localize`:@@bookmarks.restoreFailed:“${title}:title:” could not be restored: ${message(error)}:error:`,
      );
    }
  }

  /** Opens the editor for title, tags and notes of the bookmark. */
  protected startEdit(bookmark: BookmarkResponse): void {
    this.rowError.set(null);
    this.draft.set({
      id: bookmark.id,
      title: bookmark.title ?? '',
      tags: bookmark.tags.join(', '),
      notes: bookmark.description ?? '',
    });
  }

  protected cancelEdit(): void {
    this.draft.set(null);
  }

  protected setDraft(field: 'title' | 'tags' | 'notes', event: Event): void {
    const value = (event.target as HTMLInputElement | HTMLTextAreaElement).value;
    this.draft.update((draft) => (draft ? { ...draft, [field]: value } : draft));
  }

  /** Saves the editor's values; a blank title or notes field clears it. The editor stays on failure. */
  protected async saveEdit(bookmark: BookmarkResponse): Promise<void> {
    const draft = this.draft();
    if (!draft || draft.id !== bookmark.id) return;
    await this.run(bookmark, async () => {
      this.put(
        await this.service.update(bookmark, {
          title: draft.title.trim() || null,
          description: draft.notes.trim() || null,
          tags: parseTags(draft.tags),
        }),
      );
      if (this.draft()?.id === bookmark.id) this.draft.set(null);
      void this.loadTags();
    });
  }

  /** Creates an Inbox task for the bookmark, linked to it as its origin (SPEC.md §4.5). */
  protected async toInbox(bookmark: BookmarkResponse): Promise<void> {
    let taskId = this.taskIds.get(bookmark.id);
    if (!taskId) {
      taskId = uuidv7();
      this.taskIds.set(bookmark.id, taskId);
    }
    const id = taskId;
    await this.run(bookmark, async () => {
      const { task, linked } = await this.service.toInbox(bookmark, id);
      this.taskIds.delete(bookmark.id);
      const title = task.title;
      this.notifications.info(
        linked
          ? $localize`:@@bookmarks.taskCreated:Added to Inbox: ${title}:title:`
          : $localize`:@@bookmarks.taskCreatedUnlinked:Added to Inbox: ${title}:title: (the link to the bookmark could not be made)`,
      );
    });
  }

  protected isEditing(bookmark: BookmarkResponse): boolean {
    return this.draft()?.id === bookmark.id;
  }

  protected isBusy(bookmark: BookmarkResponse): boolean {
    return this.busy().has(bookmark.id);
  }

  protected errorOf(bookmark: BookmarkResponse): string | null {
    const error = this.rowError();
    return error?.id === bookmark.id ? error.message : null;
  }

  /** Whether the bookmark is shown in the current view. */
  private belongs(bookmark: BookmarkResponse): boolean {
    if (this.view() === 'queue') return inQueue(bookmark);
    const tag = this.tag()?.toLowerCase();
    return !tag || bookmark.tags.some((t) => t.toLowerCase() === tag);
  }

  private async change(
    bookmark: BookmarkResponse,
    patch: { read?: boolean; readLater?: boolean },
    after?: () => void,
  ): Promise<void> {
    await this.run(bookmark, async () => {
      this.put(await this.service.update(bookmark, patch));
      after?.();
    });
  }

  /** Shows the new state of a bookmark: replaced in place, or dropped when it left the view. */
  private put(bookmark: BookmarkResponse): void {
    if (this.belongs(bookmark)) {
      this.bookmarks.update(
        (current) => current?.map((b) => (b.id === bookmark.id ? bookmark : b)) ?? null,
      );
    } else {
      this.drop(bookmark.id);
    }
  }

  private drop(id: string): void {
    this.bookmarks.update((current) => current?.filter((b) => b.id !== id) ?? null);
  }

  /**
   * Adds a bookmark that entered the view in its place, newest first. With more pages to load, only
   * one newer than the last shown belongs on the loaded ones; an older one comes with "Show more".
   */
  private insert(bookmark: BookmarkResponse): void {
    this.bookmarks.update((current) => {
      if (!current || current.some((b) => b.id === bookmark.id)) return current;
      const at = current.findIndex((b) => isNewer(bookmark, b));
      if (at < 0) return this.nextCursor() ? current : [...current, bookmark];
      return [...current.slice(0, at), bookmark, ...current.slice(at)];
    });
  }

  /** Records a local change; `id` names the bookmark it changed. */
  private noteChange(id?: string): void {
    this.changeSeq++;
    if (id) this.changedAt.set(id, this.changeSeq);
  }

  /**
   * Loads a bookmark changed or created elsewhere (e.g. its fetched title, or read on another
   * device): replaced, dropped when it left the view, or added when it entered it. Not while it is
   * being changed here, and dropped when it was changed here meanwhile.
   */
  private async refreshOne(id: string): Promise<void> {
    if (!this.bookmarks() || this.busy().has(id)) return;
    const seq = this.loadSeq;
    const changes = this.changeSeq;
    const current = (): boolean =>
      seq === this.loadSeq && (this.changedAt.get(id) ?? 0) <= changes;
    try {
      const bookmark = await this.service.get(id);
      if (!current()) return;
      const shown = this.bookmarks()?.find((b) => b.id === id);
      if (!shown) {
        if (this.belongs(bookmark)) this.insert(bookmark);
      } else if (shown.version < bookmark.version) {
        this.put(bookmark);
      }
    } catch (error) {
      if (current() && error instanceof ApiError && error.status === 404) this.drop(id);
    }
  }

  /**
   * Loads the first page again while the current bookmarks stay on screen. When that would lose
   * what is shown (more pages loaded) or a change is running, only `created` is loaded.
   */
  private async reloadQuietly(created?: string): Promise<void> {
    if (!this.bookmarks() || this.busy().size || this.nextCursor()) {
      if (created) void this.refreshOne(created);
      void this.loadTags();
      return;
    }
    const seq = this.loadSeq;
    const changes = this.changeSeq;
    try {
      const page = await this.service.list(this.view(), this.tag());
      if (seq !== this.loadSeq || changes !== this.changeSeq) return;
      this.bookmarks.set(page.items);
      this.nextCursor.set(page.nextCursor ?? null);
    } catch {
      // The list stays; the next action shows the problem.
    }
    void this.loadTags();
  }

  private async loadTags(): Promise<void> {
    try {
      this.tags.set(await this.service.tags());
    } catch {
      // The tag filter is a shortcut: without it, all bookmarks are still listed.
    }
  }

  /** Runs a change of `bookmark`; a bookmark gone meanwhile (404) is dropped from the list. */
  private async run(bookmark: BookmarkResponse, action: () => Promise<void>): Promise<void> {
    if (this.busy().has(bookmark.id)) return;
    this.busy.update((ids) => new Set(ids).add(bookmark.id));
    this.rowError.set(null);
    this.noteChange(bookmark.id);
    try {
      await action();
      this.noteChange(bookmark.id);
    } catch (error) {
      // Counted before the conflict reload starts.
      this.noteChange(bookmark.id);
      if (error instanceof ApiError && error.status === 404) {
        this.drop(bookmark.id);
      } else {
        this.rowError.set({ id: bookmark.id, message: message(error) });
        if (isConflict(error)) void this.reloadOne(bookmark.id);
      }
    } finally {
      this.busy.update((ids) => {
        const rest = new Set(ids);
        rest.delete(bookmark.id);
        return rest;
      });
    }
  }

  /** After a conflict: the current version, so the next try works. */
  private async reloadOne(id: string): Promise<void> {
    try {
      this.put(await this.service.get(id));
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) this.drop(id);
    }
  }
}

/** Whether `a` comes before `b` in the newest-first order of the server. */
function isNewer(a: BookmarkResponse, b: BookmarkResponse): boolean {
  const diff = Date.parse(a.createdAt) - Date.parse(b.createdAt);
  return diff > 0 || (diff === 0 && a.id > b.id);
}

function isConflict(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 409 || error.status === 412);
}

function message(error: unknown): string {
  return error instanceof ApiError
    ? error.userMessage
    : $localize`:@@bookmarks.error:Something went wrong.`;
}
