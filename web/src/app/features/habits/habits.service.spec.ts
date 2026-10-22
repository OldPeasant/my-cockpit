import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NotificationService, provideHttp, SILENT_ERRORS } from '../../core/http';
import { habit } from './testing';
import { HabitsService } from './habits.service';

describe('HabitsService', () => {
  let service: HabitsService;
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
    service = TestBed.inject(HabitsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists active or archived habits without toasting errors', async () => {
    const active = service.list(false);
    const req = http.expectOne(
      (r) =>
        r.method === 'GET' && r.url === '/api/v1/habits' && r.params.get('archived') === 'false',
    );
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush({ items: [habit('01')] });
    await expect(active).resolves.toEqual([habit('01')]);

    const archived = service.list(true);
    http.expectOne((r) => r.params.get('archived') === 'true').flush({ items: [] });
    await expect(archived).resolves.toEqual([]);
  });

  it('creates a habit', async () => {
    const created = service.create({ name: 'Read', schedule: 'DAILY', targetCount: 1 });
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/habits');
    expect(req.request.body).toEqual({ name: 'Read', schedule: 'DAILY', targetCount: 1 });
    req.flush(habit('01'), { status: 201, statusText: 'Created' });
    await expect(created).resolves.toEqual(habit('01'));
  });

  it('updates with the version as If-Match', async () => {
    const updated = service.update(habit('01', { version: 4 }), { archived: true });
    const req = http.expectOne((r) => r.method === 'PATCH' && r.url === '/api/v1/habits/01');
    expect(req.request.headers.get('If-Match')).toBe('"4"');
    expect(req.request.body).toEqual({ archived: true });
    req.flush(habit('01', { archived: true, version: 5 }));
    await expect(updated).resolves.toMatchObject({ archived: true, version: 5 });
  });

  it('sets the count of a date', async () => {
    const checked = service.setCheck(habit('01'), '2026-10-22', 1);
    const req = http.expectOne(
      (r) => r.method === 'PUT' && r.url === '/api/v1/habits/01/checks/2026-10-22',
    );
    expect(req.request.body).toEqual({ count: 1 });
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush(habit('01', { stats: { todayCount: 1, doneToday: true } }));
    await expect(checked).resolves.toMatchObject({ stats: { doneToday: true } });
  });
});
