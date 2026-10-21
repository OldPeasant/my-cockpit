import { DOCUMENT, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { filter, Subject } from 'rxjs';
import { Api, ChangeEvent, listTasks, TaskResponse } from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { dayRange } from '../views/calendar-view';
import {
  OverlayTaskStore,
  TASK_OVERLAY_KEY,
  TaskOverlay,
  taskEvents,
  taskMarkOf,
} from './task-overlay';

const ZURICH = 'Europe/Zurich';

function task(id: string, fields: Partial<TaskResponse> = {}): TaskResponse {
  return {
    id,
    title: `Task ${id}`,
    list: 'NEXT',
    position: 1,
    contextIds: [],
    createdAt: '2026-03-01T08:00:00Z',
    updatedAt: '2026-03-01T08:00:00Z',
    version: 1,
    ...fields,
  };
}

const WEEK = { from: '2026-03-23', to: '2026-03-29' };

describe('taskEvents', () => {
  it('shows nothing for a completed task, even when it is due in the range', () => {
    const done = task('a', {
      dueDate: '2026-03-24',
      ticklerDate: '2026-03-25',
      completedAt: '2026-03-20T10:00:00Z',
    });
    expect(taskEvents(done, WEEK, ZURICH)).toEqual([]);
  });

  it('shows nothing for a task without dates or with dates outside the range', () => {
    expect(taskEvents(task('a'), WEEK, ZURICH)).toEqual([]);
    expect(
      taskEvents(task('b', { dueDate: '2026-03-22', ticklerDate: '2026-03-30' }), WEEK, ZURICH),
    ).toEqual([]);
  });

  it('includes both ends of the range', () => {
    expect(taskEvents(task('a', { dueDate: '2026-03-23' }), WEEK, ZURICH)).toHaveLength(1);
    expect(taskEvents(task('b', { ticklerDate: '2026-03-29' }), WEEK, ZURICH)).toHaveLength(1);
  });

  it('shows a task due and coming back from a tickler in the range twice, with distinct ids', () => {
    const both = task('a', {
      title: 'Renew passport',
      dueDate: '2026-03-27',
      ticklerDate: '2026-03-24',
    });
    const entries = taskEvents(both, WEEK, ZURICH);
    expect(entries).toEqual([
      expect.objectContaining({
        id: 'task-due-a',
        title: 'Renew passport',
        allDay: true,
        start: '2026-03-27',
        end: undefined,
        className: 'app-task-mark app-task-due',
        editable: false,
        extendedProps: { task: both, kind: 'due' },
      }),
      expect.objectContaining({
        id: 'task-tickler-a',
        allDay: true,
        start: '2026-03-24',
        className: 'app-task-mark app-task-tickler',
        editable: false,
        extendedProps: { task: both, kind: 'tickler' },
      }),
    ]);
  });

  it('shows only the part that lies in the range', () => {
    const entries = taskEvents(
      task('a', { dueDate: '2026-04-02', ticklerDate: '2026-03-25' }),
      WEEK,
      ZURICH,
    );
    expect(entries.map((entry) => entry.id)).toEqual(['task-tickler-a']);
  });

  it('puts a timed due task at its wall-clock time in the zone, for half an hour', () => {
    const call = task('a', { dueDate: '2026-03-24', dueTime: '09:15:00' });
    expect(taskEvents(call, WEEK, ZURICH)[0]).toMatchObject({
      allDay: false,
      start: '2026-03-24T08:15:00.000Z',
      end: '2026-03-24T08:45:00.000Z',
    });
    // The same wall-clock time in another zone is another instant.
    expect(taskEvents(call, WEEK, 'America/New_York')[0]).toMatchObject({
      start: '2026-03-24T13:15:00.000Z',
      end: '2026-03-24T13:45:00.000Z',
    });
  });

  it('follows the DST change in spring: CEST after it, a skipped time just after the change', () => {
    // 2026-03-29: Zurich clocks jump from 02:00 CET to 03:00 CEST.
    const after = task('a', { dueDate: '2026-03-29', dueTime: '09:00:00' });
    expect(taskEvents(after, WEEK, ZURICH)[0]).toMatchObject({
      start: '2026-03-29T07:00:00.000Z',
      end: '2026-03-29T07:30:00.000Z',
    });
    const skipped = task('b', { dueDate: '2026-03-29', dueTime: '02:30:00' });
    expect(taskEvents(skipped, WEEK, ZURICH)[0]).toMatchObject({
      start: '2026-03-29T01:30:00.000Z',
      end: '2026-03-29T02:00:00.000Z',
    });
  });

  it('takes the first of an ambiguous time in autumn', () => {
    // 2026-10-25: Zurich clocks go back from 03:00 CEST to 02:00 CET.
    const range = { from: '2026-10-19', to: '2026-10-25' };
    const ambiguous = task('a', { dueDate: '2026-10-25', dueTime: '02:30:00' });
    expect(taskEvents(ambiguous, range, ZURICH)[0]).toMatchObject({
      start: '2026-10-25T00:30:00.000Z',
      end: '2026-10-25T01:00:00.000Z',
    });
    const evening = task('b', { dueDate: '2026-10-25', dueTime: '18:00:00' });
    expect(taskEvents(evening, range, ZURICH)[0]).toMatchObject({
      start: '2026-10-25T17:00:00.000Z',
    });
  });
});

describe('taskMarkOf', () => {
  it('reads the mark of an overlay entry and nothing from an event entry', () => {
    const t = task('a', { dueDate: '2026-03-24' });
    const [entry] = taskEvents(t, WEEK, ZURICH);
    expect(taskMarkOf(entry.extendedProps as Record<string, unknown>)).toEqual({
      task: t,
      kind: 'due',
    });
    expect(taskMarkOf({ occurrence: { eventId: 'e1' } })).toBeUndefined();
    expect(taskMarkOf({ task: t })).toBeUndefined();
    expect(taskMarkOf({})).toBeUndefined();
  });
});

describe('dayRange', () => {
  it('gives the local days a day view shows on both DST change days', () => {
    // Spring: the day is 23 hours long.
    expect(
      dayRange(new Date('2026-03-28T23:00:00Z'), new Date('2026-03-29T22:00:00Z'), ZURICH),
    ).toEqual({ from: '2026-03-29', to: '2026-03-29' });
    // Autumn: the day is 25 hours long.
    expect(
      dayRange(new Date('2026-10-24T22:00:00Z'), new Date('2026-10-25T23:00:00Z'), ZURICH),
    ).toEqual({ from: '2026-10-25', to: '2026-10-25' });
  });
});

describe('TaskOverlay', () => {
  let items: ReturnType<typeof signal<TaskResponse[]>>;
  let store: {
    items: typeof items;
    error: ReturnType<typeof signal<unknown>>;
    load: ReturnType<typeof vi.fn>;
    refresh: ReturnType<typeof vi.fn>;
  };

  function create(storage?: Partial<Storage> | null): TaskOverlay {
    items = signal<TaskResponse[]>([]);
    store = {
      items,
      error: signal<unknown>(null),
      load: vi.fn(() => Promise.resolve()),
      refresh: vi.fn(() => Promise.resolve()),
    };
    const providers: unknown[] = [TaskOverlay, { provide: OverlayTaskStore, useValue: store }];
    if (storage !== undefined) {
      providers.push({
        provide: DOCUMENT,
        useValue: { defaultView: storage === null ? null : { localStorage: storage } },
      });
    }
    TestBed.configureTestingModule({ providers: providers as never[] });
    return TestBed.inject(TaskOverlay);
  }

  beforeEach(() => localStorage.removeItem(TASK_OVERLAY_KEY));
  afterEach(() => localStorage.removeItem(TASK_OVERLAY_KEY));

  it('is off by default, requests nothing and has no entries', () => {
    const overlay = create();
    overlay.show(WEEK);
    items.set([task('a', { dueDate: '2026-03-24' })]);

    expect(overlay.enabled()).toBe(false);
    expect(store.load).not.toHaveBeenCalled();
    expect(overlay.entries(ZURICH)).toEqual([]);
  });

  it('starts on when the browser remembered it, and loads the first shown range', () => {
    localStorage.setItem(TASK_OVERLAY_KEY, 'true');
    const overlay = create();
    expect(overlay.enabled()).toBe(true);
    overlay.show(WEEK);
    expect(store.load).toHaveBeenCalledExactlyOnceWith({ from: '2026-03-23', to: '2026-03-29' });
  });

  it('treats any other stored value as off', () => {
    localStorage.setItem(TASK_OVERLAY_KEY, 'yes');
    expect(create().enabled()).toBe(false);
  });

  it('turned on before a range is shown requests nothing until one is', () => {
    const overlay = create();
    overlay.setEnabled(true);
    expect(store.load).not.toHaveBeenCalled();
    expect(overlay.entries(ZURICH)).toEqual([]);

    overlay.show(WEEK);
    expect(store.load).toHaveBeenCalledExactlyOnceWith(WEEK);
  });

  it('does not refetch when the same range is shown again, but does for another one', () => {
    const overlay = create();
    overlay.setEnabled(true);
    overlay.show(WEEK);
    overlay.show({ ...WEEK });
    expect(store.load).toHaveBeenCalledTimes(1);

    overlay.show({ from: '2026-03-30', to: '2026-04-05' });
    expect(store.load).toHaveBeenCalledTimes(2);
    expect(store.load).toHaveBeenLastCalledWith({ from: '2026-03-30', to: '2026-04-05' });
  });

  it('turned off sends no request, hides the entries and remembers it', () => {
    const overlay = create();
    overlay.show(WEEK);
    overlay.setEnabled(true);
    items.set([task('a', { dueDate: '2026-03-24' })]);
    expect(overlay.entries(ZURICH)).toHaveLength(1);
    store.load.mockClear();

    overlay.setEnabled(false);
    expect(store.load).not.toHaveBeenCalled();
    expect(overlay.entries(ZURICH)).toEqual([]);
    expect(localStorage.getItem(TASK_OVERLAY_KEY)).toBe('false');

    // A range shown while off loads nothing either.
    overlay.show({ from: '2026-03-30', to: '2026-04-05' });
    expect(store.load).not.toHaveBeenCalled();
  });

  it('builds the entries of the loaded tasks within the shown range', () => {
    const overlay = create();
    overlay.setEnabled(true);
    overlay.show(WEEK);
    items.set([
      task('due', { dueDate: '2026-03-24' }),
      task('later', { dueDate: '2026-04-24' }),
      task('done', { dueDate: '2026-03-24', completedAt: '2026-03-20T10:00:00Z' }),
    ]);
    expect(overlay.entries(ZURICH).map((entry) => entry.id)).toEqual(['task-due-due']);
  });

  it('swallows a failed request (the store keeps the error) so the calendar keeps working', async () => {
    const overlay = create();
    const error = new Error('offline');
    store.load.mockReturnValueOnce(Promise.reject(error));
    store.error.set(error);
    overlay.show(WEEK);
    overlay.setEnabled(true);
    // No unhandled rejection: let the rejected promise settle.
    await new Promise((resolve) => setTimeout(resolve));
    expect(overlay.error()).toBe(error);
    expect(overlay.enabled()).toBe(true);
  });

  it('refreshes through the store', async () => {
    const overlay = create();
    await overlay.refresh();
    expect(store.refresh).toHaveBeenCalledTimes(1);
  });

  it('starts off and still toggles when storage reads and writes throw', () => {
    const blocked: Partial<Storage> = {
      getItem: vi.fn(() => {
        throw new DOMException('denied', 'SecurityError');
      }),
      setItem: vi.fn(() => {
        throw new DOMException('full', 'QuotaExceededError');
      }),
    };
    const overlay = create(blocked);
    expect(overlay.enabled()).toBe(false);

    overlay.show(WEEK);
    expect(() => overlay.setEnabled(true)).not.toThrow();
    expect(blocked.setItem).toHaveBeenCalledWith(TASK_OVERLAY_KEY, 'true');
    expect(overlay.enabled()).toBe(true);
    expect(store.load).toHaveBeenCalledExactlyOnceWith(WEEK);
  });

  it('works without a window (no storage at all)', () => {
    const overlay = create(null);
    expect(overlay.enabled()).toBe(false);
    overlay.show(WEEK);
    overlay.setEnabled(true);
    expect(overlay.enabled()).toBe(true);
    expect(store.load).toHaveBeenCalledTimes(1);
  });
});

describe('TaskOverlay with its OverlayTaskStore', () => {
  let changes: Subject<ChangeEvent>;
  let resyncs: Subject<void>;
  let invoke: ReturnType<typeof vi.fn>;

  /** Lets pending promise callbacks run. */
  const settle = () => new Promise((resolve) => setTimeout(resolve));
  const taskRequests = () => invoke.mock.calls.filter(([fn]) => fn === listTasks);

  function create(): TaskOverlay {
    changes = new Subject();
    resyncs = new Subject();
    invoke = vi.fn(() => Promise.resolve({ items: [task('a', { dueDate: '2026-03-24' })] }));
    TestBed.configureTestingModule({
      providers: [
        TaskOverlay,
        OverlayTaskStore,
        { provide: Api, useValue: { invoke } },
        {
          provide: ChangeEventsService,
          useValue: {
            resync$: resyncs.asObservable(),
            events$: (type: string) => changes.pipe(filter((event) => event.type === type)),
          },
        },
      ],
    });
    return TestBed.inject(TaskOverlay);
  }

  beforeEach(() => localStorage.removeItem(TASK_OVERLAY_KEY));
  afterEach(() => localStorage.removeItem(TASK_OVERLAY_KEY));

  it('refetches on task changes and resyncs while on', async () => {
    const overlay = create();
    overlay.show(WEEK);
    overlay.setEnabled(true);
    await settle();
    expect(taskRequests()).toHaveLength(1);
    expect(taskRequests()[0][1]).toMatchObject(WEEK);
    expect(overlay.entries(ZURICH)).toHaveLength(1);

    changes.next({ type: 'TASK', op: 'UPDATED', id: 'a', at: '2026-03-24T10:00:00Z' });
    resyncs.next();
    await settle();
    expect(taskRequests()).toHaveLength(3);
  });

  it('turned off fetches nothing on task changes, resyncs or refresh; turned on loads again', async () => {
    const overlay = create();
    overlay.show(WEEK);
    overlay.setEnabled(true);
    await settle();
    overlay.setEnabled(false);
    invoke.mockClear();

    changes.next({ type: 'TASK', op: 'CREATED', id: 'b', at: '2026-03-24T10:00:00Z' });
    changes.next({ type: 'TASK', op: 'UPDATED', id: 'a', at: '2026-03-24T10:00:01Z' });
    resyncs.next();
    await overlay.refresh();
    await settle();
    expect(taskRequests()).toHaveLength(0);
    expect(overlay.entries(ZURICH)).toEqual([]);

    overlay.setEnabled(true);
    await settle();
    expect(taskRequests()).toHaveLength(1);
    expect(taskRequests()[0][1]).toMatchObject(WEEK);
    expect(overlay.entries(ZURICH)).toHaveLength(1);
  });

  it('starts on when the browser remembered it, and follows changes', async () => {
    localStorage.setItem(TASK_OVERLAY_KEY, 'true');
    const overlay = create();
    overlay.show(WEEK);
    await settle();
    changes.next({ type: 'TASK', op: 'UPDATED', id: 'a', at: '2026-03-24T10:00:00Z' });
    await settle();
    expect(taskRequests()).toHaveLength(2);
  });
});
