import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NotificationService, provideHttp, SILENT_ERRORS } from '../../core/http';
import { TRASH_PAGE_SIZE, TrashService } from './trash.service';

const NOTE = { type: 'NOTE' as const, id: '0192f0c4-0000-7000-8000-000000000002' };

describe('TrashService', () => {
  let service: TrashService;
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
    service = TestBed.inject(TrashService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists a page with the page size and without toasting errors', async () => {
    const page = service.list();
    const req = http.expectOne(
      (r) =>
        r.method === 'GET' &&
        r.url === '/api/v1/trash' &&
        r.params.get('limit') === String(TRASH_PAGE_SIZE) &&
        !r.params.has('cursor'),
    );
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush({ items: [] });

    await expect(page).resolves.toEqual({ items: [] });
  });

  it('passes the cursor of the next page', async () => {
    const page = service.list('c2');
    http
      .expectOne((r) => r.url === '/api/v1/trash' && r.params.get('cursor') === 'c2')
      .flush({ items: [] });

    await expect(page).resolves.toEqual({ items: [] });
  });

  it('restores an item with POST', async () => {
    const done = service.restore(NOTE);
    const req = http.expectOne({ method: 'POST', url: `/api/v1/trash/NOTE/${NOTE.id}/restore` });
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush(null, { status: 204, statusText: 'No Content' });

    await expect(done).resolves.toBeUndefined();
  });

  it('deletes an item permanently with DELETE', async () => {
    const done = service.purge(NOTE);
    const req = http.expectOne({ method: 'DELETE', url: `/api/v1/trash/NOTE/${NOTE.id}` });
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush(null, { status: 204, statusText: 'No Content' });

    await expect(done).resolves.toBeUndefined();
  });

  it('rejects with the server problem', async () => {
    const done = service.purge(NOTE);
    http
      .expectOne(`/api/v1/trash/NOTE/${NOTE.id}`)
      .flush(
        { title: 'Not Found', status: 404, detail: 'No such item in the Trash' },
        { status: 404, statusText: 'Not Found' },
      );

    await expect(done).rejects.toMatchObject({ status: 404 });
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('rejects a failed restore with the problem detail and does not toast it', async () => {
    const done = service.restore(NOTE);
    http
      .expectOne(`/api/v1/trash/NOTE/${NOTE.id}/restore`)
      .flush(
        { title: 'Conflict', status: 409, detail: 'The project of this task is deleted' },
        { status: 409, statusText: 'Conflict' },
      );

    await expect(done).rejects.toMatchObject({
      status: 409,
      userMessage: 'The project of this task is deleted',
    });
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('rejects a failed list without toasting it', async () => {
    const page = service.list();
    http
      .expectOne((r) => r.url === '/api/v1/trash')
      .flush({ title: 'Unavailable', status: 503 }, { status: 503, statusText: 'Unavailable' });

    await expect(page).rejects.toMatchObject({ status: 503 });
    expect(notifications.error).not.toHaveBeenCalled();
  });
});
