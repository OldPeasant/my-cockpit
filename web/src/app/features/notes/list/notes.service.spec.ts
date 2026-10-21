import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NoteResponse } from '../../../api/generated';
import { NotificationService, provideHttp, SILENT_ERRORS } from '../../../core/http';
import { NOTES_PAGE_SIZE, NotesService } from './notes.service';

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

function note(id: string, fields: Partial<NoteResponse> = {}): NoteResponse {
  return {
    id,
    displayTitle: `Note ${id}`,
    bodyMd: '',
    tags: [],
    pinned: false,
    archived: false,
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-01T08:00:00Z',
    version: 3,
    ...fields,
  };
}

describe('NotesService', () => {
  let service: NotesService;
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
    service = TestBed.inject(NotesService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads every page of the active notes with a tag, without toasting errors', async () => {
    const result = service.list({ tag: 'Holiday', archived: false });

    const first = http.expectOne(
      (r) =>
        r.method === 'GET' &&
        r.url === '/api/v1/notes' &&
        r.params.get('tag') === 'Holiday' &&
        r.params.get('archived') === 'false' &&
        r.params.get('limit') === String(NOTES_PAGE_SIZE) &&
        !r.params.has('cursor'),
    );
    expect(first.request.context.get(SILENT_ERRORS)).toBe(true);
    first.flush({ items: [note('01')], nextCursor: 'c2' });
    await tick();

    http
      .expectOne((r) => r.url === '/api/v1/notes' && r.params.get('cursor') === 'c2')
      .flush({ items: [note('02')] });

    await expect(result).resolves.toEqual([note('01'), note('02')]);
  });

  it('asks for archived notes only and leaves out an absent tag', async () => {
    const result = service.list({ archived: true });
    http
      .expectOne(
        (r) =>
          r.url === '/api/v1/notes' && r.params.get('archived') === 'true' && !r.params.has('tag'),
      )
      .flush({ items: [], nextCursor: null });

    await expect(result).resolves.toEqual([]);
  });

  it('lists the tags', async () => {
    const tags = service.tags();
    http
      .expectOne((r) => r.method === 'GET' && r.url === '/api/v1/notes/tags')
      .flush({ items: [{ tag: 'Holiday', count: 2 }] });

    await expect(tags).resolves.toEqual([{ tag: 'Holiday', count: 2 }]);
  });

  it('creates a note', async () => {
    const created = service.create({ title: 'Packing', bodyMd: 'Passport', tags: ['Holiday'] });
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/notes');
    expect(req.request.body).toEqual({ title: 'Packing', bodyMd: 'Passport', tags: ['Holiday'] });
    req.flush(note('01'), { status: 201, statusText: 'Created' });

    await expect(created).resolves.toEqual(note('01'));
  });

  it('pins and archives with the version as If-Match', async () => {
    const pinned = service.setPinned(note('01'), true);
    let req = http.expectOne((r) => r.method === 'PATCH' && r.url === '/api/v1/notes/01');
    expect(req.request.headers.get('If-Match')).toBe('"3"');
    expect(req.request.body).toEqual({ pinned: true });
    req.flush(note('01', { pinned: true, version: 4 }));
    await expect(pinned).resolves.toMatchObject({ pinned: true, version: 4 });

    const archived = service.setArchived(note('01', { version: 4 }), true);
    req = http.expectOne((r) => r.method === 'PATCH' && r.url === '/api/v1/notes/01');
    expect(req.request.headers.get('If-Match')).toBe('"4"');
    expect(req.request.body).toEqual({ archived: true });
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush(note('01', { archived: true, version: 5 }));
    await expect(archived).resolves.toMatchObject({ archived: true });
  });

  it('follows the cursor across three pages and keeps the server order', async () => {
    const result = service.list({ archived: false });
    http
      .expectOne((r) => r.url === '/api/v1/notes' && !r.params.has('cursor'))
      .flush({ items: [note('01', { pinned: true }), note('02')], nextCursor: 'c2' });
    await tick();
    http
      .expectOne((r) => r.params.get('cursor') === 'c2')
      .flush({ items: [], nextCursor: 'c3' });
    await tick();
    http
      .expectOne((r) => r.params.get('cursor') === 'c3')
      .flush({ items: [note('03')], nextCursor: null });

    await expect(result).resolves.toEqual([note('01', { pinned: true }), note('02'), note('03')]);
  });

  it('fails as a whole when a later page fails, instead of returning part of the notes', async () => {
    const result = service.list({ archived: false });
    http.expectOne((r) => r.url === '/api/v1/notes').flush({ items: [note('01')], nextCursor: 'c2' });
    await tick();
    http
      .expectOne((r) => r.params.get('cursor') === 'c2')
      .flush(
        { title: 'Service Unavailable', status: 503 },
        { status: 503, statusText: 'Service Unavailable' },
      );

    await expect(result).rejects.toMatchObject({ name: 'ApiError', status: 503 });
  });

  it('rejects a change of a note deleted elsewhere with a 404 ApiError', async () => {
    const pinned = service.setPinned(note('01'), false);
    http
      .expectOne((r) => r.method === 'PATCH')
      .flush({ title: 'Not Found', status: 404 }, { status: 404, statusText: 'Not Found' });

    await expect(pinned).rejects.toMatchObject({ name: 'ApiError', status: 404 });
  });

  it('passes a failed create on with its problem detail', async () => {
    const created = service.create({ bodyMd: 'x', tags: [] });
    const req = http.expectOne((r) => r.method === 'POST');
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush(
      { title: 'Bad Request', status: 400, detail: 'Too many tags.' },
      { status: 400, statusText: 'Bad Request' },
    );

    await expect(created).rejects.toMatchObject({ status: 400, userMessage: 'Too many tags.' });
  });
});
