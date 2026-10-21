import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ApiError, NotificationService, provideHttp, SILENT_ERRORS } from '../../../core/http';
import { summary } from './mail-testing';
import { MAIL_PAGE_SIZE, MailService } from './mail.service';

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

describe('MailService', () => {
  let service: MailService;
  let http: HttpTestingController;
  let notifications: { error: ReturnType<typeof vi.fn>; info: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    notifications = { error: vi.fn(), info: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: notifications },
      ],
    });
    service = TestBed.inject(MailService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function request(url: string) {
    await tick();
    return http.expectOne((req) => req.url === url);
  }

  it('lists the accounts silently', async () => {
    const result = service.accounts();
    const req = await request('/api/v1/mail/accounts');
    expect(req.request.method).toBe('GET');
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush({ items: [{ id: 'acc-1', displayName: 'Work', emailAddress: 'w@example.test' }] });
    expect((await result).map((a) => a.id)).toEqual(['acc-1']);
  });

  it('lists the folders of an account', async () => {
    const result = service.folders('acc-1');
    const req = await request('/api/v1/mail/accounts/acc-1/folders');
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush({ items: [] });
    expect(await result).toEqual([]);
  });

  it('lists the unified Inbox flat: no folderId, no threaded flag, default page size', async () => {
    const page = service.list({ folderId: null, threaded: false });
    const req = await request('/api/v1/mail/messages');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.has('folderId')).toBe(false);
    expect(req.request.params.has('threaded')).toBe(false);
    expect(req.request.params.has('cursor')).toBe(false);
    expect(req.request.params.get('limit')).toBe(String(MAIL_PAGE_SIZE));
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush({ items: [summary('a'), { subject: 'no id' }] });
    expect(await page).toEqual({ items: [summary('a')], nextCursor: null });
  });

  it('lists a folder threaded from a cursor with a limit, and returns the next cursor', async () => {
    const page = service.list({ folderId: 'f-1', threaded: true }, 'cur-1', 120);
    const req = await request('/api/v1/mail/messages');
    expect(req.request.params.get('folderId')).toBe('f-1');
    expect(req.request.params.get('threaded')).toBe('true');
    expect(req.request.params.get('cursor')).toBe('cur-1');
    expect(req.request.params.get('limit')).toBe('120');
    req.flush({ nextCursor: 'cur-2' });
    expect(await page).toEqual({ items: [], nextCursor: 'cur-2' });
  });

  it('loads the conversation of a message, dropping entries without id', async () => {
    const thread = service.thread('m-1');
    const req = await request('/api/v1/mail/messages/m-1/thread');
    req.flush({ items: [summary('m-0'), {}, summary('m-1')] });
    expect((await thread).map((m) => m.id)).toEqual(['m-0', 'm-1']);
  });

  it('posts an action with its ids and target folder and returns the per-id results', async () => {
    const result = service.apply('MOVE', ['m-1', 'm-2'], 'f-2');
    const req = await request('/api/v1/mail/messages/actions');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      action: 'MOVE',
      ids: ['m-1', 'm-2'],
      targetFolderId: 'f-2',
    });
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    const results = [
      { id: 'm-1', ok: true },
      { id: 'm-2', ok: false, errorCode: 'NOT_FOUND', detail: 'Gone' },
    ];
    req.flush({ results });
    expect(await result).toEqual(results);
  });

  it('rejects with an ApiError and does not toast', async () => {
    const result = service.apply('READ', ['m-1']);
    const req = await request('/api/v1/mail/messages/actions');
    req.flush(
      { status: 503, title: 'Service Unavailable', detail: 'Mail server unreachable' },
      { status: 503, statusText: 'Service Unavailable' },
    );
    const error = await result.catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).userMessage).toBe('Mail server unreachable');
    expect(notifications.error).not.toHaveBeenCalled();
  });
});
