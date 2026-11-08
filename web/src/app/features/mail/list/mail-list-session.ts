import { inject, Injectable, signal } from '@angular/core';
import { BackNavigation } from '../../../shared/navigation';
import { MailListSnapshot } from './mail-list-store';
import { MailScope, MailService } from './mail.service';

/** The mail list a message was opened from, as the list page left it. */
export interface SavedMailList extends MailListSnapshot {
  /** The list's URL (with its search), as the router serializes it. */
  readonly url: string;
  /** How far the list was scrolled. */
  readonly scrollTop: number;
}

/** Where a message is in the list it was opened from. */
export interface MailListPosition {
  /** 0-based. */
  readonly index: number;
  /** The loaded items. */
  readonly total: number;
  /** Whether older items can be loaded after the last one. */
  readonly more: boolean;
  readonly previousId: string | null;
  /** The next loaded item; null at the end even when `more` can load one. */
  readonly nextId: string | null;
}

/**
 * The mail list the user left for a message, kept for the app's lifetime: going back to the list
 * shows it again (search results without searching again, at the same scroll position and cursor),
 * and the read view steps through it (previous/next, the next message after archiving). The read
 * view uses it only when the list is the page right behind it in the history, i.e. the message was
 * opened from that list.
 */
@Injectable({ providedIn: 'root' })
export class MailListSession {
  private readonly mail = inject(MailService);
  private readonly backNavigation = inject(BackNavigation);

  /** The list the user left last; null before the mail list was shown. */
  readonly saved = signal<SavedMailList | null>(null);
  private loadingMore: Promise<void> | null = null;

  save(list: SavedMailList | null): void {
    this.saved.set(list);
  }

  /** The saved list, if it is the one to show at `url` for `scope`. */
  restorable(url: string, scope: MailScope): SavedMailList | null {
    const saved = this.saved();
    return saved && saved.url === url && sameScope(saved.scope, scope) ? saved : null;
  }

  /**
   * Where message `id` is in the list it was opened from, or null when it was not opened from the
   * saved list (another page lies behind it, or the list does not show it). Reads signals: use it in
   * a `computed`.
   */
  position(id: string): MailListPosition | null {
    const saved = this.fromList();
    if (!saved) return null;
    const index = saved.items.findIndex((item) => item.id === id);
    if (index < 0) return null;
    return {
      index,
      total: saved.items.length,
      more: this.canLoadMore(saved),
      previousId: saved.items[index - 1]?.id ?? null,
      nextId: saved.items[index + 1]?.id ?? null,
    };
  }

  /**
   * The message before (`-1`) or after (`1`) `id` in the list it was opened from, loading the next
   * page of a folder list at its end; null at either end, when the next page fails to load, or when
   * the message was not opened from the list.
   */
  async step(id: string, direction: 1 | -1): Promise<string | null> {
    let position = this.position(id);
    if (!position) return null;
    if (direction === -1) return position.previousId;
    if (!position.nextId && position.more) {
      await this.loadMore();
      position = this.position(id);
    }
    return position?.nextId ?? null;
  }

  /**
   * The list shows message `id` as read and its cursor is on it, so returning to the list shows
   * where the user was.
   */
  visited(id: string): void {
    this.update((saved) => {
      if (!saved.items.some((item) => item.id === id)) return saved;
      return {
        ...saved,
        cursorId: id,
        items: saved.items.map((item) =>
          item.id === id && !item.seen
            ? { ...item, seen: true, threadUnread: Math.max(0, (item.threadUnread ?? 1) - 1) }
            : item,
        ),
      };
    });
  }

  /**
   * Takes message `id` out of the saved list (it was archived, deleted or marked as spam) and
   * returns the message to show instead: the next one in the list, else the one before it; null
   * when none is left or the message was not opened from the list.
   */
  remove(id: string): string | null {
    const position = this.position(id);
    const following = position ? (position.nextId ?? position.previousId) : null;
    this.update((saved) => {
      if (!saved.items.some((item) => item.id === id)) return saved;
      const cursorId =
        saved.cursorId === id ? (following ?? nextAfter(saved, id)) : saved.cursorId;
      return { ...saved, cursorId, items: saved.items.filter((item) => item.id !== id) };
    });
    return following;
  }

  /** The saved list, if the page behind the current one in the history is that list. */
  private fromList(): SavedMailList | null {
    const saved = this.saved();
    const previous = this.backNavigation.previous();
    return saved && previous?.url === saved.url ? saved : null;
  }

  private canLoadMore(saved: SavedMailList): boolean {
    return !saved.scope.search && saved.nextCursor !== null;
  }

  /** Appends the next page of the saved folder list (one request at a time). */
  private loadMore(): Promise<void> {
    this.loadingMore ??= this.fetchMore().finally(() => (this.loadingMore = null));
    return this.loadingMore;
  }

  private async fetchMore(): Promise<void> {
    const saved = this.saved();
    if (!saved || !saved.nextCursor || saved.scope.search) return;
    try {
      const page = await this.mail.list(saved.scope, saved.nextCursor);
      // The list was left again (or saved anew) meanwhile: the page belongs to the one before.
      if (this.saved()?.url !== saved.url || this.saved()?.nextCursor !== saved.nextCursor) return;
      this.update((current) => {
        const shown = new Set(current.items.map((item) => item.id));
        return {
          ...current,
          items: [...current.items, ...page.items.filter((item) => !shown.has(item.id))],
          nextCursor: page.nextCursor,
        };
      });
    } catch {
      // Best effort: "next" stays at the last loaded message; the list loads more when scrolled.
    }
  }

  private update(change: (saved: SavedMailList) => SavedMailList): void {
    const saved = this.saved();
    if (saved) this.saved.set(change(saved));
  }
}

/** Whether two list scopes show the same: the folder, the search, and the view of a folder list. */
export function sameScope(a: MailScope, b: MailScope): boolean {
  if (a.folderId !== b.folderId) return false;
  if (!a.search || !b.search) return !a.search && !b.search && a.threaded === b.threaded;
  return a.search.query === b.search.query && a.search.server === b.search.server;
}

/** The item after `id` in the saved list, else the one before it. */
function nextAfter(saved: SavedMailList, id: string): string | null {
  const index = saved.items.findIndex((item) => item.id === id);
  return saved.items[index + 1]?.id ?? saved.items[index - 1]?.id ?? null;
}
