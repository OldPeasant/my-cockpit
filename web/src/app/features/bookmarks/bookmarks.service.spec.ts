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

  it('loads one bookmark without toasting errors', async () => {
    const one = service.get('01');
    const req = http.expectOne((r) => r.method === 'GET' && r.url === `${LIST}/01`);
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush(bookmark('01'));
    await expect(one).resolves.toEqual(bookmark('01'));
  });
});
