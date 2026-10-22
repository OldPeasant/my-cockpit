import { Component, input, output } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import {
  CreateDraftRequest,
  DraftContentRequest,
  DraftResponse,
  MailAccountResponse,
} from '../../../api/generated';
import { ApiError, NotificationService } from '../../../core/http';
import MailComposePage, { AUTOSAVE_INTERVAL_MS } from './mail-compose-page';
import { MailComposeService, ResponseMode, VersionedDraft } from './mail-compose.service';
import { RichTextEditor } from './rich-text-editor';

const ACCOUNT = '0192f0c4-0000-7000-8000-0000000acc01';
const OTHER_ACCOUNT = '0192f0c4-0000-7000-8000-0000000acc02';
const DRAFT = '0192f0c4-0000-7000-8000-00000000d001';
const MESSAGE = '0192f0c4-0000-7000-8000-00000000a001';

@Component({ selector: 'app-rich-text-editor', template: '' })
class StubEditor {
  readonly html = input('');
  readonly label = input('');
  readonly changed = output<string>();
}

function account(id: string, email: string, type: MailAccountResponse['type'] = 'IMAP_SMTP') {
  return { id, displayName: 'Jane Doe', emailAddress: email, type } as MailAccountResponse;
}

function draft(extra: Partial<DraftResponse> = {}): DraftResponse & { id: string } {
  return {
    id: DRAFT,
    accountId: ACCOUNT,
    folderId: 'f-drafts',
    identityId: 'ident-1',
    from: { name: 'Jane Doe', address: 'jane@example.test' },
    to: [],
    cc: [],
    bcc: [],
    subject: '',
    html: '',
    text: '',
    inReplyTo: '',
    references: '',
    attachments: [],
    savedAt: '2026-10-22T08:00:00Z',
    ...extra,
  };
}

class FakeComposeService {
  accountList = [account(ACCOUNT, 'jane@example.test'), account(OTHER_ACCOUNT, 'jd@work.test')];
  creates: CreateDraftRequest[] = [];
  updates: { id: string; etag: string; body: DraftContentRequest }[] = [];
  uploads: { id: string; etag: string; name: string }[] = [];
  sends: { id: string; etag: string }[] = [];
  discards: { id: string; etag: string }[] = [];
  responds: { id: string; mode: ResponseMode }[] = [];
  gets: string[] = [];
  version = 1;
  stored = draft();
  updateError?: Error;
  sendError?: Error;

  private next(extra: Partial<DraftResponse> = {}): Promise<VersionedDraft> {
    this.stored = { ...this.stored, ...extra };
    return Promise.resolve({ draft: this.stored, etag: `"${this.version++}"` });
  }

  accounts() {
    return Promise.resolve(this.accountList);
  }
  get(id: string) {
    this.gets.push(id);
    return this.next();
  }
  create(body: CreateDraftRequest) {
    this.creates.push(body);
    return this.next({ accountId: body.accountId });
  }
  update(id: string, etag: string, body: DraftContentRequest) {
    this.updates.push({ id, etag, body });
    if (this.updateError) return Promise.reject(this.updateError);
    const attachments = body.attachments
      ? body.attachments.map((a, i) => ({
          partId: a.partId ?? `${i + 2}`,
          filename: a.fileId ? 'from-files.pdf' : `part-${a.partId}`,
          sizeBytes: 10,
          contentId: '',
        }))
      : this.stored.attachments;
    return this.next({ attachments });
  }
  upload(id: string, etag: string, file: File) {
    this.uploads.push({ id, etag, name: file.name });
    const attachments = [
      ...(this.stored.attachments ?? []),
      { partId: '9', filename: file.name, sizeBytes: file.size, contentId: '' },
    ];
    return this.next({ attachments });
  }
  send(id: string, etag: string) {
    this.sends.push({ id, etag });
    return this.sendError
      ? Promise.reject(this.sendError)
      : Promise.resolve({ sentMessageId: 'sent-1', appendedToSent: true, draftRemoved: true });
  }
  discard(id: string, etag: string) {
    this.discards.push({ id, etag });
    return Promise.resolve();
  }
  respond(id: string, mode: ResponseMode) {
    this.responds.push({ id, mode });
    return this.next({
      to: [{ name: 'Max Muster', address: 'max@example.test' }],
      subject: 'Re: Budget',
      html: '<p></p><blockquote><p>Original</p></blockquote>',
      inReplyTo: '<1@example.test>',
    });
  }
  suggestions() {
    return Promise.resolve([]);
  }
  files() {
    return Promise.resolve({ entries: [], more: false });
  }
}

/** Lets pending promises (the fake service's answers) settle under fake timers. */
async function settle(): Promise<void> {
  for (let i = 0; i < 20; i++) await Promise.resolve();
}

describe('MailComposePage', () => {
  let service: FakeComposeService;
  let fixture: ComponentFixture<MailComposePage>;
  let infos: string[];
  let errors: string[];

  async function open(query: Record<string, string> = {}) {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: MailComposeService, useValue: service },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap(query) } },
        },
        {
          provide: NotificationService,
          useValue: { info: (m: string) => infos.push(m), error: (m: string) => errors.push(m) },
        },
      ],
    });
    TestBed.overrideComponent(MailComposePage, {
      remove: { imports: [RichTextEditor] },
      add: { imports: [StubEditor] },
    });
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    fixture = TestBed.createComponent(MailComposePage);
    await settle();
    fixture.detectChanges();
    await settle();
  }

  const page = () => fixture.componentInstance as unknown as Record<string, unknown>;
  const call = (name: string, ...args: unknown[]) =>
    (page()[name] as (...a: unknown[]) => unknown).apply(fixture.componentInstance, args);
  const el = () => fixture.nativeElement as HTMLElement;

  beforeEach(() => {
    vi.useFakeTimers();
    service = new FakeComposeService();
    infos = [];
    errors = [];
  });

  afterEach(() => {
    fixture?.destroy();
    vi.useRealTimers();
  });

  describe('autosave', () => {
    it('saves nothing while nothing changed', async () => {
      await open();
      await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS * 3);
      expect(service.creates).toEqual([]);
      expect(service.updates).toEqual([]);
    });

    it('creates the draft 10 s after the first edit, not before', async () => {
      await open();
      call('setSubject', 'Budget 2027');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS - 1);
      expect(service.creates).toEqual([]);

      await vi.advanceTimersByTimeAsync(1);
      await settle();
      expect(service.creates).toEqual([
        expect.objectContaining({ accountId: ACCOUNT, subject: 'Budget 2027' }),
      ]);
    });

    it('updates with the latest ETag on the following ticks, only when changed', async () => {
      await open();
      call('setSubject', 'One');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);
      await settle();
      expect(service.creates).toHaveLength(1);

      await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);
      expect(service.updates).toEqual([]);

      call('setBody', '<p>Hello</p>');
      call('setTo', [{ address: 'ann@example.test', name: 'Ann' }]);
      await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);
      await settle();
      expect(service.updates).toEqual([
        {
          id: DRAFT,
          etag: '"1"',
          body: expect.objectContaining({
            html: '<p>Hello</p>',
            to: [{ address: 'ann@example.test', name: 'Ann' }],
            attachments: undefined,
          }),
        },
      ]);

      call('setSubject', 'Two');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);
      await settle();
      expect(service.updates[1].etag).toBe('"2"');
    });

    it('leaves invalid addresses out of the saved draft', async () => {
      await open();
      call('setTo', [{ address: 'ann@example.test' }, { address: 'not-an-address' }]);
      await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);
      await settle();
      expect(service.creates[0].to).toEqual([{ address: 'ann@example.test' }]);
    });

    it('shows the save state and stops autosaving after a conflict', async () => {
      await open();
      call('setSubject', 'One');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);
      await settle();
      fixture.detectChanges();
      expect(el().querySelector('.saved')?.textContent).toContain('Draft saved');

      service.updateError = new ApiError(412, 'Precondition Failed', 'stale');
      call('setSubject', 'Two');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);
      await settle();
      fixture.detectChanges();
      expect(el().querySelector('.saved')?.textContent).toContain('changed elsewhere');

      call('setSubject', 'Three');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS * 2);
      expect(service.updates).toHaveLength(1);
    });

    it('saves unsaved changes when leaving', async () => {
      await open();
      call('setSubject', 'Left behind');
      fixture.destroy();
      await settle();
      expect(service.creates).toEqual([expect.objectContaining({ subject: 'Left behind' })]);
    });
  });

  describe('reply, forward and drafts', () => {
    it('starts a reply on the server and shows its recipients, subject and quote', async () => {
      await open({ reply: MESSAGE });
      expect(service.responds).toEqual([{ id: MESSAGE, mode: 'reply' }]);
      expect(TestBed.inject(Router).navigate).toHaveBeenCalledWith(
        [],
        expect.objectContaining({ queryParams: { draft: DRAFT }, replaceUrl: true }),
      );
      expect(el().querySelector<HTMLInputElement>('input[name=subject]')?.value).toBe('Re: Budget');
      expect(el().querySelector('mat-chip-row')?.textContent).toContain('Max Muster');
      const editor = fixture.debugElement.query((d) => d.componentInstance instanceof StubEditor);
      expect((editor.componentInstance as StubEditor).html()).toContain('Original');
      // A reply keeps its threading: the sender cannot move to another account.
      expect((page()['canChangeSender'] as () => boolean)()).toBe(false);
    });

    it.each([
      ['replyAll', 'replyAll'],
      ['forward', 'forward'],
    ] as const)('starts a %s draft', async (param, mode) => {
      await open({ [param]: MESSAGE });
      expect(service.responds).toEqual([{ id: MESSAGE, mode }]);
    });

    it('opens a stored draft without creating another', async () => {
      await open({ draft: DRAFT });
      expect(service.gets).toEqual([DRAFT]);
      expect(service.creates).toEqual([]);
    });

    it('asks for a mail account when there is none', async () => {
      service.accountList = [];
      await open();
      expect(el().textContent).toContain('Add a mail account to write mail.');
    });
  });

  describe('attachments', () => {
    it('uploads into a draft it creates first', async () => {
      await open();
      const input = document.createElement('input');
      Object.defineProperty(input, 'files', {
        value: [new File(['x'], 'agenda.txt', { type: 'text/plain' })],
      });
      await call('upload', input);
      expect(service.creates).toHaveLength(1);
      expect(service.uploads).toEqual([{ id: DRAFT, etag: '"1"', name: 'agenda.txt' }]);
      fixture.detectChanges();
      expect(el().querySelector('.attachments')?.textContent).toContain('agenda.txt');
    });

    it('attaches a file from Files, keeping the current attachments', async () => {
      service.stored = draft({
        attachments: [{ partId: '2', filename: 'old.pdf', sizeBytes: 1, contentId: '' }],
      });
      await open({ draft: DRAFT });
      const dialog = (fixture.componentInstance as unknown as { dialog: { open: unknown } }).dialog;
      vi.spyOn(dialog as { open: () => unknown }, 'open').mockReturnValue({
        afterClosed: () => ({ subscribe: (fn: (v: unknown) => void) => fn({ id: 'file-1' }) }),
      });
      await call('pickFile');
      expect(service.updates[0].body.attachments).toEqual([{ partId: '2' }, { fileId: 'file-1' }]);
    });

    it('removes an attachment by saving without it', async () => {
      service.stored = draft({
        attachments: [
          { partId: '2', filename: 'a.pdf', sizeBytes: 1, contentId: '' },
          { partId: '3', filename: 'b.pdf', sizeBytes: 1, contentId: '' },
        ],
      });
      await open({ draft: DRAFT });
      await call('removeAttachment', { partId: '2', filename: 'a.pdf', sizeBytes: 1 });
      expect(service.updates[0].body.attachments).toEqual([{ partId: '3' }]);
    });
  });

  describe('send', () => {
    it('refuses without a recipient in To', async () => {
      await open();
      await call('send');
      fixture.detectChanges();
      expect(service.sends).toEqual([]);
      expect(el().querySelector('[role=alert]')?.textContent).toContain('Add a recipient in To.');
    });

    it('refuses invalid addresses', async () => {
      await open();
      call('setTo', [{ address: 'ann@example.test' }]);
      call('setCc', [{ address: 'oops' }]);
      await call('send');
      expect(service.sends).toEqual([]);
      expect((page()['sendError'] as () => string)()).toContain('oops');
    });

    it('saves the latest changes, sends with the new ETag and returns to Mail', async () => {
      await open();
      call('setTo', [{ address: 'ann@example.test' }]);
      call('setSubject', 'Hello');
      await call('send');
      expect(service.creates).toHaveLength(1);
      expect(service.sends).toEqual([{ id: DRAFT, etag: '"1"' }]);
      expect(infos).toEqual(['Message sent']);
      expect(TestBed.inject(Router).navigate).toHaveBeenCalledWith(['/mail']);

      // Nothing more is saved once sent.
      await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS * 2);
      expect(service.updates).toEqual([]);
    });

    it('shows why sending failed and keeps the draft', async () => {
      service.sendError = new ApiError(422, 'Unprocessable', 'Recipient rejected');
      await open();
      call('setTo', [{ address: 'ann@example.test' }]);
      await call('send');
      expect((page()['sendError'] as () => string)()).toBeTruthy();
      expect(TestBed.inject(Router).navigate).not.toHaveBeenCalledWith(['/mail']);
    });
  });

  describe('sender', () => {
    it('moves a stored draft to the other account and discards the old copy', async () => {
      await open();
      call('setSubject', 'Hi');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);
      await settle();
      call('changeSender', OTHER_ACCOUNT);
      await settle();
      expect(service.creates.map((c) => c.accountId)).toEqual([ACCOUNT, OTHER_ACCOUNT]);
      expect(service.discards).toEqual([{ id: DRAFT, etag: '"1"' }]);
    });

    it('only switches the account before the first save', async () => {
      await open();
      call('changeSender', OTHER_ACCOUNT);
      call('setTo', [{ address: 'ann@example.test' }]);
      await call('send');
      expect(service.creates.map((c) => c.accountId)).toEqual([OTHER_ACCOUNT]);
    });
  });

  it('discards the draft and returns to Mail', async () => {
    await open({ draft: DRAFT });
    await call('discard');
    expect(service.discards).toEqual([{ id: DRAFT, etag: '"1"' }]);
    expect(infos).toEqual(['Draft discarded']);
  });

  describe('writes in flight', () => {
    it('saves an edit made while a save is running on the next tick, with the new ETag', async () => {
      await open({ draft: DRAFT });
      const gate = deferred();
      const update = service.update.bind(service);
      vi.spyOn(service, 'update').mockImplementationOnce(async (id, etag, body) => {
        await gate.promise;
        return update(id, etag, body);
      });
      call('setSubject', 'First');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);
      expect(service.update).toHaveBeenCalledTimes(1);

      // Typed while the first save is still on its way.
      call('setSubject', 'Second');
      gate.resolve();
      await settle();
      expect(service.updates.map((u) => u.body.subject)).toEqual(['First']);
      fixture.detectChanges();
      expect(el().querySelector('.saved')?.textContent).toContain('Unsaved changes');

      await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);
      await settle();
      expect(service.updates.map((u) => [u.body.subject, u.etag])).toEqual([
        ['First', '"1"'],
        ['Second', '"2"'],
      ]);
      fixture.detectChanges();
      expect(el().querySelector('.saved')?.textContent).toContain('Draft saved');
    });

    it('never runs two saves at once: a tick during a slow save waits for it', async () => {
      await open({ draft: DRAFT });
      const gate = deferred();
      const update = service.update.bind(service);
      let running = 0;
      let most = 0;
      vi.spyOn(service, 'update').mockImplementation(async (id, etag, body) => {
        running++;
        most = Math.max(most, running);
        await gate.promise;
        running--;
        return update(id, etag, body);
      });
      call('setSubject', 'One');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);
      call('setSubject', 'Two');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);
      expect(service.update).toHaveBeenCalledTimes(1);

      gate.resolve();
      await settle();
      expect(most).toBe(1);
      expect(service.updates.map((u) => u.etag)).toEqual(['"1"', '"2"']);
    });

    it('sends only once when Send is pressed twice', async () => {
      await open();
      call('setTo', [{ address: 'ann@example.test' }]);
      await Promise.all([call('send'), call('send')]);
      expect(service.sends).toHaveLength(1);
      expect(service.creates).toHaveLength(1);
    });

    it('discards with the ETag of a save still running', async () => {
      await open({ draft: DRAFT });
      const gate = deferred();
      const update = service.update.bind(service);
      vi.spyOn(service, 'update').mockImplementationOnce(async (id, etag, body) => {
        await gate.promise;
        return update(id, etag, body);
      });
      call('setSubject', 'Pending');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);
      const discarding = call('discard') as Promise<void>;
      await settle();
      expect(service.discards).toEqual([]);
      gate.resolve();
      await discarding;
      expect(service.discards).toEqual([{ id: DRAFT, etag: '"2"' }]);
    });
  });

  describe('failures', () => {
    it('shows a failed save and retries it on the next tick', async () => {
      await open({ draft: DRAFT });
      service.updateError = new ApiError(503, 'Unavailable', 'Mail server unreachable');
      call('setSubject', 'Try');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);
      await settle();
      fixture.detectChanges();
      expect(el().querySelector('.saved')?.textContent).toContain(
        'Not saved: Mail server unreachable',
      );

      service.updateError = undefined;
      await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);
      await settle();
      fixture.detectChanges();
      expect(service.updates).toHaveLength(2);
      expect(service.updates[1].body.subject).toBe('Try');
      expect(el().querySelector('.saved')?.textContent).toContain('Draft saved');
    });

    it('reports an unexpected save error without details', async () => {
      await open({ draft: DRAFT });
      service.updateError = new Error('boom');
      call('setSubject', 'Try');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);
      await settle();
      fixture.detectChanges();
      expect(el().querySelector('.saved')?.textContent).toContain(
        'Not saved: Something went wrong.',
      );
    });

    it('reports a failed upload and lets the user attach again', async () => {
      await open({ draft: DRAFT });
      vi.spyOn(service, 'upload').mockRejectedValueOnce(
        new ApiError(413, 'Payload Too Large', 'The file is too large.'),
      );
      const input = fileInput(new File(['x'], 'huge.bin'));
      await call('upload', input);
      fixture.detectChanges();
      expect(errors).toEqual(['The file is too large.']);
      expect(input.value).toBe('');
      expect((page()['busy'] as () => unknown)()).toBeNull();
      expect(el().querySelector('.attachments')).toBeNull();

      await call('upload', fileInput(new File(['y'], 'small.txt')));
      expect(service.uploads.map((u) => u.name)).toEqual(['small.txt']);
    });

    it('reports a failed draft creation before an upload and uploads nothing', async () => {
      await open();
      vi.spyOn(service, 'create').mockRejectedValueOnce(
        new ApiError(502, 'Bad Gateway', 'Drafts folder not reachable'),
      );
      await call('upload', fileInput(new File(['x'], 'agenda.txt')));
      expect(errors).toEqual(['Drafts folder not reachable']);
      expect(service.uploads).toEqual([]);
    });

    it('reports a failed attach from Files', async () => {
      await open({ draft: DRAFT });
      stubDialog({ id: 'file-1' });
      service.updateError = new ApiError(404, 'Not Found', 'The file no longer exists.');
      await call('pickFile');
      expect(errors).toEqual(['The file no longer exists.']);
    });

    it('shows why the draft could not be opened and opens it on retry', async () => {
      vi.spyOn(service, 'get').mockRejectedValueOnce(
        new ApiError(404, 'Not Found', 'Draft not found'),
      );
      await open({ draft: DRAFT });
      expect(el().querySelector('[role=alert]')?.textContent).toContain(
        'The draft could not be opened: Draft not found',
      );
      expect(el().querySelector('form')).toBeNull();

      (el().querySelector('[role=alert] button') as HTMLButtonElement).click();
      await settle();
      fixture.detectChanges();
      expect(el().querySelector('form')).not.toBeNull();
    });

    it('reports a failed discard and stays on the page', async () => {
      await open({ draft: DRAFT });
      vi.spyOn(service, 'discard').mockRejectedValueOnce(
        new ApiError(412, 'Precondition Failed', 'Changed elsewhere'),
      );
      await call('discard');
      expect(errors).toEqual(['Changed elsewhere']);
      expect(TestBed.inject(Router).navigate).not.toHaveBeenCalledWith(['/mail']);
      expect((page()['busy'] as () => unknown)()).toBeNull();
    });

    it('shows a send failure caused by saving the latest changes', async () => {
      await open({ draft: DRAFT });
      call('setTo', [{ address: 'ann@example.test' }]);
      service.updateError = new ApiError(412, 'Precondition Failed', 'Changed elsewhere');
      await call('send');
      expect(service.sends).toEqual([]);
      expect((page()['sendError'] as () => string)()).toBe('Changed elsewhere');
    });
  });

  describe('more compose behaviour', () => {
    it('discards a never saved message without asking the server', async () => {
      await open();
      await call('discard');
      expect(service.discards).toEqual([]);
      expect(TestBed.inject(Router).navigate).toHaveBeenCalledWith(['/mail']);
    });

    it('adds nothing when the Files dialog is cancelled or no file was chosen', async () => {
      await open({ draft: DRAFT });
      stubDialog(undefined);
      await call('pickFile');
      await call('upload', fileInput());
      expect(service.updates).toEqual([]);
      expect(service.uploads).toEqual([]);
    });

    it('says when no copy was kept in Sent for an IMAP account', async () => {
      vi.spyOn(service, 'send').mockResolvedValueOnce({
        sentMessageId: 's',
        appendedToSent: false,
        draftRemoved: true,
      });
      await open();
      call('setTo', [{ address: 'ann@example.test' }]);
      await call('send');
      expect(infos).toEqual(['Message sent, but no copy was saved in Sent']);
    });

    it('does not warn about Sent for providers that store sent mail themselves', async () => {
      service.accountList = [account(ACCOUNT, 'jane@example.test', 'GOOGLE')];
      vi.spyOn(service, 'send').mockResolvedValueOnce({
        sentMessageId: 's',
        appendedToSent: false,
        draftRemoved: true,
      });
      await open();
      call('setTo', [{ address: 'ann@example.test' }]);
      await call('send');
      expect(infos).toEqual(['Message sent']);
    });

    it('sends a saved, unchanged draft without saving it again', async () => {
      service.stored = draft({ to: [{ name: '', address: 'ann@example.test' }] });
      await open({ draft: DRAFT });
      await call('send');
      expect(service.updates).toEqual([]);
      expect(service.sends).toEqual([{ id: DRAFT, etag: '"1"' }]);
    });

    it('shows Cc/Bcc of a stored draft and saves Bcc changes', async () => {
      service.stored = draft({ bcc: [{ name: '', address: 'boss@example.test' }] });
      await open({ draft: DRAFT });
      expect(el().querySelectorAll('app-recipient-input')).toHaveLength(3);
      call('setBcc', [{ address: 'boss@example.test' }, { address: 'audit@example.test' }]);
      await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);
      await settle();
      expect(service.updates[0].body.bcc).toEqual([
        { address: 'boss@example.test' },
        { address: 'audit@example.test' },
      ]);
    });

    it('opens the Cc/Bcc fields with their button', async () => {
      await open();
      expect(el().querySelectorAll('app-recipient-input')).toHaveLength(1);
      const toggle = [...el().querySelectorAll('button')].find((b) =>
        b.textContent?.includes('Cc/Bcc'),
      )!;
      toggle.click();
      fixture.detectChanges();
      expect(toggle.getAttribute('aria-expanded')).toBe('true');
      expect(el().querySelectorAll('app-recipient-input')).toHaveLength(3);
    });

    it('lists real attachments but not inline images of the body', async () => {
      service.stored = draft({
        attachments: [
          { partId: '2', filename: 'report.pdf', sizeBytes: 2048, contentId: '' },
          { partId: '3', filename: 'logo.png', sizeBytes: 10, contentId: 'logo', inline: true },
        ],
      });
      await open({ draft: DRAFT });
      const list = el().querySelector('.attachments')!;
      expect(list.textContent).toContain('report.pdf');
      expect(list.textContent).not.toContain('logo.png');
      // Removing one keeps the inline image (it belongs to the body).
      await call('removeAttachment', { partId: '2', filename: 'report.pdf', sizeBytes: 2048 });
      expect(service.updates[0].body.attachments).toEqual([{ partId: '3' }]);
    });

    it('keeps the sender of a stored draft with attachments', async () => {
      service.stored = draft({
        attachments: [{ partId: '2', filename: 'a.pdf', sizeBytes: 1, contentId: '' }],
      });
      await open({ draft: DRAFT });
      expect((page()['canChangeSender'] as () => boolean)()).toBe(false);
      call('changeSender', OTHER_ACCOUNT);
      await settle();
      expect((page()['accountId'] as () => string)()).toBe(ACCOUNT);
      expect(service.creates).toEqual([]);
    });

    it('saves nothing when leaving after sending or without changes', async () => {
      await open({ draft: DRAFT });
      fixture.destroy();
      await settle();
      expect(service.updates).toEqual([]);
    });

    it('stops autosaving when the page is left', async () => {
      await open({ draft: DRAFT });
      call('setSubject', 'Bye');
      fixture.destroy();
      await settle();
      await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS * 3);
      expect(service.updates).toHaveLength(1);
    });
  });

  describe('sender move and repeated clicks', () => {
    it('keeps the moved draft when the old copy cannot be removed', async () => {
      await open();
      call('setSubject', 'Hi');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);
      await settle();
      vi.spyOn(service, 'discard').mockRejectedValueOnce(new ApiError(503, 'Unavailable'));
      call('changeSender', OTHER_ACCOUNT);
      await settle();
      expect(service.stored.accountId).toBe(OTHER_ACCOUNT);
      expect(errors).toEqual([]);
      // Later saves go to the moved draft, with its version.
      call('setSubject', 'Hi again');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);
      await settle();
      expect(service.updates.map((u) => u.etag)).toEqual(['"2"']);
    });

    it('discards only once when Discard is pressed twice', async () => {
      await open({ draft: DRAFT });
      await Promise.all([call('discard'), call('discard')]);
      expect(service.discards).toHaveLength(1);
    });
  });

  describe('sender changes racing a save', () => {
    it('reports a failed move to another account and keeps showing the account holding the draft', async () => {
      await open({ draft: DRAFT });
      vi.spyOn(service, 'create').mockRejectedValueOnce(
        new ApiError(502, 'Bad Gateway', 'Drafts folder not reachable'),
      );
      call('changeSender', OTHER_ACCOUNT);
      await settle();
      fixture.detectChanges();
      const reported = errors.length > 0 || (page()['saveProblem'] as () => string)() !== '';
      expect(reported).toBe(true);
      // The draft is still in the first account: that is where Send would send it from.
      expect((page()['accountId'] as () => string)()).toBe(ACCOUNT);
    });

    it('stores the draft in the account picked while its first save was running', async () => {
      await open();
      const gate = deferred();
      const create = service.create.bind(service);
      vi.spyOn(service, 'create').mockImplementationOnce(async (body) => {
        await gate.promise;
        return create(body);
      });
      call('setTo', [{ address: 'ann@example.test' }]);
      await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);
      expect(service.create).toHaveBeenCalledTimes(1);

      call('changeSender', OTHER_ACCOUNT);
      gate.resolve();
      await settle();
      await call('send');
      // Sent from the account shown as sender: the draft must have ended up there.
      expect(service.stored.accountId).toBe(OTHER_ACCOUNT);
      expect((page()['accountId'] as () => string)()).toBe(OTHER_ACCOUNT);
    });

    it('reports a failed move once and shows the account holding the draft in From', async () => {
      await open({ draft: DRAFT });
      vi.spyOn(service, 'create').mockRejectedValueOnce(
        new ApiError(502, 'Bad Gateway', 'Drafts folder not reachable'),
      );
      call('changeSender', OTHER_ACCOUNT);
      await settle();
      fixture.detectChanges();
      expect(errors).toEqual(['Drafts folder not reachable']);
      expect(el().querySelector('mat-select')?.textContent).toContain('jane@example.test');
      // Later saves and Send keep using the draft where it is.
      call('setTo', [{ address: 'ann@example.test' }]);
      await call('send');
      expect(service.updates.map((u) => u.id)).toEqual([DRAFT]);
      expect(service.sends).toEqual([{ id: DRAFT, etag: '"2"' }]);
    });

    it('moves nothing when the sender is changed and changed back during the first save', async () => {
      await open();
      const gate = deferred();
      const create = service.create.bind(service);
      vi.spyOn(service, 'create').mockImplementationOnce(async (body) => {
        await gate.promise;
        return create(body);
      });
      call('setSubject', 'Hi');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);
      call('changeSender', OTHER_ACCOUNT);
      call('changeSender', ACCOUNT);
      gate.resolve();
      await settle();
      expect(service.creates.map((c) => c.accountId)).toEqual([ACCOUNT]);
      expect(service.discards).toEqual([]);
    });

    it('starts the moved draft with the new account’s default identity', async () => {
      await open({ draft: DRAFT });
      call('changeSender', OTHER_ACCOUNT);
      await settle();
      expect(service.creates).toEqual([
        expect.objectContaining({ accountId: OTHER_ACCOUNT, identityId: undefined }),
      ]);
    });
  });

  describe('Send button and Enter', () => {
    it('does not send when the form is submitted (Enter in Subject); the Send button sends', async () => {
      await open();
      call('setTo', [{ address: 'ann@example.test' }]);
      fixture.detectChanges();
      const form = el().querySelector('form')!;
      expect(form.noValidate).toBe(true);
      expect(form.querySelector('button[type=submit]')).toBeNull();

      const subject = el().querySelector<HTMLInputElement>('input[name=subject]')!;
      subject.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
      // What the browser's implicit submission fires on Enter in a text field.
      const submit = new Event('submit', { bubbles: true, cancelable: true });
      form.dispatchEvent(submit);
      await settle();
      expect(submit.defaultPrevented).toBe(true);
      expect(service.sends).toEqual([]);

      const send = [...el().querySelectorAll('button')].find(
        (b) => b.textContent?.trim() === 'Send',
      )!;
      send.click();
      await settle();
      expect(service.sends).toEqual([{ id: DRAFT, etag: '"1"' }]);
    });
  });

  describe('draft in the URL', () => {
    const urlCalls = () =>
      vi
        .mocked(TestBed.inject(Router).navigate)
        .mock.calls.filter(([commands]) => Array.isArray(commands) && commands.length === 0);

    it('puts a new draft into the URL once it is first saved, and only then', async () => {
      await open();
      expect(urlCalls()).toEqual([]);
      call('setSubject', 'One');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);
      await settle();
      expect(urlCalls()).toEqual([
        [
          [],
          expect.objectContaining({
            relativeTo: expect.anything(),
            queryParams: { draft: DRAFT },
            replaceUrl: true,
          }),
        ],
      ]);

      call('setSubject', 'Two');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);
      await settle();
      expect(service.updates).toHaveLength(1);
      expect(urlCalls()).toHaveLength(1);
    });

    it('leaves the URL of an opened draft as it is', async () => {
      await open({ draft: DRAFT });
      call('setSubject', 'Changed');
      await vi.advanceTimersByTimeAsync(AUTOSAVE_INTERVAL_MS);
      await settle();
      expect(urlCalls()).toEqual([]);
    });

    it('puts the moved draft into the URL', async () => {
      const MOVED = '0192f0c4-0000-7000-8000-00000000d002';
      await open({ draft: DRAFT });
      vi.spyOn(service, 'create').mockImplementationOnce((body) => {
        service.creates.push(body);
        service.stored = { ...service.stored, id: MOVED, accountId: body.accountId };
        return Promise.resolve({ draft: service.stored, etag: '"7"' });
      });
      call('changeSender', OTHER_ACCOUNT);
      await settle();
      expect(service.discards).toEqual([{ id: DRAFT, etag: '"1"' }]);
      expect(urlCalls()).toEqual([
        [[], expect.objectContaining({ queryParams: { draft: MOVED }, replaceUrl: true })],
      ]);
    });

    it('does not touch the URL when the draft is first saved on leaving the page', async () => {
      await open();
      call('setSubject', 'Left behind');
      fixture.destroy();
      await settle();
      expect(service.creates).toHaveLength(1);
      expect(urlCalls()).toEqual([]);
    });
  });

  describe('sender change queued behind other writes', () => {
    const THIRD_ACCOUNT = '0192f0c4-0000-7000-8000-0000000acc03';
    const sender = () => (page()['accountId'] as () => string)();

    it('puts From back when the draft got an attachment before the queued move ran', async () => {
      await open({ draft: DRAFT });
      const gate = deferred();
      const upload = service.upload.bind(service);
      vi.spyOn(service, 'upload').mockImplementationOnce(async (id, etag, file) => {
        await gate.promise;
        return upload(id, etag, file);
      });
      const uploading = call('upload', fileInput(new File(['x'], 'agenda.txt'))) as Promise<void>;
      await settle();
      expect(service.upload).toHaveBeenCalledTimes(1);

      // Picked while the upload is on its way: the draft has no attachment yet.
      call('changeSender', OTHER_ACCOUNT);
      gate.resolve();
      await uploading;
      await settle();
      expect(service.creates).toEqual([]);
      expect(service.discards).toEqual([]);
      expect(errors).toEqual([]);
      expect(sender()).toBe(ACCOUNT);
      fixture.detectChanges();
      expect(el().querySelector('mat-select')?.textContent).toContain('jane@example.test');
    });

    it('keeps a sender picked while an earlier move was failing and moves the draft there', async () => {
      service.accountList = [...service.accountList, account(THIRD_ACCOUNT, 'j.doe@club.test')];
      await open({ draft: DRAFT });
      const gate = deferred();
      vi.spyOn(service, 'create').mockImplementationOnce(async (body) => {
        service.creates.push(body);
        await gate.promise;
        throw new ApiError(502, 'Bad Gateway', 'Drafts folder not reachable');
      });
      call('changeSender', OTHER_ACCOUNT);
      await settle();
      call('changeSender', THIRD_ACCOUNT);
      gate.resolve();
      await settle();

      expect(errors).toEqual(['Drafts folder not reachable']);
      expect(service.creates.map((c) => c.accountId)).toEqual([OTHER_ACCOUNT, THIRD_ACCOUNT]);
      expect(service.stored.accountId).toBe(THIRD_ACCOUNT);
      expect(sender()).toBe(THIRD_ACCOUNT);
      expect(service.discards).toEqual([{ id: DRAFT, etag: '"1"' }]);
    });

    it('after a failed move, Send treats the mail as sent from the account holding the draft', async () => {
      service.accountList = [
        account(ACCOUNT, 'jane@example.test'),
        account(OTHER_ACCOUNT, 'jd@work.test', 'GOOGLE'),
      ];
      service.stored = draft({ to: [{ name: '', address: 'ann@example.test' }] });
      await open({ draft: DRAFT });
      vi.spyOn(service, 'create').mockRejectedValueOnce(
        new ApiError(502, 'Bad Gateway', 'Drafts folder not reachable'),
      );
      vi.spyOn(service, 'send').mockResolvedValueOnce({
        sentMessageId: 's',
        appendedToSent: false,
        draftRemoved: true,
      });
      call('changeSender', OTHER_ACCOUNT);
      await settle();
      await call('send');
      expect(service.send).toHaveBeenCalledExactlyOnceWith(DRAFT, '"1"');
      // The IMAP account sent it, so the missing Sent copy is reported.
      expect(infos).toEqual(['Message sent, but no copy was saved in Sent']);
    });

    it('sends a moved draft from its new account', async () => {
      service.accountList = [
        account(ACCOUNT, 'jane@example.test'),
        account(OTHER_ACCOUNT, 'jd@work.test', 'GOOGLE'),
      ];
      service.stored = draft({ to: [{ name: '', address: 'ann@example.test' }] });
      await open({ draft: DRAFT });
      vi.spyOn(service, 'send').mockResolvedValueOnce({
        sentMessageId: 's',
        appendedToSent: false,
        draftRemoved: true,
      });
      call('changeSender', OTHER_ACCOUNT);
      await settle();
      await call('send');
      expect(service.creates).toEqual([
        expect.objectContaining({
          accountId: OTHER_ACCOUNT,
          to: [{ address: 'ann@example.test' }],
        }),
      ]);
      expect(service.send).toHaveBeenCalledExactlyOnceWith(DRAFT, '"2"');
      // Google keeps its own Sent copy: no warning.
      expect(infos).toEqual(['Message sent']);
    });
  });

  function fileInput(...files: File[]): HTMLInputElement {
    const input = document.createElement('input');
    Object.defineProperty(input, 'files', { value: files });
    return input;
  }

  function stubDialog(result: unknown): void {
    const dialog = (fixture.componentInstance as unknown as { dialog: { open: unknown } }).dialog;
    vi.spyOn(dialog as { open: () => unknown }, 'open').mockReturnValue({
      afterClosed: () => ({ subscribe: (fn: (v: unknown) => void) => fn(result) }),
    });
  }
});

function deferred(): { promise: Promise<void>; resolve: () => void } {
  let resolve!: () => void;
  const promise = new Promise<void>((r) => (resolve = r));
  return { promise, resolve };
}
