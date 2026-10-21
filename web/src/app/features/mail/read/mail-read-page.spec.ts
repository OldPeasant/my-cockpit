import { Component, input } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, ParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { ItemRefDto, MailMessageResponse } from '../../../api/generated';
import { ApiError, NotificationService } from '../../../core/http';
import { LinkedItems } from '../../../shared/links';
import { IMAGE_ALLOWLIST_KEY } from './image-allowlist';
import { MAIL_FRAME_SANDBOX } from './mail-frame';
import MailReadPage, { TEXT_PREVIEW_LIMIT } from './mail-read-page';
import { MailGtdAction, MailReadService } from './mail-read.service';

const ID = '0192f0c4-0000-7000-8000-00000000a001';

const flush = async () => {
  for (let i = 0; i < 10; i++) await Promise.resolve();
  await new Promise((resolve) => setTimeout(resolve));
};

@Component({ selector: 'app-linked-items', template: '' })
class StubLinkedItems {
  readonly item = input.required<ItemRefDto>();
  reloads = 0;
  reload(): Promise<void> {
    this.reloads++;
    return Promise.resolve();
  }
}

function message(extra: Partial<MailMessageResponse> = {}): MailMessageResponse {
  return {
    id: ID,
    accountId: 'acc-1',
    folderId: 'f-inbox',
    subject: 'Quarterly report',
    from: [{ name: 'Max Muster', address: 'Max.Muster@example.test' }],
    to: [{ name: 'Erika Beispiel', address: 'erika@example.test' }],
    cc: [],
    receivedAt: '2026-10-20T10:00:00Z',
    seen: true,
    inReplyTo: '',
    messageIdHeader: '<1@example.test>',
    references: '',
    sentAt: '2026-10-20T09:59:00Z',
    threadKey: 't-1',
    body: {
      html: '<p>Hello <img src="/x"></p>',
      remoteImageCount: 0,
      remoteImagesBlocked: false,
      text: '',
    },
    attachments: [],
    ...extra,
  };
}

/** What a `get` answers: a message, an error, or a promise the test settles. */
type Answer = MailMessageResponse | Error | Promise<MailMessageResponse>;

/** A promise the test resolves or rejects when it wants, to control the order of answers. */
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

class FakeReadService {
  answers: Answer[] = [];
  gets: { id: string; loadImages: boolean }[] = [];
  reads: string[] = [];
  /** When set, marking read fails with it. */
  readError?: Error;
  gtds: { id: string; action: MailGtdAction }[] = [];
  gtdResult: Promise<{ id: string; title: string }> = Promise.resolve({ id: 't', title: 'T' });
  saves: { id: string; partId: string; name: string }[] = [];
  saveResult: Promise<{ name: string }> = Promise.resolve({ name: 'report.pdf' });
  blob: Promise<Blob> = Promise.resolve(new Blob(['line one\nline two']));

  get(id: string, loadImages: boolean) {
    this.gets.push({ id, loadImages });
    const answer = this.answers.shift() ?? message();
    if (answer instanceof Promise) return answer;
    return answer instanceof Error ? Promise.reject(answer) : Promise.resolve(answer);
  }
  markRead(id: string) {
    this.reads.push(id);
    return this.readError ? Promise.reject(this.readError) : Promise.resolve();
  }
  gtd(id: string, action: MailGtdAction) {
    this.gtds.push({ id, action });
    return this.gtdResult;
  }
  attachment() {
    return this.blob;
  }
  saveToFiles(id: string, partId: string, name: string) {
    this.saves.push({ id, partId, name });
    return this.saveResult;
  }
}

describe('MailReadPage', () => {
  let fixture: ComponentFixture<MailReadPage>;
  let service: FakeReadService;
  let params: BehaviorSubject<ParamMap>;
  let notifications: { info: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };

  /** Adjusts the fake service before the page starts. */
  let setup: ((fake: FakeReadService) => void) | undefined;

  async function create(...answers: Answer[]) {
    service = new FakeReadService();
    service.answers = answers;
    setup?.(service);
    params = new BehaviorSubject(convertToParamMap({ id: ID }));
    notifications = { info: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
      imports: [MailReadPage],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap: params } },
        { provide: MailReadService, useValue: service },
        { provide: NotificationService, useValue: notifications },
      ],
    });
    TestBed.overrideComponent(MailReadPage, {
      remove: { imports: [LinkedItems] },
      add: { imports: [StubLinkedItems] },
    });
    fixture = TestBed.createComponent(MailReadPage);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const button = (text: string) =>
    [...el().querySelectorAll<HTMLButtonElement>('button, a')].find(
      (b) => b.textContent?.trim() === text,
    ) as HTMLButtonElement | undefined;
  const iframe = () => el().querySelector<HTMLIFrameElement>('iframe');

  beforeEach(() => {
    setup = undefined;
    localStorage.removeItem(IMAGE_ALLOWLIST_KEY);
  });
  afterEach(() => localStorage.removeItem(IMAGE_ALLOWLIST_KEY));

  it('shows headers and renders the HTML body in a sandboxed frame without scripts', async () => {
    await create();
    expect(el().querySelector('h1')?.textContent?.trim()).toBe('Quarterly report');
    expect(el().querySelector('.sender')?.textContent?.trim()).toBe('Max Muster');
    expect(el().querySelector('.recipients dd')?.textContent?.trim()).toBe('Erika Beispiel');
    const frame = iframe()!;
    expect(frame.getAttribute('sandbox')).toBe(MAIL_FRAME_SANDBOX);
    expect(frame.getAttribute('sandbox')!.split(' ')).not.toContain('allow-scripts');
    expect(frame.srcdoc).toContain('<p>Hello <img src="/x"></p>');
    expect(frame.srcdoc).toContain("script-src 'none'");
    expect(service.gets).toEqual([{ id: ID, loadImages: false }]);
  });

  it('shows plain-text mail as text, not in a frame', async () => {
    await create(
      message({ body: { html: '', text: '<b>not bold</b>', remoteImagesBlocked: false } }),
    );
    expect(iframe()).toBeNull();
    expect(el().querySelector('pre.text')?.textContent).toBe('<b>not bold</b>');
  });

  it('marks an unread message read, not a read one', async () => {
    await create(message({ seen: false }));
    expect(service.reads).toEqual([ID]);
    TestBed.resetTestingModule();
    await create();
    expect(service.reads).toEqual([]);
  });

  describe('remote images', () => {
    const blocked = () =>
      message({
        body: { html: '<p>x</p>', remoteImageCount: 2, remoteImagesBlocked: true, text: '' },
      });
    const proxied = () =>
      message({
        body: {
          html: '<img src="/api/v1/mail/image-proxy?url=x">',
          remoteImageCount: 2,
          remoteImagesBlocked: false,
          text: '',
        },
      });

    it('are blocked until "Load images", which reloads the message through the proxy', async () => {
      await create(blocked(), proxied());
      expect(el().querySelector('.images')).not.toBeNull();
      expect(iframe()!.srcdoc).not.toContain('image-proxy');

      button('Load images')!.click();
      await flush();
      fixture.detectChanges();
      expect(service.gets.at(-1)).toEqual({ id: ID, loadImages: true });
      expect(iframe()!.srcdoc).toContain('image-proxy');
      expect(el().querySelector('.images')).toBeNull();
      expect(localStorage.getItem(IMAGE_ALLOWLIST_KEY)).toBeNull();
    });

    it('"Always load from this sender" remembers the sender and loads them next time at once', async () => {
      await create(blocked(), proxied());
      button('Always load from this sender')!.click();
      await flush();
      fixture.detectChanges();
      expect(JSON.parse(localStorage.getItem(IMAGE_ALLOWLIST_KEY)!)).toEqual([
        'max.muster@example.test',
      ]);
      expect(el().querySelector('.images.quiet')?.textContent).toContain('load automatically');

      TestBed.resetTestingModule();
      await create(blocked(), proxied());
      expect(service.gets.map((g) => g.loadImages)).toEqual([false, true]);
      expect(iframe()!.srcdoc).toContain('image-proxy');

      button('Stop')!.click();
      expect(JSON.parse(localStorage.getItem(IMAGE_ALLOWLIST_KEY)!)).toEqual([]);
    });

    it('offers nothing when the message has no remote images', async () => {
      await create();
      expect(el().querySelector('.images')).toBeNull();
    });

    it('reports a failed reload and keeps the images blocked', async () => {
      await create(blocked(), new ApiError(502, 'Bad gateway', 'The mail server failed'));
      button('Load images')!.click();
      await flush();
      fixture.detectChanges();
      expect(notifications.error).toHaveBeenCalledWith('The mail server failed');
      expect(el().querySelector('.images')).not.toBeNull();
    });

    it('shows the blocked copy when the proxied load for an allowed sender fails', async () => {
      localStorage.setItem(IMAGE_ALLOWLIST_KEY, JSON.stringify(['max.muster@example.test']));
      await create(
        blocked(),
        new ApiError(502, 'Bad gateway', 'The mail server failed'),
        proxied(),
      );
      expect(service.gets.map((g) => g.loadImages)).toEqual([false, true]);
      expect(notifications.error).toHaveBeenCalledWith('The mail server failed');
      expect(el().querySelector('[role=alert]')).toBeNull();
      expect(el().querySelector('h1')?.textContent?.trim()).toBe('Quarterly report');
      expect(iframe()!.srcdoc).toContain('<p>x</p>');
      expect(el().querySelector('.images:not(.quiet)')).not.toBeNull();

      // The banner offers the images again.
      button('Load images')!.click();
      await flush();
      fixture.detectChanges();
      expect(iframe()!.srcdoc).toContain('image-proxy');
    });
  });

  describe('GTD actions', () => {
    it.each([
      ['→ Inbox task', 'task', 'Added to Inbox: T'],
      ['→ Waiting For', 'waiting', 'Added to Waiting For: T'],
      ['Create note', 'note', 'Note created: T'],
    ] as const)('%s creates the item and reloads the linked items', async (label, action, info) => {
      await create();
      button(label)!.click();
      await flush();
      expect(service.gtds).toEqual([{ id: ID, action }]);
      expect(notifications.info).toHaveBeenCalledWith(info);
      const links = fixture.debugElement.query(
        (d) => d.componentInstance instanceof StubLinkedItems,
      ).componentInstance as StubLinkedItems;
      expect(links.reloads).toBe(1);
      expect(links.item()).toEqual({ type: 'EMAIL', id: ID });
    });

    it('reports a failure', async () => {
      await create();
      service.gtdResult = Promise.reject(new ApiError(404, 'Not found', 'No such message'));
      button('Create note')!.click();
      await flush();
      expect(notifications.error).toHaveBeenCalledWith('No such message');
    });
  });

  describe('attachments', () => {
    const withParts = () =>
      message({
        attachments: [
          {
            partId: '2',
            filename: 'report.pdf',
            contentId: '',
            contentType: 'application/pdf',
            sizeBytes: 2048,
          },
          {
            partId: '3',
            filename: 'photo.png',
            contentId: '',
            contentType: 'image/png',
            sizeBytes: 10,
          },
          {
            partId: '4',
            filename: 'notes.txt',
            contentId: '',
            contentType: 'text/plain; charset=utf-8',
            sizeBytes: 10,
          },
          {
            partId: '5',
            filename: 'logo.png',
            contentId: 'logo',
            contentType: 'image/png',
            sizeBytes: 10,
            inline: true,
          },
        ],
      });

    it('lists real attachments with a download link, not inline parts', async () => {
      await create(withParts());
      const items = [...el().querySelectorAll('.attachments li')];
      expect(items.map((li) => li.querySelector('.file-name')?.textContent)).toEqual([
        'report.pdf',
        'photo.png',
        'notes.txt',
      ]);
      expect(items[0].querySelector('.file-size')?.textContent).toBe('2 KB');
      expect(items[0].querySelector('a')?.getAttribute('href')).toBe(
        `/api/v1/mail/messages/${ID}/attachments/2`,
      );
      expect(items[0].textContent).not.toContain('Preview');
    });

    it('previews an image inline and a text file as text', async () => {
      await create(withParts());
      const items = () => [...el().querySelectorAll('.attachments li')];
      items()[1].querySelector<HTMLButtonElement>('button')!.click();
      fixture.detectChanges();
      expect(el().querySelector('.preview img')?.getAttribute('src')).toBe(
        `/api/v1/mail/messages/${ID}/attachments/3?inline=true`,
      );
      items()[2].querySelector<HTMLButtonElement>('button')!.click();
      await flush();
      fixture.detectChanges();
      expect(el().querySelector('.preview pre')?.textContent).toBe('line one\nline two');
      button('Close preview')!.click();
      fixture.detectChanges();
      expect(el().querySelector('.preview')).toBeNull();
    });

    it('saves an attachment to Files', async () => {
      await create(withParts());
      const save = el().querySelectorAll<HTMLButtonElement>('.attachments li')[0];
      [...save.querySelectorAll('button')].find((b) => b.textContent?.includes('Save'))!.click();
      await flush();
      expect(service.saves).toEqual([{ id: ID, partId: '2', name: 'report.pdf' }]);
      expect(notifications.info).toHaveBeenCalledWith('Saved to Files: report.pdf');
    });
  });

  it('says so when the message is gone', async () => {
    await create(new ApiError(404, 'Not found'));
    expect(el().querySelector('[role=alert]')?.textContent).toContain(
      'no longer on the mail server',
    );
  });

  it('reports a load failure and retries', async () => {
    await create(new ApiError(502, 'Bad gateway', 'The mail server failed'));
    expect(el().textContent).toContain('The message could not be loaded: The mail server failed');
    button('Try again')!.click();
    await flush();
    fixture.detectChanges();
    expect(el().querySelector('h1')?.textContent?.trim()).toBe('Quarterly report');
  });

  it('reports an unexpected load failure in general words', async () => {
    await create(new Error('socket hang up'));
    expect(el().textContent).toContain('The message could not be loaded: Something went wrong.');
    expect(el().textContent).not.toContain('socket hang up');
  });

  it('keeps the message shown when marking it read fails', async () => {
    setup = (fake) => (fake.readError = new ApiError(503, 'Unavailable', 'Mail server down'));
    await create(message({ seen: false }));
    await flush();
    fixture.detectChanges();
    expect(service.reads).toEqual([ID]);
    expect(el().querySelector('h1')?.textContent?.trim()).toBe('Quarterly report');
    expect(notifications.error).not.toHaveBeenCalled();
  });

  describe('headers and body edge cases', () => {
    it('names a message without subject or body', async () => {
      await create(
        message({ subject: '', body: { html: '', text: '', remoteImagesBlocked: false } }),
      );
      expect(el().querySelector('h1')?.textContent?.trim()).toBe('(no subject)');
      expect(iframe()).toBeNull();
      expect(el().querySelector('.empty')?.textContent?.trim()).toBe('This message has no text.');
    });

    it('shows a sender by address when it has no name, and the Cc line', async () => {
      await create(
        message({
          from: [{ name: '', address: 'noreply@example.test' }],
          cc: [
            { name: 'Anna Probe', address: 'anna@example.test' },
            { name: '', address: 'team@example.test' },
          ],
        }),
      );
      expect(el().querySelector('.sender')?.textContent?.trim()).toBe('noreply@example.test');
      expect(el().querySelector('.from .address')).toBeNull();
      expect(el().querySelector('.initial')?.textContent?.trim()).toBe('N');
      const terms = [...el().querySelectorAll('.recipients dt')].map((dt) => dt.textContent);
      expect(terms).toEqual(['To', 'Cc']);
      expect(el().querySelectorAll('.recipients dd')[1].textContent?.trim()).toBe(
        'Anna Probe, team@example.test',
      );
    });

    it('offers only "Load images" when the sender has no address', async () => {
      await create(
        message({
          from: [{ name: 'Unknown', address: '' }],
          body: { html: '<p>x</p>', remoteImageCount: 1, remoteImagesBlocked: true, text: '' },
        }),
      );
      expect(button('Load images')).toBeDefined();
      expect(button('Always load from this sender')).toBeUndefined();
    });
  });

  describe('navigating to another message', () => {
    const OTHER = '0192f0c4-0000-7000-8000-00000000a002';
    const lunch = () => message({ id: OTHER, subject: 'Lunch plans', seen: true });

    async function navigate(id: string) {
      params.next(convertToParamMap({ id }));
      fixture.detectChanges();
      await flush();
      fixture.detectChanges();
    }

    it('shows the loading state, not the previous message, while the next one loads', async () => {
      const next = deferred<MailMessageResponse>();
      await create(message(), next.promise);
      expect(el().querySelector('h1')?.textContent?.trim()).toBe('Quarterly report');
      await navigate(OTHER);
      expect(el().querySelector('h1')).toBeNull();
      expect(el().querySelector('[role=status]')?.textContent?.trim()).toBe('Loading message…');
      next.resolve(lunch());
      await flush();
      fixture.detectChanges();
      expect(el().querySelector('h1')?.textContent?.trim()).toBe('Lunch plans');
      expect(service.gets.map((g) => g.id)).toEqual([ID, OTHER]);
    });

    it('ignores an older load that answers after the newer one', async () => {
      const first = deferred<MailMessageResponse>();
      await create(first.promise, lunch());
      await navigate(OTHER);
      expect(el().querySelector('h1')?.textContent?.trim()).toBe('Lunch plans');

      first.resolve(message({ seen: false }));
      await flush();
      fixture.detectChanges();
      expect(el().querySelector('h1')?.textContent?.trim()).toBe('Lunch plans');
      // The stale, unread message is not marked read either.
      expect(service.reads).toEqual([]);
    });

    it('ignores an older load that fails after the newer one', async () => {
      const first = deferred<MailMessageResponse>();
      await create(first.promise, lunch());
      await navigate(OTHER);
      first.reject(new ApiError(404, 'Not found'));
      await flush();
      fixture.detectChanges();
      expect(el().querySelector('h1')?.textContent?.trim()).toBe('Lunch plans');
      expect(el().querySelector('[role=alert]')).toBeNull();
    });

    it('does not apply proxied images of the previous message', async () => {
      const blocked = message({
        body: { html: '<p>blocked</p>', remoteImageCount: 1, remoteImagesBlocked: true, text: '' },
      });
      const proxy = deferred<MailMessageResponse>();
      await create(blocked, proxy.promise, lunch());
      button('Load images')!.click();
      await flush();
      await navigate(OTHER);
      expect(el().querySelector('h1')?.textContent?.trim()).toBe('Lunch plans');

      proxy.resolve(
        message({
          body: {
            html: '<img src="/api/v1/mail/image-proxy?url=x">',
            remoteImagesBlocked: false,
            text: '',
          },
        }),
      );
      await flush();
      fixture.detectChanges();
      expect(el().querySelector('h1')?.textContent?.trim()).toBe('Lunch plans');
      expect(iframe()!.srcdoc).not.toContain('image-proxy');
    });

    it('does not report a failed image load of the previous message', async () => {
      const blocked = message({
        body: { html: '<p>blocked</p>', remoteImageCount: 1, remoteImagesBlocked: true, text: '' },
      });
      const proxy = deferred<MailMessageResponse>();
      await create(blocked, proxy.promise, lunch());
      button('Load images')!.click();
      await flush();
      await navigate(OTHER);
      proxy.reject(new ApiError(502, 'Bad gateway', 'The mail server failed'));
      await flush();
      expect(notifications.error).not.toHaveBeenCalled();
    });

    it('closes an open attachment preview', async () => {
      await create(
        message({
          attachments: [
            {
              partId: '3',
              filename: 'photo.png',
              contentId: '',
              contentType: 'image/png',
              sizeBytes: 10,
            },
          ],
        }),
        lunch(),
      );
      el().querySelector<HTMLButtonElement>('.attachments li button')!.click();
      fixture.detectChanges();
      expect(el().querySelector('.preview')).not.toBeNull();
      await navigate(OTHER);
      expect(el().querySelector('.preview')).toBeNull();
    });

    /** A message with one text attachment under part id "4" (part ids repeat across messages). */
    const withNotes = (id: string, filename: string) =>
      message({
        id,
        subject: filename,
        attachments: [
          { partId: '4', filename, contentId: '', contentType: 'text/plain', sizeBytes: 10 },
        ],
      });
    const partButton = (label: string) =>
      [...el().querySelectorAll<HTMLButtonElement>('.attachments li button')].find(
        (b) => b.textContent?.trim() === label,
      )!;

    it('does not show a late text preview of the previous message under the same part id', async () => {
      await create(withNotes(ID, 'old.txt'), withNotes(OTHER, 'new.txt'));
      const oldBlob = deferred<Blob>();
      service.blob = oldBlob.promise;
      partButton('Preview').click();
      await navigate(OTHER);

      const newBlob = deferred<Blob>();
      service.blob = newBlob.promise;
      partButton('Preview').click();
      oldBlob.resolve(new Blob(['old text']));
      await flush();
      fixture.detectChanges();
      expect(el().querySelector('.preview figcaption span')?.textContent).toBe('new.txt');
      expect(el().querySelector('.preview pre')).toBeNull();
      expect(el().querySelector('.preview [role=status]')?.textContent).toContain(
        'Loading preview',
      );

      newBlob.resolve(new Blob(['new text']));
      await flush();
      fixture.detectChanges();
      expect(el().querySelector('.preview pre')?.textContent).toBe('new text');
    });

    it('does not show a late preview failure of the previous message either', async () => {
      await create(withNotes(ID, 'old.txt'), withNotes(OTHER, 'new.txt'));
      const oldBlob = deferred<Blob>();
      service.blob = oldBlob.promise;
      partButton('Preview').click();
      await navigate(OTHER);

      service.blob = deferred<Blob>().promise;
      partButton('Preview').click();
      oldBlob.reject(new ApiError(404, 'Not found', 'The attachment is gone'));
      await flush();
      fixture.detectChanges();
      expect(el().querySelector('.preview [role=alert]')).toBeNull();
    });

    it('lets the next message save a part with the same id while an older save runs', async () => {
      await create(withNotes(ID, 'old.txt'), withNotes(OTHER, 'new.txt'));
      const oldSave = deferred<{ name: string }>();
      service.saveResult = oldSave.promise;
      partButton('Save to Files').click();
      fixture.detectChanges();
      expect(partButton('Save to Files').disabled).toBe(true);

      await navigate(OTHER);
      expect(partButton('Save to Files').disabled).toBe(false);
      const newSave = deferred<{ name: string }>();
      service.saveResult = newSave.promise;
      partButton('Save to Files').click();
      fixture.detectChanges();
      expect(service.saves).toEqual([
        { id: ID, partId: '4', name: 'old.txt' },
        { id: OTHER, partId: '4', name: 'new.txt' },
      ]);

      // The older save ends first: the newer one is still running, so its button stays disabled.
      oldSave.resolve({ name: 'old.txt' });
      await flush();
      fixture.detectChanges();
      expect(partButton('Save to Files').disabled).toBe(true);

      newSave.resolve({ name: 'new.txt' });
      await flush();
      fixture.detectChanges();
      expect(partButton('Save to Files').disabled).toBe(false);
    });
  });

  describe('running actions', () => {
    const gtdButtons = () =>
      ['→ Inbox task', '→ Waiting For', 'Create note'].map((label) => button(label)!);

    it('disables the GTD buttons while one runs, so it cannot run twice', async () => {
      await create();
      const pending = deferred<{ id: string; title: string }>();
      service.gtdResult = pending.promise;
      button('→ Inbox task')!.click();
      fixture.detectChanges();
      expect(gtdButtons().map((b) => b.disabled)).toEqual([true, true, true]);
      button('→ Inbox task')!.click();
      expect(service.gtds).toHaveLength(1);

      pending.resolve({ id: 't', title: 'Follow up' });
      await flush();
      fixture.detectChanges();
      expect(gtdButtons().map((b) => b.disabled)).toEqual([false, false, false]);
      expect(notifications.info).toHaveBeenCalledWith('Added to Inbox: Follow up');
    });

    it('reports an unexpected GTD failure in general words and enables the buttons again', async () => {
      await create();
      const pending = deferred<{ id: string; title: string }>();
      service.gtdResult = pending.promise;
      button('→ Waiting For')!.click();
      pending.reject(new TypeError('Failed to fetch'));
      await flush();
      fixture.detectChanges();
      expect(notifications.error).toHaveBeenCalledWith('Something went wrong.');
      expect(gtdButtons().every((b) => !b.disabled)).toBe(true);
    });
  });

  describe('attachment edge cases', () => {
    const parts = () =>
      message({
        attachments: [
          {
            partId: '2',
            filename: 'report.pdf',
            contentId: '',
            contentType: 'application/pdf',
            sizeBytes: 10,
          },
          {
            partId: '3',
            filename: 'photo.png',
            contentId: '',
            contentType: 'image/png',
            sizeBytes: 10,
          },
          {
            partId: '4',
            filename: 'notes.txt',
            contentId: '',
            contentType: 'TEXT/PLAIN',
            sizeBytes: 10,
          },
          {
            partId: '',
            filename: 'broken.bin',
            contentId: '',
            contentType: 'text/plain',
            sizeBytes: 10,
          },
        ],
      });
    const item = (name: string) =>
      [...el().querySelectorAll('.attachments li')].find(
        (li) => li.querySelector('.file-name')?.textContent === name,
      )!;
    const action = (name: string, label: string) =>
      [...item(name).querySelectorAll<HTMLButtonElement>('button')].find(
        (b) => b.textContent?.trim() === label,
      )!;

    it('skips parts without a part id', async () => {
      await create(parts());
      expect(item('broken.bin')).toBeUndefined();
    });

    it('shows a loading text preview, then its failure', async () => {
      await create(parts());
      const blob = deferred<Blob>();
      service.blob = blob.promise;
      action('notes.txt', 'Preview').click();
      fixture.detectChanges();
      expect(el().querySelector('.preview [role=status]')?.textContent).toContain(
        'Loading preview',
      );
      blob.reject(new ApiError(404, 'Not found', 'The attachment is gone'));
      await flush();
      fixture.detectChanges();
      expect(el().querySelector('.preview [role=alert]')?.textContent).toBe(
        'The attachment is gone',
      );
    });

    it('cuts a long text preview at the limit', async () => {
      await create(parts());
      service.blob = Promise.resolve(new Blob(['a'.repeat(TEXT_PREVIEW_LIMIT + 50)]));
      action('notes.txt', 'Preview').click();
      await flush();
      fixture.detectChanges();
      expect(el().querySelector('.preview pre')?.textContent).toHaveLength(TEXT_PREVIEW_LIMIT);
    });

    it('keeps the newer preview when an older text preview answers late', async () => {
      await create(parts());
      const blob = deferred<Blob>();
      service.blob = blob.promise;
      action('notes.txt', 'Preview').click();
      action('photo.png', 'Preview').click();
      blob.resolve(new Blob(['late text']));
      await flush();
      fixture.detectChanges();
      expect(el().querySelector('.preview img')).not.toBeNull();
      expect(el().querySelector('.preview pre')).toBeNull();
      expect(el().querySelector('.preview figcaption span')?.textContent).toBe('photo.png');
    });

    it('saves a part once while saving, then reports a failure and allows a retry', async () => {
      await create(parts());
      const pending = deferred<{ name: string }>();
      service.saveResult = pending.promise;
      action('report.pdf', 'Save to Files').click();
      fixture.detectChanges();
      expect(action('report.pdf', 'Save to Files').disabled).toBe(true);
      expect(action('photo.png', 'Save to Files').disabled).toBe(false);
      action('report.pdf', 'Save to Files').click();
      expect(service.saves).toHaveLength(1);

      pending.reject(new ApiError(413, 'Payload Too Large', 'The file is too large'));
      await flush();
      fixture.detectChanges();
      expect(notifications.error).toHaveBeenCalledWith('The file is too large');
      expect(action('report.pdf', 'Save to Files').disabled).toBe(false);
    });
  });
});
