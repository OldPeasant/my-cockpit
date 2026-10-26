import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { debounceTime, Subject } from 'rxjs';
import { ContactResponse, OAuthConnectionResponse, SkippedVCardDto } from '../../api/generated';
import { ChangeEventsService } from '../../core/events';
import { ApiError, NotificationService } from '../../core/http';
import { CardDavAccounts } from './carddav-accounts';
import { initials, roleLine, sourceLabel } from './contact-display';
import { ContactsService } from './contacts.service';

/** The item type of contacts in change events. */
const CONTACT_TYPE = 'CONTACT';

/** How long typing in the search waits before it searches. */
export const CONTACT_SEARCH_DEBOUNCE_MS = 250;

/** The result of the last vCard import. */
interface ImportResult {
  file: string;
  imported: number;
  skipped: SkippedVCardDto[];
}

/**
 * Contacts (SPEC.md §8.3): all contacts by name, searched by name or email (`/contacts?q=…`), the
 * accounts they sync with (CardDAV accounts managed here), vCard import of a file and export of all contacts.
 */
@Component({
  selector: 'app-contacts-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CardDavAccounts, MatButtonModule, RouterLink],
  templateUrl: './contacts-page.html',
  styleUrl: './contacts-page.scss',
})
export default class ContactsPage {
  private readonly service = inject(ContactsService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  /** The search of the URL, which the list shows. */
  protected readonly query = signal('');
  /** The text in the search field. */
  protected readonly searchText = signal('');

  /** The contacts by name; null while they load. */
  protected readonly contacts = signal<ContactResponse[] | null>(null);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly loadingMore = signal(false);
  protected readonly loadError = signal<string | null>(null);

  /** Connected accounts with access to contacts; null until known or when they failed to load. */
  protected readonly syncAccounts = signal<OAuthConnectionResponse[] | null>(null);

  protected readonly importing = signal(false);
  protected readonly importResult = signal<ImportResult | null>(null);
  protected readonly importError = signal<string | null>(null);

  protected readonly exportUrl = this.service.exportUrl();
  protected readonly initials = initials;
  protected readonly roleLine = roleLine;
  protected readonly sourceLabel = sourceLabel;

  private readonly typed = new Subject<string>();
  /** Counts the loads: contacts that arrive for an earlier search are ignored. */
  private loadSeq = 0;

  constructor() {
    const destroyRef = inject(DestroyRef);
    this.route.queryParamMap.pipe(takeUntilDestroyed(destroyRef)).subscribe((params) => {
      const q = params.get('q') ?? '';
      if (this.contacts() !== null && q === this.query()) return;
      this.query.set(q);
      if (q.trim() !== this.searchText().trim()) this.searchText.set(q);
      void this.load();
    });
    this.typed
      .pipe(debounceTime(CONTACT_SEARCH_DEBOUNCE_MS), takeUntilDestroyed(destroyRef))
      .subscribe((text) => this.search(text));
    const events = inject(ChangeEventsService);
    events
      .events$(CONTACT_TYPE)
      .pipe(debounceTime(CONTACT_SEARCH_DEBOUNCE_MS), takeUntilDestroyed(destroyRef))
      .subscribe(() => void this.reloadQuietly());
    events.resync$.pipe(takeUntilDestroyed(destroyRef)).subscribe(() => void this.reloadQuietly());
    void this.loadSyncAccounts();
  }

  protected async load(): Promise<void> {
    const seq = ++this.loadSeq;
    this.loadError.set(null);
    this.contacts.set(null);
    this.nextCursor.set(null);
    try {
      const page = await this.service.list(this.query());
      if (seq !== this.loadSeq) return;
      this.contacts.set(page.items);
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
      const page = await this.service.list(this.query(), cursor);
      if (seq !== this.loadSeq) return;
      this.contacts.update((current) => {
        const known = new Set(current?.map((c) => c.id));
        return [...(current ?? []), ...page.items.filter((c) => !known.has(c.id))];
      });
      this.nextCursor.set(page.nextCursor ?? null);
    } catch (error) {
      if (seq === this.loadSeq) this.notifications.error(message(error));
    } finally {
      this.loadingMore.set(false);
    }
  }

  protected setSearch(event: Event): void {
    const text = (event.target as HTMLInputElement).value;
    this.searchText.set(text);
    this.typed.next(text);
  }

  /** Searches at once (Enter), without waiting for the typing pause. */
  protected searchNow(): void {
    this.search(this.searchText());
  }

  protected clearSearch(): void {
    this.searchText.set('');
    this.search('');
  }

  /** Imports the chosen vCard file; its new contacts appear in the list. */
  protected async importFile(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file || this.importing()) return;
    this.importing.set(true);
    this.importError.set(null);
    this.importResult.set(null);
    try {
      const result = await this.service.import(file);
      this.importResult.set({
        file: file.name,
        imported: result.imported.length,
        skipped: result.skipped,
      });
      void this.load();
    } catch (error) {
      this.importError.set(message(error));
    } finally {
      this.importing.set(false);
    }
  }

  protected dismissImport(): void {
    this.importResult.set(null);
    this.importError.set(null);
  }

  /** Puts the search into the URL (replacing the entry), which loads it. */
  private search(text: string): void {
    const q = text.trim();
    if (q === this.query()) return;
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { q: q || null },
      replaceUrl: true,
    });
  }

  /** Loads the first page again while the current contacts stay on screen. */
  private async reloadQuietly(): Promise<void> {
    if (!this.contacts() || this.nextCursor()) return;
    const seq = this.loadSeq;
    try {
      const page = await this.service.list(this.query());
      if (seq !== this.loadSeq) return;
      this.contacts.set(page.items);
      this.nextCursor.set(page.nextCursor ?? null);
    } catch {
      // The list stays; the next action shows the problem.
    }
  }

  private async loadSyncAccounts(): Promise<void> {
    try {
      this.syncAccounts.set(await this.service.syncAccounts());
    } catch {
      // The sync status is extra information: without it, the contacts are still listed.
    }
  }
}

function message(error: unknown): string {
  return error instanceof ApiError
    ? error.userMessage
    : $localize`:@@contacts.error:Something went wrong.`;
}
