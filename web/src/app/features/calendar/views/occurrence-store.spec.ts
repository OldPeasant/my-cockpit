import { TestBed } from '@angular/core/testing';
import { filter, Subject } from 'rxjs';
import { Api, ChangeEvent, listEventOccurrences } from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { OccurrenceStore } from './occurrence-store';
import { fakeOccurrence as occurrence } from './testing';

/** A request the test answers when it wants to, so it controls the order of answers. */
interface Call {
  fn: unknown;
  params: Record<string, unknown>;
  resolve: (value: unknown) => void;
  reject: (error: unknown) => void;
}

const WEEK = { from: '2026-03-22T23:00:00.000Z', to: '2026-03-29T22:00:00.000Z' };
const NEXT_WEEK = { from: '2026-03-29T22:00:00.000Z', to: '2026-04-05T22:00:00.000Z' };

describe('OccurrenceStore', () => {
  let calls: Call[];
  let changes: Subject<ChangeEvent>;
  let resyncs: Subject<void>;
  let store: OccurrenceStore;

  const titles = () => store.items().map((o) => o.title);

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
        OccurrenceStore,
        { provide: Api, useValue: api },
        { provide: ChangeEventsService, useValue: events },
      ],
    });
    store = TestBed.inject(OccurrenceStore);
  });

  it('loads the occurrences of the range for the given calendars', async () => {
    const done = store.load(WEEK, ['work', 'family']);
    expect(store.loading()).toBe(true);
    expect(calls).toHaveLength(1);
    expect(calls[0].fn).toBe(listEventOccurrences);
    expect(calls[0].params).toEqual({ ...WEEK, calendarIds: ['work', 'family'] });

    calls[0].resolve({ items: [occurrence('Standup')] });
    await done;
    expect(titles()).toEqual(['Standup']);
    expect(store.loaded()).toBe(true);
    expect(store.loading()).toBe(false);
  });

  it('drops a slower answer for an earlier range (out of order)', async () => {
    const first = store.load(WEEK, ['work']);
    const second = store.load(NEXT_WEEK, ['work']);

    calls[1].resolve({ items: [occurrence('Next week')] });
    await second;
    calls[0].resolve({ items: [occurrence('This week')] });
    await first;

    expect(titles()).toEqual(['Next week']);
    expect(store.loading()).toBe(false);
  });

  it('keeps the previous occurrences while the next range loads', async () => {
    const first = store.load(WEEK, ['work']);
    calls[0].resolve({ items: [occurrence('This week')] });
    await first;

    void store.load(NEXT_WEEK, ['work']);
    expect(titles()).toEqual(['This week']);
  });

  it('asks nothing without calendars', async () => {
    await store.load(WEEK, []);
    expect(calls).toHaveLength(0);
    expect(titles()).toEqual([]);
    expect(store.loaded()).toBe(true);
  });

  it('keeps the error of the newest request only', async () => {
    const first = store.load(WEEK, ['work']);
    const second = store.load(NEXT_WEEK, ['work']);
    calls[0].reject(new Error('offline'));
    await first;
    expect(store.error()).toBeNull();

    calls[1].reject(new Error('offline'));
    await second;
    expect(store.error()).toBeInstanceOf(Error);

    const retry = store.refresh();
    calls[2].resolve({ items: [] });
    await retry;
    expect(store.error()).toBeNull();
  });

  it('refetches the range on an event change and on a resync', async () => {
    const first = store.load(WEEK, ['work']);
    calls[0].resolve({ items: [] });
    await first;

    changes.next({ type: 'EVENT', id: 'e1', op: 'CREATED', at: '2026-03-23T08:00:00Z' });
    expect(calls).toHaveLength(2);
    expect(calls[1].params).toEqual({ ...WEEK, calendarIds: ['work'] });

    changes.next({ type: 'TASK', id: 't1', op: 'CREATED', at: '2026-03-23T08:00:00Z' });
    expect(calls).toHaveLength(2);

    resyncs.next();
    expect(calls).toHaveLength(3);
  });

  it('does not fetch on events before the first load', () => {
    changes.next({ type: 'EVENT', id: 'e1', op: 'UPDATED', at: '2026-03-23T08:00:00Z' });
    resyncs.next();
    expect(calls).toHaveLength(0);
  });
});
