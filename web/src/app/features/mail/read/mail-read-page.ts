import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { firstValueFrom, map } from 'rxjs';
import {
  ItemRefDto,
  MailAddressResponse,
  MailAttachmentResponse,
  MailMessageResponse,
} from '../../../api/generated';
import { ApiError, NotificationService } from '../../../core/http';
import { LinkedItems } from '../../../shared/links';
import { BackButton, BackNavigation } from '../../../shared/navigation';
import { PageShortcuts } from '../../../shell/page-shortcuts';
import { folderTree, moveTargetsOf } from '../list/mail-folders-store';
import { MailListSession } from '../list/mail-list-session';
import { MailMoveDialog, MailMoveDialogData } from '../list/mail-move-dialog';
import { MailAction, MailService } from '../list/mail.service';
import { nextActionArchiveFailed, nextActionFailed } from '../list/mail-next-action';
import { MAIL_READ_HELP, MailShortcut, mailShortcutFor } from '../list/mail-shortcuts';
import { formatBytes } from './format-bytes';
import { ImageAllowlist } from './image-allowlist';
import { MailFrame } from './mail-frame';
import { attachmentUrl, MailGtdAction, MailReadService } from './mail-read.service';

type State = 'loading' | 'notFound' | 'failed' | 'ready';

/** The mail actions that take the message out of the list it was opened from. */
type TriageAction = Extract<MailAction, 'ARCHIVE' | 'SPAM' | 'DELETE'>;

/** The mail actions of the read view (one runs at a time). */
type ReadAction = TriageAction | Extract<MailAction, 'UNREAD' | 'FLAG' | 'UNFLAG' | 'MOVE'>;

/** The compose page's query parameter for `r`, `a` and `f`. */
type Respond = 'reply' | 'replyAll' | 'forward';
const RESPOND_SHORTCUTS: ReadonlyMap<MailShortcut, Respond> = new Map<MailShortcut, Respond>([
  ['reply', 'reply'],
  ['replyAll', 'replyAll'],
  ['forward', 'forward'],
]);

/** An attachment preview: an image (by URL) or the start of a text file. */
type Preview =
  | { partId: string; name: string; kind: 'image'; url: string }
  | { partId: string; name: string; kind: 'text'; text: string | null; error: string | null };

/** Text previews show at most this many characters. */
export const TEXT_PREVIEW_LIMIT = 100_000;

/**
 * Reading one message (SPEC.md §7.3): headers, the sanitized body in a sandboxed frame (or the
 * plain text), "load images" (once or always for the sender), attachments (download, preview, save
 * to Files), the GTD actions, Archive, Spam and Delete (buttons or `e`, `!`, `#`, then back to where
 * it was opened from) and the linked items. Opening an unread message marks it read.
 *
 * Opened from a mail list (a folder, the unified Inbox or search results), previous/next (buttons or
 * `k`/`j`) step through that list in its order, and Archive, Spam and Delete move on to the next
 * message of the list (back to it when none is left).
 *
 * Mark unread (`u`) returns to where the message was opened from, Star (`s`) stars or unstars it,
 * and Move (`v`) moves it to a folder of its account (then on like Archive). `r`, `a` and `f` reply,
 * reply to all and forward; `c` writes a new message.
 */
@Component({
  selector: 'app-mail-read-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [BackButton, DatePipe, LinkedItems, MailFrame, MatButtonModule, RouterLink],
  templateUrl: './mail-read-page.html',
  styleUrl: './mail-read-page.scss',
  host: { '(document:keydown)': 'onKeydown($event)' },
})
export default class MailReadPage {
  private readonly service = inject(MailReadService);
  private readonly allowlist = inject(ImageAllowlist);
  private readonly notifications = inject(NotificationService);
  private readonly mail = inject(MailService);
  private readonly backNavigation = inject(BackNavigation);
  private readonly dialog = inject(MatDialog);
  private readonly session = inject(MailListSession);
  private readonly router = inject(Router);

  /** The back button's destination when the message was not opened from a page of the app. */
  protected readonly mailLabel = $localize`:@@mailRead.back:Mail`;

  /** The message id (route parameter). */
  protected readonly id = toSignal(
    inject(ActivatedRoute).paramMap.pipe(map((params) => params.get('id') ?? '')),
    { initialValue: '' },
  );

  protected readonly state = signal<State>('loading');
  protected readonly message = signal<MailMessageResponse | null>(null);
  protected readonly loadError = signal<string | null>(null);
  /** Whether remote images are shown (through the server's proxy). */
  protected readonly loadImages = signal(false);
  protected readonly loadingImages = signal(false);
  /** The GTD action that is running. */
  protected readonly busy = signal<MailGtdAction | null>(null);
  /** Attachments being saved to Files, by message id and part id (part ids repeat across messages). */
  protected readonly saving = signal<ReadonlySet<string>>(new Set());
  protected readonly preview = signal<Preview | null>(null);
  /** The mail action that runs (one at a time), e.g. Archive or Star. */
  protected readonly triaging = signal<ReadAction | null>(null);

  /** Where the message is in the list it was opened from; null when not opened from a list. */
  protected readonly position = computed(() => {
    const id = this.id();
    return id ? this.session.position(id) : null;
  });
  /** A step to the previous or next message is looking for its target (loading more of the list). */
  protected readonly stepping = signal(false);

  /** The linked items panel (by reference, so tests can stand in for it). */
  private readonly links = viewChild<{ reload(): Promise<void> }>('links');
  /** Counts the loads: only the latest one may apply its result. */
  private seq = 0;
  private destroyed = false;
  /** Marking the shown message read: marking it unread waits for it, so the two cannot cross. */
  private marking: Promise<unknown> = Promise.resolve();

  protected readonly sender = computed<MailAddressResponse | null>(
    () => this.message()?.from?.[0] ?? null,
  );
  /**
   * Whether the images load only because the sender is listed: with the policy "allow" removing
   * the sender would change nothing, so "Stop" is not offered then.
   */
  protected readonly senderAllowed = computed(
    () => this.allowlist.policy() === 'block' && this.allowlist.has(this.sender()?.address),
  );
  protected readonly ref = computed<ItemRefDto>(() => ({
    type: 'EMAIL',
    id: this.message()?.id ?? this.id(),
  }));
  /** Real attachments; inline parts (`cid:` images) are part of the body. */
  protected readonly attachments = computed(() =>
    (this.message()?.attachments ?? []).filter((part) => !part.inline && !!part.partId),
  );
  protected readonly imagesBlocked = computed(
    () => !!this.message()?.body?.remoteImagesBlocked && !this.loadImages(),
  );

  constructor() {
    inject(DestroyRef).onDestroy(() => (this.destroyed = true));
    inject(PageShortcuts).register({
      title: $localize`:@@mailRead.shortcutsTitle:Message`,
      shortcuts: MAIL_READ_HELP,
    });
    effect(() => {
      const id = this.id();
      if (id) untracked(() => void this.open(id));
    });
  }

  protected retry(): void {
    void this.open(this.id());
  }

  /** Shows the remote images of this message. */
  protected async showImages(): Promise<void> {
    const message = this.message();
    if (!message?.id || this.loadImages()) return;
    const seq = ++this.seq;
    this.loadingImages.set(true);
    try {
      const proxied = await this.service.get(message.id, true);
      if (seq !== this.seq) return;
      this.message.set(proxied);
      this.loadImages.set(true);
    } catch (error) {
      if (seq === this.seq) this.notifications.error(messageOf(error));
    } finally {
      if (seq === this.seq) this.loadingImages.set(false);
    }
  }

  /**
   * Adds the sender to the synced allowed senders and shows the images. A failed save is toasted
   * by the HTTP layer and rolled back; the images of this message show anyway.
   */
  protected async alwaysShowImages(): Promise<void> {
    const address = this.sender()?.address;
    if (address) this.allowlist.add(address).catch(() => undefined);
    await this.showImages();
  }

  /** Removes the sender from the allowed senders (a failed save is toasted and rolled back). */
  protected forgetSender(): void {
    const address = this.sender()?.address;
    if (address) this.allowlist.remove(address).catch(() => undefined);
  }

  protected async gtd(action: MailGtdAction): Promise<void> {
    const id = this.message()?.id;
    if (!id || this.busy()) return;
    this.busy.set(action);
    try {
      const item = await this.service.gtd(id, action);
      const title = item.title;
      this.notifications.info(
        action === 'note'
          ? $localize`:@@mailRead.noteCreated:Note created: ${title}:title:`
          : action === 'waiting'
            ? $localize`:@@mailRead.waitingCreated:Added to Waiting For: ${title}:title:`
            : $localize`:@@mailRead.taskCreated:Added to Inbox: ${title}:title:`,
      );
      await this.links()?.reload();
    } catch (error) {
      this.notifications.error(messageOf(error));
    } finally {
      this.busy.set(null);
    }
  }

  /** "→ Next action & archive" (`Shift+T`) is running. */
  protected readonly makingNextAction = signal(false);

  /**
   * "→ Next action & archive": a Next action linked to the message, then the message moves to the
   * Archive. The task's detail opens in place of the message, so Back returns to where the message
   * was opened from. A failed archive keeps the task (its detail still opens) and says so; a failed
   * task creation keeps the message open.
   */
  protected async toNextAction(): Promise<void> {
    const id = this.message()?.id;
    if (!id || !this.canAct()) return;
    const subject = this.subject();
    this.makingNextAction.set(true);
    try {
      const [result] = await this.mail.toNextActions([id]);
      if (!result) throw new Error('no result');
      if (result.archived) {
        // The list it was opened from no longer shows it.
        void this.session.remove(id).catch(() => undefined);
      } else {
        this.notifications.error(nextActionArchiveFailed(subject, result));
      }
      if (this.destroyed || this.message()?.id !== id) return;
      await this.router.navigate(['/tasks', result.task.id], { replaceUrl: true });
    } catch (error) {
      this.notifications.error(nextActionFailed(messageOf(error)));
    } finally {
      this.makingNextAction.set(false);
    }
  }

  /**
   * Archives, marks as spam or deletes the message with the list's mail action (the server moves it
   * to the account's Archive, Spam or Trash folder, or removes it from Trash) and returns to the list
   * it was opened from. A failure (e.g. no Archive or Spam folder) keeps the message open.
   */
  protected async triage(action: TriageAction): Promise<void> {
    const id = this.message()?.id;
    if (!id || !this.canAct()) return;
    await this.run(action, id, () => this.leave(id));
  }

  /**
   * Marks the message unread and returns to where it was opened from (staying would show it as
   * read), as Gmail does.
   */
  protected async markUnread(): Promise<void> {
    const id = this.message()?.id;
    if (!id || !this.canAct()) return;
    this.triaging.set('UNREAD');
    // Marking it read on opening must not land after this.
    await this.marking;
    await this.run('UNREAD', id, async () => {
      this.session.markedUnread(id);
      if (this.destroyed || this.message()?.id !== id) return;
      this.message.update((shown) => (shown ? { ...shown, seen: false } : shown));
      await this.backNavigation.back('/mail', { replaceUrl: true });
    });
  }

  /** Stars the message, or removes its star; it stays open. */
  protected async toggleFlag(): Promise<void> {
    const shown = this.message();
    const id = shown?.id;
    if (!id || !this.canAct()) return;
    const flagged = !shown.flagged;
    await this.run(flagged ? 'FLAG' : 'UNFLAG', id, async () => {
      this.session.flagged(id, flagged);
      this.message.update((current) => (current?.id === id ? { ...current, flagged } : current));
    });
  }

  /**
   * Moves the message to a folder of its account picked in the move dialog, then goes on like
   * Archive (the next message of the list, else back to it).
   */
  protected async move(): Promise<void> {
    const shown = this.message();
    const id = shown?.id;
    const accountId = shown?.accountId;
    if (!id || !accountId || !this.canAct() || this.dialog.openDialogs.length) return;
    this.triaging.set('MOVE');
    let target: string | undefined;
    try {
      const folders = moveTargetsOf(folderTree(await this.mail.folders(accountId)), shown.folderId);
      if (this.destroyed || this.message()?.id !== id) return;
      target = await firstValueFrom(
        this.dialog
          .open<MailMoveDialog, MailMoveDialogData, string>(MailMoveDialog, {
            data: { folders },
            width: '24rem',
          })
          .afterClosed(),
      );
    } catch (error) {
      this.notifications.error(actionFailed('MOVE', messageOf(error)));
    } finally {
      this.triaging.set(null);
    }
    if (!target || this.destroyed || this.message()?.id !== id) return;
    await this.run('MOVE', id, () => this.leave(id), target);
  }

  /** No mail action is running: neither a triage action nor "→ Next action & archive". */
  private canAct(): boolean {
    return this.state() === 'ready' && !this.triaging() && !this.makingNextAction();
  }

  /**
   * Applies `action` to message `id`, then `done`. A failure is toasted and keeps the message open.
   */
  private async run(
    action: ReadAction,
    id: string,
    done: () => Promise<void>,
    targetFolderId?: string,
  ): Promise<void> {
    this.triaging.set(action);
    try {
      const results = await this.mail.apply(action, [id], targetFolderId);
      const result = results.find((entry) => entry.id === id);
      if (!result?.ok) {
        const reason = result?.detail || $localize`:@@mailRead.unexpected:Something went wrong.`;
        this.notifications.error(actionFailed(action, reason));
        return;
      }
      await done();
    } catch (error) {
      this.notifications.error(actionFailed(action, messageOf(error)));
    } finally {
      this.triaging.set(null);
    }
  }

  /** Message `id` left the list it was opened from: on to its next message, else back to the list. */
  private async leave(id: string): Promise<void> {
    // The list it was opened from no longer shows it; the message to show instead, if any (this
    // may load the next page of the list).
    const following = await this.session.remove(id);
    // The user may have left this message (or the page) while the request ran: stay there.
    if (this.destroyed || this.message()?.id !== id) return;
    if (following) {
      // On to the next message of the list, in place of this one: Back still returns to the list.
      await this.router.navigate(['/mail/messages', following], { replaceUrl: true });
      return;
    }
    // Back to where the message was opened from (or to Mail, replacing the message in the history,
    // so Back does not open it again).
    await this.backNavigation.back('/mail', { replaceUrl: true });
  }

  /**
   * Shows the previous (`-1`) or next (`1`) message of the list the message was opened from, in
   * place of this one (Back still returns to the list).
   */
  protected async step(direction: 1 | -1): Promise<void> {
    const id = this.id();
    if (!this.position() || this.stepping()) return;
    this.stepping.set(true);
    try {
      const target = await this.session.step(id, direction);
      // The user may have moved on (or left) while more of the list loaded.
      if (!target || this.destroyed || this.id() !== id) return;
      await this.router.navigate(['/mail/messages', target], { replaceUrl: true });
    } finally {
      this.stepping.set(false);
    }
  }

  /**
   * `e` archives, `!` marks as spam and `#` deletes, as in the list; `u` marks unread, `s` stars and
   * `v` moves; `r`, `a` and `f` reply, reply to all and forward; `c` writes a new message; `j`/`k`
   * step to the next and previous message of the list it was opened from (never while typing or
   * while a dialog is open).
   */
  protected onKeydown(event: KeyboardEvent): void {
    if (this.dialog.openDialogs.length) return;
    const shortcut = mailShortcutFor(event);
    if (!shortcut) return;
    if ((shortcut === 'next' || shortcut === 'previous') && this.position()) {
      event.preventDefault();
      void this.step(shortcut === 'next' ? 1 : -1);
      return;
    }
    if (shortcut === 'compose') {
      event.preventDefault();
      void this.router.navigate(['/mail/compose']);
      return;
    }
    const id = this.message()?.id;
    if (this.state() !== 'ready' || !id) return;
    const respond = RESPOND_SHORTCUTS.get(shortcut);
    if (respond) {
      event.preventDefault();
      void this.router.navigate(['/mail/compose'], { queryParams: { [respond]: id } });
      return;
    }
    const action = this.shortcutAction(shortcut);
    if (!action) return;
    event.preventDefault();
    void action();
  }

  private shortcutAction(shortcut: MailShortcut): (() => Promise<void>) | null {
    switch (shortcut) {
      case 'archive':
        return () => this.triage('ARCHIVE');
      case 'spam':
        return () => this.triage('SPAM');
      case 'delete':
        return () => this.triage('DELETE');
      case 'unread':
        return () => this.markUnread();
      case 'flag':
        return () => this.toggleFlag();
      case 'move':
        return () => this.move();
      case 'nextAction':
        return () => this.toNextAction();
      default:
        return null;
    }
  }

  protected downloadUrl(part: MailAttachmentResponse): string {
    return attachmentUrl(this.message()!.id!, part.partId!);
  }

  protected canPreview(part: MailAttachmentResponse): boolean {
    return previewKind(part) !== null;
  }

  protected async showPreview(part: MailAttachmentResponse): Promise<void> {
    const id = this.message()?.id;
    const partId = part.partId;
    if (!id || !partId) return;
    const name = part.filename;
    if (previewKind(part) === 'image') {
      this.preview.set({ partId, name, kind: 'image', url: attachmentUrl(id, partId, true) });
      return;
    }
    // Only this very preview may take the answer: part ids repeat across messages, and the user may
    // have moved to another message or preview meanwhile.
    const pending: Preview = { partId, name, kind: 'text', text: null, error: null };
    this.preview.set(pending);
    try {
      const text = (await (await this.service.attachment(id, partId)).text()).slice(
        0,
        TEXT_PREVIEW_LIMIT,
      );
      if (this.preview() === pending) {
        this.preview.set({ partId, name, kind: 'text', text, error: null });
      }
    } catch (error) {
      if (this.preview() === pending) {
        this.preview.set({ partId, name, kind: 'text', text: null, error: messageOf(error) });
      }
    }
  }

  protected closePreview(): void {
    this.preview.set(null);
  }

  protected async saveToFiles(part: MailAttachmentResponse): Promise<void> {
    const id = this.message()?.id;
    const partId = part.partId;
    if (!id || !partId) return;
    const key = savingKey(id, partId);
    if (this.saving().has(key)) return;
    this.saving.update((keys) => new Set(keys).add(key));
    try {
      const file = await this.service.saveToFiles(id, partId);
      const name = file.name;
      this.notifications.info($localize`:@@mailRead.savedToFiles:Saved to Files: ${name}:name:`);
    } catch (error) {
      this.notifications.error(messageOf(error));
    } finally {
      this.saving.update((keys) => {
        const rest = new Set(keys);
        rest.delete(key);
        return rest;
      });
    }
  }

  /** Whether this attachment of the shown message is being saved to Files. */
  protected isSaving(part: MailAttachmentResponse): boolean {
    const id = this.message()?.id;
    return !!id && !!part.partId && this.saving().has(savingKey(id, part.partId));
  }

  protected size(part: MailAttachmentResponse): string {
    return formatBytes(part.sizeBytes);
  }

  protected name(address: MailAddressResponse): string {
    return address.name || address.address || '';
  }

  protected list(addresses: MailAddressResponse[] | undefined): string {
    return (addresses ?? []).map((address) => this.name(address)).join(', ');
  }

  protected subject(): string {
    return this.message()?.subject || $localize`:@@mailRead.noSubject:(no subject)`;
  }

  private async open(id: string): Promise<void> {
    const seq = ++this.seq;
    this.state.set('loading');
    this.message.set(null);
    this.preview.set(null);
    this.loadImages.set(false);
    this.loadingImages.set(false);
    try {
      await this.allowlist.ready();
      if (seq !== this.seq) return;
      // With the policy "allow" every mail loads its images; an allowed sender is only known from
      // the message, so its images are requested in a second load.
      const allowAll = this.allowlist.policy() === 'allow';
      let message = await this.service.get(id, allowAll);
      if (seq !== this.seq) return;
      if (allowAll) this.loadImages.set(true);
      const sender = message.from?.[0]?.address;
      if (
        !allowAll &&
        message.body?.remoteImagesBlocked &&
        this.allowlist.loadsImagesFrom(sender) &&
        message.id
      ) {
        try {
          const proxied = await this.service.get(message.id, true);
          if (seq !== this.seq) return;
          message = proxied;
          this.loadImages.set(true);
        } catch (error) {
          // The blocked copy is readable: show it (its banner offers "Load images" again).
          if (seq !== this.seq || this.destroyed) return;
          this.notifications.error(messageOf(error));
        }
      }
      this.message.set(message);
      this.state.set('ready');
      this.session.visited(id);
      if (!message.seen && message.id) {
        // Best effort: the list shows the change through its events, a failure only keeps it unread.
        this.marking = this.service.markRead(message.id).catch(() => undefined);
      }
    } catch (error) {
      if (seq !== this.seq || this.destroyed) return;
      if (error instanceof ApiError && error.status === 404) {
        this.state.set('notFound');
      } else {
        this.loadError.set(messageOf(error));
        this.state.set('failed');
      }
    }
  }
}

function savingKey(messageId: string, partId: string): string {
  return `${messageId}/${partId}`;
}

function previewKind(part: MailAttachmentResponse): 'image' | 'text' | null {
  const type = (part.contentType ?? '').toLowerCase().split(';')[0].trim();
  if (['image/png', 'image/jpeg', 'image/gif', 'image/webp'].includes(type)) return 'image';
  if (type.startsWith('text/')) return 'text';
  return null;
}

function actionFailed(action: ReadAction, reason: string): string {
  switch (action) {
    case 'ARCHIVE':
      return $localize`:@@mailRead.archiveFailed:The message could not be archived: ${reason}:reason:`;
    case 'SPAM':
      return $localize`:@@mailRead.spamFailed:The message could not be marked as spam: ${reason}:reason:`;
    case 'DELETE':
      return $localize`:@@mailRead.deleteFailed:The message could not be deleted: ${reason}:reason:`;
    case 'UNREAD':
      return $localize`:@@mailRead.unreadFailed:The message could not be marked as unread: ${reason}:reason:`;
    case 'FLAG':
      return $localize`:@@mailRead.flagFailed:The message could not be starred: ${reason}:reason:`;
    case 'UNFLAG':
      return $localize`:@@mailRead.unflagFailed:The star could not be removed: ${reason}:reason:`;
    case 'MOVE':
      return $localize`:@@mailRead.moveFailed:The message could not be moved: ${reason}:reason:`;
  }
}

function messageOf(error: unknown): string {
  return error instanceof ApiError
    ? error.userMessage
    : $localize`:@@mailRead.unexpected:Something went wrong.`;
}
