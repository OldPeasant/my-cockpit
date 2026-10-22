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
    return Promise.resolve([]);
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
});
