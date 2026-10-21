import { TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { Api, AreaResponse, ChangeEvent, listAreas } from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { AreaStore } from './area-store';

interface Call {
  fn: unknown;
  resolve: (value: unknown) => void;
  reject: (error: unknown) => void;
}

function area(id: string, position: number): AreaResponse {
  return { id, title: `Area ${id}`, position, version: 1 };
}

describe('AreaStore', () => {
  let calls: Call[];
  let store: AreaStore;

  beforeEach(() => {
    calls = [];
    const api = {
      invoke: (fn: unknown) =>
        new Promise((resolve, reject) => calls.push({ fn, resolve, reject })),
    };
    TestBed.configureTestingModule({
      providers: [
        { provide: Api, useValue: api },
        {
          provide: ChangeEventsService,
          useValue: { resync$: new Subject<void>(), events$: () => new Subject<ChangeEvent>() },
        },
      ],
    });
    store = TestBed.inject(AreaStore);
  });

  it('loads the areas once for concurrent callers, in their position order', async () => {
    const first = store.ensureLoaded();
    const second = store.ensureLoaded();

    expect(calls).toHaveLength(1);
    expect(calls[0].fn).toBe(listAreas);
    calls[0].resolve({ items: [area('work', 2), area('home', 0), area('health', 1)] });
    await Promise.all([first, second]);

    expect(store.loaded()).toBe(true);
    expect(store.items().map((a) => a.id)).toEqual(['home', 'health', 'work']);
    expect(store.byId().get('work')?.title).toBe('Area work');
  });

  it('does not fetch again once loaded', async () => {
    const first = store.ensureLoaded();
    calls[0].resolve({ items: [area('home', 0)] });
    await first;

    await store.ensureLoaded();

    expect(calls).toHaveLength(1);
  });

  it('fetches again after a failed load', async () => {
    const first = store.ensureLoaded();
    calls[0].reject(new Error('503'));
    await first;
    expect(store.loaded()).toBe(false);
    expect(store.error()).toBeInstanceOf(Error);

    const retry = store.ensureLoaded();
    expect(calls).toHaveLength(2);
    calls[1].resolve({ items: [] });
    await retry;

    expect(store.loaded()).toBe(true);
    expect(store.error()).toBeNull();
    expect(store.items()).toEqual([]);
  });
});
