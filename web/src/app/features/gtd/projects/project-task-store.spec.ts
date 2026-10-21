import { TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import {
  Api,
  ChangeEvent,
  listTasks,
  orderProjectTasks,
  TaskResponse,
} from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { ProjectTaskStore } from './project-task-store';

/** A request the test answers when it wants to, so it controls the order of answers. */
interface Call {
  fn: unknown;
  params: Record<string, unknown>;
  resolve: (value: unknown) => void;
  reject: (error: unknown) => void;
}

function task(id: string, position: number, version = 1): TaskResponse {
  return {
    id,
    title: `Task ${id}`,
    list: 'NEXT',
    projectId: 'p1',
    position,
    contextIds: [],
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-01T08:00:00Z',
    version,
  };
}

/** The server's answer to an order request: the tasks with their new positions. */
function answer(ids: string[], version: number) {
  return { items: ids.map((id, position) => task(id, position, version)) };
}

const settle = () => new Promise((resolve) => setTimeout(resolve));

describe('ProjectTaskStore', () => {
  let calls: Call[];
  let store: ProjectTaskStore;

  const callsOf = (fn: unknown) => calls.filter((call) => call.fn === fn);
  const shown = () => store.ordered().map((t) => t.id);

  beforeEach(async () => {
    calls = [];
    const api = {
      invoke: (fn: unknown, params: Record<string, unknown> = {}) =>
        new Promise((resolve, reject) => calls.push({ fn, params, resolve, reject })),
    };
    TestBed.configureTestingModule({
      providers: [
        ProjectTaskStore,
        { provide: Api, useValue: api },
        {
          provide: ChangeEventsService,
          useValue: { resync$: new Subject<void>(), events$: () => new Subject<ChangeEvent>() },
        },
      ],
    });
    store = TestBed.inject(ProjectTaskStore);
    const loaded = store.load({ projectId: 'p1' });
    callsOf(listTasks)[0].resolve({ items: [task('t1', 0), task('t2', 1), task('t3', 2)] });
    await loaded;
  });

  it('shows the new order at once and sends it for the project', async () => {
    const saved = store.reorder('p1', ['t3', 't1', 't2']);

    expect(shown()).toEqual(['t3', 't1', 't2']);
    expect(callsOf(orderProjectTasks)[0].params).toEqual({
      id: 'p1',
      body: { taskIds: ['t3', 't1', 't2'] },
    });

    callsOf(orderProjectTasks)[0].resolve(answer(['t3', 't1', 't2'], 2));
    await saved;

    expect(shown()).toEqual(['t3', 't1', 't2']);
    expect(store.items().map((t) => [t.id, t.position, t.version])).toEqual([
      ['t3', 0, 2],
      ['t1', 1, 2],
      ['t2', 2, 2],
    ]);
  });

  it('rolls back to the previous order and rethrows when the server refuses', async () => {
    const saved = store.reorder('p1', ['t2', 't1', 't3']);
    expect(shown()).toEqual(['t2', 't1', 't3']);

    const refusal = new Error('412 Precondition Failed');
    callsOf(orderProjectTasks)[0].reject(refusal);

    await expect(saved).rejects.toBe(refusal);
    expect(shown()).toEqual(['t1', 't2', 't3']);
  });

  it('keeps showing a newer pending order when an older reorder fails', async () => {
    const first = store.reorder('p1', ['t2', 't1', 't3']);
    const second = store.reorder('p1', ['t3', 't2', 't1']);
    expect(shown()).toEqual(['t3', 't2', 't1']);

    callsOf(orderProjectTasks)[0].reject(new Error('network'));
    await expect(first).rejects.toThrow('network');
    expect(shown()).toEqual(['t3', 't2', 't1']);

    callsOf(orderProjectTasks)[1].resolve(answer(['t3', 't2', 't1'], 3));
    await second;
    expect(shown()).toEqual(['t3', 't2', 't1']);
  });

  it('keeps the newer order when the older answer arrives last', async () => {
    const first = store.reorder('p1', ['t2', 't1', 't3']);
    const second = store.reorder('p1', ['t3', 't2', 't1']);

    callsOf(orderProjectTasks)[1].resolve(answer(['t3', 't2', 't1'], 3));
    await second;
    callsOf(orderProjectTasks)[0].resolve(answer(['t2', 't1', 't3'], 2));
    await first;
    await settle();

    expect(shown()).toEqual(['t3', 't2', 't1']);
  });

  it('places tasks missing from the pending order after the ordered ones', () => {
    void store.reorder('p1', ['t3', 't1']);

    expect(shown()).toEqual(['t3', 't1', 't2']);
  });

  it('shows the loaded order while no reorder runs', () => {
    expect(shown()).toEqual(['t1', 't2', 't3']);
    expect(store.ordered()).toBe(store.items());
  });
});
