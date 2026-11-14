import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { debounceTime, filter, merge } from 'rxjs';
import { ChangeEventsService } from '../../../core/events';
import { ApiError, NotificationService } from '../../../core/http';
import {
  MAIL_MAX_ACTION_IDS,
  MAIL_MAX_PAGE_SIZE,
  MAIL_PAGE_SIZE,
  MailAction,
  MailPage,
  MailScope,
  MailService,
  MailSummary,
} from './mail.service';
import { MailToTaskResultResponse } from '../../../api/generated';

/** Change event type of a folder whose messages changed (server: `MailFolderService.FOLDER_EVENT`). */
export const MAIL_FOLDER_EVENT = 'MAIL_FOLDER';
/** Change event type of an added, changed or removed account. */
export const MAIL_ACCOUNT_EVENT = 'MAIL_ACCOUNT';
/** Bursts of change events (a sync touches several folders) cause one refetch. */
export const REFRESH_DEBOUNCE_MS = 300;
/** At most this many conversations of an action are looked up at the same time. */
export const THREAD_LOOKUP_CONCURRENCY = 6;

/** What a search found beyond its items. */
export interface MailSearchExtras {
  hasMore: boolean;
  failedAccountIds: string[];
}

/** A loaded list as it is shown, to show it again without loading it (see `MailListSession`). */
export interface MailListSnapshot {
  readonly scope: MailScope;
  readonly items: readonly MailSummary[];
  readonly nextCursor: string | null;
  readonly cursorId: string | null;
  readonly searchResult: MailSearchExtras | null;
}

/** Actions after which a message is no longer in the shown folder. */
const REMOVING: ReadonlySet<MailAction> = new Set(['MOVE', 'ARCHIVE', 'DELETE', 'SPAM']);
/** The fields of an item each flag action changes (what its rollback puts back). */
const CHANGED_FIELDS: Partial<Record<MailAction, readonly (keyof MailSummary)[]>> = {
  READ: ['seen', 'threadUnread'],
  UNREAD: ['seen', 'threadUnread'],
  FLAG: ['flagged'],
  UNFLAG: ['flagged'],
};

/**
 * The shown message list (SPEC.md §7.3): pages loaded so far, the keyboard cursor, the selection
 * and the triage actions.
 *
 * - Actions are optimistic: the list changes at once. When an action fails for an item, only what
 *   that action changed is put back (a removed item at its old place, else its changed flags, and
 *   only where no later action changed them again), the server's message is shown as an error,
 *   and the list is refetched to settle what overlapping actions did.
 * - In the threaded view an item stands for its conversation: actions apply to every message of
 *   the conversation in the shown folder.
 * - Live: a `MAIL_FOLDER` change event for the shown folder (any folder for the unified Inbox), a
 *   `MAIL_ACCOUNT` event and every SSE reconnect refetch the loaded part of the list. A refetch
 *   that would overwrite a running action's optimistic state waits until the action is done.
 * - Search: a scope with `search` lists the matching messages, flat and in one page. Search results
 *   are not refetched live (a search on the server is slow and loads mail itself); an action still
 *   changes them optimistically. Repeating a search on the server keeps the cached results shown
 *   until the server's answer arrives.
 */
@Injectable()
export class MailListStore {
  private readonly mail = inject(MailService);
  private readonly notifications = inject(NotificationService);

  readonly scope = signal<MailScope>({ folderId: null, threaded: false });
  /** The loaded items, newest first; null while the first page loads. */
  readonly items = signal<MailSummary[] | null>(null);
  readonly nextCursor = signal<string | null>(null);
  readonly loadError = signal<string | null>(null);
  readonly loadingMore = signal(false);
  readonly moreError = signal<string | null>(null);
  /** The item the keyboard works on (`j`/`k`). */
  readonly cursorId = signal<string | null>(null);
  readonly selectedIds = signal<ReadonlySet<string>>(new Set());
  /** What the shown search found beyond its items; null without a search or while it loads. */
  readonly searchResult = signal<MailSearchExtras | null>(null);
  /** Older messages are being loaded from the mail server ("load older from server"). */
  readonly loadingOlder = signal(false);
  /** Why loading older messages from the server failed; null otherwise. */
  readonly olderError = signal<string | null>(null);
  /** Loading older messages from the server found nothing older; not a failure. */
  readonly olderNote = signal<string | null>(null);
  /** A search on the server runs while the cached results of the same query are shown. */
  readonly searchingServer = signal(false);

  /** The selected items that are loaded, in list order. */
  readonly selection = computed(() => {
    const selected = this.selectedIds();
    return (this.items() ?? []).filter((item) => selected.has(item.id));
  });
  readonly cursorItem = computed(
    () => (this.items() ?? []).find((item) => item.id === this.cursorId()) ?? null,
  );
  /** What an action applies to: the selection, else the item under the cursor. */
  readonly targets = computed(() => {
    const selection = this.selection();
    if (selection.length) return selection;
    const cursor = this.cursorItem();
    return cursor ? [cursor] : [];
  });
  readonly allSelected = computed(() => {
    const items = this.items() ?? [];
    return items.length > 0 && this.selection().length === items.length;
  });

  /** Counts the loads: a page that arrives for an earlier load is ignored. */
  private loadSeq = 0;
  /**
   * Counts the changes of the loaded list (optimistic changes, rollbacks and appended pages): a
   * refetch that started before one is dropped and redone.
   */
  private mutationSeq = 0;
  /**
   * Counts the replacements of the loaded list (a load or a refetch): a next page requested for
   * the list before is dropped, since its cursor no longer follows the list's last item.
   */
  private listGeneration = 0;
  private running = 0;
  private refreshQueued = false;
  /** Called once no action runs any more (see {@link actionsSettled}). */
  private settledWaiters: (() => void)[] = [];

  constructor() {
    const events = inject(ChangeEventsService);
    merge(
      events.events$(MAIL_FOLDER_EVENT).pipe(filter((event) => this.concerns(event.id))),
      events.events$(MAIL_ACCOUNT_EVENT),
      events.resync$,
    )
      .pipe(debounceTime(REFRESH_DEBOUNCE_MS), takeUntilDestroyed(inject(DestroyRef)))
      .subscribe(() => void this.refresh());
  }

  /** Whether a change event of `folderId` concerns the shown list. */
  concerns(folderId: string): boolean {
    const shown = this.scope().folderId;
    return shown === null || shown === folderId;
  }

  /** Shows a scope from its first page, with nothing selected. */
  async load(requested: MailScope): Promise<void> {
    const scope: MailScope = requested.search ? { ...requested, threaded: false } : requested;
    const seq = ++this.loadSeq;
    this.listGeneration++;
    const keep = this.keepsResults(this.scope(), scope);
    this.scope.set(scope);
    this.nextCursor.set(null);
    this.loadError.set(null);
    this.moreError.set(null);
    this.resetOlder();
    this.selectedIds.set(new Set());
    this.searchingServer.set(keep);
    if (!keep) {
      this.items.set(null);
      this.cursorId.set(null);
      this.searchResult.set(null);
    }
    try {
      const page = await this.firstPage(scope);
      if (seq !== this.loadSeq) return;
      this.items.set(page.items);
      this.nextCursor.set(page.nextCursor);
      const cursor = this.cursorId();
      if (!cursor || !page.items.some((item) => item.id === cursor))
        this.cursorId.set(page.items[0]?.id ?? null);
    } catch (error) {
      if (seq === this.loadSeq) {
        this.items.set(null);
        this.searchResult.set(null);
        this.loadError.set(errorMessage(error));
      }
    } finally {
      if (seq === this.loadSeq) this.searchingServer.set(false);
    }
  }

  /**
   * Whether loading `next` after `shown` keeps the shown items until it answers: a search repeated
   * on the server, whose cached results are already listed.
   */
  private keepsResults(shown: MailScope, next: MailScope): boolean {
    return (
      !!next.search?.server &&
      !!shown.search &&
      shown.search.query === next.search.query &&
      shown.folderId === next.folderId &&
      this.items() !== null &&
      this.loadError() === null
    );
  }

  /** The first page of a scope: a page of the list, or a search's results (and its extras). */
  private async firstPage(scope: MailScope): Promise<MailPage> {
    const search = scope.search;
    if (!search) return this.mail.list(scope);
    const result = await this.mail.search(search.query, scope.folderId, search.server);
    if (this.scope() === scope)
      this.searchResult.set({ hasMore: result.hasMore, failedAccountIds: result.failedAccountIds });
    return { items: result.items, nextCursor: null };
  }

  reload(): Promise<void> {
    return this.load(this.scope());
  }

  /** The shown list, or null while it loads or after it failed. */
  snapshot(): MailListSnapshot | null {
    const items = this.items();
    if (items === null || this.loadError() !== null || this.searchingServer()) return null;
    return {
      scope: this.scope(),
      items,
      nextCursor: this.nextCursor(),
      cursorId: this.cursorId(),
      searchResult: this.searchResult(),
    };
  }

  /**
   * Shows a list as it was taken by {@link snapshot}, without loading it, with nothing selected. A
   * load or page still on its way is dropped.
   */
  restore(snapshot: MailListSnapshot): void {
    this.loadSeq++;
    this.listGeneration++;
    this.mutationSeq++;
    this.scope.set(snapshot.scope);
    this.items.set([...snapshot.items]);
    this.nextCursor.set(snapshot.nextCursor);
    this.cursorId.set(snapshot.cursorId);
    this.searchResult.set(snapshot.searchResult);
    this.loadError.set(null);
    this.moreError.set(null);
    this.resetOlder();
    this.searchingServer.set(false);
    this.selectedIds.set(new Set());
  }

  /** Forgets a load-older of the list before: its answer is dropped, so its progress ends here. */
  private resetOlder(): void {
    this.loadingOlder.set(false);
    this.olderError.set(null);
    this.olderNote.set(null);
  }

  /** Whether the end of the shown list is the end of a folder's cache ("load older from server"). */
  readonly canLoadOlder = computed(
    () =>
      this.scope().folderId !== null &&
      !this.scope().search &&
      this.items() !== null &&
      this.loadError() === null &&
      this.nextCursor() === null,
  );

  /**
   * Loads messages older than the sync window of the shown folder from the mail server (SPEC.md
   * §7.2), then shows the newly cached ones at the end of the list. A failure is shown in
   * {@link olderError}, nothing older on the server in {@link olderNote}; the list stays as it is.
   */
  async loadOlder(): Promise<void> {
    if (!this.canLoadOlder() || this.loadingOlder()) return;
    const scope = this.scope();
    const folderId = scope.folderId!;
    const seq = this.loadSeq;
    this.loadingOlder.set(true);
    this.olderError.set(null);
    this.olderNote.set(null);
    try {
      const result = await this.mail.loadOlder(folderId);
      if (seq !== this.loadSeq) return;
      if (result.loaded === 0) {
        this.olderNote.set(
          $localize`:@@mail.list.nothingOlder:There are no older messages on the server.`,
        );
        return;
      }
      await this.showUpTo(seq, scope, (this.items() ?? []).length + result.loaded);
    } catch (error) {
      if (seq === this.loadSeq) this.olderError.set(errorMessage(error));
    } finally {
      if (seq === this.loadSeq) this.loadingOlder.set(false);
    }
  }

  /**
   * Reads the first `wanted` items of the shown list and shows them, keeping the cursor and the
   * selection on the items still there. Like {@link refresh}, it does not overwrite a running
   * action's optimistic state: it waits until the actions are done and reads the list again when
   * one changed it while it was read.
   */
  private async showUpTo(seq: number, scope: MailScope, wanted: number): Promise<void> {
    for (;;) {
      if (this.running > 0) {
        await this.actionsSettled();
        if (seq !== this.loadSeq) return;
      }
      const mutation = this.mutationSeq;
      const page = await this.fetchUpTo(scope, wanted);
      if (seq !== this.loadSeq) return;
      if (mutation !== this.mutationSeq || this.running > 0) continue;
      const ids = new Set(page.items.map((item) => item.id));
      this.selectedIds.update((selected) => new Set([...selected].filter((id) => ids.has(id))));
      this.items.set(page.items);
      this.nextCursor.set(page.nextCursor);
      this.listGeneration++;
      this.mutationSeq++;
      const cursor = this.cursorId();
      if (!cursor || !ids.has(cursor)) this.cursorId.set(page.items[0]?.id ?? null);
      return;
    }
  }

  /** Resolves once no action runs any more (after a failed action's rollback). */
  private actionsSettled(): Promise<void> {
    return new Promise((resolve) => this.settledWaiters.push(resolve));
  }

  /** The first `wanted` items of a scope (fewer if it has fewer), in pages of the server's limit. */
  private async fetchUpTo(scope: MailScope, wanted: number): Promise<MailPage> {
    const items: MailSummary[] = [];
    let cursor: string | undefined;
    for (;;) {
      const limit = Math.min(MAIL_MAX_PAGE_SIZE, Math.max(1, wanted - items.length));
      const page = await this.mail.list(scope, cursor, limit);
      const seen = new Set(items.map((item) => item.id));
      items.push(...page.items.filter((item) => !seen.has(item.id)));
      if (!page.nextCursor || items.length >= wanted || !page.items.length)
        return { items, nextCursor: page.nextCursor };
      cursor = page.nextCursor;
    }
  }

  /** Appends the next page (infinite scroll). */
  async loadMore(): Promise<void> {
    const cursor = this.nextCursor();
    if (!cursor || this.loadingMore() || this.items() === null) return;
    const seq = this.loadSeq;
    const generation = this.listGeneration;
    this.loadingMore.set(true);
    this.moreError.set(null);
    try {
      const page = await this.mail.list(this.scope(), cursor);
      if (seq !== this.loadSeq) return;
      // A refetch replaced the list meanwhile: the page would leave a gap after its new end. The
      // next scroll loads more from the refetch's cursor.
      if (generation !== this.listGeneration) return;
      const shown = new Set((this.items() ?? []).map((item) => item.id));
      this.items.update((items) => [
        ...(items ?? []),
        ...page.items.filter((item) => !shown.has(item.id)),
      ]);
      this.nextCursor.set(page.nextCursor);
      // A refetch running now was sized for the shorter list: it must not shrink it back.
      this.mutationSeq++;
    } catch (error) {
      if (seq === this.loadSeq) this.moreError.set(errorMessage(error));
    } finally {
      this.loadingMore.set(false);
    }
  }

  /**
   * Refetches as many items as are loaded (up to the server's page limit) and keeps the cursor and
   * the selection on the items still there. Waits while an action runs.
   */
  async refresh(): Promise<void> {
    if (this.items() === null || this.scope().search) return;
    if (this.running > 0) {
      this.refreshQueued = true;
      return;
    }
    const seq = this.loadSeq;
    const mutation = this.mutationSeq;
    const limit = Math.min(MAIL_MAX_PAGE_SIZE, Math.max(MAIL_PAGE_SIZE, this.items()!.length));
    try {
      const page = await this.mail.list(this.scope(), undefined, limit);
      if (seq !== this.loadSeq) return;
      if (mutation !== this.mutationSeq || this.running > 0) {
        this.refreshQueued = true;
        this.runQueuedRefresh();
        return;
      }
      const ids = new Set(page.items.map((item) => item.id));
      const oldItems = this.items() ?? [];
      const cursor = this.cursorId();
      if (cursor && !ids.has(cursor))
        this.cursorId.set(nextAfterRemoval(oldItems, page.items, cursor));
      this.selectedIds.update((selected) => new Set([...selected].filter((id) => ids.has(id))));
      this.items.set(page.items);
      this.nextCursor.set(page.nextCursor);
      this.listGeneration++;
    } catch {
      // A live update is best effort: the list stays as it is until the next event or reload.
    }
  }

  moveCursor(step: 1 | -1): void {
    const items = this.items() ?? [];
    if (!items.length) return;
    const index = items.findIndex((item) => item.id === this.cursorId());
    const next = index < 0 ? 0 : Math.min(items.length - 1, Math.max(0, index + step));
    this.cursorId.set(items[next].id);
    if (step === 1 && next >= items.length - 1) void this.loadMore();
  }

  setCursor(id: string): void {
    this.cursorId.set(id);
  }

  toggleSelected(id: string): void {
    this.selectedIds.update((selected) => {
      const next = new Set(selected);
      if (!next.delete(id)) next.add(id);
      return next;
    });
  }

  /** Selects every loaded item, or nothing when all are selected already. */
  toggleAll(): void {
    this.selectedIds.set(
      this.allSelected() ? new Set() : new Set((this.items() ?? []).map((item) => item.id)),
    );
  }

  clearSelection(): void {
    this.selectedIds.set(new Set());
  }

  /**
   * Applies an action to {@link targets}, or to the items `targetIds` names (those still loaded),
   * e.g. the targets as they were when a dialog for the action opened. Returns false when nothing
   * was targeted or something failed (the failed items are back in the list, and the error has
   * been shown).
   */
  async apply(
    action: MailAction,
    targetFolderId?: string,
    targetIds?: readonly string[],
  ): Promise<boolean> {
    const targets = targetIds ? this.loaded(targetIds) : this.targets();
    if (!targets.length) return false;
    this.running++;
    this.mutationSeq++;
    const seq = this.loadSeq;
    const snapshot = (this.items() ?? []).slice();
    this.applyOptimistic(action, new Set(targets.map((item) => item.id)));
    let failed: MailSummary[] = targets;
    let detail: string | null;
    try {
      const idsByItem = await this.messageIds(targets);
      const outcome = await this.send(action, [...new Set(idsByItem.flat())], targetFolderId);
      detail = outcome.detail;
      failed = targets.filter((_, index) => idsByItem[index].some((id) => outcome.failed.has(id)));
    } catch (error) {
      detail = errorMessage(error);
    } finally {
      this.running--;
    }
    if (failed.length && seq === this.loadSeq) {
      this.mutationSeq++;
      this.rollback(action, snapshot, failed);
      const reason = detail ?? fallbackMessage();
      this.notifications.error(
        $localize`:@@mail.list.actionFailed:Not every message could be changed: ${reason}:reason:`,
      );
      // Overlapping actions on the same items: the server's state settles what the view shows.
      this.refreshQueued = true;
    }
    this.settle();
    return failed.length === 0;
  }

  /**
   * "→ Next action & archive" on the targets (the selection, else the item under the cursor): a
   * Next action per message, then the messages move to the Archive. The archived ones leave the
   * list; one whose archive failed stays (its task is kept). Returns each target with its result,
   * in list order, or `null` without targets. A failed request (no task was created) throws.
   */
  async toNextActions(): Promise<{ item: MailSummary; result: MailToTaskResultResponse }[] | null> {
    const targets = this.targets();
    if (!targets.length) return null;
    this.running++;
    this.mutationSeq++;
    try {
      const results = await this.mail.toNextActions(targets.map((item) => item.id));
      const byId = new Map(results.map((result) => [result.id, result]));
      const archived = new Set(
        targets.filter((item) => byId.get(item.id)?.archived).map((item) => item.id),
      );
      if (archived.size) {
        this.mutationSeq++;
        this.applyOptimistic('ARCHIVE', archived);
      }
      return targets.flatMap((item) => {
        const result = byId.get(item.id);
        return result ? [{ item, result }] : [];
      });
    } finally {
      this.running--;
      this.settle();
    }
  }

  /** Wakes those waiting for the actions to finish, then runs a refresh that waited for them. */
  private settle(): void {
    if (this.running === 0) {
      const waiters = this.settledWaiters;
      this.settledWaiters = [];
      waiters.forEach((resolve) => resolve());
    }
    this.runQueuedRefresh();
  }

  /** The loaded items among `ids`, in list order. */
  private loaded(ids: readonly string[]): MailSummary[] {
    const wanted = new Set(ids);
    return (this.items() ?? []).filter((item) => wanted.has(item.id));
  }

  /**
   * Sends the action in requests of at most {@link MAIL_MAX_ACTION_IDS} ids (the server's limit).
   * Returns the ids that failed (every id of a request that failed as a whole) and the first
   * failure's message.
   */
  private async send(
    action: MailAction,
    ids: string[],
    targetFolderId?: string,
  ): Promise<{ failed: ReadonlySet<string>; detail: string | null }> {
    const chunks: string[][] = [];
    for (let i = 0; i < ids.length; i += MAIL_MAX_ACTION_IDS) {
      chunks.push(ids.slice(i, i + MAIL_MAX_ACTION_IDS));
    }
    const outcomes = await Promise.all(
      chunks.map(async (chunk) => {
        try {
          const results = await this.mail.apply(action, chunk, targetFolderId);
          const failures = results.filter((result) => !result.ok);
          return { failed: failures.map((result) => result.id), detail: failures[0]?.detail };
        } catch (error) {
          return { failed: chunk, detail: errorMessage(error) };
        }
      }),
    );
    const failed = new Set(outcomes.flatMap((outcome) => outcome.failed));
    const detail = outcomes.find((outcome) => outcome.failed.length)?.detail ?? null;
    return { failed, detail };
  }

  /**
   * The message ids behind each item: the item itself, or its conversation in the shown folder.
   * At most {@link THREAD_LOOKUP_CONCURRENCY} conversations are looked up at a time; the first
   * failed lookup fails the whole action.
   */
  private async messageIds(items: MailSummary[]): Promise<string[][]> {
    if (!this.scope().threaded) return items.map((item) => [item.id]);
    return mapWithLimit(items, THREAD_LOOKUP_CONCURRENCY, async (item) => {
      if ((item.threadSize ?? 1) <= 1) return [item.id];
      const thread = await this.mail.thread(item.id);
      const ids = thread
        .filter((message) => message.folderId === item.folderId)
        .map((message) => message.id);
      return ids.includes(item.id) ? ids : [item.id, ...ids];
    });
  }

  private applyOptimistic(action: MailAction, ids: ReadonlySet<string>): void {
    const items = this.items() ?? [];
    if (REMOVING.has(action)) {
      const cursor = this.cursorId();
      const rest = items.filter((item) => !ids.has(item.id));
      if (cursor && ids.has(cursor)) this.cursorId.set(nextAfterRemoval(items, rest, cursor));
      this.items.set(rest);
      this.selectedIds.update((selected) => new Set([...selected].filter((id) => !ids.has(id))));
      return;
    }
    this.items.set(items.map((item) => (ids.has(item.id) ? withAction(item, action) : item)));
  }

  /**
   * Undoes what `action` changed on the failed items, and nothing else: a removing action puts the
   * items that are still gone back at their old place (as they were before it); a flag action
   * puts back the fields it changed on the items still shown, unless a later action changed them
   * again. Items removed meanwhile by another action stay removed.
   */
  private rollback(action: MailAction, snapshot: MailSummary[], failed: MailSummary[]): void {
    const failedById = new Map(failed.map((item) => [item.id, item]));
    const current = this.items() ?? [];
    if (!REMOVING.has(action)) {
      const fields = CHANGED_FIELDS[action] ?? [];
      this.items.set(
        current.map((item) => {
          const old = failedById.get(item.id);
          return old ? withFieldsRestored(item, old, withAction(old, action), fields) : item;
        }),
      );
      return;
    }
    const currentById = new Map(current.map((item) => [item.id, item]));
    const restored: MailSummary[] = [];
    for (const old of snapshot) {
      const now = currentById.get(old.id);
      if (now) restored.push(now);
      else if (failedById.has(old.id)) restored.push(old);
    }
    // Items that arrived after the snapshot (a refetch) stay, after the restored ones.
    const known = new Set(restored.map((item) => item.id));
    this.items.set([...restored, ...current.filter((item) => !known.has(item.id))]);
  }

  private runQueuedRefresh(): void {
    if (this.refreshQueued && this.running === 0) {
      this.refreshQueued = false;
      void this.refresh();
    }
  }
}

/** The item as it is after a flag action. */
export function withAction(item: MailSummary, action: MailAction): MailSummary {
  switch (action) {
    case 'READ':
      return { ...item, seen: true, threadUnread: 0 };
    case 'UNREAD':
      return { ...item, seen: false, threadUnread: Math.max(1, item.threadUnread ?? 0) };
    case 'FLAG':
      return { ...item, flagged: true };
    case 'UNFLAG':
      return { ...item, flagged: false };
    default:
      return item;
  }
}

/**
 * `item` with each of `fields` back at its value in `before`, where it still has the value the
 * failed action set (`after`): a later action's change of the field is kept.
 */
function withFieldsRestored(
  item: MailSummary,
  before: MailSummary,
  after: MailSummary,
  fields: readonly (keyof MailSummary)[],
): MailSummary {
  const changed = fields.filter((field) => item[field] === after[field]);
  if (!changed.length) return item;
  const restored: MailSummary = { ...item };
  for (const field of changed) Object.assign(restored, { [field]: before[field] });
  return restored;
}

/**
 * Where the cursor goes when its item leaves the list: to the next remaining item after it (the
 * next older mail, as in Gmail), else the one before it, else nowhere.
 */
function nextAfterRemoval(
  before: MailSummary[],
  after: MailSummary[],
  removed: string,
): string | null {
  const remaining = new Set(after.map((item) => item.id));
  const index = before.findIndex((item) => item.id === removed);
  for (let i = index + 1; i < before.length; i++) {
    if (remaining.has(before[i].id)) return before[i].id;
  }
  for (let i = index - 1; i >= 0; i--) {
    if (remaining.has(before[i].id)) return before[i].id;
  }
  return after[0]?.id ?? null;
}

/**
 * `fn` applied to every item, with at most `limit` calls running at a time; results in item
 * order. Rejects with the first failure and starts no further calls after it.
 */
async function mapWithLimit<T, R>(
  items: readonly T[],
  limit: number,
  fn: (item: T) => Promise<R>,
): Promise<R[]> {
  const results: R[] = new Array<R>(items.length);
  let next = 0;
  let failed = false;
  const worker = async (): Promise<void> => {
    while (!failed && next < items.length) {
      const index = next++;
      try {
        results[index] = await fn(items[index]);
      } catch (error) {
        failed = true;
        throw error;
      }
    }
  };
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
  return results;
}

function errorMessage(error: unknown): string {
  return error instanceof ApiError ? error.userMessage : fallbackMessage();
}

function fallbackMessage(): string {
  return $localize`:@@mail.list.error:Something went wrong.`;
}
