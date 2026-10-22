import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  DraftAttachmentRequest,
  DraftContentRequest,
  FileResponse,
  MailAccountResponse,
  MailAddressRequest,
  MailAddressResponse,
  MailAttachmentResponse,
} from '../../../api/generated';
import { ApiError, NotificationService } from '../../../core/http';
import { formatBytes } from '../read/format-bytes';
import { FilePickerDialog } from './file-picker-dialog';
import { MailComposeService, ResponseMode, VersionedDraft } from './mail-compose.service';
import { isValidAddress, Recipient, RecipientInput } from './recipient-input';
import { RichTextEditor } from './rich-text-editor';

/** Unsaved changes are saved to the Drafts folder this often (SPEC.md §7.3). */
export const AUTOSAVE_INTERVAL_MS = 10_000;

type State = 'loading' | 'failed' | 'noAccount' | 'ready';
type Busy = 'send' | 'discard' | 'attach';
type SaveState =
  | { kind: 'unsaved' }
  | { kind: 'saving' }
  | { kind: 'saved'; at: string | null }
  | { kind: 'failed'; message: string }
  | { kind: 'conflict' };

/**
 * Writing a mail (SPEC.md §7.3): a new one (`/mail/compose`), a reply, reply to all or forward of a
 * message (`?reply=`, `?replyAll=`, `?forward=` with its id: the server starts the draft) or a
 * stored draft (`?draft=`). The sender (one per account), To/Cc/Bcc with suggestions, subject, a
 * rich text body and attachments (uploaded or from Files). Changes are saved to the account's
 * Drafts folder every 10 seconds; every write waits for the one before, since each needs the
 * version (`If-Match`) the previous one returned.
 */
@Component({
  selector: 'app-mail-compose-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    RecipientInput,
    RichTextEditor,
    RouterLink,
  ],
  templateUrl: './mail-compose-page.html',
  styleUrl: './mail-compose-page.scss',
})
export default class MailComposePage {
  private readonly service = inject(MailComposeService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly route = inject(ActivatedRoute);

  protected readonly state = signal<State>('loading');
  protected readonly loadError = signal<string | null>(null);
  protected readonly accounts = signal<MailAccountResponse[]>([]);
  protected readonly accountId = signal<string | null>(null);
  protected readonly to = signal<Recipient[]>([]);
  protected readonly cc = signal<Recipient[]>([]);
  protected readonly bcc = signal<Recipient[]>([]);
  protected readonly showCopies = signal(false);
  protected readonly subject = signal('');
  /** The body the editor starts with. */
  protected readonly startHtml = signal('');
  protected readonly attachments = signal<MailAttachmentResponse[]>([]);
  protected readonly saveState = signal<SaveState>({ kind: 'saved', at: null });
  protected readonly busy = signal<Busy | null>(null);
  protected readonly sendError = signal<string | null>(null);
  /** Whether the draft is stored (it has an id and a version). */
  protected readonly stored = signal(false);
  private readonly isReply = signal(false);

  /** Real attachments; inline parts (`cid:` images of a forward) belong to the body. */
  protected readonly shownAttachments = computed(() =>
    this.attachments().filter((part) => !part.inline),
  );
  /**
   * The sender can change until the draft holds what a new draft in another account could not take
   * along: attachments, or the threading headers of a reply.
   */
  protected readonly canChangeSender = computed(
    () => !this.stored() || (!this.isReply() && this.attachments().length === 0),
  );

  protected readonly savedAt = computed(() => {
    const state = this.saveState();
    return state.kind === 'saved' ? state.at : null;
  });
  protected readonly saveProblem = computed(() => {
    const state = this.saveState();
    return state.kind === 'failed' ? state.message : '';
  });

  private readonly editor = viewChild(RichTextEditor);

  private draftId: string | null = null;
  private etag: string | null = null;
  private identityId: string | null = null;
  private html = '';
  /** Counts the edits; `savedRevision` is the one stored in the Drafts folder. */
  private revision = 0;
  private savedRevision = 0;
  /** The writes to the server, one after the other. */
  private queue: Promise<unknown> = Promise.resolve();
  /** Sent or discarded: nothing is saved any more. */
  private finished = false;
  private timer?: ReturnType<typeof setInterval>;

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      clearInterval(this.timer);
      // Leaving with unsaved changes: they still go to the Drafts folder.
      if (this.dirty()) void this.save();
    });
    void this.open();
  }

  protected retry(): void {
    void this.open();
  }

  protected toggleCopies(): void {
    this.showCopies.update((shown) => !shown);
  }

  protected setTo(recipients: Recipient[]): void {
    this.to.set(recipients);
    this.edited();
  }

  protected setCc(recipients: Recipient[]): void {
    this.cc.set(recipients);
    this.edited();
  }

  protected setBcc(recipients: Recipient[]): void {
    this.bcc.set(recipients);
    this.edited();
  }

  protected setSubject(subject: string): void {
    this.subject.set(subject);
    this.edited();
  }

  protected setBody(html: string): void {
    this.html = html;
    this.edited();
  }

  protected accountLabel(account: MailAccountResponse): string {
    return addressLabel(account.displayName, account.emailAddress);
  }

  /** Another sender: before the first save just the account; afterwards the draft moves to it. */
  protected changeSender(accountId: string): void {
    if (accountId === this.accountId() || !this.canChangeSender()) return;
    this.accountId.set(accountId);
    this.identityId = null;
    if (!this.stored()) {
      this.edited();
      return;
    }
    void this.write(async () => {
      const old = { id: this.draftId!, etag: this.etag! };
      const revision = this.revision;
      this.apply(await this.service.create({ accountId, ...this.content() }), revision);
      // The copy in the other account's Drafts folder is no longer needed.
      await this.service.discard(old.id, old.etag).catch(() => undefined);
    });
  }

  protected size(part: MailAttachmentResponse): string {
    return formatBytes(part.sizeBytes);
  }

  protected async upload(input: HTMLInputElement): Promise<void> {
    const files = [...(input.files ?? [])];
    input.value = '';
    if (!files.length) return;
    await this.attach(async () => {
      await this.ensureStored();
      for (const file of files) {
        this.apply(await this.service.upload(this.draftId!, this.etag!, file));
      }
    });
  }

  protected async pickFile(): Promise<void> {
    const file = await new Promise<FileResponse | undefined>((resolve) =>
      this.dialog
        .open(FilePickerDialog, { width: '32rem', autoFocus: 'first-tabbable' })
        .afterClosed()
        .subscribe(resolve),
    );
    if (!file) return;
    await this.attach(() => this.storeContent([...this.keptAttachments(), { fileId: file.id }]));
  }

  protected async removeAttachment(part: MailAttachmentResponse): Promise<void> {
    await this.attach(() =>
      this.storeContent(this.keptAttachments().filter((kept) => kept.partId !== part.partId)),
    );
  }

  protected async send(): Promise<void> {
    if (this.busy()) return;
    const problem = this.recipientProblem();
    this.sendError.set(problem);
    if (problem) return;
    this.busy.set('send');
    try {
      const sent = await this.write(async () => {
        if (!this.stored() || this.dirty()) await this.storeContent();
        const result = await this.service.send(this.draftId!, this.etag!);
        this.finished = true;
        return result;
      });
      clearInterval(this.timer);
      this.notifications.info(
        sent.appendedToSent || !this.ownSent()
          ? $localize`:@@mailCompose.sent:Message sent`
          : $localize`:@@mailCompose.sentNoCopy:Message sent, but no copy was saved in Sent`,
      );
      void this.router.navigate(['/mail']);
    } catch (error) {
      this.sendError.set(messageOf(error));
    } finally {
      this.busy.set(null);
    }
  }

  protected async discard(): Promise<void> {
    if (this.busy()) return;
    this.busy.set('discard');
    try {
      await this.write(async () => {
        if (this.draftId && this.etag) await this.service.discard(this.draftId, this.etag);
        this.finished = true;
      });
      clearInterval(this.timer);
      this.notifications.info($localize`:@@mailCompose.discarded:Draft discarded`);
      void this.router.navigate(['/mail']);
    } catch (error) {
      this.notifications.error(messageOf(error));
    } finally {
      this.busy.set(null);
    }
  }

  /** Saves the changes now (also what the autosave runs). */
  protected save(): Promise<void> {
    return this.write(async () => {
      if (!this.dirty() || this.finished) return;
      await this.storeContent();
    }).catch(() => undefined);
  }

  private async open(): Promise<void> {
    this.state.set('loading');
    const params = this.route.snapshot.queryParamMap;
    try {
      const accounts = await this.service.accounts();
      this.accounts.set(accounts);
      const draftId = params.get('draft');
      const source = (['reply', 'replyAll', 'forward'] as const).find((mode) => params.has(mode));
      let loaded: VersionedDraft | null = null;
      if (draftId) {
        loaded = await this.service.get(draftId);
      } else if (source) {
        loaded = await this.service.respond(params.get(source)!, source satisfies ResponseMode);
        // A reload opens this draft instead of starting another one.
        void this.router.navigate([], {
          relativeTo: this.route,
          queryParams: { draft: loaded.draft.id },
          replaceUrl: true,
        });
      }
      if (loaded) {
        this.load(loaded);
      } else if (accounts.length) {
        this.accountId.set(accounts[0].id);
      } else {
        this.state.set('noAccount');
        return;
      }
      this.state.set('ready');
      this.timer = setInterval(() => {
        if (this.dirty() && this.saveState().kind !== 'conflict') void this.save();
      }, AUTOSAVE_INTERVAL_MS);
    } catch (error) {
      this.loadError.set(messageOf(error));
      this.state.set('failed');
    }
  }

  /** Shows a stored draft (opened, or started from a message). */
  private load(loaded: VersionedDraft): void {
    const { draft } = loaded;
    this.accountId.set(draft.accountId ?? null);
    this.to.set(recipients(draft.to));
    this.cc.set(recipients(draft.cc));
    this.bcc.set(recipients(draft.bcc));
    this.showCopies.set(this.cc().length + this.bcc().length > 0);
    this.subject.set(draft.subject ?? '');
    this.html = draft.html ?? '';
    this.startHtml.set(this.html);
    this.isReply.set(!!draft.inReplyTo);
    this.apply(loaded);
  }

  /** Takes over what the server stored: the version, the attachments, when. */
  private apply(saved: VersionedDraft, revision?: number): void {
    this.draftId = saved.draft.id;
    this.etag = saved.etag;
    this.identityId = saved.draft.identityId ?? null;
    this.attachments.set(saved.draft.attachments ?? []);
    this.stored.set(true);
    if (revision !== undefined) this.savedRevision = revision;
    this.saveState.set(
      this.dirty() ? { kind: 'unsaved' } : { kind: 'saved', at: saved.draft.savedAt ?? null },
    );
  }

  /** Saves the content (and, when given, the attachments to keep) as a new version. */
  private async storeContent(attachments?: DraftAttachmentRequest[]): Promise<void> {
    const revision = this.revision;
    const content: DraftContentRequest = { ...this.content(), attachments };
    this.saveState.set({ kind: 'saving' });
    try {
      this.apply(
        this.draftId && this.etag
          ? await this.service.update(this.draftId, this.etag, content)
          : await this.service.create({ accountId: this.accountId()!, ...content }),
        revision,
      );
    } catch (error) {
      this.saveState.set(
        error instanceof ApiError && error.status === 412
          ? { kind: 'conflict' }
          : { kind: 'failed', message: messageOf(error) },
      );
      throw error;
    }
  }

  private async ensureStored(): Promise<void> {
    if (!this.stored()) await this.storeContent();
  }

  /** Runs an attachment change; failures are reported, not thrown. */
  private async attach(change: () => Promise<void>): Promise<void> {
    if (this.busy()) return;
    this.busy.set('attach');
    try {
      await this.write(change);
    } catch (error) {
      this.notifications.error(messageOf(error));
    } finally {
      this.busy.set(null);
    }
  }

  /** Queues a write behind the running one. */
  private write<T>(work: () => Promise<T>): Promise<T> {
    const run = this.queue.then(work, work);
    this.queue = run.catch(() => undefined);
    return run;
  }

  /** The draft as the server takes it; invalid addresses wait in the view until they are fixed. */
  private content(): DraftContentRequest {
    return {
      identityId: this.identityId ?? undefined,
      to: addresses(this.to()),
      cc: addresses(this.cc()),
      bcc: addresses(this.bcc()),
      subject: this.subject(),
      html: this.editor()?.getHtml() ?? this.html,
    };
  }

  /** The current attachments, all kept (inline parts too). */
  private keptAttachments(): DraftAttachmentRequest[] {
    return this.attachments()
      .filter((part) => !!part.partId)
      .map((part) => ({ partId: part.partId }));
  }

  private recipientProblem(): string | null {
    const all = [...this.to(), ...this.cc(), ...this.bcc()];
    const invalid = all.find((recipient) => !isValidAddress(recipient.address));
    if (invalid) {
      const address = invalid.address;
      return $localize`:@@mailCompose.invalidRecipient:${address}:address: is not a valid email address.`;
    }
    if (!this.to().length) return $localize`:@@mailCompose.noRecipient:Add a recipient in To.`;
    return null;
  }

  /** Whether this account's server stores sent mail itself (Google, Microsoft) or the cockpit does. */
  private ownSent(): boolean {
    return this.accounts().find((a) => a.id === this.accountId())?.type === 'IMAP_SMTP';
  }

  private edited(): void {
    this.revision++;
    if (this.saveState().kind !== 'conflict') this.saveState.set({ kind: 'unsaved' });
  }

  private dirty(): boolean {
    return !this.finished && this.revision !== this.savedRevision;
  }
}

function recipients(list: MailAddressResponse[] | undefined): Recipient[] {
  return (list ?? [])
    .filter((address) => !!address.address)
    .map((address) =>
      address.name
        ? { address: address.address!, name: address.name }
        : { address: address.address! },
    );
}

function addresses(list: Recipient[]): MailAddressRequest[] {
  return list.filter((recipient) => isValidAddress(recipient.address));
}

function addressLabel(name: string, address: string): string {
  return name ? `${name} <${address}>` : address;
}

function messageOf(error: unknown): string {
  return error instanceof ApiError
    ? error.userMessage
    : $localize`:@@mailCompose.unexpected:Something went wrong.`;
}
