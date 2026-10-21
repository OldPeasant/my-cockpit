import { Component, input } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, ParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { ItemRefDto, MailMessageResponse } from '../../../api/generated';
import { ApiError, NotificationService } from '../../../core/http';
import { LinkedItems } from '../../../shared/links';
import { IMAGE_ALLOWLIST_KEY } from './image-allowlist';
import { MAIL_FRAME_SANDBOX } from './mail-frame';
import MailReadPage from './mail-read-page';
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

class FakeReadService {
  answers: (MailMessageResponse | Error)[] = [];
  gets: { id: string; loadImages: boolean }[] = [];
  reads: string[] = [];
  gtds: { id: string; action: MailGtdAction }[] = [];
  gtdResult: Promise<{ id: string; title: string }> = Promise.resolve({ id: 't', title: 'T' });
  saves: { id: string; partId: string; name: string }[] = [];
  saveResult: Promise<{ name: string }> = Promise.resolve({ name: 'report.pdf' });
  blob: Promise<Blob> = Promise.resolve(new Blob(['line one\nline two']));

  get(id: string, loadImages: boolean) {
    this.gets.push({ id, loadImages });
    const answer = this.answers.shift() ?? message();
    return answer instanceof Error ? Promise.reject(answer) : Promise.resolve(answer);
  }
  markRead(id: string) {
    this.reads.push(id);
    return Promise.resolve();
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

  async function create(...answers: (MailMessageResponse | Error)[]) {
    service = new FakeReadService();
    service.answers = answers;
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

  beforeEach(() => localStorage.removeItem(IMAGE_ALLOWLIST_KEY));
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
});
