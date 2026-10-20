import { TestBed } from '@angular/core/testing';
import { filter, Subject } from 'rxjs';
import {
  Api,
  ChangeEvent,
  createProject,
  deleteProject,
  listProjects,
  ProjectResponse,
  setProjectNextStep,
  updateProject,
} from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { PROJECT_PAGE_SIZE, ProjectStore } from './project-store';

/** A request the test answers when it wants to, so it controls the order of answers. */
interface Call {
  fn: unknown;
  params: Record<string, unknown>;
  resolve: (value: unknown) => void;
  reject: (error: unknown) => void;
}

function project(id: string, fields: Partial<ProjectResponse> = {}): ProjectResponse {
  return {
    id,
    title: `Project ${id}`,
    status: 'ACTIVE',
    stalled: false,
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-01T08:00:00Z',
    version: 1,
    ...fields,
  };
}

const page = (items: ProjectResponse[], nextCursor?: string) => ({ items, nextCursor });

/** Lets pending promise callbacks run. */
const settle = () => new Promise((resolve) => setTimeout(resolve));

describe('ProjectStore', () => {
  let calls: Call[];
  let changes: Subject<ChangeEvent>;
  let resyncs: Subject<void>;
  let store: ProjectStore;

  const callsOf = (fn: unknown) => calls.filter((call) => call.fn === fn);
  const titles = () => store.items().map((p) => p.title);

  beforeEach(() => {
    calls = [];
    changes = new Subject();
    resyncs = new Subject();
    const api = {
      invoke: (fn: unknown, params: Record<string, unknown> = {}) =>
        new Promise((resolve, reject) => calls.push({ fn, params, resolve, reject })),
    };
    const events = {
      resync$: resyncs.asObservable(),
      events$: (type: string) => changes.pipe(filter((event) => event.type === type)),
    };
    TestBed.configureTestingModule({
      providers: [
        { provide: Api, useValue: api },
        { provide: ChangeEventsService, useValue: events },
      ],
    });
    store = TestBed.inject(ProjectStore);
  });

  async function loaded(items: ProjectResponse[]): Promise<void> {
    const done = store.ensureLoaded();
    callsOf(listProjects).at(-1)!.resolve(page(items));
    await done;
  }

  it('loads all projects once with ensureLoaded, following cursors, ordered by title', async () => {
    const done = store.ensureLoaded();
    // A second caller while loading does not start another request.
    void store.ensureLoaded();
    expect(callsOf(listProjects)).toHaveLength(1);
    expect(callsOf(listProjects)[0].params).toEqual({
      cursor: undefined,
      limit: PROJECT_PAGE_SIZE,
    });

    callsOf(listProjects)[0].resolve(page([project('b', { title: 'Write novel' })], 'c1'));
    await settle();
    expect(callsOf(listProjects)[1].params['cursor']).toBe('c1');
    callsOf(listProjects)[1].resolve(page([project('a', { title: 'Plan garden' })]));
    await done;

    expect(titles()).toEqual(['Plan garden', 'Write novel']);
    expect(store.loaded()).toBe(true);

    // Already loaded: no new request.
    await store.ensureLoaded();
    expect(callsOf(listProjects)).toHaveLength(2);
  });

  it('lets a second caller wait until the running load has all pages', async () => {
    const first = store.ensureLoaded();
    let secondDone = false;
    const second = store.ensureLoaded().then(() => (secondDone = true));

    callsOf(listProjects)[0].resolve(page([project('b', { title: 'Write novel' })], 'c1'));
    await settle();
    expect(secondDone).toBe(false);
    expect(callsOf(listProjects)).toHaveLength(2);

    callsOf(listProjects)[1].resolve(page([project('a', { title: 'Plan garden' })]));
    await second;
    expect(titles()).toEqual(['Plan garden', 'Write novel']);
    await first;
  });

  it('can retry ensureLoaded after a failed load', async () => {
    const failed = store.ensureLoaded();
    const error = new Error('offline');
    callsOf(listProjects)[0].reject(error);
    await failed;
    expect(store.error()).toBe(error);
    expect(store.loading()).toBe(false);
    expect(store.loaded()).toBe(false);

    const retried = store.ensureLoaded();
    expect(callsOf(listProjects)).toHaveLength(2);
    callsOf(listProjects)[1].resolve(page([project('a')]));
    await retried;
    expect(store.error()).toBeNull();
    expect(store.loaded()).toBe(true);
  });

  it('settles a waiting caller when a later page of the shared load fails', async () => {
    const first = store.ensureLoaded();
    const second = store.ensureLoaded();
    callsOf(listProjects)[0].resolve(page([project('b')], 'c1'));
    await settle();

    const error = new Error('offline');
    callsOf(listProjects)[1].reject(error);
    await Promise.all([first, second]);
    expect(store.loaded()).toBe(false);
    expect(store.error()).toBe(error);
    expect(store.loading()).toBe(false);

    void store.ensureLoaded();
    expect(callsOf(listProjects)).toHaveLength(3);
    expect(callsOf(listProjects)[2].params['cursor']).toBeUndefined();
  });

  it("sends the version of the caller's copy as If-Match, so a stale copy gets a conflict", async () => {
    const stale = project('p');
    await loaded([project('p', { version: 5 })]);

    void store.setNextStep(stale, 't1');
    expect(callsOf(setProjectNextStep)[0].params['If-Match']).toBe('"1"');
    void store.update(stale, { title: 'Renamed' });
    expect(callsOf(updateProject)[0].params['If-Match']).toBe('"1"');

    // The current (optimistic) state still carries the server's version 5.
    void store.setNextStep(store.find('p')!, 't2');
    expect(callsOf(setProjectNextStep)[1].params['If-Match']).toBe('"5"');

    void store.remove(stale);
    expect(callsOf(deleteProject)[0].params['If-Match']).toBe('"1"');
  });

  it('sets the next step at once and takes the server answer', async () => {
    const p = project('p', { stalled: true });
    await loaded([p]);

    const saved = store.setNextStep(p, 't1');
    expect(store.find('p')).toMatchObject({ nextTaskId: 't1', stalled: false });
    const call = callsOf(setProjectNextStep)[0];
    expect(call.params).toEqual({ id: 'p', 'If-Match': '"1"', body: { taskId: 't1' } });

    call.resolve(project('p', { nextTaskId: 't1', version: 2 }));
    await saved;
    expect(store.find('p')).toMatchObject({ nextTaskId: 't1', stalled: false, version: 2 });
  });

  it('clearing the next step of an active project shows it stalled, but not one on hold', async () => {
    const active = project('a', { nextTaskId: 't1' });
    const onHold = project('h', { nextTaskId: 't2', status: 'ON_HOLD' });
    await loaded([active, onHold]);

    void store.setNextStep(active, null);
    void store.setNextStep(onHold, null);

    expect(store.find('a')).toMatchObject({ nextTaskId: undefined, stalled: true });
    expect(store.find('h')).toMatchObject({ nextTaskId: undefined, stalled: false });
    expect(callsOf(setProjectNextStep)[0].params['body']).toEqual({ taskId: null });
  });

  it('rolls a next step back when the server refuses it', async () => {
    const p = project('p', { nextTaskId: 'old' });
    await loaded([p]);

    const saved = store.setNextStep(p, 'new');
    expect(store.find('p')?.nextTaskId).toBe('new');
    const error = new Error('422');
    callsOf(setProjectNextStep)[0].reject(error);

    await expect(saved).rejects.toBe(error);
    expect(store.find('p')).toEqual(p);
  });

  it('updates optimistically and rolls back on an error', async () => {
    const p = project('p', { areaId: 'area-1' });
    await loaded([p]);

    const saved = store.update(p, { title: 'Renamed', areaId: null });
    expect(store.find('p')?.title).toBe('Renamed');
    expect(store.find('p')?.areaId).toBeUndefined();
    expect(callsOf(updateProject)[0].params).toEqual({
      id: 'p',
      'If-Match': '"1"',
      body: { title: 'Renamed', areaId: null },
    });

    callsOf(updateProject)[0].reject(new Error('412'));
    await expect(saved).rejects.toThrow('412');
    expect(store.find('p')).toEqual(p);
  });

  it('removes a project at once and brings it back on an error', async () => {
    const a = project('a', { title: 'Alpha' });
    await loaded([a, project('b', { title: 'Beta' })]);

    const removed = store.remove(a);
    expect(titles()).toEqual(['Beta']);
    expect(callsOf(deleteProject)[0].params).toEqual({ id: 'a', 'If-Match': '"1"' });

    callsOf(deleteProject)[0].reject(new Error('409'));
    await expect(removed).rejects.toThrow('409');
    expect(titles()).toEqual(['Alpha', 'Beta']);
  });

  it('keeps a removed project gone once the server confirmed it', async () => {
    const a = project('a');
    await loaded([a]);

    const removed = store.remove(a);
    callsOf(deleteProject)[0].resolve(undefined);
    await removed;
    expect(store.items()).toEqual([]);
  });

  it('shows a created project once the server answered', async () => {
    await loaded([]);

    const created = store.create({ title: 'Learn Italian' });
    expect(store.items()).toEqual([]);
    expect(callsOf(createProject)[0].params).toEqual({ body: { title: 'Learn Italian' } });
    callsOf(createProject)[0].resolve(project('n', { title: 'Learn Italian' }));

    expect((await created).id).toBe('n');
    expect(titles()).toEqual(['Learn Italian']);
  });

  it('applies the query filters to server answers of a filtered instance', async () => {
    const done = store.load({ status: 'ACTIVE', stalled: true, areaId: 'area-1' });
    expect(callsOf(listProjects)[0].params).toEqual({
      status: 'ACTIVE',
      stalled: true,
      areaId: 'area-1',
      cursor: undefined,
      limit: PROJECT_PAGE_SIZE,
    });
    const p = project('p', { stalled: true, areaId: 'area-1' });
    callsOf(listProjects)[0].resolve(page([p]));
    await done;

    // Setting a next step makes it no longer stalled: it leaves the "stalled" list.
    const saved = store.setNextStep(p, 't1');
    expect(store.items()).toEqual([]);
    callsOf(setProjectNextStep)[0].resolve(project('p', { areaId: 'area-1', nextTaskId: 't1' }));
    await saved;
    expect(store.items()).toEqual([]);

    const other = store.create({ title: 'Elsewhere' });
    callsOf(createProject)[0].resolve(project('o', { stalled: true, areaId: 'area-2' }));
    await other;
    const held = store.create({ title: 'Held' });
    callsOf(createProject)[1].resolve(
      project('h', { stalled: true, areaId: 'area-1', status: 'ON_HOLD' }),
    );
    await held;
    expect(store.items()).toEqual([]);
  });

  it('refetches on task events (stalled may change) and on resync, drops on PROJECT DELETED', async () => {
    await loaded([project('a'), project('b')]);

    changes.next({ type: 'TASK', op: 'UPDATED', id: 't', at: '2026-10-20T10:00:00Z' });
    expect(callsOf(listProjects)).toHaveLength(2);

    // A deleted task refetches too: only PROJECT deletions drop an item.
    changes.next({ type: 'TASK', op: 'DELETED', id: 'a', at: '2026-10-20T10:00:01Z' });
    expect(callsOf(listProjects)).toHaveLength(3);
    expect(store.find('a')).toBeDefined();

    changes.next({ type: 'PROJECT', op: 'DELETED', id: 'a', at: '2026-10-20T10:00:02Z' });
    expect(store.items().map((p) => p.id)).toEqual(['b']);
    expect(callsOf(listProjects)).toHaveLength(3);

    resyncs.next();
    expect(callsOf(listProjects)).toHaveLength(4);
  });
});
