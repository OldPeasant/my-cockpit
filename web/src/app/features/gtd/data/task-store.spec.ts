import { TestBed } from '@angular/core/testing';
import { filter, Subject } from 'rxjs';
import {
  Api,
  ChangeEvent,
  completeTask,
  convertTaskToNote,
  createTask,
  deleteTask,
  listTasks,
  moveTask,
  NoteResponse,
  TaskResponse,
  uncompleteTask,
  updateTask,
} from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { addDays, localDate } from './dates';
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

function note(id: string): NoteResponse {
  return {
    id,
    title: 'Reading list',
    displayTitle: 'Reading list',
    bodyMd: 'Books for the winter',
    tags: [],
    pinned: false,
    archived: false,
    createdAt: '2026-10-02T08:00:00Z',
    updatedAt: '2026-10-02T08:00:00Z',
    version: 1,
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
    callsOf(listTasks)
      .at(-1)!
      .resolve(page([a]));
    await refetch;

    expect(store.find('a')).toMatchObject({ title: 'New', version: 2 });
  });

  it("sends the version of the caller's copy as If-Match, so a stale copy gets a conflict", async () => {
    const stale = task('a');
    await loaded([task('a', { version: 3 })]);

    void store.update(stale, { title: 'x' });
    expect(callsOf(updateTask)[0].params['If-Match']).toBe('"1"');

    void store.update(store.find('a')!, { title: 'y' });
    expect(callsOf(updateTask)[1].params['If-Match']).toBe('"3"');
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

  it('keeps the error of a newer failed request when an older one succeeds later', async () => {
    const older = store.load({ list: 'NEXT' });
    const newer = store.refresh();
    const [first, second] = callsOf(listTasks);
    const error = new Error('offline');
    second.reject(error);
    await newer;
    first.resolve(page([task('old')]));
    await older;

    expect(store.error()).toBe(error);
    expect(store.items()).toEqual([]);
    expect(store.loading()).toBe(false);
  });

  it('ends loading after a failed load and recovers on a retry', async () => {
    const failed = store.load({ list: 'NEXT' });
    const error = new Error('503');
    callsOf(listTasks)[0].reject(error);
    await failed;
    expect(store.loading()).toBe(false);
    expect(store.loaded()).toBe(false);
    expect(store.error()).toBe(error);

    const retry = store.refresh();
    expect(store.loading()).toBe(true);
    callsOf(listTasks)[1].resolve(page([task('a')]));
    await retry;
    expect(store.error()).toBeNull();
    expect(store.loaded()).toBe(true);
    expect(store.items().map((t) => t.id)).toEqual(['a']);
  });

  it('clears the old items when switching to another query, but not on the same query', async () => {
    await loaded([task('a')]);

    void store.load({ list: 'NEXT' });
    expect(store.items().map((t) => t.id)).toEqual(['a']);
    expect(store.loaded()).toBe(true);

    void store.load({ list: 'INBOX' });
    expect(store.items()).toEqual([]);
    expect(store.loaded()).toBe(false);
    expect(callsOf(listTasks).at(-1)!.params['list']).toBe('INBOX');
  });

  it('keeps the newer server answer when two writes to the same task answer in reverse order', async () => {
    const a = task('a');
    await loaded([a]);

    const first = store.update(a, { title: 'First' });
    const second = store.update(a, { title: 'Second' });
    expect(store.find('a')?.title).toBe('Second');
    const [firstCall, secondCall] = callsOf(updateTask);

    secondCall.resolve(task('a', { title: 'Second', version: 3 }));
    await second;
    firstCall.resolve(task('a', { title: 'First', version: 2 }));
    await first;

    expect(store.find('a')).toMatchObject({ title: 'Second', version: 3 });
  });

  it('keeps the other optimistic write when one of two overlapping writes fails', async () => {
    const a = task('a');
    await loaded([a]);

    const title = store.update(a, { title: 'Renamed' });
    const energy = store.update(a, { energy: 'HIGH' });
    expect(store.find('a')).toMatchObject({ title: 'Renamed', energy: 'HIGH' });

    callsOf(updateTask)[0].reject(new Error('412'));
    await expect(title).rejects.toThrow('412');
    // The second write was built on the first one's optimistic state and is still pending.
    expect(store.find('a')?.energy).toBe('HIGH');

    callsOf(updateTask)[1].resolve(task('a', { energy: 'HIGH', version: 2 }));
    await energy;
    expect(store.find('a')).toMatchObject({ title: 'Task a', energy: 'HIGH', version: 2 });
  });

  it('does not bring back a task deleted by an event while a list request was running', async () => {
    await loaded([task('a'), task('b', { position: 2 })]);

    const refetch = store.refresh();
    changes.next({ type: 'TASK', op: 'DELETED', id: 'a', at: '2026-10-20T10:00:00Z' });
    // The refetch started before the deletion answers with the deleted task still in it.
    callsOf(listTasks)[1].resolve(page([task('a'), task('b', { position: 2 })]));
    await refetch;

    expect(store.items().map((t) => t.id)).toEqual(['b']);
  });

  it('keeps a task created while an older list request runs, and forgets old local changes', async () => {
    await loaded([task('a')]);

    const refetch = store.refresh();
    const created = store.create({ title: 'Book dentist' });
    callsOf(createTask)[0].resolve(task('n', { position: 2 }));
    await created;
    // The list answer was computed before the task existed.
    callsOf(listTasks)[1].resolve(page([task('a')]));
    await refetch;
    expect(store.items().map((t) => t.id)).toEqual(['a', 'n']);

    // A later list answer is authoritative again: the old local change no longer protects 'n'.
    const later = store.refresh();
    callsOf(listTasks)[2].resolve(page([task('a')]));
    await later;
    expect(store.items().map((t) => t.id)).toEqual(['a']);
  });

  it('hides a task put in a tickler from the Inbox, also after the server answered', async () => {
    const done = store.load({ list: 'INBOX' });
    const item = task('a', { list: 'INBOX' });
    callsOf(listTasks)[0].resolve(page([item]));
    await done;
    const later = addDays(localDate(), 7);

    const moved = store.move(item, { list: 'INBOX', ticklerDate: later });
    expect(store.items()).toEqual([]);
    expect(callsOf(moveTask)[0].params['body']).toEqual({ list: 'INBOX', ticklerDate: later });
    callsOf(moveTask)[0].resolve({ ...item, ticklerDate: later, version: 2 });
    await moved;

    expect(store.items()).toEqual([]);
  });

  it('brings a task back to the Inbox when its tickler move fails', async () => {
    const done = store.load({ list: 'INBOX' });
    const item = task('a', { list: 'INBOX' });
    callsOf(listTasks)[0].resolve(page([item]));
    await done;

    const moved = store.move(item, { list: 'INBOX', ticklerDate: addDays(localDate(), 1) });
    expect(store.items()).toEqual([]);
    callsOf(moveTask)[0].reject(new Error('ticklerDate must be in the future'));
    await expect(moved).rejects.toThrow('ticklerDate');

    expect(store.items()).toEqual([item]);
  });

  it('moves a task to Scheduled with its date', async () => {
    const done = store.load({ list: 'SCHEDULED' });
    callsOf(listTasks)[0].resolve(page([]));
    await done;

    void store.move(task('a'), { list: 'SCHEDULED', scheduledDate: '2026-11-02' });
    expect(store.find('a')).toMatchObject({ list: 'SCHEDULED', scheduledDate: '2026-11-02' });
  });

  it('shows a created task only if it matches the query', async () => {
    await loaded([]);

    const inbox = store.create({ title: 'Buy milk' });
    callsOf(createTask)[0].resolve(task('i', { list: 'INBOX' }));
    await inbox;
    expect(store.items()).toEqual([]);

    const next = store.create({ title: 'Call Bob' });
    callsOf(createTask)[1].resolve(task('n'));
    expect((await next).id).toBe('n');
    expect(store.items().map((t) => t.id)).toEqual(['n']);
  });

  it('removes a task and keeps it gone once the server confirmed it', async () => {
    const a = task('a');
    await loaded([a]);

    const removed = store.remove(a);
    expect(store.items()).toEqual([]);
    expect(callsOf(deleteTask)[0].params).toEqual({ id: 'a', 'If-Match': '"1"' });
    callsOf(deleteTask)[0].resolve(undefined);
    await removed;
    expect(store.items()).toEqual([]);
  });

  it('converts a task to a note: it leaves the list at once and the note is returned', async () => {
    const a = task('a', { list: 'INBOX', version: 4 });
    const done = store.load({ list: 'INBOX' });
    callsOf(listTasks)[0].resolve(page([a, task('b', { list: 'INBOX', position: 2 })]));
    await done;

    const converted = store.convertToNote(a);
    expect(store.items().map((t) => t.id)).toEqual(['b']);
    expect(callsOf(convertTaskToNote)[0].params).toEqual({ id: 'a', 'If-Match': '"4"' });

    const created = note('n1');
    callsOf(convertTaskToNote)[0].resolve(created);
    expect(await converted).toEqual(created);
    // The task is gone on the server (trashed): it stays out of the list, and no note is added.
    expect(store.items().map((t) => t.id)).toEqual(['b']);
  });

  it('brings a task back when converting it to a note fails, and rethrows the error', async () => {
    const a = task('a');
    await loaded([a, task('b', { position: 2 })]);

    const converted = store.convertToNote(a);
    expect(store.items().map((t) => t.id)).toEqual(['b']);
    const error = new Error('412');
    callsOf(convertTaskToNote)[0].reject(error);
    await expect(converted).rejects.toBe(error);
    expect(store.items().map((t) => t.id)).toEqual(['a', 'b']);
  });

  it('keeps a converted task out of a list answer requested before the conversion', async () => {
    const a = task('a');
    await loaded([a]);
    const refetch = store.refresh();

    const converted = store.convertToNote(a);
    callsOf(convertTaskToNote)[0].resolve(note('n1'));
    await converted;
    // The older list answer still contains the task.
    callsOf(listTasks).at(-1)!.resolve(page([a]));
    await refetch;

    expect(store.items()).toEqual([]);
  });

  it('reopens a done task in the Logbook: it leaves the list, and comes back on an error', async () => {
    const done = store.load({ completed: true });
    const older = task('o', { completedAt: '2026-10-18T10:00:00Z' });
    const newer = task('n', { completedAt: '2026-10-19T10:00:00Z' });
    callsOf(listTasks)[0].resolve(page([older, newer]));
    await done;
    expect(store.items().map((t) => t.id)).toEqual(['n', 'o']);

    const reopened = store.uncomplete(older);
    expect(store.items().map((t) => t.id)).toEqual(['n']);
    callsOf(uncompleteTask)[0].reject(new Error('409'));
    await expect(reopened).rejects.toThrow('409');
    expect(store.items().map((t) => t.id)).toEqual(['n', 'o']);
  });

  it('moves a task to Waiting with its waiting-for fields and keeps it in a Waiting list', async () => {
    const done = store.load({ list: 'WAITING' });
    callsOf(listTasks)[0].resolve(page([]));
    await done;
    const a = task('a', { scheduledDate: '2026-11-01' });

    void store.move(a, { list: 'WAITING', waitingForText: 'Reply from Carol' });
    // A task moved into the shown list appears at once, without fields of its old list.
    expect(store.find('a')).toMatchObject({
      list: 'WAITING',
      waitingForText: 'Reply from Carol',
      scheduledDate: undefined,
    });
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

  it('applies project, energy and creativity filters', () => {
    const t = task('a', { projectId: 'p1', energy: 'LOW', creativity: 'ROUTINE' });
    expect(matchesTask(t, { projectId: 'p1', energy: 'LOW', creativity: 'ROUTINE' }, today)).toBe(
      true,
    );
    expect(matchesTask(t, { projectId: 'p2' }, today)).toBe(false);
    expect(matchesTask(t, { energy: 'HIGH' }, today)).toBe(false);
    expect(matchesTask(t, { creativity: 'CREATIVE' }, today)).toBe(false);
    expect(matchesTask(task('b'), { energy: 'LOW' }, today)).toBe(false);
  });

  it('matches only open tasks unless completed is asked for', () => {
    const done = task('a', { completedAt: '2026-10-19T10:00:00Z' });
    expect(matchesTask(done, {}, today)).toBe(false);
    expect(matchesTask(done, { completed: true }, today)).toBe(true);
    expect(matchesTask(done, { completed: false }, today)).toBe(false);
  });

  it('applies due windows relative to today at their edges', () => {
    const due = (dueDate: string) => task('a', { dueDate });
    expect(matchesTask(due('2026-10-21'), { due: 'today' }, today)).toBe(false);
    expect(matchesTask(due('2026-10-19'), { due: 'today' }, today)).toBe(false);
    expect(matchesTask(due('2026-10-20'), { due: 'week' }, today)).toBe(true);
    expect(matchesTask(due('2026-10-19'), { due: 'week' }, today)).toBe(false);
  });

  it('hides a task whose tickler date is still to come, on every list (server rule)', () => {
    const tickler = (ticklerDate: string, fields: Partial<TaskResponse> = {}) =>
      task('a', { list: 'INBOX', ticklerDate, ...fields });
    // Tomorrow and later: hidden.
    expect(matchesTask(tickler('2026-10-21'), { list: 'INBOX' }, today)).toBe(false);
    expect(matchesTask(tickler('2027-01-01'), { list: 'INBOX' }, today)).toBe(false);
    expect(matchesTask(tickler('2026-10-21'), {}, today)).toBe(false);
    expect(matchesTask(tickler('2026-10-21', { list: 'NEXT' }), { list: 'NEXT' }, today)).toBe(
      false,
    );
    // Today or earlier (not yet released by the job): shown, as `ticklerDate <= today` on the server.
    expect(matchesTask(tickler('2026-10-20'), { list: 'INBOX' }, today)).toBe(true);
    expect(matchesTask(tickler('2026-10-19'), { list: 'INBOX' }, today)).toBe(true);
    // Without a tickler date the other filters decide.
    expect(matchesTask(task('b', { list: 'INBOX' }), { list: 'INBOX' }, today)).toBe(true);
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
