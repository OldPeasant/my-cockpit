import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NotificationService, provideHttp, SILENT_ERRORS } from '../../core/http';
import { BookmarksService } from './bookmarks.service';
import { bookmark } from './testing';

const LIST = '/api/v1/bookmarks';

describe('BookmarksService', () => {
  let service: BookmarksService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: { error: vi.fn(), info: vi.fn() } },
      ],
    });
    service = TestBed.inject(BookmarksService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists the read-later queue as unread bookmarks in the list, without toasting errors', async () => {
    const page = service.list('queue', null);
    const req = http.expectOne((r) => r.method === 'GET' && r.url === LIST);
    expect(req.request.params.get('readLater')).toBe('true');
    expect(req.request.params.get('read')).toBe('false');
    expect(req.request.params.has('tag')).toBe(false);
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush({ items: [bookmark('01')], nextCursor: 'c1' });
    await expect(page).resolves.toEqual({ items: [bookmark('01')], nextCursor: 'c1' });
  });

  it('lists all bookmarks of a tag from a cursor', async () => {
    const page = service.list('all', 'kotlin', 'c1');
    const req = http.expectOne((r) => r.method === 'GET' && r.url === LIST);
    expect(req.request.params.keys().sort()).toEqual(['cursor', 'tag']);
    expect(req.request.params.get('tag')).toBe('kotlin');
    expect(req.request.params.get('cursor')).toBe('c1');
    req.flush({ items: [] });
    await expect(page).resolves.toEqual({ items: [] });
  });

  it('lists the tags', async () => {
    const tags = service.tags();
    http
      .expectOne((r) => r.method === 'GET' && r.url === `${LIST}/tags`)
      .flush({ items: [{ tag: 'kotlin', count: 2 }] });
    await expect(tags).resolves.toEqual([{ tag: 'kotlin', count: 2 }]);
  });

  it('creates a bookmark', async () => {
    const body = { url: 'https://example.org/a', readLater: true, tags: ['x'] };
    const created = service.create(body);
    const req = http.expectOne((r) => r.method === 'POST' && r.url === LIST);
    expect(req.request.body).toEqual(body);
    req.flush(bookmark('01'), { status: 201, statusText: 'Created' });
    await expect(created).resolves.toEqual(bookmark('01'));
  });

  it('marks read and deletes with the version as If-Match', async () => {
    const updated = service.update(bookmark('01', { version: 4 }), { read: true });
    const patch = http.expectOne((r) => r.method === 'PATCH' && r.url === `${LIST}/01`);
    expect(patch.request.headers.get('If-Match')).toBe('"4"');
    expect(patch.request.body).toEqual({ read: true });
    patch.flush(bookmark('01', { readAt: '2026-10-22T08:00:00Z', version: 5 }));
    await expect(updated).resolves.toMatchObject({ version: 5 });

    const removed = service.remove(bookmark('01', { version: 5 }));
    const del = http.expectOne((r) => r.method === 'DELETE' && r.url === `${LIST}/01`);
    expect(del.request.headers.get('If-Match')).toBe('"5"');
    del.flush(null, { status: 204, statusText: 'No Content' });
    await removed;
  });

  it('loads a snapshot as text and tells Markdown apart', async () => {
    const markdown = service.snapshot('f1');
    const req = http.expectOne('/api/v1/files/f1/preview');
    expect(req.request.responseType).toBe('text');
    req.flush('# Title', {
      headers: { 'Content-Type': 'text/markdown; charset=utf-8', 'X-Preview-Truncated': 'true' },
    });
    await expect(markdown).resolves.toEqual({ text: '# Title', markdown: true, truncated: true });

    const plain = service.snapshot('f2');
    http
      .expectOne('/api/v1/files/f2/preview')
      .flush('Plain', { headers: { 'Content-Type': 'text/plain' } });
    await expect(plain).resolves.toEqual({ text: 'Plain', markdown: false, truncated: false });
  });

  it('encodes the file id and treats a snapshot without a type as plain text', async () => {
    const snapshot = service.snapshot('a/b');
    const req = http.expectOne('/api/v1/files/a%2Fb/preview');
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush('');
    await expect(snapshot).resolves.toEqual({ text: '', markdown: false, truncated: false });
  });

  describe('restore and to inbox', () => {
    const TASK_ID = '0192f0c4-0000-7000-8000-0000000000a1';
    const ARTICLE = bookmark('0192f0c4-0000-7000-8000-000000000001', {
      displayTitle: 'On slow software',
      url: 'https://example.org/slow',
    });

    function problem(status: number, title: string) {
      return [
        { type: 'about:blank', title, status },
        { status, statusText: title },
      ] as const;
    }

    it('restores a bookmark from the Trash without toasting errors', async () => {
      const restored = service.restore({ id: ARTICLE.id });
      const req = http.expectOne(`/api/v1/trash/BOOKMARK/${ARTICLE.id}/restore`);
      expect(req.request.method).toBe('POST');
      expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
      req.flush(null, { status: 204, statusText: 'No Content' });
      await expect(restored).resolves.toBeUndefined();
    });

    it('rejects when the restore fails', async () => {
      const restored = service.restore(ARTICLE);
      http
        .expectOne(`/api/v1/trash/BOOKMARK/${ARTICLE.id}/restore`)
        .flush(...problem(404, 'Not Found'));
      await expect(restored).rejects.toMatchObject({ status: 404 });
    });

    it('creates an Inbox task with the given id and links the bookmark as its origin', async () => {
      const result = service.toInbox(ARTICLE, TASK_ID);
      const create = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/tasks');
      expect(create.request.body).toEqual({
        id: TASK_ID,
        title: 'On slow software',
        notesMd: 'https://example.org/slow',
        list: 'INBOX',
      });
      expect(create.request.context.get(SILENT_ERRORS)).toBe(true);
      const task = { id: TASK_ID, title: 'On slow software', list: 'INBOX' };
      create.flush(task, { status: 201, statusText: 'Created' });
      await new Promise((resolve) => setTimeout(resolve));

      const link = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/links');
      expect(link.request.body).toEqual({
        source: { type: 'TASK', id: TASK_ID },
        target: { type: 'BOOKMARK', id: ARTICLE.id },
        label: 'origin',
      });
      expect(link.request.context.get(SILENT_ERRORS)).toBe(true);
      link.flush({ id: '0192f0c4-0000-7000-8000-0000000000b1' }, { status: 201, statusText: '' });
      await expect(result).resolves.toEqual({ task, linked: true });
    });

    it('links to the id of the task the server answered with', async () => {
      const result = service.toInbox(ARTICLE, TASK_ID);
      const other = '0192f0c4-0000-7000-8000-0000000000a9';
      http
        .expectOne((r) => r.url === '/api/v1/tasks')
        .flush({ id: other, title: 'On slow software' }, { status: 201, statusText: '' });
      await new Promise((resolve) => setTimeout(resolve));
      const link = http.expectOne((r) => r.url === '/api/v1/links');
      expect(link.request.body.source).toEqual({ type: 'TASK', id: other });
      link.flush({}, { status: 201, statusText: '' });
      await expect(result).resolves.toMatchObject({ linked: true });
    });

    it('caps the task title at the server limit', async () => {
      const long = { ...ARTICLE, displayTitle: 'x'.repeat(600) };
      const result = service.toInbox(long, TASK_ID);
      const create = http.expectOne((r) => r.url === '/api/v1/tasks');
      expect(create.request.body.title).toBe('x'.repeat(500));
      create.flush({ id: TASK_ID, title: 'x'.repeat(500) }, { status: 201, statusText: '' });
      await new Promise((resolve) => setTimeout(resolve));
      http.expectOne((r) => r.url === '/api/v1/links').flush({}, { status: 201, statusText: '' });
      await result;
    });

    it('reports the task as unlinked when the link fails', async () => {
      const result = service.toInbox(ARTICLE, TASK_ID);
      const task = { id: TASK_ID, title: 'On slow software' };
      http.expectOne((r) => r.url === '/api/v1/tasks').flush(task, { status: 201, statusText: '' });
      await new Promise((resolve) => setTimeout(resolve));
      http.expectOne((r) => r.url === '/api/v1/links').flush(...problem(500, 'Server error'));
      await expect(result).resolves.toEqual({ task, linked: false });
    });

    it('rejects without linking when the task cannot be created', async () => {
      const result = service.toInbox(ARTICLE, TASK_ID);
      http.expectOne((r) => r.url === '/api/v1/tasks').flush(...problem(500, 'Server error'));
      await expect(result).rejects.toMatchObject({ status: 500 });
      http.expectNone((r) => r.url === '/api/v1/links');
    });
  });

  it('loads one bookmark without toasting errors', async () => {
    const one = service.get('01');
    const req = http.expectOne((r) => r.method === 'GET' && r.url === `${LIST}/01`);
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush(bookmark('01'));
    await expect(one).resolves.toEqual(bookmark('01'));
  });
});
