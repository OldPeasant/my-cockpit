import { DatePipe } from '@angular/common';
import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  Injector,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { firstValueFrom, merge } from 'rxjs';
import { MailAddressResponse } from '../../../api/generated';
import { folderLabel, MailFolderTree } from './mail-folder-tree';
import { MailFoldersStore } from './mail-folders-store';
import { MailListSession } from './mail-list-session';
import { MailListStore, NextActionOutcome, NextActionsPartlyFailedError } from './mail-list-store';
import { MailMoveDialog, MailMoveDialogData } from './mail-move-dialog';
import {
  nextActionArchiveFailed,
  nextActionFailed,
  nextActionsCreated,
  nextActionsIncomplete,
  OPEN_NEXT_ACTIONS,
} from './mail-next-action';
import { ApiError, NotificationService } from '../../../core/http';
import { isTypingTarget } from '../../../shell/hotkeys';
import { PageShortcuts } from '../../../shell/page-shortcuts';
import { MAIL_LIST_HELP, mailShortcutFor, MailShortcut } from './mail-shortcuts';
import { MAIL_MAX_PAGE_SIZE, MailAction, MailScope, MailSearch, MailSummary } from './mail.service';

/** `localStorage` key of the threaded/flat choice. */
export const THREADED_KEY = 'cockpit.mail.threaded';

/**
 * The mail list (SPEC.md §7.3): the unified Inbox (`/mail`) or one folder (`/mail/folders/:id`)
 * beside the folder tree, flat or by conversation, loading older mail while scrolling. Items can be
 * selected; the toolbar and the Gmail-like keys act on the selection, else on the item under the
 * keyboard cursor. The search box (`/` focuses it) lists the matching messages of the folder, or of
 * all folders for the unified Inbox, optionally also searched on the mail servers. The search is
 * part of the URL (`?q=…&server=1`), and the list the user leaves for a message is kept
 * ({@link MailListSession}): going back shows it again as it was (no second search, same scroll
 * position and cursor), and the read view steps through it.
 */
@Component({
  selector: 'app-mail-list-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, MailFolderTree, MatButtonModule, RouterLink],
  providers: [MailListStore, MailFoldersStore],
  templateUrl: './mail-list-page.html',
  styleUrls: ['./mail-list-page.scss', './mail-list-search.scss'],
  host: { '(document:keydown)': 'onKeydown($event)' },
})
export default class MailListPage {
  protected readonly store = inject(MailListStore);
  protected readonly folders = inject(MailFoldersStore);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly session = inject(MailListSession);
  private readonly injector = inject(Injector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  /** How far the list is scrolled (kept while scrolling: it is gone when the page is left). */
  private scrollTop = 0;
  private routeApplied = false;

  protected readonly threaded = signal(storedThreaded());
  protected readonly folderId = signal<string | null>(null);
  /** The submitted search; null shows the folder's list. */
  protected readonly search = signal<MailSearch | null>(null);
  /** The "also search on the server" option, applied to the current and the next searches. */
  protected readonly serverSearch = signal(false);
  /** Whether the shown list has one item per conversation (search results never do). */
  protected readonly listThreaded = computed(() => this.store.scope().threaded);
  /** The accounts whose server could not be searched, by address, comma-separated. */
  protected readonly failedAccounts = computed(() => {
    const ids = this.store.searchResult()?.failedAccountIds ?? [];
    const accounts = this.folders.accountById();
    return ids
      .map(
        (id) =>
          accounts.get(id)?.emailAddress || $localize`:@@mail.search.unknownAccount:an account`,
      )
      .join(', ');
  });
  protected readonly folder = computed(() => {
    const id = this.folderId();
    return id ? (this.folders.folderById().get(id) ?? null) : null;
  });
  protected readonly title = computed(() => {
    const folder = this.folder();
    if (folder) return folderLabel(folder);
    return this.folderId()
      ? $localize`:@@mail.folder:Folder`
      : $localize`:@@mail.unified:All inboxes`;
  });
  protected readonly accountName = computed(() => {
    const folder = this.folder();
    return folder ? (this.folders.accountById().get(folder.accountId)?.emailAddress ?? '') : '';
  });
  /** Account names are shown on items when they can come from several accounts. */
  protected readonly showAccount = computed(
    () => this.folderId() === null && (this.folders.trees()?.length ?? 0) > 1,
  );
  protected readonly noAccounts = computed(() => this.folders.trees()?.length === 0);
  protected readonly hasTargets = computed(() => this.store.targets().length > 0);
  /** Moving needs one account: the folders to move to are that account's. */
  protected readonly moveAccountId = computed(() => {
    const accounts = new Set(this.store.targets().map((item) => item.accountId));
    return accounts.size === 1 ? ([...accounts][0] ?? null) : null;
  });
  /** Whether every target is starred: the star button then removes the star. */
  protected readonly allFlagged = computed(() => {
    const targets = this.store.targets();
    return targets.length > 0 && targets.every((item) => item.flagged);
  });
  protected readonly someSelected = computed(
    () => this.store.selection().length > 0 && !this.store.allSelected(),
  );

  private readonly sentinel = viewChild<ElementRef<HTMLElement>>('sentinel');
  private readonly searchInput = viewChild<ElementRef<HTMLInputElement>>('searchInput');

  constructor() {
    inject(PageShortcuts).register({
      title: $localize`:@@mail.shortcutsTitle:Mail`,
      shortcuts: MAIL_LIST_HELP,
    });
    void this.folders.load();
    const destroyRef = inject(DestroyRef);
    // The route's snapshot is complete when either map emits: a navigation that changes the folder
    // and the search is applied once.
    merge(this.route.paramMap, this.route.queryParamMap)
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe(() => this.applyRoute());
    destroyRef.onDestroy(() => {
      const snapshot = this.store.snapshot();
      this.session.save(
        snapshot && { ...snapshot, url: this.listUrl(), scrollTop: this.scrollTop },
      );
    });
    afterNextRender(() => this.trackScroll(destroyRef));

    // Infinite scroll: the next page loads when the end of the list comes into view.
    effect((onCleanup) => {
      const sentinel = this.sentinel()?.nativeElement;
      if (!sentinel || typeof IntersectionObserver === 'undefined') return;
      const observer = new IntersectionObserver(
        (entries) => {
          if (entries.some((entry) => entry.isIntersecting)) void this.store.loadMore();
        },
        { rootMargin: '400px' },
      );
      observer.observe(sentinel);
      onCleanup(() => observer.disconnect());
    });

    // Keeps the keyboard cursor in view.
    effect(() => {
      const id = this.store.cursorId();
      if (!id) return;
      const row = this.host.nativeElement.querySelector<HTMLElement>(
        `[data-id="${CSS.escape(id)}"]`,
      );
      row?.scrollIntoView?.({ block: 'nearest' });
    });
  }

  protected setThreaded(threaded: boolean): void {
    if (threaded === this.threaded()) return;
    this.threaded.set(threaded);
    try {
      localStorage.setItem(THREADED_KEY, String(threaded));
    } catch {
      // Storage may be unavailable (private mode): the choice then lasts for this page only.
    }
    // Search results are always flat: the choice applies when the search is cleared,
    // instead of re-running a (possibly slow server) search with the same result.
    if (this.search()) return;
    void this.store.load(this.scope());
  }

  /**
   * Shows the folder and the search the URL names. The first time, a list saved when the user left
   * it for a message is shown again as it was; a URL change that the page made itself (a submitted
   * search) changes nothing.
   */
  private applyRoute(): void {
    const { paramMap, queryParamMap } = this.route.snapshot;
    const folderId = paramMap.get('folderId');
    const query = queryParamMap.get('q')?.trim();
    const search = query ? { query, server: queryParamMap.get('server') === '1' } : null;
    const first = !this.routeApplied;
    this.routeApplied = true;
    if (!first && folderId === this.folderId() && sameSearch(search, this.search())) return;
    this.folderId.set(folderId);
    this.search.set(search);
    if (search) this.serverSearch.set(search.server);
    const input = this.searchInput()?.nativeElement;
    if (input) input.value = search?.query ?? '';
    const saved = first ? this.session.restorable(this.listUrl(), this.scope()) : null;
    if (!saved) {
      void this.store.load(this.scope());
      return;
    }
    this.store.restore(saved);
    // A folder list may have changed meanwhile; search results stay as they were found. A list
    // longer than the server returns at once is kept as it is: refetching it would cut it short.
    if (!saved.scope.search && saved.items.length <= MAIL_MAX_PAGE_SIZE) void this.store.refresh();
    afterNextRender(() => this.restoreScroll(saved.scrollTop), { injector: this.injector });
  }

  /** The URL of the shown list and search. */
  private listUrl(): string {
    const folderId = this.folderId();
    const search = this.search();
    const tree = this.router.createUrlTree(folderId ? ['/mail', 'folders', folderId] : ['/mail'], {
      queryParams: search ? { q: search.query, server: search.server ? 1 : null } : {},
    });
    return this.router.serializeUrl(tree);
  }

  /** Shows `search` (null: the list), and puts it into the URL. */
  private showSearch(search: MailSearch | null, replaceUrl = false): void {
    this.search.set(search);
    void this.store.load(this.scope());
    this.router.navigateByUrl(this.listUrl(), { replaceUrl }).catch(() => undefined);
  }

  private trackScroll(destroyRef: DestroyRef): void {
    const container = scrollContainer(this.host.nativeElement);
    const target: EventTarget = container === document.scrollingElement ? window : container;
    const onScroll = () => (this.scrollTop = container.scrollTop);
    target.addEventListener('scroll', onScroll, { passive: true });
    destroyRef.onDestroy(() => target.removeEventListener('scroll', onScroll));
  }

  private restoreScroll(scrollTop: number): void {
    const container = scrollContainer(this.host.nativeElement);
    container.scrollTop = scrollTop;
    this.scrollTop = container.scrollTop;
    const id = this.store.cursorId();
    if (!id) return;
    this.host.nativeElement
      .querySelector<HTMLElement>(`[data-id="${CSS.escape(id)}"]`)
      ?.scrollIntoView?.({ block: 'nearest' });
  }

  private scope(): MailScope {
    const scope: MailScope = { folderId: this.folderId(), threaded: this.threaded() };
    const search = this.search();
    return search ? { ...scope, search } : scope;
  }

  /** Enter in the search box: searches for its text, or shows the list again when it is blank. */
  protected submitSearch(event: Event, text: string): void {
    event.preventDefault();
    const query = text.trim();
    if (!query) return this.clearSearch();
    const search = { query, server: this.serverSearch() };
    // The same search again: searched again, the URL stays.
    if (sameSearch(search, this.search())) {
      void this.store.load(this.scope());
      return;
    }
    this.showSearch(search);
  }

  /** An emptied search box (e.g. its clear button) shows the list again. */
  protected onSearchInput(text: string): void {
    if (!text) this.clearSearch();
  }

  /** Escape in the search box empties it and shows the list again. */
  protected onSearchEscape(event: Event, input: HTMLInputElement): void {
    event.preventDefault();
    input.value = '';
    this.clearSearch();
  }

  /** Turns the server option on or off; a shown search is repeated with it. */
  protected setServerSearch(server: boolean): void {
    this.serverSearch.set(server);
    const search = this.search();
    if (!search || search.server === server) return;
    this.showSearch({ ...search, server }, true);
  }

  protected clearSearch(): void {
    if (!this.search()) return;
    this.showSearch(null);
  }

  protected act(action: MailAction): void {
    void this.store.apply(action);
  }

  /** "→ Next action & archive" (`Shift+T`) is running. */
  protected readonly makingNextActions = signal(false);

  /**
   * "→ Next action & archive" on the selection (else the message under the cursor): a Next action
   * per mail, then the mails are archived. One mail opens its task's detail (Back returns here);
   * several stay in the list with a message linking to Next Actions. A mail whose archive failed
   * keeps its task and stays in the list, with an error naming it; with several mails, a single
   * error names every mail not archived (and those that got no task), since each snack bar
   * replaces the one before.
   */
  protected async toNextActions(): Promise<void> {
    if (this.makingNextActions()) return;
    this.makingNextActions.set(true);
    try {
      let created: readonly NextActionOutcome[];
      let notCreated: readonly MailSummary[] = [];
      let reason = '';
      try {
        created = (await this.store.toNextActions()) ?? [];
      } catch (error) {
        // A later request failed: the earlier ones' tasks exist and are reported with it.
        if (!(error instanceof NextActionsPartlyFailedError)) throw error;
        created = error.outcome;
        notCreated = error.failed;
        reason = failureMessage(error.failure);
      }
      if (!created.length && !notCreated.length) return;
      const notArchived = created.filter(({ result }) => !result.archived);
      if (created.length === 1 && !notCreated.length) {
        const [{ item, result }] = created;
        if (!result.archived) {
          this.notifications.error(nextActionArchiveFailed(this.subject(item), result));
        }
        await this.router.navigate(['/tasks', result.task.id]);
        return;
      }
      if (notArchived.length || notCreated.length) {
        this.notifications.error(
          nextActionsIncomplete(
            created.length,
            notArchived.map(({ item }) => this.subject(item)),
            notCreated.length,
            reason,
          ),
        );
        return;
      }
      this.snackBar
        .open(nextActionsCreated(created.length), OPEN_NEXT_ACTIONS, { duration: 8000 })
        .onAction()
        .subscribe(() => void this.router.navigate(['/next']));
    } catch (error) {
      this.notifications.error(nextActionFailed(failureMessage(error)));
    } finally {
      this.makingNextActions.set(false);
    }
  }

  protected toggleFlag(): void {
    this.act(this.allFlagged() ? 'UNFLAG' : 'FLAG');
  }

  protected async move(): Promise<void> {
    const accountId = this.moveAccountId();
    if (!accountId || this.dialog.openDialogs.length) return;
    // The folder is picked for these items: a live refetch while the dialog is open may move the
    // cursor or prune the selection, but must not change what is moved.
    const targets = this.store.targets();
    const data: MailMoveDialogData = {
      folders: this.folders.moveTargets(accountId, targets[0]?.folderId),
    };
    const target = await firstValueFrom(
      this.dialog
        .open<MailMoveDialog, MailMoveDialogData, string>(MailMoveDialog, { data, width: '24rem' })
        .afterClosed(),
    );
    if (target)
      await this.store.apply(
        'MOVE',
        target,
        targets.map((item) => item.id),
      );
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (this.dialog.openDialogs.length) return;
    if (isSearchKey(event)) {
      event.preventDefault();
      this.searchInput()?.nativeElement.focus();
      return;
    }
    const shortcut = mailShortcutFor(event);
    // Replying and forwarding are keys of the read view.
    if (!shortcut || shortcut === 'reply' || shortcut === 'replyAll' || shortcut === 'forward') {
      return;
    }
    event.preventDefault();
    this.run(shortcut);
  }

  private run(shortcut: Exclude<MailShortcut, 'reply' | 'replyAll' | 'forward'>): void {
    switch (shortcut) {
      case 'next':
        this.store.moveCursor(1);
        return this.focusCursor();
      case 'previous':
        this.store.moveCursor(-1);
        return this.focusCursor();
      case 'select': {
        const id = this.store.cursorId();
        if (id) this.store.toggleSelected(id);
        return;
      }
      case 'archive':
        this.act('ARCHIVE');
        return this.focusCursor();
      case 'delete':
        this.act('DELETE');
        return this.focusCursor();
      case 'unread':
        return this.act('UNREAD');
      case 'read':
        return this.act('READ');
      case 'flag':
        return this.toggleFlag();
      case 'move':
        void this.move();
        return;
      case 'spam':
        this.act('SPAM');
        return this.focusCursor();
      case 'clear':
        return this.store.clearSelection();
      case 'open': {
        const id = this.store.cursorId();
        if (id) this.open(id);
        return;
      }
      case 'compose':
        void this.router.navigate(['/mail/compose']);
        return;
      case 'nextAction':
        void this.toNextActions();
        return;
    }
  }

  /** Opens a message in the read view; a message in a Drafts folder opens in compose instead. */
  protected open(id: string): void {
    const item = (this.store.items() ?? []).find((candidate) => candidate.id === id);
    if (item && this.isDraft(item)) {
      void this.router.navigate(['/mail/compose'], { queryParams: { draft: id } });
    } else {
      void this.router.navigate(['/mail/messages', id]);
    }
  }

  /** Whether the message lies in a Drafts folder: opening it continues the draft in compose. */
  protected isDraft(item: MailSummary): boolean {
    return !!item.folderId && this.folders.folderById().get(item.folderId)?.role === 'DRAFTS';
  }

  /** The row link: the read view, or compose for a draft. */
  protected link(item: MailSummary): string[] {
    return this.isDraft(item) ? ['/mail/compose'] : ['/mail/messages', item.id];
  }

  /** The query of the row link: the draft compose opens. */
  protected linkQuery(item: MailSummary): Record<string, string> | null {
    return this.isDraft(item) ? { draft: item.id } : null;
  }

  /**
   * Enter on a focused row opens it like `o`, moving the cursor there first. The browser would follow
   * the row's link on its own; handling it here keeps one path for both keys. Ctrl/Cmd+Enter is not
   * matched by `keydown.enter`, so the browser still opens the link in a new tab then.
   */
  protected openFromKey(event: Event, id: string): void {
    event.preventDefault();
    this.store.setCursor(id);
    this.open(id);
  }

  /**
   * Roving focus: after a key moved the cursor, focus follows it to the row's link, so assistive
   * technology announces the message the next key acts on.
   */
  private focusCursor(): void {
    const id = this.store.cursorId();
    if (!id) return;
    this.host.nativeElement
      .querySelector<HTMLElement>(`[data-id="${CSS.escape(id)}"] .open`)
      ?.focus({ preventScroll: true });
  }

  protected unread(item: MailSummary): boolean {
    return this.listThreaded() ? (item.threadUnread ?? 0) > 0 : !item.seen;
  }

  protected sender(item: MailSummary): string {
    return senderName(item.from);
  }

  protected subject(item: MailSummary): string {
    return item.subject || $localize`:@@mail.noSubject:(no subject)`;
  }

  protected accountOf(item: MailSummary): string {
    return (item.accountId && this.folders.accountById().get(item.accountId)?.emailAddress) || '';
  }

  /** `shortTime` today, the day and month this year, else the full date. */
  protected dateFormat(item: MailSummary): string {
    const date = new Date(item.receivedAt);
    const now = new Date();
    if (date.toDateString() === now.toDateString()) return 'shortTime';
    return date.getFullYear() === now.getFullYear() ? 'MMM d' : 'mediumDate';
  }

  protected selectLabel(item: MailSummary): string {
    const sender = this.sender(item);
    const subject = this.subject(item);
    return $localize`:@@mail.select:Select “${subject}:subject:” from ${sender}:sender:`;
  }
}

/** The names (else addresses) of the senders, comma-separated. */
export function senderName(from: MailAddressResponse[] | undefined): string {
  const names = (from ?? [])
    .map((address) => address.name || address.address || '')
    .filter(Boolean);
  return names.length ? names.join(', ') : $localize`:@@mail.unknownSender:(unknown sender)`;
}

/** `/` outside a text field focuses the search box (as in Gmail). */
function isSearchKey(event: KeyboardEvent): boolean {
  return (
    event.key === '/' &&
    !event.defaultPrevented &&
    !event.isComposing &&
    !event.ctrlKey &&
    !event.altKey &&
    !event.metaKey &&
    !isTypingTarget(event.target)
  );
}

function sameSearch(a: MailSearch | null, b: MailSearch | null): boolean {
  return a === b || (!!a && !!b && a.query === b.query && a.server === b.server);
}

/** The element the list scrolls in: the closest scrolling ancestor, else the document. */
function scrollContainer(element: HTMLElement): Element {
  for (let node = element.parentElement; node; node = node.parentElement) {
    const overflow = getComputedStyle(node).overflowY;
    if (overflow === 'auto' || overflow === 'scroll') return node;
  }
  return document.scrollingElement ?? document.documentElement;
}

/** What a failed "→ Next action & archive" request says to the user. */
function failureMessage(error: unknown): string {
  return error instanceof ApiError
    ? error.userMessage
    : $localize`:@@mail.nextAction.unexpected:Something went wrong.`;
}

function storedThreaded(): boolean {
  try {
    return localStorage.getItem(THREADED_KEY) === 'true';
  } catch {
    return false;
  }
}
