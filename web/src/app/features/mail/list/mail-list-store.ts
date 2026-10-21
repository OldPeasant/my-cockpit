import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { debounceTime, filter, merge } from 'rxjs';
import { ChangeEventsService } from '../../../core/events';
import { ApiError, NotificationService } from '../../../core/http';
import {
  MAIL_MAX_PAGE_SIZE,
  MAIL_PAGE_SIZE,
  MailAction,
  MailScope,
  MailService,
  MailSummary,
} from './mail.service';

/** Change event type of a folder whose messages changed (server: `MailFolderService.FOLDER_EVENT`). */
export const MAIL_FOLDER_EVENT = 'MAIL_FOLDER';
/** Change event type of an added, changed or removed account. */
export const MAIL_ACCOUNT_EVENT = 'MAIL_ACCOUNT';
/** Bursts of change events (a sync touches several folders) cause one refetch. */
export const REFRESH_DEBOUNCE_MS = 300;

/** Actions after which a message is no longer in the shown folder. */
const REMOVING: ReadonlySet<MailAction> = new Set(['MOVE', 'ARCHIVE', 'DELETE', 'SPAM']);

/**
 * The shown message list (SPEC.md §7.3): pages loaded so far, the keyboard cursor, the selection
 * and the triage actions.
 *
 * - Actions are optimistic: the list changes at once, and items whose action failed are put back
 *   as they were (at their old place), with the server's message as an error.
 * - In the threaded view an item stands for its conversation: actions apply to every message of
 *   the conversation in the shown folder.
 * - Live: a `MAIL_FOLDER` change event for the shown folder (any folder for the unified Inbox), a
 *   `MAIL_ACCOUNT` event and every SSE reconnect refetch the loaded part of the list. A refetch
 *   that would overwrite a running action's optimistic state waits until the action is done.
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
  /** Counts the optimistic changes: a refetch that started before one is dropped and redone. */
  private mutationSeq = 0;
  private running = 0;
  private refreshQueued = false;

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
  async load(scope: MailScope): Promise<void> {
    const seq = ++this.loadSeq;
    this.scope.set(scope);
    this.items.set(null);
    this.nextCursor.set(null);
    this.loadError.set(null);
    this.moreError.set(null);
    this.selectedIds.set(new Set());
    this.cursorId.set(null);
    try {
      const page = await this.mail.list(scope);
      if (seq !== this.loadSeq) return;
      this.items.set(page.items);
      this.nextCursor.set(page.nextCursor);
      this.cursorId.set(page.items[0]?.id ?? null);
    } catch (error) {
      if (seq === this.loadSeq) this.loadError.set(errorMessage(error));
    }
  }

  reload(): Promise<void> {
    return this.load(this.scope());
  }

  /** Appends the next page (infinite scroll). */
  async loadMore(): Promise<void> {
    const cursor = this.nextCursor();
    if (!cursor || this.loadingMore() || this.items() === null) return;
    const seq = this.loadSeq;
    this.loadingMore.set(true);
    this.moreError.set(null);
    try {
      const page = await this.mail.list(this.scope(), cursor);
      if (seq !== this.loadSeq) return;
      const shown = new Set((this.items() ?? []).map((item) => item.id));
      this.items.update((items) => [
        ...(items ?? []),
        ...page.items.filter((item) => !shown.has(item.id)),
      ]);
      this.nextCursor.set(page.nextCursor);
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
    if (this.items() === null) return;
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
   * Applies an action to {@link targets}. Returns false when nothing was targeted or something
   * failed (the failed items are back in the list, and the error has been shown).
   */
  async apply(action: MailAction, targetFolderId?: string): Promise<boolean> {
    const targets = this.targets();
    if (!targets.length) return false;
    this.running++;
    this.mutationSeq++;
    const seq = this.loadSeq;
    const snapshot = (this.items() ?? []).slice();
    const targetIds = new Set(targets.map((item) => item.id));
    this.applyOptimistic(action, targetIds);
    let failed: MailSummary[] = targets;
    let detail: string | null;
    try {
      const idsByItem = await this.messageIds(targets);
      const results = await this.mail.apply(action, [...new Set(idsByItem.flat())], targetFolderId);
      const failedIds = new Set(results.filter((result) => !result.ok).map((result) => result.id));
      detail = results.find((result) => !result.ok)?.detail ?? null;
      failed = targets.filter((_, index) => idsByItem[index].some((id) => failedIds.has(id)));
    } catch (error) {
      detail = errorMessage(error);
    } finally {
      this.running--;
    }
    if (failed.length && seq === this.loadSeq) {
      this.mutationSeq++;
      this.rollback(snapshot, failed);
      const reason = detail ?? fallbackMessage();
      this.notifications.error(
        $localize`:@@mail.list.actionFailed:Not every message could be changed: ${reason}:reason:`,
      );
    }
    this.runQueuedRefresh();
    return failed.length === 0;
  }

  /** The message ids behind each item: the item itself, or its conversation in the shown folder. */
  private async messageIds(items: MailSummary[]): Promise<string[][]> {
    if (!this.scope().threaded) return items.map((item) => [item.id]);
    return Promise.all(
      items.map(async (item) => {
        if ((item.threadSize ?? 1) <= 1) return [item.id];
        const thread = await this.mail.thread(item.id);
        const ids = thread
          .filter((message) => message.folderId === item.folderId)
          .map((message) => message.id);
        return ids.includes(item.id) ? ids : [item.id, ...ids];
      }),
    );
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

  /** Puts the failed items back as they were, at their old place among the others. */
  private rollback(snapshot: MailSummary[], failed: MailSummary[]): void {
    const failedIds = new Set(failed.map((item) => item.id));
    const current = this.items() ?? [];
    const present = new Set(current.map((item) => item.id));
    const restored: MailSummary[] = [];
    for (const old of snapshot) {
      if (failedIds.has(old.id)) restored.push(old);
      else if (present.has(old.id)) restored.push(current.find((item) => item.id === old.id)!);
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

function errorMessage(error: unknown): string {
  return error instanceof ApiError ? error.userMessage : fallbackMessage();
}

function fallbackMessage(): string {
  return $localize`:@@mail.list.error:Something went wrong.`;
}
