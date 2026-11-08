import { Location } from '@angular/common';
import { provideLocationMocks } from '@angular/common/testing';
import { Component, input, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import {
  ActivatedRoute,
  convertToParamMap,
  ParamMap,
  provideRouter,
  Router,
} from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { BehaviorSubject } from 'rxjs';
import { ItemRefDto, MailMessageResponse, Settings, SettingsPatch } from '../../../api/generated';
import { ApiError, NotificationService } from '../../../core/http';
import { LinkedItems } from '../../../shared/links';
import { provideBackNavigation } from '../../../shared/navigation';
import { SettingsService } from '../../settings/settings.service';
import { fakeSettings } from '../../settings/testing';
import { MailListSession, SavedMailList } from '../list/mail-list-session';
import { summary } from '../list/mail-testing';
import { MailAction, MailService, MailSummary } from '../list/mail.service';
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
  saves: { id: string; partId: string }[] = [];
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
  saveToFiles(id: string, partId: string) {
    this.saves.push({ id, partId });
    return this.saveResult;
  }
}

/** What a mail action answers: per-id results, or an error for the whole request. */
type ActionAnswer = { id: string; ok: boolean; error?: string; detail?: string }[] | Error;

class FakeMailService {
  applies: { action: MailAction; ids: string[] }[] = [];
  /** The answer of the next `apply`; by default every id succeeds. */
  answer?: ActionAnswer | Promise<ActionAnswer>;

  async apply(action: MailAction, ids: string[]) {
    this.applies.push({ action, ids });
    const answer = (await this.answer) ?? ids.map((id) => ({ id, ok: true }));
    if (answer instanceof Error) throw answer;
    return answer;
  }
}

/** The synced settings as the server holds them: they outlive a page (and a test module). */
class FakeSettingsService {
  readonly current = signal<Settings | null>(fakeSettings());
  readonly settings = this.current.asReadonly();
  patches: SettingsPatch[] = [];
  /** When set, saving fails with it (and the change is rolled back). */
  updateError?: Error;

  load() {
    return Promise.resolve(this.current()!);
  }
  update(patch: SettingsPatch) {
    this.patches.push(patch);
    if (this.updateError) return Promise.reject(this.updateError);
    this.current.update((settings) => ({ ...settings!, ...(patch as Partial<Settings>) }));
    return Promise.resolve(this.current()!);
  }
  allow(senders: string[]) {
    this.current.update((settings) => ({ ...settings!, mailImageAllowedSenders: senders }));
  }
}

describe('MailReadPage', () => {
  let fixture: ComponentFixture<MailReadPage>;
  let service: FakeReadService;
  let mail: FakeMailService;
  let settings: FakeSettingsService;
  let params: BehaviorSubject<ParamMap>;
  let notifications: { info: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };

  /** Adjusts the fake service before the page starts. */
  let setup: ((fake: FakeReadService) => void) | undefined;

  async function create(...answers: Answer[]) {
    service = new FakeReadService();
    service.answers = answers;
    mail = new FakeMailService();
    setup?.(service);
    params = new BehaviorSubject(convertToParamMap({ id: ID }));
    notifications = { info: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
      imports: [MailReadPage],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap: params } },
        { provide: MailReadService, useValue: service },
        { provide: MailService, useValue: mail },
        { provide: NotificationService, useValue: notifications },
        { provide: SettingsService, useValue: settings },
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
    settings = new FakeSettingsService();
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

  it('links Reply, Reply all and Forward to the compose page for this message', async () => {
    await create();
    const href = (text: string) => button(text)?.getAttribute('href');
    expect(href('Reply')).toBe(`/mail/compose?reply=${ID}`);
    expect(href('Reply all')).toBe(`/mail/compose?replyAll=${ID}`);
    expect(href('Forward')).toBe(`/mail/compose?forward=${ID}`);
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
      expect(settings.patches).toEqual([]);
    });

    it('"Always load from this sender" adds the sender to the synced setting and loads them next time at once', async () => {
      settings.allow(['someone@example.test']);
      await create(blocked(), proxied());
      button('Always load from this sender')!.click();
      await flush();
      fixture.detectChanges();
      expect(settings.patches).toEqual([
        { mailImageAllowedSenders: ['someone@example.test', 'max.muster@example.test'] },
      ]);
      expect(localStorage.getItem(IMAGE_ALLOWLIST_KEY)).toBeNull();
      expect(el().querySelector('.images.quiet')?.textContent).toContain('load automatically');

      TestBed.resetTestingModule();
      await create(blocked(), proxied());
      expect(service.gets.map((g) => g.loadImages)).toEqual([false, true]);
      expect(iframe()!.srcdoc).toContain('image-proxy');

      button('Stop')!.click();
      await flush();
      expect(settings.patches.at(-1)).toEqual({
        mailImageAllowedSenders: ['someone@example.test'],
      });
    });

    it('load through the proxy at once, without a banner, when the policy is "allow"', async () => {
      settings.current.update((s) => ({ ...s!, mailImagePolicy: 'allow' }));
      await create(proxied());
      expect(service.gets).toEqual([{ id: ID, loadImages: true }]);
      expect(iframe()!.srcdoc).toContain('image-proxy');
      expect(el().querySelector('.images')).toBeNull();
    });

    it('load at once for a sender whose domain is allowed', async () => {
      settings.allow(['EXAMPLE.test']);
      await create(blocked(), proxied());
      expect(service.gets.map((g) => g.loadImages)).toEqual([false, true]);
      expect(iframe()!.srcdoc).toContain('image-proxy');
    });

    it('stay blocked for other senders', async () => {
      settings.allow(['other@example.test', 'example.org']);
      await create(blocked(), proxied());
      expect(service.gets.map((g) => g.loadImages)).toEqual([false]);
      expect(button('Load images')).toBeDefined();
    });

    it('merge the old per-browser list into the setting once and remove it', async () => {
      settings.allow(['someone@example.test']);
      localStorage.setItem(
        IMAGE_ALLOWLIST_KEY,
        JSON.stringify(['Max.Muster@example.test', 'someone@example.test', 42]),
      );
      await create(blocked(), proxied());
      expect(settings.patches).toEqual([
        { mailImageAllowedSenders: ['someone@example.test', 'max.muster@example.test'] },
      ]);
      expect(localStorage.getItem(IMAGE_ALLOWLIST_KEY)).toBeNull();
      expect(service.gets.map((g) => g.loadImages)).toEqual([false, true]);

      TestBed.resetTestingModule();
      await create(blocked(), proxied());
      expect(settings.patches).toHaveLength(1);
    });

    it('keep the old per-browser list when merging it fails, and still show the message', async () => {
      localStorage.setItem(IMAGE_ALLOWLIST_KEY, JSON.stringify(['max.muster@example.test']));
      settings.updateError = new ApiError(500, 'Server error', 'Not saved');
      await create(blocked());
      expect(localStorage.getItem(IMAGE_ALLOWLIST_KEY)).not.toBeNull();
      expect(el().querySelector('h1')?.textContent?.trim()).toBe('Quarterly report');
      expect(button('Load images')).toBeDefined();
    });

    it('still load once when saving the sender fails', async () => {
      settings.updateError = new ApiError(500, 'Server error', 'Not saved');
      await create(blocked(), proxied());
      button('Always load from this sender')!.click();
      await flush();
      fixture.detectChanges();
      expect(iframe()!.srcdoc).toContain('image-proxy');
      expect(settings.current()!.mailImageAllowedSenders).toEqual([]);
    });

    it('show the error state, without a blocked fallback, when the policy is "allow" and the proxied load fails', async () => {
      settings.current.update((s) => ({ ...s!, mailImagePolicy: 'allow' }));
      await create(new ApiError(502, 'Bad gateway', 'The mail server failed'));
      expect(service.gets).toEqual([{ id: ID, loadImages: true }]);
      expect(el().querySelector('h1')?.textContent?.trim()).not.toBe('Quarterly report');
      expect(el().textContent).toContain('The mail server failed');
    });

    it('load only once through the proxy when the policy is "allow" and the sender is allowed too', async () => {
      settings.current.update((s) => ({
        ...s!,
        mailImagePolicy: 'allow',
        mailImageAllowedSenders: ['max.muster@example.test'],
      }));
      await create(proxied());
      expect(service.gets).toEqual([{ id: ID, loadImages: true }]);
      expect(el().querySelector('.images:not(.quiet)')).toBeNull();
    });

    it('do not offer "Stop" for an allowed sender when the policy is "allow"', async () => {
      settings.current.update((s) => ({
        ...s!,
        mailImagePolicy: 'allow',
        mailImageAllowedSenders: ['max.muster@example.test'],
      }));
      await create(proxied());
      expect(iframe()!.srcdoc).toContain('image-proxy');
      expect(el().querySelector('.images.quiet')).toBeNull();
      expect(button('Stop')).toBeUndefined();
    });

    it('hide "Stop" for a listed sender when the policy changes to "allow" while the mail is open', async () => {
      settings.current.update((s) => ({
        ...s!,
        mailImageAllowedSenders: ['max.muster@example.test'],
      }));
      await create(blocked(), proxied());
      expect(button('Stop')).toBeDefined();

      settings.current.update((s) => ({ ...s!, mailImagePolicy: 'allow' }));
      fixture.detectChanges();
      expect(button('Stop')).toBeUndefined();
      expect(iframe()!.srcdoc).toContain('image-proxy');
    });

    it('stay blocked, and the message still shows, when the settings cannot be loaded', async () => {
      settings.current.set(null);
      settings.load = () => Promise.reject(new ApiError(503, 'Unavailable', 'Offline'));
      await create(blocked(), proxied());
      expect(service.gets).toEqual([{ id: ID, loadImages: false }]);
      expect(el().querySelector('h1')?.textContent?.trim()).toBe('Quarterly report');
      expect(button('Load images')).toBeDefined();
    });

    it('load once, without saving the sender, on "Always load" when the settings cannot be loaded', async () => {
      settings.current.set(null);
      settings.load = () => Promise.reject(new ApiError(503, 'Unavailable', 'Offline'));
      await create(blocked(), proxied());
      button('Always load from this sender')!.click();
      await flush();
      fixture.detectChanges();
      expect(service.gets.map((g) => g.loadImages)).toEqual([false, true]);
      expect(iframe()!.srcdoc).toContain('image-proxy');
      expect(settings.patches).toEqual([]);
      expect(button('Stop')).toBeUndefined();
    });

    it('do not request a message left while the settings were still loading', async () => {
      const pending = deferred<Settings>();
      settings.current.set(null);
      settings.load = async () => {
        const loaded = await pending.promise;
        settings.current.set(loaded);
        return loaded;
      };
      await create(message({ id: 'next-message' }));
      params.next(convertToParamMap({ id: 'next-message' }));
      fixture.detectChanges();
      pending.resolve(fakeSettings());
      await flush();
      fixture.detectChanges();
      expect(service.gets).toEqual([{ id: 'next-message', loadImages: false }]);
    });

    it('keep the sender allowed, and the images shown, when saving "Stop" fails', async () => {
      settings.allow(['max.muster@example.test']);
      await create(blocked(), proxied());
      settings.updateError = new ApiError(500, 'Server error', 'Not saved');
      button('Stop')!.click();
      await flush();
      fixture.detectChanges();
      expect(settings.patches).toEqual([{ mailImageAllowedSenders: [] }]);
      expect(settings.current()!.mailImageAllowedSenders).toEqual(['max.muster@example.test']);
      expect(iframe()!.srcdoc).toContain('image-proxy');
      expect(button('Stop')).toBeDefined();
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
      settings.allow(['max.muster@example.test']);
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
      expect(service.saves).toEqual([{ id: ID, partId: '2' }]);
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

    it('ignores a proxied load for an allowed sender that answers after navigating', async () => {
      settings.allow(['max.muster@example.test']);
      const blocked = message({
        body: { html: '<p>blocked</p>', remoteImageCount: 1, remoteImagesBlocked: true, text: '' },
      });
      const proxy = deferred<MailMessageResponse>();
      await create(blocked, proxy.promise, lunch());
      await navigate(OTHER);
      expect(el().querySelector('h1')?.textContent?.trim()).toBe('Lunch plans');

      proxy.resolve(
        message({
          body: {
            html: '<img src="/api/v1/mail/image-proxy?u=x">',
            remoteImageCount: 1,
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

    it('ignores a failed proxied load for an allowed sender that answers after navigating', async () => {
      settings.allow(['max.muster@example.test']);
      const blocked = message({
        body: { html: '<p>blocked</p>', remoteImageCount: 1, remoteImagesBlocked: true, text: '' },
      });
      const proxy = deferred<MailMessageResponse>();
      await create(blocked, proxy.promise, lunch());
      expect(service.gets.map((g) => g.loadImages)).toEqual([false, true]);
      await navigate(OTHER);
      expect(el().querySelector('h1')?.textContent?.trim()).toBe('Lunch plans');

      proxy.reject(new ApiError(502, 'Bad gateway', 'The mail server failed'));
      await flush();
      fixture.detectChanges();
      expect(notifications.error).not.toHaveBeenCalled();
      expect(el().querySelector('h1')?.textContent?.trim()).toBe('Lunch plans');
      expect(iframe()!.srcdoc).not.toContain('<p>blocked</p>');
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
        { id: ID, partId: '4' },
        { id: OTHER, partId: '4' },
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

    it('still blocks a second save when coming back to a message whose save runs', async () => {
      await create(withNotes(ID, 'old.txt'), withNotes(OTHER, 'new.txt'), withNotes(ID, 'old.txt'));
      const oldSave = deferred<{ name: string }>();
      service.saveResult = oldSave.promise;
      partButton('Save to Files').click();
      await navigate(OTHER);
      await navigate(ID);
      expect(partButton('Save to Files').disabled).toBe(true);
      partButton('Save to Files').click();
      expect(service.saves).toEqual([{ id: ID, partId: '4' }]);

      oldSave.resolve({ name: 'old.txt' });
      await flush();
      fixture.detectChanges();
      expect(partButton('Save to Files').disabled).toBe(false);
    });

    it('ignores a failed proxied load for an allowed sender after the page is gone', async () => {
      settings.allow(['max.muster@example.test']);
      const blocked = message({
        body: { html: '<p>blocked</p>', remoteImageCount: 1, remoteImagesBlocked: true, text: '' },
      });
      const proxy = deferred<MailMessageResponse>();
      await create(blocked, proxy.promise);
      expect(service.gets.map((g) => g.loadImages)).toEqual([false, true]);
      fixture.destroy();

      proxy.reject(new ApiError(502, 'Bad gateway', 'The mail server failed'));
      await flush();
      expect(notifications.error).not.toHaveBeenCalled();
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

    it('does not reopen a text preview that was closed while it loaded', async () => {
      await create(parts());
      const blob = deferred<Blob>();
      service.blob = blob.promise;
      action('notes.txt', 'Preview').click();
      fixture.detectChanges();
      button('Close preview')!.click();
      fixture.detectChanges();
      expect(el().querySelector('.preview')).toBeNull();

      blob.resolve(new Blob(['late text']));
      await flush();
      fixture.detectChanges();
      expect(el().querySelector('.preview')).toBeNull();
    });

    it('takes only the latest answer when the same text part is previewed again', async () => {
      await create(parts());
      const first = deferred<Blob>();
      service.blob = first.promise;
      action('notes.txt', 'Preview').click();
      fixture.detectChanges();
      button('Close preview')!.click();
      const second = deferred<Blob>();
      service.blob = second.promise;
      action('notes.txt', 'Preview').click();
      fixture.detectChanges();

      first.reject(new ApiError(404, 'Not found', 'The attachment is gone'));
      await flush();
      fixture.detectChanges();
      expect(el().querySelector('.preview [role=alert]')).toBeNull();
      expect(el().querySelector('.preview [role=status]')?.textContent).toContain(
        'Loading preview',
      );

      second.resolve(new Blob(['second text']));
      await flush();
      fixture.detectChanges();
      expect(el().querySelector('.preview pre')?.textContent).toBe('second text');
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

  describe('Delete', () => {
    const deleteButton = () => button('Delete');
    const press = (key: string, init: KeyboardEventInit = {}, target: EventTarget = document) => {
      const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init });
      target.dispatchEvent(event);
      return event;
    };
    let navigate: ReturnType<typeof vi.spyOn>;

    async function open(...answers: Answer[]) {
      await create(...answers);
      navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    }

    it('is not offered before the message has loaded', async () => {
      await create(deferred<MailMessageResponse>().promise);
      expect(deleteButton()).toBeUndefined();
      expect(press('#').defaultPrevented).toBe(false);
      expect(mail.applies).toEqual([]);
    });

    it('is not offered when the message could not be loaded', async () => {
      await create(new ApiError(404, 'Not found'));
      expect(deleteButton()).toBeUndefined();
      expect(press('#').defaultPrevented).toBe(false);
      expect(mail.applies).toEqual([]);
    });

    it('deletes the message with the mail action and returns to the unified Inbox', async () => {
      await open();
      expect(deleteButton()!.getAttribute('title')).toBe('Delete (#)');
      deleteButton()!.click();
      await flush();
      expect(mail.applies).toEqual([{ action: 'DELETE', ids: [ID] }]);
      expect(navigate).toHaveBeenCalledWith('/mail', { replaceUrl: true });
      expect(notifications.error).not.toHaveBeenCalled();
    });

    it('is disabled while the request runs, so it cannot run twice', async () => {
      await open();
      const pending = deferred<ActionAnswer>();
      mail.answer = pending.promise;
      deleteButton()!.click();
      fixture.detectChanges();
      expect(deleteButton()!.disabled).toBe(true);
      deleteButton()!.click();
      press('#');
      expect(mail.applies).toHaveLength(1);

      pending.resolve([{ id: ID, ok: true }]);
      await flush();
      fixture.detectChanges();
      expect(navigate).toHaveBeenCalledTimes(1);
      expect(deleteButton()!.disabled).toBe(false);
    });

    it('stays open and shows the server reason when the message could not be deleted', async () => {
      await open();
      mail.answer = [{ id: ID, ok: false, detail: 'The mail server refused the change' }];
      deleteButton()!.click();
      await flush();
      fixture.detectChanges();
      expect(navigate).not.toHaveBeenCalled();
      expect(notifications.error).toHaveBeenCalledWith(
        'The message could not be deleted: The mail server refused the change',
      );
      expect(el().querySelector('h1')?.textContent?.trim()).toBe('Quarterly report');
      expect(deleteButton()!.disabled).toBe(false);
    });

    it('reports a failed request with the API error and enables the button again', async () => {
      await open();
      mail.answer = new ApiError(409, 'Conflict', 'The account is being synced');
      deleteButton()!.click();
      await flush();
      fixture.detectChanges();
      expect(navigate).not.toHaveBeenCalled();
      expect(notifications.error).toHaveBeenCalledWith(
        'The message could not be deleted: The account is being synced',
      );
      expect(deleteButton()!.disabled).toBe(false);
    });

    it('reports an unexpected failure, or a missing result, in general words', async () => {
      await open();
      mail.answer = new TypeError('Failed to fetch');
      deleteButton()!.click();
      await flush();
      expect(notifications.error).toHaveBeenLastCalledWith(
        'The message could not be deleted: Something went wrong.',
      );
      mail.answer = [];
      deleteButton()!.click();
      await flush();
      expect(notifications.error).toHaveBeenLastCalledWith(
        'The message could not be deleted: Something went wrong.',
      );
      expect(navigate).not.toHaveBeenCalled();
    });

    it('does not leave a message the user moved to while the delete ran', async () => {
      await open();
      const pending = deferred<ActionAnswer>();
      mail.answer = pending.promise;
      deleteButton()!.click();
      service.answers.push(message({ id: 'other' }));
      params.next(convertToParamMap({ id: 'other' }));
      await flush();
      pending.resolve([{ id: ID, ok: true }]);
      await flush();
      expect(navigate).not.toHaveBeenCalled();
    });

    it('does not navigate when the page was left while the delete ran', async () => {
      await open();
      const pending = deferred<ActionAnswer>();
      mail.answer = pending.promise;
      deleteButton()!.click();
      fixture.destroy();
      pending.resolve([{ id: ID, ok: true }]);
      await flush();
      expect(mail.applies).toEqual([{ action: 'DELETE', ids: [ID] }]);
      expect(navigate).not.toHaveBeenCalled();
    });

    it('treats a result for another id only as a failure', async () => {
      await open();
      mail.answer = [{ id: 'someone-else', ok: true }];
      deleteButton()!.click();
      await flush();
      expect(navigate).not.toHaveBeenCalled();
      expect(notifications.error).toHaveBeenCalledWith(
        'The message could not be deleted: Something went wrong.',
      );
    });

    it('does not delete with # while the next message loads', async () => {
      await open(message(), deferred<MailMessageResponse>().promise);
      params.next(convertToParamMap({ id: 'next-message' }));
      fixture.detectChanges();
      await flush();
      fixture.detectChanges();
      expect(press('#').defaultPrevented).toBe(false);
      await flush();
      expect(mail.applies).toEqual([]);
    });

    describe('the # key', () => {
      it('deletes, as in the list', async () => {
        await open();
        const event = press('#');
        await flush();
        expect(event.defaultPrevented).toBe(true);
        expect(mail.applies).toEqual([{ action: 'DELETE', ids: [ID] }]);
        expect(navigate).toHaveBeenCalledWith('/mail', { replaceUrl: true });
      });

      it('also deletes when typed with AltGr (Swiss German keyboards)', async () => {
        await open();
        press('#', { ctrlKey: true, altKey: true });
        await flush();
        expect(mail.applies).toHaveLength(1);
      });

      it('does nothing while typing in an input or editor', async () => {
        await open();
        const input = document.createElement('input');
        const editor = document.createElement('div');
        editor.contentEditable = 'true';
        // jsdom does not derive isContentEditable from the attribute.
        Object.defineProperty(editor, 'isContentEditable', { value: true });
        document.body.append(input, editor);
        try {
          expect(press('#', {}, input).defaultPrevented).toBe(false);
          expect(press('#', {}, editor).defaultPrevented).toBe(false);
          await flush();
          expect(mail.applies).toEqual([]);
        } finally {
          input.remove();
          editor.remove();
        }
      });

      it('does nothing while a dialog is open, and deletes again once it is closed', async () => {
        await open();
        const dialog = TestBed.inject(MatDialog);
        // No animations: jsdom never ends them, so the dialog would not finish closing.
        const ref = dialog.open(StubDialog, {
          enterAnimationDuration: 0,
          exitAnimationDuration: 0,
        });
        await flush();
        expect(dialog.openDialogs).toHaveLength(1);
        expect(press('#').defaultPrevented).toBe(false);
        await flush();
        expect(mail.applies).toEqual([]);
        expect(navigate).not.toHaveBeenCalled();

        ref.close();
        await flush();
        expect(dialog.openDialogs).toHaveLength(0);
        expect(press('#').defaultPrevented).toBe(true);
        await flush();
        expect(mail.applies).toEqual([{ action: 'DELETE', ids: [ID] }]);
      });

      it('ignores other keys, chords and an auto-repeated #', async () => {
        await open();
        press('x');
        press('#', { metaKey: true });
        press('#', { repeat: true });
        await flush();
        expect(mail.applies).toEqual([]);
      });
    });
  });

  describe('Archive and Spam', () => {
    const press = (key: string, init: KeyboardEventInit = {}, target: EventTarget = document) => {
      const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init });
      target.dispatchEvent(event);
      return event;
    };
    const triageButtons = () => ['Archive', 'Spam', 'Delete'].map((label) => button(label)!);
    let navigate: ReturnType<typeof vi.spyOn>;

    async function open(...answers: Answer[]) {
      await create(...answers);
      navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    }

    it('are not offered before the message has loaded', async () => {
      await create(deferred<MailMessageResponse>().promise);
      expect(button('Archive')).toBeUndefined();
      expect(button('Spam')).toBeUndefined();
      expect(press('e').defaultPrevented).toBe(false);
      expect(press('!').defaultPrevented).toBe(false);
      expect(mail.applies).toEqual([]);
    });

    it('are not offered when the message could not be loaded', async () => {
      await create(new ApiError(404, 'Not found'));
      expect(button('Archive')).toBeUndefined();
      expect(button('Spam')).toBeUndefined();
      expect(press('e').defaultPrevented).toBe(false);
      expect(press('!').defaultPrevented).toBe(false);
      await flush();
      expect(mail.applies).toEqual([]);
    });

    it('ignores e and ! while the next message loads', async () => {
      await open(message(), deferred<MailMessageResponse>().promise);
      params.next(convertToParamMap({ id: 'next-message' }));
      fixture.detectChanges();
      await flush();
      fixture.detectChanges();
      expect(press('e').defaultPrevented).toBe(false);
      expect(press('!').defaultPrevented).toBe(false);
      await flush();
      expect(mail.applies).toEqual([]);
    });

    it('ignores e and ! while a dialog is open', async () => {
      await open();
      const dialog = TestBed.inject(MatDialog);
      const ref = dialog.open(StubDialog, { enterAnimationDuration: 0, exitAnimationDuration: 0 });
      await flush();
      try {
        expect(press('e').defaultPrevented).toBe(false);
        expect(press('!').defaultPrevented).toBe(false);
        await flush();
        expect(mail.applies).toEqual([]);
      } finally {
        ref.close();
        await flush();
      }
    });

    it('does not leave a message the user moved to while the archive ran', async () => {
      await open();
      const pending = deferred<ActionAnswer>();
      mail.answer = pending.promise;
      button('Archive')!.click();
      service.answers.push(message({ id: 'other' }));
      params.next(convertToParamMap({ id: 'other' }));
      await flush();
      pending.resolve([{ id: ID, ok: true }]);
      await flush();
      expect(mail.applies).toEqual([{ action: 'ARCHIVE', ids: [ID] }]);
      expect(navigate).not.toHaveBeenCalled();
    });

    it('sit next to Delete in the action bar, with their shortcuts in the title', async () => {
      await open();
      const group = button('Delete')!.closest('[role="group"]')!;
      expect([...group.querySelectorAll('button')].map((b) => b.textContent?.trim())).toEqual([
        'Archive',
        'Spam',
        'Delete',
      ]);
      expect(button('Archive')!.getAttribute('title')).toBe('Archive (e)');
      expect(button('Spam')!.getAttribute('title')).toBe('Spam (!)');
    });

    for (const [label, action, key] of [
      ['Archive', 'ARCHIVE', 'e'],
      ['Spam', 'SPAM', '!'],
    ] as const) {
      describe(label, () => {
        it(`calls the ${action} mail action and returns to the list`, async () => {
          await open();
          button(label)!.click();
          await flush();
          expect(mail.applies).toEqual([{ action, ids: [ID] }]);
          expect(navigate).toHaveBeenCalledWith('/mail', { replaceUrl: true });
          expect(notifications.error).not.toHaveBeenCalled();
        });

        it('disables all three buttons while it runs, so nothing runs twice', async () => {
          await open();
          const pending = deferred<ActionAnswer>();
          mail.answer = pending.promise;
          button(label)!.click();
          fixture.detectChanges();
          expect(triageButtons().map((b) => b.disabled)).toEqual([true, true, true]);
          for (const b of triageButtons()) b.click();
          press('e');
          press('!');
          press('#');
          expect(mail.applies).toEqual([{ action, ids: [ID] }]);

          pending.resolve([{ id: ID, ok: true }]);
          await flush();
          fixture.detectChanges();
          expect(navigate).toHaveBeenCalledTimes(1);
          expect(triageButtons().map((b) => b.disabled)).toEqual([false, false, false]);
        });

        it(`runs with the ${key} key, as in the list`, async () => {
          await open();
          const event = press(key);
          await flush();
          expect(event.defaultPrevented).toBe(true);
          expect(mail.applies).toEqual([{ action, ids: [ID] }]);
          expect(navigate).toHaveBeenCalledWith('/mail', { replaceUrl: true });
        });

        it(`ignores ${key} while typing in an input or editor, or as a chord or repeat`, async () => {
          await open();
          const input = document.createElement('input');
          const editor = document.createElement('div');
          editor.contentEditable = 'true';
          Object.defineProperty(editor, 'isContentEditable', { value: true });
          document.body.append(input, editor);
          try {
            expect(press(key, {}, input).defaultPrevented).toBe(false);
            expect(press(key, {}, editor).defaultPrevented).toBe(false);
            press(key, { metaKey: true });
            press(key, { repeat: true });
            await flush();
            expect(mail.applies).toEqual([]);
          } finally {
            input.remove();
            editor.remove();
          }
        });
      });
    }

    it('also marks as spam when ! is typed with Shift', async () => {
      await open();
      press('!', { shiftKey: true });
      await flush();
      expect(mail.applies).toEqual([{ action: 'SPAM', ids: [ID] }]);
    });

    it('stays open with the server reason when the account has no Archive folder', async () => {
      await open();
      const detail =
        'The account has no folder with the role this action needs (Archive, Trash or Spam)';
      mail.answer = [{ id: ID, ok: false, error: 'NO_ROLE_FOLDER', detail }];
      button('Archive')!.click();
      await flush();
      fixture.detectChanges();
      expect(navigate).not.toHaveBeenCalled();
      expect(notifications.error).toHaveBeenCalledWith(
        `The message could not be archived: ${detail}`,
      );
      expect(el().querySelector('h1')?.textContent?.trim()).toBe('Quarterly report');
      expect(triageButtons().map((b) => b.disabled)).toEqual([false, false, false]);
    });

    it('stays open with the server reason when the account has no Spam folder', async () => {
      await open();
      mail.answer = [{ id: ID, ok: false, error: 'NO_ROLE_FOLDER', detail: 'No Spam folder' }];
      press('!');
      await flush();
      expect(navigate).not.toHaveBeenCalled();
      expect(notifications.error).toHaveBeenCalledWith(
        'The message could not be marked as spam: No Spam folder',
      );
    });

    it('reports a failed request with the API error', async () => {
      await open();
      mail.answer = new ApiError(409, 'Conflict', 'The account is being synced');
      button('Spam')!.click();
      await flush();
      expect(navigate).not.toHaveBeenCalled();
      expect(notifications.error).toHaveBeenCalledWith(
        'The message could not be marked as spam: The account is being synced',
      );
      mail.answer = new TypeError('Failed to fetch');
      button('Archive')!.click();
      await flush();
      expect(notifications.error).toHaveBeenLastCalledWith(
        'The message could not be archived: Something went wrong.',
      );
    });

    it('reports a refusal without a reason, or a missing result, in general words', async () => {
      await open();
      mail.answer = [{ id: ID, ok: false }];
      press('!');
      await flush();
      expect(notifications.error).toHaveBeenLastCalledWith(
        'The message could not be marked as spam: Something went wrong.',
      );
      mail.answer = [];
      press('e');
      await flush();
      expect(notifications.error).toHaveBeenLastCalledWith(
        'The message could not be archived: Something went wrong.',
      );
      expect(navigate).not.toHaveBeenCalled();
    });

    it('can mark as spam after archiving failed, and then returns to the list', async () => {
      await open();
      mail.answer = [{ id: ID, ok: false, error: 'NO_ROLE_FOLDER', detail: 'No Archive folder' }];
      press('e');
      await flush();
      expect(navigate).not.toHaveBeenCalled();
      mail.answer = [{ id: ID, ok: true }];
      press('!');
      await flush();
      expect(mail.applies).toEqual([
        { action: 'ARCHIVE', ids: [ID] },
        { action: 'SPAM', ids: [ID] },
      ]);
      expect(navigate).toHaveBeenCalledExactlyOnceWith('/mail', { replaceUrl: true });
    });

    it('does not navigate when the page was left while marking as spam ran', async () => {
      await open();
      const pending = deferred<ActionAnswer>();
      mail.answer = pending.promise;
      button('Spam')!.click();
      fixture.destroy();
      pending.resolve([{ id: ID, ok: true }]);
      await flush();
      expect(mail.applies).toEqual([{ action: 'SPAM', ids: [ID] }]);
      expect(navigate).not.toHaveBeenCalled();
      expect(notifications.error).not.toHaveBeenCalled();
    });
  });
});

@Component({ selector: 'app-stub-dialog', template: '<p>dialog</p>' })
class StubDialog {}

@Component({ selector: 'app-stub-list', template: '<p class="list">list</p>' })
class StubList {}

describe('MailReadPage Archive, Spam and Delete: back to where it was opened from', () => {
  let mail: FakeMailService;

  /** Opens the message after visiting the given pages in order (none: opened directly). */
  async function openFrom(...from: (string | null)[]): Promise<RouterTestingHarness> {
    mail = new FakeMailService();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'mail', component: StubList },
          { path: 'mail/folders/:folderId', component: StubList },
          { path: 'new', component: StubList },
          { path: 'notes', component: StubList },
          { path: 'mail/compose', component: StubList },
          { path: 'mail/messages/:id', component: MailReadPage },
        ]),
        provideLocationMocks(),
        provideBackNavigation(),
        { provide: MailReadService, useValue: new FakeReadService() },
        { provide: MailService, useValue: mail },
        { provide: NotificationService, useValue: { info: vi.fn(), error: vi.fn() } },
      ],
    });
    TestBed.overrideComponent(MailReadPage, {
      remove: { imports: [LinkedItems] },
      add: { imports: [StubLinkedItems] },
    });
    const harness = await RouterTestingHarness.create();
    // As in the app: Back and Forward (popstate) navigate.
    TestBed.inject(Router).setUpLocationChangeListener();
    for (const url of from) if (url) await harness.navigateByUrl(url);
    await harness.navigateByUrl(`/mail/messages/${ID}`);
    await flush();
    harness.detectChanges();
    return harness;
  }

  /** Clicks an action bar button and returns the URL the app ends up on. */
  async function clickAction(harness: RouterTestingHarness, label: string): Promise<string> {
    [...harness.routeNativeElement!.querySelectorAll('button')]
      .find((b) => b.textContent?.trim() === label)!
      .click();
    await flush();
    await harness.fixture.whenStable();
    await flush();
    return TestBed.inject(Router).url;
  }

  const clickDelete = (harness: RouterTestingHarness) => clickAction(harness, 'Delete');

  for (const [label, action] of [
    ['Archive', 'ARCHIVE'],
    ['Spam', 'SPAM'],
  ] as const) {
    describe(label, () => {
      it('returns to the folder the message was opened from', async () => {
        const harness = await openFrom('/mail/folders/f-work');
        expect(await clickAction(harness, label)).toBe('/mail/folders/f-work');
        expect(mail.applies).toEqual([{ action, ids: [ID] }]);
        expect(harness.routeNativeElement!.querySelector('.list')).not.toBeNull();
      });

      it('returns to the page it was opened from, with its filter', async () => {
        expect(await clickAction(await openFrom('/new?filter=mail'), label)).toBe(
          '/new?filter=mail',
        );
      });

      it('goes back in the history, so Back does not open the message again', async () => {
        const harness = await openFrom('/notes', '/mail/folders/f-work');
        expect(await clickAction(harness, label)).toBe('/mail/folders/f-work');
        const location = TestBed.inject(Location);
        location.back();
        expect(location.path()).toBe('/notes');
      });

      it('stays on the message when the action fails', async () => {
        const harness = await openFrom('/mail/folders/f-work');
        mail.answer = [{ id: ID, ok: false, error: 'NO_ROLE_FOLDER', detail: 'No folder' }];
        expect(await clickAction(harness, label)).toBe(`/mail/messages/${ID}`);
        expect(harness.routeNativeElement!.querySelector('.list')).toBeNull();
      });
    });
  }

  it('returns to the folder the message was opened from', async () => {
    const harness = await openFrom('/mail/folders/f-archive');
    expect(await clickDelete(harness)).toBe('/mail/folders/f-archive');
    expect(mail.applies).toEqual([{ action: 'DELETE', ids: [ID] }]);
    expect(harness.routeNativeElement!.querySelector('.list')).not.toBeNull();
  });

  it('returns to the unified Inbox it was opened from', async () => {
    expect(await clickDelete(await openFrom('/mail'))).toBe('/mail');
  });

  it('returns to another page it was opened from', async () => {
    expect(await clickDelete(await openFrom('/notes'))).toBe('/notes');
  });

  it('returns to the unified Inbox when opened directly, replacing the message', async () => {
    const harness = await openFrom(null);
    expect(await clickDelete(harness)).toBe('/mail');
    const location = TestBed.inject(Location);
    location.back();
    // The deleted message is gone from the history: Back leaves the (mock) history's first page.
    expect(location.path()).not.toBe(`/mail/messages/${ID}`);
  });

  it('returns to a folder whose id needs URL encoding', async () => {
    expect(await clickDelete(await openFrom('/mail/folders/f%20work%2Fsub'))).toBe(
      '/mail/folders/f%20work%2Fsub',
    );
  });

  it('goes back in the history, so Back does not open the deleted message again', async () => {
    const harness = await openFrom('/notes', '/mail/folders/f-archive');
    expect(await clickDelete(harness)).toBe('/mail/folders/f-archive');
    const location = TestBed.inject(Location);
    location.back();
    expect(location.path()).toBe('/notes');
  });

  describe('while going back', () => {
    /** Lets the action's request finish, but not the router's (later) popstate navigation. */
    const microtasks = async () => {
      for (let i = 0; i < 10; i++) await Promise.resolve();
    };
    const deleteButton = (harness: RouterTestingHarness) =>
      [...harness.routeNativeElement!.querySelectorAll('button')].find(
        (b) => b.textContent?.trim() === 'Delete',
      )!;

    it('does not delete again on a second # until the list is shown', async () => {
      const harness = await openFrom('/mail/folders/f-trash');
      document.dispatchEvent(new KeyboardEvent('keydown', { key: '#', bubbles: true }));
      await microtasks();
      expect(mail.applies).toHaveLength(1);
      expect(TestBed.inject(Router).url).toBe(`/mail/messages/${ID}`);
      document.dispatchEvent(new KeyboardEvent('keydown', { key: '#', bubbles: true }));
      await flush();
      await harness.fixture.whenStable();
      await flush();
      expect(TestBed.inject(Router).url).toBe('/mail/folders/f-trash');
      expect(mail.applies).toEqual([{ action: 'DELETE', ids: [ID] }]);
    });

    it('does not delete again on a second click until the list is shown', async () => {
      const harness = await openFrom('/mail/folders/f-trash');
      deleteButton(harness).click();
      await microtasks();
      expect(TestBed.inject(Router).url).toBe(`/mail/messages/${ID}`);
      harness.detectChanges();
      deleteButton(harness).click();
      await flush();
      await harness.fixture.whenStable();
      await flush();
      expect(TestBed.inject(Router).url).toBe('/mail/folders/f-trash');
      expect(mail.applies).toEqual([{ action: 'DELETE', ids: [ID] }]);
    });
  });

  it('returns to the message it was opened from when moving on to another message', async () => {
    const harness = await openFrom('/mail/folders/f-work');
    await harness.navigateByUrl('/mail/messages/0192f0c4-0000-7000-8000-00000000a002');
    await flush();
    harness.detectChanges();
    expect(await clickDelete(harness)).toBe(`/mail/messages/${ID}`);
  });
});

describe('MailReadPage back button', () => {
  async function openFrom(...from: string[]): Promise<RouterTestingHarness> {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'mail', title: 'Mail', component: StubList },
          { path: 'new', title: 'New', component: StubList },
          { path: 'mail/messages/:id', title: 'Message', component: MailReadPage },
        ]),
        provideLocationMocks(),
        provideBackNavigation(),
        { provide: MailReadService, useValue: new FakeReadService() },
        { provide: MailService, useValue: new FakeMailService() },
        { provide: NotificationService, useValue: { info: vi.fn(), error: vi.fn() } },
      ],
    });
    TestBed.overrideComponent(MailReadPage, {
      remove: { imports: [LinkedItems] },
      add: { imports: [StubLinkedItems] },
    });
    const harness = await RouterTestingHarness.create();
    TestBed.inject(Router).setUpLocationChangeListener();
    for (const url of from) await harness.navigateByUrl(url);
    await harness.navigateByUrl(`/mail/messages/${ID}`);
    await flush();
    harness.detectChanges();
    return harness;
  }

  const backLink = (harness: RouterTestingHarness) =>
    harness.routeNativeElement!.querySelector<HTMLAnchorElement>('app-back-button a')!;

  it('names New and returns to it with its filter when opened from New', async () => {
    const harness = await openFrom('/new?filter=mail');
    expect(backLink(harness).textContent?.trim()).toBe('New');
    backLink(harness).click();
    await flush();
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/new?filter=mail');
  });

  it('reads Mail and goes to the mail list when opened directly', async () => {
    const harness = await openFrom();
    expect(backLink(harness).textContent?.trim()).toBe('Mail');
    expect(backLink(harness).getAttribute('aria-label')).toBe('Back to Mail');
    backLink(harness).click();
    await flush();
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/mail');
  });
});

describe('MailReadPage previous/next through the list it was opened from', () => {
  const A = '0192f0c4-0000-7000-8000-0000000000aa';
  const B = '0192f0c4-0000-7000-8000-0000000000bb';
  const C = '0192f0c4-0000-7000-8000-0000000000cc';
  const LIST = '/mail?q=report';
  let mail: FakeMailService & { list: ReturnType<typeof vi.fn> };
  let read: FakeReadService;

  const item = (id: string, seen = true): MailSummary => summary(id, { seen });

  /** Shows `from`, saves the list as the list page does when it is left, then opens `id`. */
  async function openFromList(
    id: string,
    saved: Partial<SavedMailList> = {},
    from: string = LIST,
  ): Promise<RouterTestingHarness> {
    mail = Object.assign(new FakeMailService(), { list: vi.fn() });
    read = new FakeReadService();
    read.get = (messageId: string, loadImages: boolean) => {
      read.gets.push({ id: messageId, loadImages });
      return Promise.resolve(message({ id: messageId }));
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'mail', component: StubList },
          { path: 'new', component: StubList },
          { path: 'mail/messages/:id', component: MailReadPage },
        ]),
        provideLocationMocks(),
        provideBackNavigation(),
        { provide: MailReadService, useValue: read },
        { provide: MailService, useValue: mail },
        { provide: NotificationService, useValue: { info: vi.fn(), error: vi.fn() } },
      ],
    });
    TestBed.overrideComponent(MailReadPage, {
      remove: { imports: [LinkedItems] },
      add: { imports: [StubLinkedItems] },
    });
    const harness = await RouterTestingHarness.create();
    TestBed.inject(Router).setUpLocationChangeListener();
    await harness.navigateByUrl(from);
    TestBed.inject(MailListSession).save({
      url: LIST,
      scrollTop: 0,
      scope: { folderId: null, threaded: false, search: { query: 'report', server: false } },
      items: [item(A), item(B, false), item(C)],
      nextCursor: null,
      cursorId: A,
      searchResult: { hasMore: false, failedAccountIds: [] },
      ...saved,
    });
    await harness.navigateByUrl(`/mail/messages/${id}`);
    await settle(harness);
    return harness;
  }

  async function settle(harness: RouterTestingHarness) {
    await flush();
    await harness.fixture.whenStable();
    await flush();
    harness.detectChanges();
  }

  const button = (harness: RouterTestingHarness, selector: string) =>
    harness.routeNativeElement!.querySelector<HTMLButtonElement>(`.steps ${selector}`);
  const count = (harness: RouterTestingHarness) =>
    harness.routeNativeElement!.querySelector('.step-count')?.textContent?.trim();
  const url = () => TestBed.inject(Router).url;
  const key = (k: string) =>
    document.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true }));

  it('shows previous/next with the position, disabled at the ends', async () => {
    const harness = await openFromList(A);
    expect(count(harness)).toBe('1 of 3');
    expect(button(harness, '.previous')!.disabled).toBe(true);
    expect(button(harness, '.next')!.disabled).toBe(false);
    expect(button(harness, '.next')!.title).toBe('Next message (j)');
    button(harness, '.next')!.click();
    await settle(harness);
    expect(url()).toBe(`/mail/messages/${B}`);
    expect(count(harness)).toBe('2 of 3');
    button(harness, '.next')!.click();
    await settle(harness);
    expect(url()).toBe(`/mail/messages/${C}`);
    expect(button(harness, '.next')!.disabled).toBe(true);
    expect(button(harness, '.previous')!.disabled).toBe(false);
  });

  it('steps with j and k, replacing the message so Back returns to the list', async () => {
    const harness = await openFromList(B);
    key('j');
    await settle(harness);
    expect(url()).toBe(`/mail/messages/${C}`);
    key('k');
    await settle(harness);
    key('k');
    await settle(harness);
    expect(url()).toBe(`/mail/messages/${A}`);
    // At the start, k does nothing.
    key('k');
    await settle(harness);
    expect(url()).toBe(`/mail/messages/${A}`);
    TestBed.inject(Location).back();
    await settle(harness);
    expect(url()).toBe(LIST);
  });

  it('keeps the list cursor on the message read last and shows it as read', async () => {
    const harness = await openFromList(A);
    key('j');
    await settle(harness);
    const saved = TestBed.inject(MailListSession).saved()!;
    expect(saved.cursorId).toBe(B);
    expect(saved.items.find((entry) => entry.id === B)?.seen).toBe(true);
  });

  it('steps into the next page of a folder list at its end', async () => {
    const D = '0192f0c4-0000-7000-8000-0000000000dd';
    const harness = await openFromList(C, {
      scope: { folderId: null, threaded: false },
      searchResult: null,
      nextCursor: 'cur-1',
    });
    expect(count(harness)).toBe('3 of 3+');
    expect(button(harness, '.next')!.disabled).toBe(false);
    mail.list.mockResolvedValue({ items: [item(D)], nextCursor: null });
    button(harness, '.next')!.click();
    await settle(harness);
    expect(mail.list).toHaveBeenCalledWith({ folderId: null, threaded: false }, 'cur-1');
    expect(url()).toBe(`/mail/messages/${D}`);
    expect(count(harness)).toBe('4 of 4');
  });

  it('shows no previous/next when opened from another page', async () => {
    const harness = await openFromList(A, {}, '/new');
    expect(button(harness, '.next')).toBeNull();
    key('j');
    await settle(harness);
    expect(url()).toBe(`/mail/messages/${A}`);
  });

  for (const [label, action] of [
    ['Archive', 'ARCHIVE'],
    ['Delete', 'DELETE'],
    ['Spam', 'SPAM'],
  ] as const) {
    it(`${label} moves on to the next message of the list`, async () => {
      const harness = await openFromList(B);
      [...harness.routeNativeElement!.querySelectorAll('button')]
        .find((b) => b.textContent?.trim() === label)!
        .click();
      await settle(harness);
      expect(mail.applies).toEqual([{ action, ids: [B] }]);
      expect(url()).toBe(`/mail/messages/${C}`);
      expect(count(harness)).toBe('2 of 2');
      expect(
        TestBed.inject(MailListSession)
          .saved()!
          .items.map((entry) => entry.id),
      ).toEqual([A, C]);
    });
  }

  it('moves to the message before after archiving the last one, then back to the list', async () => {
    const harness = await openFromList(C, { items: [item(B), item(C)] });
    key('e');
    await settle(harness);
    expect(url()).toBe(`/mail/messages/${B}`);
    key('e');
    await settle(harness);
    expect(url()).toBe(LIST);
    expect(TestBed.inject(MailListSession).saved()!.items).toEqual([]);
  });
});
