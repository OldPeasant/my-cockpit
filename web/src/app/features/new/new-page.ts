import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { ActivatedRoute, Router } from '@angular/router';
import { map } from 'rxjs';
import { ApiError, NotificationService } from '../../core/http';
import { isTypingTarget } from '../../shell/hotkeys';
import { TaskStore } from '../gtd/data';
import { senderName } from '../mail/list/mail-list-page';
import { MailListStore } from '../mail/list/mail-list-store';
import { MailSummary } from '../mail/list/mail.service';
import { MailReadService } from '../mail/read/mail-read.service';
import { FILTER_PARAM, mergeNew, NewFilter, NewRow, parseFilter } from './new-feed';

/** The filter keys (`1`, `2`, `3`), in the order of the filter buttons. */
const FILTER_KEYS: Readonly<Record<string, NewFilter>> = { '1': 'all', '2': 'inbox', '3': 'mail' };

/**
 * The New view (SPEC.md §5.3 "New view", §9.1): the GTD Inbox and the unified mail inbox in one
 * list, newest first, filtered by source (`?source=inbox|mail`, keys `1`/`2`/`3`). Only a view on
 * the two inboxes: processing happens in their own views, a mail becomes a task only on
 * "→ Inbox task". Both lists stay live through their stores' change events; older mail loads
 * while scrolling.
 */
@Component({
  selector: 'app-new-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, MatButtonModule],
  providers: [TaskStore, MailListStore],
  templateUrl: './new-page.html',
  styleUrl: './new-page.scss',
  host: { '(document:keydown)': 'onKeydown($event)' },
})
export default class NewPage {
  protected readonly tasks = inject(TaskStore);
  protected readonly mail = inject(MailListStore);
  private readonly reader = inject(MailReadService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly dialog = inject(MatDialog);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  protected readonly filters: readonly { value: NewFilter; label: string; key: string }[] = [
    { value: 'all', label: $localize`:@@new.filter.all:All`, key: '1' },
    { value: 'inbox', label: $localize`:@@new.filter.inbox:GTD Inbox`, key: '2' },
    { value: 'mail', label: $localize`:@@new.filter.mail:Mail`, key: '3' },
  ];

  protected readonly filter = toSignal(
    this.route.queryParamMap.pipe(map((params) => parseFilter(params.get(FILTER_PARAM)))),
    { initialValue: parseFilter(this.route.snapshot.queryParamMap.get(FILTER_PARAM)) },
  );
  protected readonly rows = computed(() =>
    mergeNew(this.tasks.items(), this.mail.items() ?? [], !!this.mail.nextCursor(), this.filter()),
  );
  /** Whether more mail can load below the list (only where mails are shown). */
  protected readonly moreMail = computed(
    () => this.filter() !== 'inbox' && !!this.mail.nextCursor(),
  );
  /** Whether a shown source is still loading: it has neither data nor an error yet. */
  protected readonly loading = computed(
    () =>
      (this.filter() !== 'mail' && !this.tasks.loaded() && !this.tasks.error()) ||
      (this.filter() !== 'inbox' && this.mail.items() === null && !this.mail.loadError()),
  );
  protected readonly taskError = computed(() =>
    this.filter() !== 'mail' && this.tasks.error() ? errorMessage(this.tasks.error()) : null,
  );
  protected readonly mailError = computed(() =>
    this.filter() !== 'inbox' ? this.mail.loadError() : null,
  );

  /** The row the keyboard works on (`j`/`k`), by {@link NewRow.key}. */
  protected readonly cursorKey = signal<string | null>(null);
  /** Mails whose "→ Inbox task" request is running. */
  protected readonly converting = signal<ReadonlySet<string>>(new Set());

  private readonly sentinel = viewChild<ElementRef<HTMLElement>>('sentinel');

  constructor() {
    void this.tasks.load({ list: 'INBOX' });
    void this.mail.load({ folderId: null, threaded: false });

    // Infinite scroll: the next mail page loads when the end of the list comes into view.
    effect((onCleanup) => {
      const sentinel = this.sentinel()?.nativeElement;
      if (!sentinel || typeof IntersectionObserver === 'undefined') return;
      const observer = new IntersectionObserver(
        (entries) => {
          if (entries.some((entry) => entry.isIntersecting)) void this.mail.loadMore();
        },
        { rootMargin: '400px' },
      );
      observer.observe(sentinel);
      onCleanup(() => observer.disconnect());
    });

    // Keeps the keyboard cursor in view.
    effect(() => {
      const key = this.cursorKey();
      if (!key) return;
      const row = this.host.nativeElement.querySelector<HTMLElement>(
        `[data-key="${CSS.escape(key)}"]`,
      );
      row?.scrollIntoView?.({ block: 'nearest' });
    });
  }

  /** Shows `filter`; the URL keeps it, so it survives a reload. */
  protected setFilter(filter: NewFilter): void {
    if (filter === this.filter()) return;
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { [FILTER_PARAM]: filter === 'all' ? null : filter },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  protected open(row: NewRow): void {
    void this.router.navigate(
      row.kind === 'task' ? ['/tasks', row.task.id] : ['/mail/messages', row.mail.id],
    );
  }

  /**
   * Creates an Inbox task from `mail` (linked to it, SPEC.md §7.3); the mail stays in the inbox.
   * The task shows at once, before its change event arrives.
   */
  protected async toTask(mail: MailSummary): Promise<void> {
    if (this.converting().has(mail.id)) return;
    this.converting.update((ids) => new Set([...ids, mail.id]));
    try {
      const item = await this.reader.gtd(mail.id, 'task');
      this.notifications.info(
        $localize`:@@new.toTask.done:Added “${item.title}:title:” to the Inbox`,
      );
      await this.tasks.refresh();
    } catch (error) {
      const reason = errorMessage(error);
      this.notifications.error(
        $localize`:@@new.toTask.failed:The task could not be created: ${reason}:reason:`,
      );
    } finally {
      this.converting.update((ids) => new Set([...ids].filter((id) => id !== mail.id)));
    }
  }

  protected retry(): void {
    if (this.taskError()) void this.tasks.refresh();
    if (this.mailError()) void this.mail.reload();
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (event.defaultPrevented || this.dialog.openDialogs.length) return;
    if (event.ctrlKey || event.metaKey || event.altKey || isTypingTarget(event.target)) return;
    // Like the mail list, only the cursor keys repeat while held.
    if (event.repeat && event.key !== 'j' && event.key !== 'k') return;
    const filter = FILTER_KEYS[event.key];
    if (filter) {
      event.preventDefault();
      this.setFilter(filter);
      return;
    }
    const cursor = this.cursorRow();
    switch (event.key) {
      case 'j':
        event.preventDefault();
        return this.moveCursor(1);
      case 'k':
        event.preventDefault();
        return this.moveCursor(-1);
      case 'o':
      case 'Enter':
        if (!cursor || (event.key === 'Enter' && event.target instanceof HTMLButtonElement)) return;
        event.preventDefault();
        return this.open(cursor);
      case 't':
        if (cursor?.kind !== 'mail') return;
        event.preventDefault();
        void this.toTask(cursor.mail);
        return;
    }
  }

  /** The row under the cursor, if it is still shown. */
  private cursorRow(): NewRow | undefined {
    return this.rows().find((row) => row.key === this.cursorKey());
  }

  private moveCursor(step: 1 | -1): void {
    const rows = this.rows();
    if (!rows.length) return;
    const index = rows.findIndex((row) => row.key === this.cursorKey());
    const next = index < 0 ? 0 : Math.min(rows.length - 1, Math.max(0, index + step));
    this.cursorKey.set(rows[next].key);
    this.focusCursor();
    if (step === 1 && next >= rows.length - 1) void this.mail.loadMore();
  }

  /**
   * Roving focus, as in the mail list: focus follows the cursor to the row's button, so assistive
   * technology announces the row the next key acts on.
   */
  private focusCursor(): void {
    const key = this.cursorKey();
    if (!key) return;
    this.host.nativeElement
      .querySelector<HTMLElement>(`[data-key="${CSS.escape(key)}"] .open`)
      ?.focus({ preventScroll: true });
  }

  protected sender(mail: MailSummary): string {
    return senderName(mail.from);
  }

  protected subject(mail: MailSummary): string {
    return mail.subject || $localize`:@@mail.noSubject:(no subject)`;
  }

  /** `shortTime` today, the day and month this year, else the full date. */
  protected dateFormat(at: string): string {
    const date = new Date(at);
    const now = new Date();
    if (date.toDateString() === now.toDateString()) return 'shortTime';
    return date.getFullYear() === now.getFullYear() ? 'MMM d' : 'mediumDate';
  }
}

function errorMessage(error: unknown): string {
  return error instanceof ApiError
    ? error.userMessage
    : $localize`:@@mail.list.error:Something went wrong.`;
}
