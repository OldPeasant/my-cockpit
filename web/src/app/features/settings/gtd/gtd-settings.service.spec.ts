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
import { GtdSettingsService } from './gtd-settings.service';

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

  it('deletes a context without a replacement and without touching its tasks', async () => {
    const done = service.deleteContext(HOME, null);
    const del = http.expectOne(
      (r) => r.method === 'DELETE' && r.url === `/api/v1/contexts/${HOME.id}`,
    );
    expect(del.request.headers.get('If-Match')).toBe('"3"');
    expect(del.request.params.has('replaceWith')).toBe(false);
    del.flush(null, { status: 204, statusText: 'No Content' });
    await expect(done).resolves.toBeUndefined();
    http.expectNone((r) => r.url === '/api/v1/tasks');
  });

  it('lets the server give the tasks the replacement context in the delete itself', async () => {
    const done = service.deleteContext(HOME, PHONE.id);
    const del = http.expectOne(
      (r) => r.method === 'DELETE' && r.url === `/api/v1/contexts/${HOME.id}`,
    );
    expect(del.request.headers.get('If-Match')).toBe('"3"');
    expect(del.request.params.get('replaceWith')).toBe(PHONE.id);
    del.flush(null, { status: 204, statusText: 'No Content' });
    await expect(done).resolves.toBeUndefined();
    http.expectNone((r) => r.url === '/api/v1/tasks');
    http.expectNone({ method: 'PATCH' });
  });

  it('deletes an area without a target and without touching its projects', async () => {
    const done = service.deleteArea(WORK, null);
    const del = http.expectOne(
      (r) => r.method === 'DELETE' && r.url === `/api/v1/areas/${WORK.id}`,
    );
    expect(del.request.headers.get('If-Match')).toBe('"2"');
    expect(del.request.params.has('moveTo')).toBe(false);
    del.flush(null, { status: 204, statusText: 'No Content' });
    await expect(done).resolves.toBeUndefined();
    http.expectNone((r) => r.url === '/api/v1/projects');
  });

  it('lets the server move the projects to the target area in the delete itself', async () => {
    const done = service.deleteArea(WORK, 'a-home');
    const del = http.expectOne(
      (r) => r.method === 'DELETE' && r.url === `/api/v1/areas/${WORK.id}`,
    );
    expect(del.request.headers.get('If-Match')).toBe('"2"');
    expect(del.request.params.get('moveTo')).toBe('a-home');
    del.flush(null, { status: 204, statusText: 'No Content' });
    await expect(done).resolves.toBeUndefined();
    http.expectNone((r) => r.url === '/api/v1/projects');
    http.expectNone({ method: 'PATCH' });
  });

  it('rejects with the API error of a failed delete, without a toast', async () => {
    const done = service.deleteContext(HOME, 'c-gone');
    http
      .expectOne((r) => r.method === 'DELETE' && r.url === `/api/v1/contexts/${HOME.id}`)
      .flush({ status: 400, title: 'Bad Request' }, { status: 400, statusText: 'Bad Request' });

    const error = await done.catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(400);
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
});
