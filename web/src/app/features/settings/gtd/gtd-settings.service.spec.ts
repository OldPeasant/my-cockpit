import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import {
  AreaResponse,
  ContextResponse,
  ProjectResponse,
  TaskResponse,
} from '../../../api/generated';
import { ApiError, NotificationService, provideHttp, SILENT_ERRORS } from '../../../core/http';
import { GtdSettingsService, ReassignError } from './gtd-settings.service';

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

const HOME: ContextResponse = {
  id: 'c-home',
  name: '@home',
  color: '#3366ff',
  position: 0,
  version: 3,
};
const PHONE: ContextResponse = { id: 'c-phone', name: '@phone', position: 1, version: 1 };
const WORK: AreaResponse = { id: 'a-work', title: 'Work', position: 0, version: 2 };

function task(id: string, contextIds: string[], version = 1): TaskResponse {
  return { id, title: `Task ${id}`, contextIds, version } as TaskResponse;
}

function project(id: string, version = 1): ProjectResponse {
  return { id, title: `Project ${id}`, areaId: WORK.id, version } as ProjectResponse;
}

describe('GtdSettingsService', () => {
  let service: GtdSettingsService;
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
    service = TestBed.inject(GtdSettingsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists contexts and areas without toasting errors', async () => {
    const contexts = service.contexts();
    const req = http.expectOne({ method: 'GET', url: '/api/v1/contexts' });
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush({ items: [HOME, PHONE] });
    expect((await contexts).map((c) => c.name)).toEqual(['@home', '@phone']);

    const areas = service.areas();
    http.expectOne({ method: 'GET', url: '/api/v1/areas' }).flush({ items: [WORK] });
    expect(await areas).toEqual([WORK]);
  });

  it('creates and renames with If-Match', async () => {
    const created = service.createContext('@errands', '#00aa00');
    const post = http.expectOne({ method: 'POST', url: '/api/v1/contexts' });
    expect(post.request.body).toEqual({ name: '@errands', color: '#00aa00' });
    post.flush({ id: 'c-new', name: '@errands', color: '#00aa00', position: 2, version: 0 });
    expect((await created).id).toBe('c-new');

    const renamed = service.updateContext(HOME, { color: null });
    const patch = http.expectOne({ method: 'PATCH', url: `/api/v1/contexts/${HOME.id}` });
    expect(patch.request.headers.get('If-Match')).toBe('"3"');
    expect(patch.request.body).toEqual({ color: null });
    patch.flush({ ...HOME, color: undefined, version: 4 });
    await renamed;

    const area = service.renameArea(WORK, 'Job');
    const areaPatch = http.expectOne({ method: 'PATCH', url: `/api/v1/areas/${WORK.id}` });
    expect(areaPatch.request.headers.get('If-Match')).toBe('"2"');
    expect(areaPatch.request.body).toEqual({ title: 'Job' });
    areaPatch.flush({ ...WORK, title: 'Job', version: 3 });
    expect((await area).title).toBe('Job');
  });

  it('reorders contexts and areas with every id in the new order', async () => {
    const contexts = service.orderContexts([PHONE.id, HOME.id]);
    const put = http.expectOne({ method: 'PUT', url: '/api/v1/contexts/order' });
    expect(put.request.body).toEqual({ ids: [PHONE.id, HOME.id] });
    put.flush({
      items: [
        { ...PHONE, position: 0 },
        { ...HOME, position: 1 },
      ],
    });
    expect((await contexts).map((c) => c.id)).toEqual([PHONE.id, HOME.id]);

    const areas = service.orderAreas([WORK.id]);
    const areaPut = http.expectOne({ method: 'PUT', url: '/api/v1/areas/order' });
    expect(areaPut.request.body).toEqual({ ids: [WORK.id] });
    areaPut.flush({ items: [WORK] });
    expect(await areas).toEqual([WORK]);
  });

  it('collects open and done tasks of a context over all pages', async () => {
    const tasks = service.tasksWithContext(HOME.id);
    const first = http.expectOne(
      (r) =>
        r.url === '/api/v1/tasks' &&
        r.params.get('completed') === 'false' &&
        !r.params.has('cursor'),
    );
    expect(first.request.params.get('context')).toBe(HOME.id);
    first.flush({ items: [task('t1', [HOME.id])], nextCursor: 'p2' });
    await tick();
    http
      .expectOne((r) => r.url === '/api/v1/tasks' && r.params.get('cursor') === 'p2')
      .flush({ items: [task('t2', [HOME.id])] });
    await tick();
    http
      .expectOne((r) => r.url === '/api/v1/tasks' && r.params.get('completed') === 'true')
      .flush({ items: [task('t3', [HOME.id])] });

    expect((await tasks).map((t) => t.id)).toEqual(['t1', 't2', 't3']);
  });

  it('deletes a context without touching its tasks when they just lose it', async () => {
    const done = service.deleteContext(HOME, null);
    const del = http.expectOne({ method: 'DELETE', url: `/api/v1/contexts/${HOME.id}` });
    expect(del.request.headers.get('If-Match')).toBe('"3"');
    del.flush(null, { status: 204, statusText: 'No Content' });
    await expect(done).resolves.toBeUndefined();
  });

  it('gives the tasks the replacement context before deleting, without duplicates', async () => {
    const done = service.deleteContext(HOME, PHONE.id);
    http
      .expectOne((r) => r.url === '/api/v1/tasks' && r.params.get('completed') === 'false')
      .flush({ items: [task('t1', [HOME.id, 'c-x'], 5), task('t2', [PHONE.id, HOME.id], 2)] });
    await tick();
    http
      .expectOne((r) => r.url === '/api/v1/tasks' && r.params.get('completed') === 'true')
      .flush({ items: [] });
    await tick();

    const p1 = http.expectOne({ method: 'PATCH', url: '/api/v1/tasks/t1' });
    expect(p1.request.headers.get('If-Match')).toBe('"5"');
    expect(p1.request.body).toEqual({ contextIds: [PHONE.id, 'c-x'] });
    p1.flush(task('t1', [PHONE.id, 'c-x'], 6));
    await tick();
    const p2 = http.expectOne({ method: 'PATCH', url: '/api/v1/tasks/t2' });
    expect(p2.request.body).toEqual({ contextIds: [PHONE.id] });
    p2.flush(task('t2', [PHONE.id], 3));
    await tick();

    http
      .expectOne({ method: 'DELETE', url: `/api/v1/contexts/${HOME.id}` })
      .flush(null, { status: 204, statusText: 'No Content' });
    await expect(done).resolves.toBeUndefined();
  });

  it('moves the projects to the target area before deleting', async () => {
    const done = service.deleteArea(WORK, 'a-home');
    const list = http.expectOne((r) => r.url === '/api/v1/projects');
    expect(list.request.params.get('areaId')).toBe(WORK.id);
    expect(list.request.params.has('status')).toBe(false);
    list.flush({ items: [project('p1', 4)] });
    await tick();

    const patch = http.expectOne({ method: 'PATCH', url: '/api/v1/projects/p1' });
    expect(patch.request.headers.get('If-Match')).toBe('"4"');
    expect(patch.request.body).toEqual({ areaId: 'a-home' });
    patch.flush({ ...project('p1', 5), areaId: 'a-home' });
    await tick();

    const del = http.expectOne({ method: 'DELETE', url: `/api/v1/areas/${WORK.id}` });
    expect(del.request.headers.get('If-Match')).toBe('"2"');
    del.flush(null, { status: 204, statusText: 'No Content' });
    await expect(done).resolves.toBeUndefined();
  });

  it('stops before deleting when moving an item fails, and does not toast', async () => {
    const done = service.deleteArea(WORK, 'a-home');
    http.expectOne((r) => r.url === '/api/v1/projects').flush({ items: [project('p1')] });
    await tick();
    http
      .expectOne({ method: 'PATCH', url: '/api/v1/projects/p1' })
      .flush({ status: 412, title: 'Precondition Failed' }, { status: 412, statusText: 'Failed' });

    const error = await done.catch((e: unknown) => e);
    // Told apart from a failing DELETE: the error of the move step is the cause.
    expect(error).toBeInstanceOf(ReassignError);
    const cause = (error as ReassignError).cause;
    expect(cause).toBeInstanceOf(ApiError);
    expect((cause as ApiError).status).toBe(412);
    http.expectNone({ method: 'DELETE' });
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('rejects a duplicate context name with the 409 problem, without a toast', async () => {
    const created = service.createContext('@home');
    const post = http.expectOne({ method: 'POST', url: '/api/v1/contexts' });
    expect(post.request.context.get(SILENT_ERRORS)).toBe(true);
    post.flush(
      { status: 409, title: 'Conflict', detail: 'A context named @home already exists' },
      { status: 409, statusText: 'Conflict' },
    );

    const error = await created.catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(409);
    expect((error as ApiError).userMessage).toBe('A context named @home already exists');
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('creates an area', async () => {
    const created = service.createArea('Health');
    const post = http.expectOne({ method: 'POST', url: '/api/v1/areas' });
    expect(post.request.body).toEqual({ title: 'Health' });
    post.flush({ id: 'a-health', title: 'Health', position: 1, version: 0 });
    expect((await created).id).toBe('a-health');
  });

  it('deletes an area without listing its projects when they just lose it', async () => {
    const done = service.deleteArea(WORK, null);
    http.expectNone((r) => r.url === '/api/v1/projects');
    const del = http.expectOne({ method: 'DELETE', url: `/api/v1/areas/${WORK.id}` });
    expect(del.request.headers.get('If-Match')).toBe('"2"');
    del.flush(null, { status: 204, statusText: 'No Content' });
    await expect(done).resolves.toBeUndefined();
  });

  it('collects the projects of an area over all pages', async () => {
    const projects = service.projectsInArea(WORK.id);
    const first = http.expectOne((r) => r.url === '/api/v1/projects' && !r.params.has('cursor'));
    expect(first.request.params.get('limit')).toBe('200');
    first.flush({ items: [project('p1')], nextCursor: 'n2' });
    await tick();
    http
      .expectOne((r) => r.url === '/api/v1/projects' && r.params.get('cursor') === 'n2')
      .flush({ items: [project('p2')], nextCursor: null });

    expect((await projects).map((p) => p.id)).toEqual(['p1', 'p2']);
  });

  it('includes done tasks when replacing a context, and stops when one update fails', async () => {
    const done = service.deleteContext(HOME, PHONE.id);
    http
      .expectOne((r) => r.url === '/api/v1/tasks' && r.params.get('completed') === 'false')
      .flush({ items: [] });
    await tick();
    http
      .expectOne((r) => r.url === '/api/v1/tasks' && r.params.get('completed') === 'true')
      .flush({ items: [task('t-done', [HOME.id], 7), task('t-next', [HOME.id])] });
    await tick();

    const patch = http.expectOne({ method: 'PATCH', url: '/api/v1/tasks/t-done' });
    expect(patch.request.headers.get('If-Match')).toBe('"7"');
    expect(patch.request.body).toEqual({ contextIds: [PHONE.id] });
    patch.flush(
      { status: 500, title: 'Internal Server Error' },
      { status: 500, statusText: 'Error' },
    );

    const error = await done.catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ReassignError);
    expect(((error as ReassignError).cause as ApiError).status).toBe(500);
    http.expectNone({ method: 'PATCH', url: '/api/v1/tasks/t-next' });
    http.expectNone({ method: 'DELETE' });
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('rejects with the plain API error when the delete itself fails after the move', async () => {
    const done = service.deleteContext(HOME, PHONE.id);
    http
      .expectOne((r) => r.url === '/api/v1/tasks' && r.params.get('completed') === 'false')
      .flush({ items: [task('t1', [HOME.id])] });
    await tick();
    http
      .expectOne((r) => r.url === '/api/v1/tasks' && r.params.get('completed') === 'true')
      .flush({ items: [] });
    await tick();
    http
      .expectOne({ method: 'PATCH', url: '/api/v1/tasks/t1' })
      .flush({ ...task('t1', [PHONE.id]), version: 2 });
    await tick();
    http
      .expectOne({ method: 'DELETE', url: `/api/v1/contexts/${HOME.id}` })
      .flush({ status: 412, title: 'Precondition Failed' }, { status: 412, statusText: 'Failed' });

    const error = await done.catch((e: unknown) => e);
    expect(error).not.toBeInstanceOf(ReassignError);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(412);
  });
});
