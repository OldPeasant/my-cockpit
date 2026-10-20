import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  contentChildren,
  DOCUMENT,
  effect,
  ElementRef,
  inject,
  Injector,
  input,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { catchError, debounceTime, distinctUntilChanged, from, map, of, switchMap } from 'rxjs';
import { ItemRefDto, LinkEndResponse, LinkResponse, SearchItemResponse } from '../../api/generated';
import { ApiError } from '../../core/http';
import { ItemTypeIcon, itemTypeLabel } from './item-type';
import { LinkedCreate } from './linked-create';
import { ItemType, LinksService } from './links.service';

/** Wait after the last keystroke before searching. */
export const LINK_SEARCH_DEBOUNCE_MS = 200;

/** One row of the panel: a link and its other end. */
interface LinkRow {
  readonly link: LinkResponse;
  readonly other: LinkEndResponse;
}

interface SearchState {
  /** The (trimmed) query these results are for. */
  readonly query: string;
  readonly groups: readonly { readonly type: ItemType; readonly items: SearchItemResponse[] }[];
  readonly error: string | null;
}

let nextUid = 0;

const IDLE: SearchState = { query: '', groups: [], error: null };

/**
 * The "Linked items" panel of every detail view (SPEC.md §4.3): the item's links in both
 * directions, each with icon, title, subtitle and a deep link (unavailable items greyed out),
 * removal of manual links, "Add link" with search-as-you-type over all types, and a slot for the
 * feature's "create linked…" actions (elements marked {@link LinkedCreate}).
 *
 * ```html
 * <app-linked-items [item]="{ type: 'TASK', id: task.id }">
 *   <button mat-button appLinkedCreate (click)="newNote()">New note</button>
 * </app-linked-items>
 * ```
 */
@Component({
  selector: 'app-linked-items',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ItemTypeIcon, MatButtonModule, RouterLink],
  templateUrl: './linked-items.html',
  styleUrl: './linked-items.scss',
})
export class LinkedItems {
  private readonly service = inject(LinksService);
  private readonly document = inject(DOCUMENT);
  private readonly injector = inject(Injector);

  /** Prefix of the element ids, unique per panel. */
  protected readonly uid = `app-linked-items-${nextUid++}`;

  /** The item whose links are shown. */
  readonly item = input.required<ItemRefDto>();

  protected readonly createActions = contentChildren(LinkedCreate);

  private readonly heading = viewChild<ElementRef<HTMLElement>>('heading');
  private readonly addButton = viewChild('addButton', { read: ElementRef<HTMLButtonElement> });
  private readonly searchInput = viewChild<ElementRef<HTMLInputElement>>('searchInput');
  private readonly list = viewChild<ElementRef<HTMLElement>>('list');

  /** Links of the item; null while loading. */
  protected readonly links = signal<LinkResponse[] | null>(null);
  protected readonly loadError = signal<string | null>(null);
  /** The last failed action (remove or add), shown above the list. */
  protected readonly actionError = signal<string | null>(null);
  /** Ids of the links whose removal is still running. */
  protected readonly removing = signal<ReadonlySet<string>>(new Set());

  protected readonly adding = signal(false);
  protected readonly query = signal('');
  protected readonly active = signal(0);
  /** Whether a create request is running. */
  protected readonly linking = signal(false);

  /** Counts the loads: only the latest one may apply its result or error. */
  private loadSeq = 0;
  /** Counts the item changes: a remove or add that finished for an earlier item is ignored. */
  private itemSeq = 0;

  protected readonly rows = computed<LinkRow[]>(() => {
    return (this.links() ?? []).map((link) => ({ link, other: otherEnd(link) }));
  });

  private readonly search = toSignal(
    toObservable(this.query).pipe(
      map((query) => query.trim()),
      debounceTime(LINK_SEARCH_DEBOUNCE_MS),
      distinctUntilChanged(),
      switchMap((query) => {
        if (!query) return of(IDLE);
        return from(this.service.search(query)).pipe(
          map((groups): SearchState => ({ query, groups, error: null })),
          catchError((error: unknown) =>
            of<SearchState>({ ...IDLE, query, error: message(error) }),
          ),
        );
      }),
    ),
    { initialValue: IDLE },
  );

  /** Results without the item itself and the items it is already linked to. */
  protected readonly results = computed(() => {
    const item = this.item();
    const linked = new Set(this.rows().map((row) => key(row.other)));
    linked.add(key(item));
    return this.search()
      .groups.map((group) => ({
        type: group.type,
        label: itemTypeLabel(group.type),
        items: group.items.filter((hit) => !linked.has(key(hit))),
      }))
      .filter((group) => group.items.length > 0);
  });

  /** Results in display order, for the arrow keys. */
  private readonly ordered = computed(() => this.results().flatMap((group) => group.items));

  protected readonly searchError = computed(() => this.search().error);
  /** Whether a search for the current query is still pending (debounce or request). */
  protected readonly searching = computed(() => {
    const query = this.query().trim();
    return query !== '' && this.search().query !== query;
  });

  constructor() {
    effect(() => {
      const item = this.item();
      untracked(() => {
        // A new item: drop what belonged to the previous one (no focus move, the user did not act here).
        this.itemSeq++;
        this.actionError.set(null);
        this.resetSearch();
        void this.load(item);
      });
    });
  }

  /** Reloads the links, e.g. after a "create linked…" action linked a new item. */
  reload(): Promise<void> {
    return this.load(this.item());
  }

  protected typeLabel(type: ItemType): string {
    return itemTypeLabel(type);
  }

  protected removeLabel(row: LinkRow): string {
    const title = this.title(row.other);
    return $localize`:@@links.removeAria:Remove link to ${title}:title:`;
  }

  protected title(end: LinkEndResponse | SearchItemResponse): string {
    return end.title ?? $localize`:@@links.unavailable:Unavailable item`;
  }

  protected async remove(row: LinkRow): Promise<void> {
    const id = row.link.id;
    const itemSeq = this.itemSeq;
    this.removing.update((ids) => new Set(ids).add(id));
    this.actionError.set(null);
    try {
      await this.service.remove(id);
      if (itemSeq === this.itemSeq) this.dropLink(id);
    } catch (error) {
      if (itemSeq !== this.itemSeq) return;
      // Already removed elsewhere: the list just catches up.
      if (error instanceof ApiError && error.status === 404) this.dropLink(id);
      else this.actionError.set(message(error));
    } finally {
      this.removing.update((ids) => {
        const rest = new Set(ids);
        rest.delete(id);
        return rest;
      });
    }
  }

  protected openSearch(): void {
    this.adding.set(true);
    this.actionError.set(null);
    afterNextRender(() => this.searchInput()?.nativeElement.focus(), { injector: this.injector });
  }

  protected closeSearch(): void {
    this.resetSearch();
    afterNextRender(() => this.addButton()?.nativeElement.focus(), { injector: this.injector });
  }

  protected onInput(value: string): void {
    this.query.set(value);
    this.active.set(0);
  }

  protected onKeydown(event: KeyboardEvent): void {
    const count = this.ordered().length;
    if (event.key === 'ArrowDown' && count > 0) {
      event.preventDefault();
      this.active.update((i) => (i + 1) % count);
    } else if (event.key === 'ArrowUp' && count > 0) {
      event.preventDefault();
      this.active.update((i) => (i - 1 + count) % count);
    } else if (event.key === 'Enter') {
      event.preventDefault();
      const hit = this.ordered()[this.active()];
      if (hit) void this.addLink(hit);
    } else if (event.key === 'Escape') {
      event.preventDefault();
      this.closeSearch();
    }
  }

  protected isActive(hit: SearchItemResponse): boolean {
    return this.ordered()[this.active()] === hit;
  }

  protected activeId(): string | null {
    const hit = this.ordered()[this.active()];
    return hit ? this.optionId(hit) : null;
  }

  protected optionId(hit: SearchItemResponse): string {
    return `${this.uid}-option-${hit.type}-${hit.id}`;
  }

  protected async addLink(hit: SearchItemResponse): Promise<void> {
    if (this.linking()) return;
    const item = this.item();
    const itemSeq = this.itemSeq;
    this.linking.set(true);
    this.actionError.set(null);
    try {
      const link = await this.service.create(item, hit);
      // After an item change the search is closed already and the link belongs to another item.
      if (itemSeq !== this.itemSeq) return;
      this.links.update((links) => [...(links ?? []), link]);
      this.closeSearch();
    } catch (error) {
      if (itemSeq !== this.itemSeq) return;
      if (error instanceof ApiError && error.status === 409) {
        // Linked meanwhile (another tab or device): show the link that exists.
        this.closeSearch();
        void this.reload();
      } else {
        this.actionError.set(message(error));
      }
    } finally {
      this.linking.set(false);
    }
  }

  private resetSearch(): void {
    this.adding.set(false);
    this.query.set('');
    this.active.set(0);
  }

  private async load(item: ItemRefDto): Promise<void> {
    const seq = ++this.loadSeq;
    this.loadError.set(null);
    this.links.set(null);
    try {
      const links = await this.service.list(item);
      if (seq === this.loadSeq) this.links.set(links);
    } catch (error) {
      if (seq === this.loadSeq) this.loadError.set(message(error));
    }
  }

  private dropLink(id: string): void {
    const ids = (this.links() ?? []).map((link) => link.id);
    const index = ids.indexOf(id);
    this.links.update((links) => links?.filter((link) => link.id !== id) ?? null);
    // The removed row took the focus with it: go on with the next row, the previous one or the heading.
    afterNextRender(
      () => {
        const focused = this.document.activeElement;
        if (focused && focused !== this.document.body) return;
        const buttons = [
          ...(this.list()?.nativeElement.querySelectorAll<HTMLButtonElement>('button.remove') ??
            []),
        ];
        const target = [buttons[index], buttons[index - 1]].find((b) => b && !b.disabled);
        (target ?? this.heading()?.nativeElement)?.focus();
      },
      { injector: this.injector },
    );
  }
}

function otherEnd(link: LinkResponse): LinkEndResponse {
  // The direction is relative to the queried item; a create response is seen from its source.
  return link.direction === 'OUTGOING' ? link.target : link.source;
}

function key(item: ItemRefDto): string {
  return `${item.type}:${item.id}`;
}

function message(error: unknown): string {
  return error instanceof ApiError
    ? error.userMessage
    : $localize`:@@links.error:Something went wrong.`;
}
