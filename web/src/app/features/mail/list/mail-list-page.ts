import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { MailAddressResponse } from '../../../api/generated';
import { folderLabel, MailFolderTree } from './mail-folder-tree';
import { MailFoldersStore } from './mail-folders-store';
import { MailListStore } from './mail-list-store';
import { MailMoveDialog, MailMoveDialogData } from './mail-move-dialog';
import { mailShortcutFor, MailShortcut } from './mail-shortcuts';
import { MailAction, MailSummary } from './mail.service';

/** `localStorage` key of the threaded/flat choice. */
export const THREADED_KEY = 'cockpit.mail.threaded';

/**
 * The mail list (SPEC.md §7.3): the unified Inbox (`/mail`) or one folder (`/mail/folders/:id`)
 * beside the folder tree, flat or by conversation, loading older mail while scrolling. Items can be
 * selected; the toolbar and the Gmail-like keys act on the selection, else on the item under the
 * keyboard cursor.
 */
@Component({
  selector: 'app-mail-list-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, MailFolderTree, MatButtonModule, RouterLink],
  providers: [MailListStore, MailFoldersStore],
  templateUrl: './mail-list-page.html',
  styleUrl: './mail-list-page.scss',
  host: { '(document:keydown)': 'onKeydown($event)' },
})
export default class MailListPage {
  protected readonly store = inject(MailListStore);
  protected readonly folders = inject(MailFoldersStore);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  protected readonly threaded = signal(storedThreaded());
  protected readonly folderId = signal<string | null>(null);
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
    return folder ? (this.folders.accountById().get(folder.accountId)?.displayName ?? '') : '';
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

  constructor() {
    void this.folders.load();
    inject(ActivatedRoute)
      .paramMap.pipe(takeUntilDestroyed(inject(DestroyRef)))
      .subscribe((params) => {
        this.folderId.set(params.get('folderId'));
        void this.store.load({ folderId: this.folderId(), threaded: this.threaded() });
      });

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
    void this.store.load({ folderId: this.folderId(), threaded });
  }

  protected act(action: MailAction): void {
    void this.store.apply(action);
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
    const shortcut = mailShortcutFor(event);
    if (!shortcut) return;
    event.preventDefault();
    this.run(shortcut);
  }

  private run(shortcut: MailShortcut): void {
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
    }
  }

  /** Opens a message in the read view. */
  protected open(id: string): void {
    void this.router.navigate(['/mail/messages', id]);
  }

  /**
   * Roving focus: after a key moved the cursor, focus follows it to the row's button, so assistive
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
    return this.threaded() ? (item.threadUnread ?? 0) > 0 : !item.seen;
  }

  protected sender(item: MailSummary): string {
    return senderName(item.from);
  }

  protected subject(item: MailSummary): string {
    return item.subject || $localize`:@@mail.noSubject:(no subject)`;
  }

  protected accountOf(item: MailSummary): string {
    return (item.accountId && this.folders.accountById().get(item.accountId)?.displayName) || '';
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

function storedThreaded(): boolean {
  try {
    return localStorage.getItem(THREADED_KEY) === 'true';
  } catch {
    return false;
  }
}
