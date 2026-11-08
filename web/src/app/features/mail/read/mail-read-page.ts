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
import { ActivatedRoute, RouterLink } from '@angular/router';
import { map } from 'rxjs';
import {
  ItemRefDto,
  MailAddressResponse,
  MailAttachmentResponse,
  MailMessageResponse,
} from '../../../api/generated';
import { ApiError, NotificationService } from '../../../core/http';
import { LinkedItems } from '../../../shared/links';
import { BackButton, BackNavigation } from '../../../shared/navigation';
import { MailAction, MailService } from '../list/mail.service';
import { MailShortcut, mailShortcutFor } from '../list/mail-shortcuts';
import { formatBytes } from './format-bytes';
import { ImageAllowlist } from './image-allowlist';
import { MailFrame } from './mail-frame';
import { attachmentUrl, MailGtdAction, MailReadService } from './mail-read.service';

type State = 'loading' | 'notFound' | 'failed' | 'ready';

/** The mail actions that take the message out of the list it was opened from. */
type TriageAction = Extract<MailAction, 'ARCHIVE' | 'SPAM' | 'DELETE'>;

/** The list's shortcuts that the read view takes too. */
const TRIAGE_SHORTCUTS: ReadonlyMap<MailShortcut, TriageAction> = new Map<
  MailShortcut,
  TriageAction
>([
  ['archive', 'ARCHIVE'],
  ['spam', 'SPAM'],
  ['delete', 'DELETE'],
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
  /** The Archive, Spam or Delete request that runs (one at a time). */
  protected readonly triaging = signal<TriageAction | null>(null);

  /** The linked items panel (by reference, so tests can stand in for it). */
  private readonly links = viewChild<{ reload(): Promise<void> }>('links');
  /** Counts the loads: only the latest one may apply its result. */
  private seq = 0;
  private destroyed = false;

  protected readonly sender = computed<MailAddressResponse | null>(
    () => this.message()?.from?.[0] ?? null,
  );
  protected readonly senderAllowed = computed(() =>
    this.allowlist.has(this.sender()?.address ?? undefined),
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

  /** Remembers the sender and shows the images. */
  protected async alwaysShowImages(): Promise<void> {
    const address = this.sender()?.address;
    if (address) this.allowlist.add(address);
    await this.showImages();
  }

  protected forgetSender(): void {
    const address = this.sender()?.address;
    if (address) this.allowlist.remove(address);
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

  /**
   * Archives, marks as spam or deletes the message with the list's mail action (the server moves it
   * to the account's Archive, Spam or Trash folder, or removes it from Trash) and returns to the list
   * it was opened from. A failure (e.g. no Archive or Spam folder) keeps the message open.
   */
  protected async triage(action: TriageAction): Promise<void> {
    const id = this.message()?.id;
    if (!id || this.state() !== 'ready' || this.triaging()) return;
    this.triaging.set(action);
    try {
      const results = await this.mail.apply(action, [id]);
      const result = results.find((entry) => entry.id === id);
      if (!result?.ok) {
        const reason = result?.detail || $localize`:@@mailRead.unexpected:Something went wrong.`;
        this.notifications.error(triageFailed(action, reason));
        return;
      }
      // The user may have left this message (or the page) while the request ran: stay there.
      if (this.destroyed || this.message()?.id !== id) return;
      // Back to where the message was opened from (or to Mail, replacing the message in the history,
      // so Back does not open it again).
      await this.backNavigation.back('/mail', { replaceUrl: true });
    } catch (error) {
      this.notifications.error(triageFailed(action, messageOf(error)));
    } finally {
      this.triaging.set(null);
    }
  }

  /**
   * `e` archives, `!` marks as spam and `#` deletes, as in the list (never while typing or while a
   * dialog is open).
   */
  protected onKeydown(event: KeyboardEvent): void {
    if (this.dialog.openDialogs.length || this.state() !== 'ready') return;
    const shortcut = mailShortcutFor(event);
    const action = shortcut ? TRIAGE_SHORTCUTS.get(shortcut) : undefined;
    if (!action) return;
    event.preventDefault();
    void this.triage(action);
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
      let message = await this.service.get(id, false);
      if (seq !== this.seq) return;
      const sender = message.from?.[0]?.address;
      if (message.body?.remoteImagesBlocked && this.allowlist.has(sender) && message.id) {
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
      if (!message.seen && message.id) {
        // Best effort: the list shows the change through its events, a failure only keeps it unread.
        this.service.markRead(message.id).catch(() => undefined);
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

function triageFailed(action: TriageAction, reason: string): string {
  switch (action) {
    case 'ARCHIVE':
      return $localize`:@@mailRead.archiveFailed:The message could not be archived: ${reason}:reason:`;
    case 'SPAM':
      return $localize`:@@mailRead.spamFailed:The message could not be marked as spam: ${reason}:reason:`;
    case 'DELETE':
      return $localize`:@@mailRead.deleteFailed:The message could not be deleted: ${reason}:reason:`;
  }
}

function messageOf(error: unknown): string {
  return error instanceof ApiError
    ? error.userMessage
    : $localize`:@@mailRead.unexpected:Something went wrong.`;
}
