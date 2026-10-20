import { TestBed } from '@angular/core/testing';
import { filter, Subject } from 'rxjs';
import {
  Api,
  ChangeEvent,
  ContextResponse,
  createContext,
  deleteContext,
  listContexts,
  updateContext,
} from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { ContextStore } from './context-store';

/** A request the test answers when it wants to, so it controls the order of answers. */
interface Call {
  fn: unknown;
  params: Record<string, unknown>;
  resolve: (value: unknown) => void;
  reject: (error: unknown) => void;
}

function context(id: string, fields: Partial<ContextResponse> = {}): ContextResponse {
  return { id, name: `@${id}`, position: 1, version: 1, ...fields };
}

/** Lets pending promise callbacks run. */
const settle = () => new Promise((resolve) => setTimeout(resolve));

describe('ContextStore', () => {
  let calls: Call[];
  let changes: Subject<ChangeEvent>;
  let resyncs: Subject<void>;
  let store: ContextStore;

  const callsOf = (fn: unknown) => calls.filter((call) => call.fn === fn);
  const names = () => store.items().map((c) => c.name);

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
    store = TestBed.inject(ContextStore);
  });

  async function loaded(items: ContextResponse[]): Promise<void> {
    const done = store.ensureLoaded();
    callsOf(listContexts).at(-1)!.resolve({ items });
    await done;
  }

  it('loads the contexts once, ordered by position then id', async () => {
    await loaded([
      context('phone', { position: 2 }),
      context('office', { position: 1 }),
      context('home', { position: 1 }),
    ]);
    expect(names()).toEqual(['@home', '@office', '@phone']);

    await store.ensureLoaded();
    expect(callsOf(listContexts)).toHaveLength(1);
  });

  it('lets a second caller wait for the running load instead of starting another', async () => {
    const first = store.ensureLoaded();
    let secondDone = false;
    const second = store.ensureLoaded().then(() => (secondDone = true));
    expect(callsOf(listContexts)).toHaveLength(1);

    await settle();
    expect(secondDone).toBe(false);

    callsOf(listContexts)[0].resolve({ items: [context('home')] });
    await second;
    expect(names()).toEqual(['@home']);
    await first;
    expect(store.loaded()).toBe(true);
  });

  it('can retry ensureLoaded after a failed load', async () => {
    const failed = store.ensureLoaded();
    callsOf(listContexts)[0].reject(new Error('offline'));
    await failed;
    expect(store.loaded()).toBe(false);

    const retried = store.ensureLoaded();
    expect(callsOf(listContexts)).toHaveLength(2);
    callsOf(listContexts)[1].resolve({ items: [context('home')] });
    await retried;
    expect(store.loaded()).toBe(true);
  });

  it('updates optimistically (null clears the color) and takes the server answer', async () => {
    const home = context('home', { color: '#3366ff' });
    await loaded([home]);

    const saved = store.update(home, { name: '@house', color: null });
    expect(store.find('home')?.name).toBe('@house');
    expect(store.find('home')?.color).toBeUndefined();
    expect(callsOf(updateContext)[0].params).toEqual({
      id: 'home',
      'If-Match': '"1"',
      body: { name: '@house', color: null },
    });

    callsOf(updateContext)[0].resolve(context('home', { name: '@house', version: 2 }));
    await saved;
    expect(store.find('home')).toEqual(context('home', { name: '@house', version: 2 }));
  });

  it('rolls an update back on an error', async () => {
    const home = context('home', { color: '#3366ff' });
    await loaded([home]);

    const saved = store.update(home, { position: 5 });
    callsOf(updateContext)[0].reject(new Error('412'));
    await expect(saved).rejects.toThrow('412');
    expect(store.find('home')).toEqual(home);
  });

  it('reorders at once when a position changes', async () => {
    const a = context('a', { position: 1 });
    await loaded([a, context('b', { position: 2 })]);

    void store.update(a, { position: 3 });
    expect(store.items().map((c) => c.id)).toEqual(['b', 'a']);
  });

  it('removes a context at once, keeps it gone on success and restores it on an error', async () => {
    const home = context('home');
    const phone = context('phone', { position: 2 });
    await loaded([home, phone]);

    const failed = store.remove(home);
    expect(names()).toEqual(['@phone']);
    expect(callsOf(deleteContext)[0].params).toEqual({ id: 'home', 'If-Match': '"1"' });
    callsOf(deleteContext)[0].reject(new Error('500'));
    await expect(failed).rejects.toThrow('500');
    expect(names()).toEqual(['@home', '@phone']);

    const removed = store.remove(phone);
    callsOf(deleteContext)[1].resolve(undefined);
    await removed;
    expect(names()).toEqual(['@home']);
  });

  it('shows a created context once the server answered', async () => {
    await loaded([context('home')]);

    const created = store.create({ name: '@errands' });
    expect(names()).toEqual(['@home']);
    callsOf(createContext)[0].resolve(context('errands', { position: 2 }));
    await created;
    expect(names()).toEqual(['@home', '@errands']);
  });

  it('drops a context on its DELETED event, refetches on others, ignores task events', async () => {
    await loaded([context('home'), context('phone', { position: 2 })]);

    changes.next({ type: 'CONTEXT', op: 'DELETED', id: 'home', at: '2026-10-20T10:00:00Z' });
    expect(names()).toEqual(['@phone']);

    changes.next({ type: 'TASK', op: 'UPDATED', id: 't', at: '2026-10-20T10:00:01Z' });
    expect(callsOf(listContexts)).toHaveLength(1);

    changes.next({ type: 'CONTEXT', op: 'CREATED', id: 'x', at: '2026-10-20T10:00:02Z' });
    expect(callsOf(listContexts)).toHaveLength(2);

    resyncs.next();
    expect(callsOf(listContexts)).toHaveLength(3);
  });
});
