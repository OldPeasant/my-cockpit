import { TestBed } from '@angular/core/testing';
import { filter, Subject } from 'rxjs';
import {
  Api,
  ChangeEvent,
  completeTask,
  listTasks,
  moveTask,
  TaskResponse,
  updateTask,
} from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { matchesTask, TASK_PAGE_SIZE, TaskStore } from './task-store';

/** A request the test answers when it wants to, so it controls the order of answers. */
interface Call {
  fn: unknown;
  params: Record<string, unknown>;
  resolve: (value: unknown) => void;
  reject: (error: unknown) => void;
}

function task(id: string, fields: Partial<TaskResponse> = {}): TaskResponse {
  return {
    id,
    title: `Task ${id}`,
    list: 'NEXT',
    position: 1,
    contextIds: [],
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-01T08:00:00Z',
    version: 1,
    ...fields,
  };
}

const page = (items: TaskResponse[], nextCursor?: string) => ({ items, nextCursor });

/** Lets pending promise callbacks run. */
const settle = () => new Promise((resolve) => setTimeout(resolve));

describe('TaskStore', () => {
  let calls: Call[];
  let changes: Subject<ChangeEvent>;
  let resyncs: Subject<void>;
  let store: TaskStore;

  const callsOf = (fn: unknown) => calls.filter((call) => call.fn === fn);

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
        TaskStore,
        { provide: Api, useValue: api },
        { provide: ChangeEventsService, useValue: events },
      ],
    });
    store = TestBed.inject(TaskStore);
  });

  async function loaded(items: TaskResponse[]): Promise<void> {
    const done = store.load({ list: 'NEXT' });
    callsOf(listTasks).at(-1)!.resolve(page(items));
    await done;
  }

  it('loads all pages of the list with the largest page size', async () => {
    const done = store.load({ list: 'NEXT' });
    expect(store.loading()).toBe(true);
    const first = callsOf(listTasks)[0];
    expect(first.params).toEqual({ list: 'NEXT', cursor: undefined, limit: TASK_PAGE_SIZE });
    first.resolve(page([task('a', { position: 2 })], 'c1'));
    await settle();
    const second = callsOf(listTasks)[1];
    expect(second.params['cursor']).toBe('c1');
    second.resolve(page([task('b', { position: 1 })]));
    await done;

    expect(store.items().map((t) => t.id)).toEqual(['b', 'a']);
    expect(store.loaded()).toBe(true);
    expect(store.loading()).toBe(false);
  });

  it('never lets a slower older list answer overwrite a newer one', async () => {
    const older = store.load({ list: 'NEXT' });
    const newer = store.refresh();
    const [first, second] = callsOf(listTasks);

    // Answers arrive in reverse order: the newer request first.
    second.resolve(page([task('new')]));
    await newer;
    first.resolve(page([task('old')]));
    await older;

    expect(store.items().map((t) => t.id)).toEqual(['new']);
    expect(store.loading()).toBe(false);
  });

  it('ignores an older answer of a previous query after switching the query', async () => {
    const next = store.load({ list: 'NEXT' });
    const inbox = store.load({ list: 'INBOX' });
    const [nextCall, inboxCall] = callsOf(listTasks);

    inboxCall.resolve(page([task('i', { list: 'INBOX' })]));
    await inbox;
    nextCall.resolve(page([task('n')]));
    await next;

    expect(store.items().map((t) => t.id)).toEqual(['i']);
  });

  it('keeps the error of the newest request only', async () => {
    const older = store.load({ list: 'NEXT' });
    const newer = store.refresh();
    const [first, second] = callsOf(listTasks);
    second.resolve(page([task('a')]));
    await newer;
    first.reject(new Error('offline'));
    await older;

    expect(store.error()).toBeNull();
    expect(store.items().map((t) => t.id)).toEqual(['a']);
  });

  it('shows an update at once and takes the server answer', async () => {
    const a = task('a');
    await loaded([a]);

    const saved = store.update(a, { title: 'Call Alice' });
    expect(store.find('a')?.title).toBe('Call Alice');
    const call = callsOf(updateTask)[0];
    expect(call.params).toEqual({ id: 'a', 'If-Match': '"1"', body: { title: 'Call Alice' } });

    call.resolve(task('a', { title: 'Call Alice', version: 2 }));
    await saved;
    expect(store.find('a')).toMatchObject({ title: 'Call Alice', version: 2 });
  });

  it('rolls an optimistic update back when the server refuses it', async () => {
    const a = task('a', { energy: 'LOW' });
    await loaded([a]);

    const saved = store.update(a, { title: 'Changed', energy: null });
    expect(store.find('a')?.title).toBe('Changed');
    expect(store.find('a')?.energy).toBeUndefined();

    const error = new Error('412');
    callsOf(updateTask)[0].reject(error);
    await expect(saved).rejects.toBe(error);
    expect(store.find('a')).toEqual(a);
  });

  it('removes a completed task at once and brings it back on an error', async () => {
    const a = task('a');
    await loaded([a, task('b', { position: 2 })]);

    const completed = store.complete(a);
    expect(store.items().map((t) => t.id)).toEqual(['b']);

    callsOf(completeTask)[0].reject(new Error('409'));
    await expect(completed).rejects.toThrow('409');
    expect(store.items().map((t) => t.id)).toEqual(['a', 'b']);
  });

  it('returns needsNextStep of a completion and keeps the task out of an open list', async () => {
    const a = task('a', { projectId: 'p' });
    await loaded([a]);

    const completed = store.complete(a);
    callsOf(completeTask)[0].resolve({
      ...a,
      completedAt: '2026-10-20T10:00:00Z',
      version: 2,
      needsNextStep: true,
    });

    expect((await completed).needsNextStep).toBe(true);
    expect(store.items()).toEqual([]);
  });

  it('moves a task out of the list and sends the move', async () => {
    const a = task('a');
    await loaded([a]);

    const moved = store.move(a, { list: 'SOMEDAY' });
    expect(store.items()).toEqual([]);
    expect(callsOf(moveTask)[0].params['body']).toEqual({ list: 'SOMEDAY' });
    callsOf(moveTask)[0].reject(new Error('boom'));
    await expect(moved).rejects.toThrow('boom');
    expect(store.items()).toEqual([a]);
  });

  it('keeps a local change when a list answer requested before it arrives later', async () => {
    const a = task('a');
    await loaded([a]);

    const refetch = store.refresh();
    const saved = store.update(a, { title: 'New' });
    callsOf(updateTask)[0].resolve(task('a', { title: 'New', version: 2 }));
    await saved;
    callsOf(listTasks).at(-1)!.resolve(page([a]));
    await refetch;

    expect(store.find('a')).toMatchObject({ title: 'New', version: 2 });
  });

  it('sends the newest known version as If-Match', async () => {
    const stale = task('a');
    await loaded([task('a', { version: 3 })]);

    void store.update(stale, { title: 'x' });
    expect(callsOf(updateTask)[0].params['If-Match']).toBe('"3"');
  });

  it('refetches on an SSE resync', async () => {
    await loaded([task('a')]);

    resyncs.next();
    expect(callsOf(listTasks)).toHaveLength(2);
    callsOf(listTasks)[1].resolve(page([task('a'), task('b', { position: 2 })]));
    await settle();

    expect(store.items().map((t) => t.id)).toEqual(['a', 'b']);
  });

  it('does not fetch on a resync before the first load', () => {
    resyncs.next();
    expect(calls).toEqual([]);
  });

  it('removes a task on its DELETED event and refetches on other task events', async () => {
    await loaded([task('a'), task('b', { position: 2 })]);

    changes.next({ type: 'TASK', op: 'DELETED', id: 'a', at: '2026-10-20T10:00:00Z' });
    expect(store.items().map((t) => t.id)).toEqual(['b']);
    expect(callsOf(listTasks)).toHaveLength(1);

    changes.next({ type: 'TASK', op: 'UPDATED', id: 'b', at: '2026-10-20T10:00:01Z' });
    expect(callsOf(listTasks)).toHaveLength(2);

    changes.next({ type: 'NOTE', op: 'UPDATED', id: 'n', at: '2026-10-20T10:00:02Z' });
    expect(callsOf(listTasks)).toHaveLength(2);
  });
});

describe('matchesTask', () => {
  const today = '2026-10-20';

  it('applies list, completion, context and estimate filters', () => {
    const t = task('a', { contextIds: ['home'], timeEstimateMin: 15 });
    expect(matchesTask(t, { list: 'NEXT' }, today)).toBe(true);
    expect(matchesTask(t, { list: 'INBOX' }, today)).toBe(false);
    expect(matchesTask(t, { completed: true }, today)).toBe(false);
    expect(matchesTask(t, { context: 'home' }, today)).toBe(true);
    expect(matchesTask(t, { context: 'phone' }, today)).toBe(false);
    expect(matchesTask(t, { maxMinutes: 15 }, today)).toBe(true);
    expect(matchesTask(t, { maxMinutes: 5 }, today)).toBe(false);
    expect(matchesTask(task('b'), { maxMinutes: 5 }, today)).toBe(false);
  });

  it('applies due windows relative to today', () => {
    const due = (dueDate: string) => task('a', { dueDate });
    expect(matchesTask(due('2026-10-19'), { due: 'overdue' }, today)).toBe(true);
    expect(matchesTask(due('2026-10-20'), { due: 'overdue' }, today)).toBe(false);
    expect(matchesTask(due('2026-10-20'), { due: 'today' }, today)).toBe(true);
    expect(matchesTask(due('2026-10-26'), { due: 'week' }, today)).toBe(true);
    expect(matchesTask(due('2026-10-27'), { due: 'week' }, today)).toBe(false);
    expect(matchesTask(task('b'), { due: 'today' }, today)).toBe(false);
  });
});
