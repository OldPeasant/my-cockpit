import { Location } from '@angular/common';
import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import {
  ActivatedRouteSnapshot,
  NavigationCancel,
  NavigationCancellationCode,
  NavigationEnd,
  NavigationError,
  NavigationSkipped,
  NavigationStart,
  PRIMARY_OUTLET,
  Router,
} from '@angular/router';
import { filter, firstValueFrom, map, of, timeout } from 'rxjs';

/** How many page names {@link BackNavigation.setLabel} keeps. */
const MAX_LABELS = 100;

/** How long {@link BackNavigation.back} waits at most for the Back navigation to finish. */
const BACK_TIMEOUT_MS = 1500;

/** One page of this app in the browser history, as far as the app has seen it. */
interface Entry {
  /** The router navigation id stored in the history entry's state (`navigationId`). */
  readonly id: number;
  readonly url: string;
  /** What the page is called: its route title (a page may name itself, see `setLabel`). */
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
  /** Names pages set for themselves, by URL path (most recent last). */
  private readonly labels = signal<ReadonlyMap<string, string>>(new Map());
  /** The `popstate` navigation that runs: the `navigationId` it restores. */
  private restoring: { id: number; restoredId: number | null } | null = null;
  /** The Back that {@link back} started and that has not finished yet. */
  private goingBack: Promise<void> | null = null;

  /** The app page before the current one in the browser history, if the app knows it. */
  readonly previous = computed<Entry | null>(() => {
    const { entries, index } = this.history();
    if (index <= 0) return null;
    const entry = entries[index - 1];
    return { ...entry, label: this.labels().get(pathOf(entry.url)) ?? entry.label };
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
      } else if (
        event instanceof NavigationCancel &&
        event.code === NavigationCancellationCode.Redirect
      ) {
        this.followRedirect(event.id);
      } else if (
        event instanceof NavigationError ||
        (event instanceof NavigationCancel && !isRedirecting(event))
      ) {
        this.restoreCancelled(event.id);
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
   *
   * Resolves once the resulting navigation has finished, including one a guard redirected it to
   * (or after a short safety timeout, should the browser never report the Back), so a caller can
   * stay busy until its page is left. A second call while that Back still runs (a double click)
   * does not go back once more.
   */
  async back(fallback: string, options: { replaceUrl?: boolean } = {}): Promise<void> {
    if (this.goingBack) return this.goingBack;
    if (this.canGoBack()) {
      const finished = firstValueFrom(
        this.router.events.pipe(
          filter(
            (event) =>
              event instanceof NavigationEnd ||
              // A redirect or a newer navigation takes over: wait for that one.
              (event instanceof NavigationCancel && !isRedirecting(event)) ||
              event instanceof NavigationError ||
              event instanceof NavigationSkipped,
          ),
          map(() => undefined),
          timeout({ first: BACK_TIMEOUT_MS, with: () => of(undefined) }),
        ),
      );
      this.goingBack = finished;
      this.location.back();
      try {
        await finished;
      } finally {
        this.goingBack = null;
      }
      return;
    }
    await this.router.navigateByUrl(fallback, { replaceUrl: !!options.replaceUrl });
  }

  /**
   * Names the page at `url` for the back buttons of the pages opened from it (e.g. the project's
   * title instead of "Project"). Query and fragment do not matter: `/new?filter=mail` is New.
   * Setting a name again makes it the most recent one.
   */
  setLabel(url: string, label: string): void {
    const path = pathOf(url);
    this.labels.update((labels) => {
      // Already the most recent name: nothing changes.
      if (labels.get(path) === label && [...labels.keys()].at(-1) === path) return labels;
      const next = new Map(labels);
      next.delete(path);
      next.set(path, label);
      // Keeps the most recent names only.
      while (next.size > MAX_LABELS) next.delete(next.keys().next().value!);
      return next;
    });
  }

  /**
   * A Back or Forward that was cancelled (e.g. a page with unsaved changes refused to be left) or
   * failed: the browser stays on the entry it moved to, and the router (`canceledNavigationResolution`
   * 'replace') rewrites that entry with the current page.
   */
  private restoreCancelled(id: number): void {
    const restoring = this.restoring?.id === id ? this.restoring : null;
    if (!restoring) return;
    this.restoring = null;
    this.history.update(({ entries, index }) => {
      const current = entries[index];
      if (!current) return { entries, index };
      const found = entries.findIndex((known) => known.id === restoring.restoredId);
      if (found < 0) return { entries: [current], index: 0 };
      const next = [...entries];
      next[found] = current;
      return { entries: next, index: found };
    });
  }

  /**
   * A Back or Forward that a guard redirected (e.g. to the login): the browser stays on the entry
   * it moved to, and the router's follow-up navigation replaces that entry (`replaceUrl`), which
   * {@link record} then does on the mirror too.
   */
  private followRedirect(id: number): void {
    const restoring = this.restoring?.id === id ? this.restoring : null;
    if (!restoring) return;
    this.restoring = null;
    this.history.update(({ entries, index }) => {
      const current = entries[index];
      if (!current) return { entries, index };
      const found = entries.findIndex((known) => known.id === restoring.restoredId);
      if (found < 0) return { entries: [current], index: 0 };
      return { entries, index: found };
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
        next[found] = entry;
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

/** Whether another navigation takes over from the cancelled one (a guard's redirect, a newer one). */
function isRedirecting(event: NavigationCancel): boolean {
  return (
    event.code === NavigationCancellationCode.Redirect ||
    event.code === NavigationCancellationCode.SupersededByNewNavigation
  );
}

/** The path of a URL, without query and fragment. */
function pathOf(url: string): string {
  return url.split(/[?#]/, 1)[0];
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
