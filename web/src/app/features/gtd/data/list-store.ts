import { computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { merge } from 'rxjs';
import { Api } from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';

/** What every stored entity has: an id and the version that is also its ETag. */
export interface Versioned {
  readonly id: string;
  readonly version: number;
}

/** An optimistic change shown until its request settles: maps the item (if any) to its new state. */
interface Pending<T> {
  readonly key: number;
  readonly id: string;
  readonly apply: (item: T | undefined) => T | undefined;
}

/** The `If-Match` value of a version (server: `ETags.of`). */
export function etag(version: number): string {
  return `"${version}"`;
}

/**
 * A signal-based list of server entities for one query, shared by the GTD stores.
 *
 * - {@link load} fetches the list for a query; only the newest request may set it, so a slower
 *   older answer never overwrites a newer one.
 * - {@link mutate} shows a change at once and sends it; the server answer replaces it, an error
 *   rolls it back (the optimistic state is an overlay that is dropped when the request settles).
 * - Local changes win over list answers requested before them, so a refetch that crosses a save
 *   doesn't bring the old state back.
 * - Change events keep the list current: a `DELETED` item is removed, other
 *   events refetch (they carry no contents). An SSE `resync` refetches too.
 */
export abstract class ListStore<T extends Versioned, Q> {
  protected readonly api = inject(Api);

  private readonly server = signal<readonly T[]>([]);
  private readonly pending = signal<readonly Pending<T>[]>([]);
  private readonly loadingState = signal(false);
  private readonly loadedState = signal(false);
  private readonly errorState = signal<unknown>(null);

  private readonly query = signal<Q | undefined>(undefined);
  /** Id of the newest list request; answers of older ones are dropped. */
  private requestSeq = 0;
  /** Logical clock of local changes. */
  private clock = 0;
  /** When each item last changed locally (a {@link clock} value). */
  private readonly touched = new Map<string, number>();

  /** The items for the current query, with optimistic changes applied, in {@link compare} order. */
  readonly items = computed(() => {
    const byId = new Map(this.server().map((item) => [item.id, item]));
    const query = this.query();
    for (const change of this.pending()) {
      const next = change.apply(byId.get(change.id));
      if (next && query !== undefined && this.matches(next, query)) {
        byId.set(change.id, next);
      } else {
        byId.delete(change.id);
      }
    }
    return [...byId.values()].sort((a, b) => this.compare(a, b) || a.id.localeCompare(b.id));
  });
  /** The {@link items} by id. */
  readonly byId = computed(() => new Map(this.items().map((item) => [item.id, item])));
  /** Whether a list request is running. */
  readonly loading = this.loadingState.asReadonly();
  /** Whether a list answer arrived for the current store (stays true across refetches). */
  readonly loaded = this.loadedState.asReadonly();
  /** The error of the newest list request, `null` after a successful one. */
  readonly error = this.errorState.asReadonly();

  /**
   * @param itemType the `ChangeEvent.type` of the stored items: their `DELETED` events remove them
   * @param relatedTypes other event types that may change the list (they refetch it)
   */
  protected constructor(
    private readonly itemType: string,
    relatedTypes: readonly string[] = [],
  ) {
    const events = inject(ChangeEventsService);
    const destroyRef = inject(DestroyRef);
    events.resync$.pipe(takeUntilDestroyed(destroyRef)).subscribe(() => void this.refresh());
    merge(...[itemType, ...relatedTypes].map((type) => events.events$(type)))
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe((event) => {
        if (event.op === 'DELETED' && event.type === this.itemType) this.drop(event.id);
        else void this.refresh();
      });
  }

  /** Fetches the whole list for `query`. */
  protected abstract fetch(query: Q): Promise<T[]>;
  /** Whether `item` belongs to the list of `query` (after a local change or a server answer). */
  protected abstract matches(item: T, query: Q): boolean;
  /** Display order of two items; ties are broken by id. */
  protected abstract compare(a: T, b: T): number;

  /** Shows the list for `query` (replacing the previous one) once it is fetched. */
  load(query: Q): Promise<void> {
    const previous = this.query();
    if (previous !== undefined && !sameQuery(previous, query)) {
      // The old items don't belong to the new query.
      this.server.set([]);
      this.loadedState.set(false);
    }
    this.query.set(query);
    return this.refresh();
  }

  /** Fetches the current query again; does nothing before the first {@link load}. */
  async refresh(): Promise<void> {
    const query = this.query();
    if (query === undefined) return;
    const seq = ++this.requestSeq;
    const started = this.clock;
    this.loadingState.set(true);
    try {
      const items = await this.fetch(query);
      if (seq !== this.requestSeq) return;
      this.server.set(this.mergeLoaded(items, started));
      this.errorState.set(null);
      this.loadedState.set(true);
    } catch (error) {
      if (seq === this.requestSeq) this.errorState.set(error);
    } finally {
      if (seq === this.requestSeq) this.loadingState.set(false);
    }
  }

  /** The current (optimistic) state of an item of the list, if it is in it. */
  find(id: string): T | undefined {
    return this.byId().get(id);
  }

  /** The `If-Match` of `item`: the list may know a newer version than the caller's copy. */
  protected ifMatch(item: T): string {
    return etag(Math.max(item.version, this.server().find((other) => other.id === item.id)?.version ?? 0));
  }

  /**
   * Shows `apply` on item `id` at once (returning `undefined` removes it), then runs `request`.
   * Its answer becomes the item (no answer: the item is gone); on an error the change is
   * rolled back and the error rethrown.
   */
  protected async mutate<R extends T | undefined | void>(
    id: string,
    apply: (item: T | undefined) => T | undefined,
    request: () => Promise<R>,
  ): Promise<R> {
    const change: Pending<T> = { key: ++this.clock, id, apply };
    this.pending.update((all) => [...all, change]);
    try {
      const result = await request();
      if (result) this.accept(result as T);
      else this.drop(id);
      return result;
    } finally {
      this.pending.update((all) => all.filter((other) => other.key !== change.key));
    }
  }

  /** Takes a server answer for an item: shown if it belongs to the list, removed otherwise. */
  protected accept(item: T): void {
    this.touch(item.id);
    const query = this.query();
    if (query === undefined || !this.matches(item, query)) {
      this.server.update((all) => all.filter((other) => other.id !== item.id));
      return;
    }
    this.server.update((all) => {
      const index = all.findIndex((other) => other.id === item.id);
      if (index < 0) return [...all, item];
      if (all[index].version > item.version) return all;
      return all.map((other, i) => (i === index ? item : other));
    });
  }

  /** Removes an item that is gone on the server. */
  protected drop(id: string): void {
    this.touch(id);
    this.server.update((all) => all.filter((other) => other.id !== id));
  }

  private touch(id: string): void {
    this.touched.set(id, ++this.clock);
  }

  /**
   * A list answer requested at clock `started`: items changed locally since then keep their
   * local state (or stay removed); older local changes no longer matter for later requests.
   */
  private mergeLoaded(items: readonly T[], started: number): T[] {
    const local = new Map(this.server().map((item) => [item.id, item]));
    const newer = (id: string): boolean => (this.touched.get(id) ?? 0) > started;
    const result: T[] = [];
    for (const item of items) {
      if (!newer(item.id)) result.push(item);
      else if (local.has(item.id)) result.push(local.get(item.id)!);
    }
    const loaded = new Set(items.map((item) => item.id));
    for (const item of local.values()) {
      if (!loaded.has(item.id) && newer(item.id)) result.push(item);
    }
    for (const [id, at] of this.touched) {
      if (at <= started) this.touched.delete(id);
    }
    return result;
  }
}

/** `item` with a PATCH body applied: only the fields sent change, `null` clears a field. */
export function patched<T extends object>(item: T, patch: object): T {
  const next = { ...item } as Record<string, unknown>;
  for (const [key, value] of Object.entries(patch)) {
    if (value === undefined) continue;
    if (value === null) delete next[key];
    else next[key] = value;
  }
  return next as T;
}

/** Queries are plain objects of primitives (the list parameters). */
function sameQuery(a: unknown, b: unknown): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}
