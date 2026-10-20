import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ApiError, NotificationService, provideHttp, SILENT_ERRORS } from '../../core/http';
import { LINK_SEARCH_LIMIT, LinksService } from './links.service';

const TASK = { type: 'TASK' as const, id: '0192f0c4-0000-7000-8000-000000000001' };
const NOTE = { type: 'NOTE' as const, id: '0192f0c4-0000-7000-8000-000000000002' };

describe('LinksService', () => {
  let service: LinksService;
  let http: HttpTestingController;
  let notifications: { error: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    notifications = { error: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: notifications },
      ],
    });
    service = TestBed.inject(LinksService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists the links of an item without toasting errors', async () => {
    const links = service.list(TASK);
    const req = http.expectOne(
      (r) =>
        r.method === 'GET' &&
        r.url === '/api/v1/links' &&
        r.params.get('type') === 'TASK' &&
        r.params.get('id') === TASK.id,
    );
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush({ items: [] });

    await expect(links).resolves.toEqual([]);
  });

  it('creates a link with only type and id of both ends', async () => {
    const hit = { ...NOTE, title: 'Holiday ideas', updatedAt: '2026-10-19T10:00:00Z' };
    const created = service.create(TASK, hit);
    const req = http.expectOne({ method: 'POST', url: '/api/v1/links' });
    expect(req.request.body).toEqual({ source: TASK, target: NOTE });
    req.flush({ id: 'l1' }, { status: 201, statusText: 'Created' });

    expect((await created).id).toBe('l1');
  });

  it('removes a link with DELETE', async () => {
    const done = service.remove('l1');
    http
      .expectOne({ method: 'DELETE', url: '/api/v1/links/l1' })
      .flush(null, { status: 204, statusText: 'No Content' });

    await expect(done).resolves.toBeUndefined();
  });

  it('searches all types with the link search limit', async () => {
    const groups = service.search('holiday');
    const req = http.expectOne((r) => r.url === '/api/v1/search');
    expect(req.request.params.get('q')).toBe('holiday');
    expect(req.request.params.get('limit')).toBe(String(LINK_SEARCH_LIMIT));
    expect(req.request.params.has('types')).toBe(false);
    req.flush({ groups: [{ type: 'NOTE', items: [] }] });

    expect(await groups).toEqual([{ type: 'NOTE', items: [] }]);
  });

  it('rejects with an ApiError and shows no toast', async () => {
    const done = service.remove('l1');
    http
      .expectOne({ method: 'DELETE', url: '/api/v1/links/l1' })
      .flush(
        { status: 409, title: 'Conflict', detail: 'Remove it in its source.' },
        { status: 409, statusText: 'Conflict' },
      );

    await expect(done).rejects.toBeInstanceOf(ApiError);
    expect(notifications.error).not.toHaveBeenCalled();
  });
});
