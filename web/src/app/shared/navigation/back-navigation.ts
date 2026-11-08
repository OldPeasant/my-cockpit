import { Location } from '@angular/common';
import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import {
  ActivatedRouteSnapshot,
  NavigationEnd,
  NavigationStart,
  PRIMARY_OUTLET,
  Router,
} from '@angular/router';

/** One page of this app in the browser history, as far as the app has seen it. */
interface Entry {
  /** The router navigation id stored in the history entry's state (`navigationId`). */
  readonly id: number;
  readonly url: string;
  /** What the page is called: its route title, or a name the page set (e.g. the project's). */
  readonly label: string | null;
}

interface History {
  readonly entries: readonly Entry[];
  /** The current entry, or -1 before the first navigation. */
  readonly index: number;
}

/**
 * Knows which pages of this app lie behind the current one in the browser history, so a detail
 * page's back button can go back to where the user came from (New with its filter, the dashboard, a
 * project, a search) instead of a fixed list.
 *
 * It mirrors the router's history entries: an imperative navigation pushes an entry (or replaces
 * the current one for `replaceUrl` or the same URL, as the router does), Back and Forward
 * (`popstate`) move to the entry whose `navigationId` they restore. An entry it never saw (a deep
 * link, a reload, a new tab) starts the history afresh: then nothing of the app is known to lie
 * behind, and {@link back} goes to the page's parent route.
 */
@Injectable({ providedIn: 'root' })
export class BackNavigation {
  private readonly router = inject(Router);
  private readonly location = inject(Location);

  private readonly history = signal<History>({ entries: [], index: -1 });
  /** The `popstate` navigation that runs: the `navigationId` it restores. */
  private restoring: { id: number; restoredId: number | null } | null = null;

  /** The app page before the current one in the browser history, if the app knows it. */
  readonly previous = computed<Entry | null>(() => {
    const { entries, index } = this.history();
    return index > 0 ? entries[index - 1] : null;
  });

  constructor() {
    const subscription = this.router.events.subscribe((event) => {
      if (event instanceof NavigationStart) {
        this.restoring =
          event.navigationTrigger === 'imperative'
            ? null
            : { id: event.id, restoredId: event.restoredState?.navigationId ?? null };
      } else if (event instanceof NavigationEnd) {
        this.record(event);
      }
    });
    inject(DestroyRef).onDestroy(() => subscription.unsubscribe());
  }

  /** Whether the previous history entry is a page of this app. */
  canGoBack(): boolean {
    return this.previous() !== null;
  }

  /**
   * Goes back in the browser history if the previous entry is a page of this app, else to
   * `fallback` (the page's parent route). `replaceUrl` replaces the current entry on the fallback
   * path (e.g. after the shown item was archived, so Back does not open it again).
   */
  async back(fallback: string, options: { replaceUrl?: boolean } = {}): Promise<void> {
    if (this.canGoBack()) {
      this.location.back();
      return;
    }
    await this.router.navigateByUrl(fallback, { replaceUrl: !!options.replaceUrl });
  }

  /**
   * Names the current page for the back buttons of the pages opened from it (e.g. the project's
   * title instead of "Project"). Only applies while the page at `url` is the current one, so a late
   * answer cannot rename another page.
   */
  setLabel(label: string, url: string = this.router.url): void {
    this.history.update(({ entries, index }) => {
      const current = entries[index];
      if (!current || current.url !== url || current.label === label) return { entries, index };
      const next = [...entries];
      next[index] = { ...current, label };
      return { entries: next, index };
    });
  }

  private record(event: NavigationEnd): void {
    const navigation = this.router.currentNavigation();
    if (navigation?.extras.skipLocationChange) return;
    const entry: Entry = {
      id: event.id,
      url: event.urlAfterRedirects,
      label: titleOf(this.router.routerState.snapshot.root),
    };
    const restoring = this.restoring?.id === event.id ? this.restoring : null;
    this.restoring = null;
    this.history.update(({ entries, index }) => {
      if (restoring) {
        // Back or Forward: the router rewrites the restored entry with this navigation's id.
        const found = entries.findIndex(
          (known) => known.id === restoring.restoredId && known.url === entry.url,
        );
        if (found < 0) return { entries: [entry], index: 0 };
        const next = [...entries];
        next[found] = { ...entry, label: entries[found].label ?? entry.label };
        return { entries: next, index: found };
      }
      const current = entries[index];
      if (current && (navigation?.extras.replaceUrl || current.url === entry.url)) {
        const next = entries.slice(0, index + 1);
        next[index] = entry;
        return { entries: next, index };
      }
      return { entries: [...entries.slice(0, index + 1), entry], index: index + 1 };
    });
  }
}

/** The title of the deepest primary route (`Route.title`, resolved by the router). */
function titleOf(root: ActivatedRouteSnapshot): string | null {
  let title: string | null = null;
  let route: ActivatedRouteSnapshot | undefined = root;
  while (route) {
    if (typeof route.title === 'string' && route.title) title = route.title;
    route = route.children.find((child) => child.outlet === PRIMARY_OUTLET);
  }
  return title;
}
