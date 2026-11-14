import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ApiError, NotificationService, provideHttp, SILENT_ERRORS } from '../../../core/http';
import { summary } from './mail-testing';
import {
  MAIL_LOAD_OLDER_LIMIT,
  MAIL_PAGE_SIZE,
  MailService,
  PartialNextActionsError,
} from './mail.service';

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

  describe('search', () => {
    it('searches all folders (no folderId), cached only (no server flag), silently', async () => {
      const result = service.search('invoice', null, false);
      const req = await request('/api/v1/mail/search');
      expect(req.request.method).toBe('GET');
      expect(req.request.params.get('q')).toBe('invoice');
      expect(req.request.params.has('folderId')).toBe(false);
      expect(req.request.params.has('accountId')).toBe(false);
      expect(req.request.params.has('server')).toBe(false);
      expect(req.request.params.get('limit')).toBe(String(MAIL_PAGE_SIZE));
      expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
      req.flush({ items: [summary('a'), { subject: 'no id' }, summary('b')], server: null });
      expect(await result).toEqual({
        items: [summary('a'), summary('b')],
        hasMore: false,
        failedAccountIds: [],
      });
    });

    it('searches one folder on the server and maps failures to account ids', async () => {
      const result = service.search('report', 'f-1', true, 20);
      const req = await request('/api/v1/mail/search');
      expect(req.request.params.get('q')).toBe('report');
      expect(req.request.params.get('folderId')).toBe('f-1');
      expect(req.request.params.get('server')).toBe('true');
      expect(req.request.params.get('limit')).toBe('20');
      req.flush({
        items: [summary('a')],
        hasMore: true,
        server: {
          failures: [
            { accountId: 'acc-2', errorCode: 'TIMEOUT', message: 'Timed out' },
            { errorCode: 'TLS_FAILED' },
            { accountId: 'acc-3', errorCode: 'AUTHENTICATION_FAILED' },
          ],
          loaded: 3,
          searchedFolders: 4,
        },
      });
      expect(await result).toEqual({
        items: [summary('a')],
        hasMore: true,
        failedAccountIds: ['acc-2', 'acc-3'],
      });
    });

    it('answers an empty response with no items and no failures', async () => {
      const result = service.search('nothing', null, true);
      const req = await request('/api/v1/mail/search');
      req.flush({ server: { searchedFolders: 2 } });
      expect(await result).toEqual({ items: [], hasMore: false, failedAccountIds: [] });
    });

    it('rejects a failed search with the ApiError, without a toast', async () => {
      const result = service.search('x', null, false);
      const req = await request('/api/v1/mail/search');
      req.flush(
        { status: 400, title: 'Bad Request', detail: 'Query too long' },
        { status: 400, statusText: 'Bad Request' },
      );
      const error = await result.catch((e: unknown) => e);
      expect(error).toBeInstanceOf(ApiError);
      expect((error as ApiError).userMessage).toBe('Query too long');
      expect(notifications.error).not.toHaveBeenCalled();
    });
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

  describe('folder changes', () => {
    const projects = {
      id: 'f-proj',
      accountId: 'acc-1',
      path: 'Projects',
      name: 'Projects',
      delimiter: '/',
      roleOverridden: false,
      selectable: true,
      version: 4,
    };

    it('creates a top-level folder: POST with the name and no parent, silently', async () => {
      const result = service.createFolder('acc-1', 'Receipts');
      const req = await request('/api/v1/mail/accounts/acc-1/folders');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ name: 'Receipts' });
      expect(req.request.headers.has('If-Match')).toBe(false);
      expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
      const created = { ...projects, id: 'f-new', path: 'Receipts', name: 'Receipts', version: 0 };
      req.flush(created, { status: 201, statusText: 'Created' });
      expect(await result).toEqual(created);
    });

    it('treats a null parent as the top level', async () => {
      const result = service.createFolder('acc-1', 'Receipts', null);
      const req = await request('/api/v1/mail/accounts/acc-1/folders');
      expect(req.request.body).toEqual({ name: 'Receipts' });
      req.flush({ ...projects, id: 'f-new', path: 'Receipts', name: 'Receipts' });
      await result;
    });

    it('creates a subfolder under its parent', async () => {
      const result = service.createFolder('acc-1', 'Beta', 'f-proj');
      const req = await request('/api/v1/mail/accounts/acc-1/folders');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ name: 'Beta', parentId: 'f-proj' });
      req.flush({ ...projects, id: 'f-beta', path: 'Projects/Beta', name: 'Beta' });
      expect((await result).path).toBe('Projects/Beta');
    });

    it('renames a folder: PATCH with the name and its version as quoted If-Match', async () => {
      const result = service.renameFolder(projects, 'Clients');
      const req = await request('/api/v1/mail/accounts/acc-1/folders/f-proj');
      expect(req.request.method).toBe('PATCH');
      expect(req.request.headers.get('If-Match')).toBe('"4"');
      expect(req.request.body).toEqual({ name: 'Clients' });
      expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
      req.flush({ ...projects, path: 'Clients', name: 'Clients', version: 5 });
      expect(await result).toEqual(
        expect.objectContaining({ id: 'f-proj', path: 'Clients', version: 5 }),
      );
    });

    it('deletes a folder: DELETE with its version as quoted If-Match and no body', async () => {
      const result = service.deleteFolder({ ...projects, version: 0 });
      const req = await request('/api/v1/mail/accounts/acc-1/folders/f-proj');
      expect(req.request.method).toBe('DELETE');
      expect(req.request.headers.get('If-Match')).toBe('"0"');
      expect(req.request.body).toBeNull();
      expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
      req.flush(null, { status: 204, statusText: 'No Content' });
      await expect(result).resolves.toBeUndefined();
    });

    it.each([
      ['create', () => service.createFolder('acc-1', 'Projects'), 'POST', 409, 'Name taken'],
      ['rename', () => service.renameFolder(projects, 'X'), 'PATCH', 412, 'Version mismatch'],
      ['delete', () => service.deleteFolder(projects), 'DELETE', 404, 'Folder not found'],
    ] as const)(
      'rejects a failed %s with the ApiError and its status, without a toast',
      async (_, call, method, status, detail) => {
        const result = call() as Promise<unknown>;
        await tick();
        const req = http.expectOne((r) => r.method === method);
        req.flush({ status, title: 'Error', detail }, { status, statusText: 'Error' });
        const error = await result.catch((e: unknown) => e);
        expect(error).toBeInstanceOf(ApiError);
        expect((error as ApiError).status).toBe(status);
        expect((error as ApiError).userMessage).toBe(detail);
        expect(notifications.error).not.toHaveBeenCalled();
      },
    );
  });

  describe('load older from server', () => {
    it('posts to the folder with the default limit, silently, and reads the counts', async () => {
      const result = service.loadOlder('f-1');
      const req = await request('/api/v1/mail/folders/f-1/load-older');
      expect(req.request.method).toBe('POST');
      expect(req.request.params.get('limit')).toBe(String(MAIL_LOAD_OLDER_LIMIT));
      expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
      req.flush({ loaded: 42, hasMore: true });
      expect(await result).toEqual({ loaded: 42, hasMore: true });
    });

    it('passes a given limit and reads missing counts as nothing loaded', async () => {
      const result = service.loadOlder('f-2', 7);
      const req = await request('/api/v1/mail/folders/f-2/load-older');
      expect(req.request.params.get('limit')).toBe('7');
      req.flush({});
      expect(await result).toEqual({ loaded: 0, hasMore: false });
    });

    it('rejects a failure with the ApiError, without a toast', async () => {
      const result = service.loadOlder('f-1');
      const req = await request('/api/v1/mail/folders/f-1/load-older');
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

  describe('next action & archive', () => {
    const result = (id: string, archived = true) => ({
      id,
      messageId: id,
      linkId: `link-${id}`,
      archived,
      archiveErrorCode: archived ? null : 'NO_ROLE_FOLDER',
      archiveDetail: null,
      task: { id: `task-${id}`, list: 'NEXT' as const, title: `Subject ${id}` },
    });

    it('posts the ids as Next actions to archive, silently, and returns the results', async () => {
      const done = service.toNextActions(['m-1', 'm-2']);
      const req = await request('/api/v1/mail/messages/to-tasks');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ ids: ['m-1', 'm-2'], list: 'NEXT', archive: true });
      expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
      req.flush({ results: [result('m-1'), result('m-2', false)] });
      expect(await done).toEqual([result('m-1'), result('m-2', false)]);
    });

    it('sends at most 100 ids per request, in order, and joins the results', async () => {
      const ids = Array.from({ length: 201 }, (_, i) => `m-${i}`);
      const done = service.toNextActions(ids);
      const first = await request('/api/v1/mail/messages/to-tasks');
      expect(first.request.body.ids).toEqual(ids.slice(0, 100));
      first.flush({ results: ids.slice(0, 100).map((id) => result(id)) });
      const second = await request('/api/v1/mail/messages/to-tasks');
      expect(second.request.body.ids).toEqual(ids.slice(100, 200));
      second.flush({ results: ids.slice(100, 200).map((id) => result(id)) });
      const third = await request('/api/v1/mail/messages/to-tasks');
      expect(third.request.body).toEqual({ ids: ['m-200'], list: 'NEXT', archive: true });
      third.flush({ results: [result('m-200')] });
      expect((await done).map((r) => r.id)).toEqual(ids);
    });

    it.each([
      [2, 200],
      [1, 100],
    ])(
      'keeps the earlier results when request %i fails after others succeeded (%i results)',
      async (failing, kept) => {
        const ids = Array.from({ length: 201 }, (_, i) => `m-${i}`);
        const done = service.toNextActions(ids);
        for (let index = 0; index < failing; index++) {
          const req = await request('/api/v1/mail/messages/to-tasks');
          req.flush({ results: req.request.body.ids.map((id: string) => result(id)) });
        }
        const failed = await request('/api/v1/mail/messages/to-tasks');
        failed.flush(
          { status: 503, title: 'Service Unavailable', detail: 'Mail server unreachable' },
          { status: 503, statusText: 'Service Unavailable' },
        );
        const error = await done.catch((e: unknown) => e);
        expect(error).toBeInstanceOf(PartialNextActionsError);
        const partial = error as PartialNextActionsError;
        expect(partial.results.map((r) => r.id)).toEqual(ids.slice(0, kept));
        expect(partial.failure).toBeInstanceOf(ApiError);
        expect((partial.failure as ApiError).userMessage).toBe('Mail server unreachable');
        // No request follows the failed one.
        await tick();
        http.expectNone('/api/v1/mail/messages/to-tasks');
        expect(notifications.error).not.toHaveBeenCalled();
      },
    );

    it('rejects a failed first request with its own error: no task was created', async () => {
      const ids = Array.from({ length: 150 }, (_, i) => `m-${i}`);
      const done = service.toNextActions(ids);
      const req = await request('/api/v1/mail/messages/to-tasks');
      req.flush(
        { status: 404, title: 'Not Found', detail: 'Message not found' },
        { status: 404, statusText: 'Not Found' },
      );
      const error = await done.catch((e: unknown) => e);
      expect(error).toBeInstanceOf(ApiError);
      expect(error).not.toBeInstanceOf(PartialNextActionsError);
      await tick();
      http.expectNone('/api/v1/mail/messages/to-tasks');
    });

    it('sends exactly 100 ids in one request', async () => {
      const ids = Array.from({ length: 100 }, (_, i) => `m-${i}`);
      const done = service.toNextActions(ids);
      const req = await request('/api/v1/mail/messages/to-tasks');
      expect(req.request.body.ids).toHaveLength(100);
      req.flush({ results: [] });
      expect(await done).toEqual([]);
    });

    it('sends no request for no ids', async () => {
      expect(await service.toNextActions([])).toEqual([]);
      await tick();
      http.expectNone('/api/v1/mail/messages/to-tasks');
    });

    it('rejects a failed request with the ApiError, without a toast', async () => {
      const done = service.toNextActions(['m-1']);
      const req = await request('/api/v1/mail/messages/to-tasks');
      req.flush(
        { status: 404, title: 'Not Found', detail: 'Message not found' },
        { status: 404, statusText: 'Not Found' },
      );
      const error = await done.catch((e: unknown) => e);
      expect(error).toBeInstanceOf(ApiError);
      expect((error as ApiError).userMessage).toBe('Message not found');
      expect(notifications.error).not.toHaveBeenCalled();
    });
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
