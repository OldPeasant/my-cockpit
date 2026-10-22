import { HttpContext } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { filter, Subject } from 'rxjs';
import {
  Api,
  ChangeEvent,
  getWeeklyReviewStatus,
  listBookmarks,
  listEventOccurrences,
  listHabits,
  listMailMessages,
  listNotes,
  listProjects,
  listTasks,
} from '../../api/generated';
import { ChangeEventsService } from '../../core/events';
import { SILENT_ERRORS } from '../../core/http';
import { bookmark } from '../bookmarks/testing';
import { fakeOccurrence } from '../calendar/views/testing';
import { habit } from '../habits/testing';
import { DashboardData, PAGE, UPCOMING_DAYS } from './dashboard-data';
import { WIDGET_IDS, WidgetId } from './dashboard-layout';

type Params = Record<string, unknown>;
type Handler = (params: Params) => Promise<unknown>;

/** A promise the test resolves or rejects when it wants, to control the order of answers. */
function deferred<T>(): {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (error: unknown) => void;
} {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const task = (id: string, fields: Record<string, unknown> = {}) => ({
  id,
  title: `Task ${id}`,
  list: 'NEXT',
  contextIds: [],
  position: 0,
  createdAt: '2026-10-01T08:00:00Z',
  updatedAt: '2026-10-01T08:00:00Z',
  version: 1,
  ...fields,
});

const note = (id: string) => ({
  id,
  title: `Note ${id}`,
  displayTitle: `Note ${id}`,
  bodyMd: '',
  tags: [],
  pinned: true,
  archived: false,
  createdAt: '2026-10-01T08:00:00Z',
  updatedAt: '2026-10-01T08:00:00Z',
  version: 1,
});

/** Lets the pending promise callbacks run (timers are fake). */
const flush = () => vi.advanceTimersByTimeAsync(0);

describe('DashboardData', () => {
  let handlers: Map<unknown, Handler>;
  let invoke: ReturnType<typeof vi.fn>;
  let changes: Subject<ChangeEvent>;
  let resyncs: Subject<void>;
  let data: DashboardData;

  /** The answer of `listTasks` for the given `due`/`list` params. */
  let taskAnswers: (params: Params) => Promise<unknown>;

  beforeEach(() => {
    vi.useFakeTimers({
      toFake: ['Date', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'],
    });
    vi.setSystemTime(new Date(2026, 9, 22, 9, 30));
    changes = new Subject();
    resyncs = new Subject();
    taskAnswers = () => Promise.resolve({ items: [] });
    handlers = new Map<unknown, Handler>([
      [listTasks, (params) => taskAnswers(params)],
      [listEventOccurrences, () => Promise.resolve({ items: [] })],
      [listHabits, () => Promise.resolve({ items: [] })],
      [listMailMessages, () => Promise.resolve({ items: [] })],
      [listProjects, () => Promise.resolve({ items: [] })],
      [listBookmarks, () => Promise.resolve({ items: [] })],
      [listNotes, () => Promise.resolve({ items: [] })],
      [getWeeklyReviewStatus, () => Promise.resolve({})],
    ]);
    invoke = vi.fn((fn: unknown, params: Params) => {
      const handler = handlers.get(fn);
      return handler ? handler(params) : Promise.reject(new Error('unexpected call'));
    });
    TestBed.configureTestingModule({
      providers: [
        DashboardData,
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
    data = TestBed.inject(DashboardData);
  });

  afterEach(() => vi.useRealTimers());

  function callsOf(fn: unknown): Params[] {
    return invoke.mock.calls.filter(([called]) => called === fn).map(([, params]) => params);
  }

  function change(type: string): void {
    changes.next({ op: 'UPDATED', type, id: 'x1' } as ChangeEvent);
  }

  describe('Today', () => {
    it('lists overdue then due-today tasks, each only once', async () => {
      taskAnswers = (params) =>
        Promise.resolve({
          items:
            params['due'] === 'overdue'
              ? [task('t1', { dueDate: '2026-10-20' }), task('t2', { dueDate: '2026-10-21' })]
              : [task('t2', { dueDate: '2026-10-21' }), task('t3', { dueDate: '2026-10-22' })],
        });

      data.show(['today']);
      await flush();

      const state = data.state('today');
      expect(state.status).toBe('ready');
      if (state.status !== 'ready') return;
      expect(state.value.tasks.map((t) => t.id)).toEqual(['t1', 't2', 't3']);
      expect(callsOf(listTasks)).toEqual([
        { due: 'overdue', limit: PAGE },
        { due: 'today', limit: PAGE },
      ]);
    });

    it("counts today's due habits and how many of them are done, ignoring the others", async () => {
      handlers.set(listHabits, () =>
        Promise.resolve({
          items: [
            habit('h1', { stats: { dueToday: true, doneToday: true } }),
            habit('h2', { stats: { dueToday: true, doneToday: false } }),
            habit('h3', { stats: { dueToday: true, doneToday: true } }),
            habit('h4', { stats: { dueToday: false, doneToday: true } }),
            habit('h5', { stats: { dueToday: false, doneToday: false } }),
          ],
        }),
      );

      data.show(['today']);
      await flush();

      const state = data.state('today');
      expect(state).toMatchObject({ status: 'ready', value: { habitsDue: 3, habitsDone: 2 } });
      expect(callsOf(listHabits)).toEqual([{ archived: false }]);
    });

    it("asks for today's occurrences from local midnight and leaves cancelled ones out", async () => {
      handlers.set(listEventOccurrences, () =>
        Promise.resolve({
          items: [
            fakeOccurrence('Standup'),
            fakeOccurrence('Cancelled lunch', { status: 'CANCELLED' }),
            fakeOccurrence('Maybe call', { status: 'TENTATIVE' }),
          ],
        }),
      );

      data.show(['today']);
      await flush();

      expect(callsOf(listEventOccurrences)).toEqual([
        {
          from: new Date(2026, 9, 22).toISOString(),
          to: new Date(2026, 9, 23).toISOString(),
        },
      ]);
      const state = data.state('today');
      if (state.status !== 'ready') throw new Error(`not ready: ${state.status}`);
      expect(state.value.events.map((e) => e.title)).toEqual(['Standup', 'Maybe call']);
    });

    it('fails as a whole when one of its sources fails', async () => {
      handlers.set(listHabits, () => Promise.reject(new Error('habits down')));

      data.show(['today']);
      await flush();

      expect(data.state('today').status).toBe('error');
    });
  });

  describe('the other widgets', () => {
    it('asks for the upcoming events from now until midnight in seven days, without cancelled ones', async () => {
      handlers.set(listEventOccurrences, () =>
        Promise.resolve({
          items: [
            fakeOccurrence('Dentist'),
            fakeOccurrence('Cancelled trip', { status: 'CANCELLED' }),
          ],
        }),
      );

      data.show(['upcoming']);
      await flush();

      expect(UPCOMING_DAYS).toBe(7);
      expect(callsOf(listEventOccurrences)).toEqual([
        {
          from: new Date(2026, 9, 22, 9, 30).toISOString(),
          to: new Date(2026, 9, 29).toISOString(),
        },
      ]);
      const state = data.state('upcoming');
      if (state.status !== 'ready') throw new Error(`not ready: ${state.status}`);
      expect(state.value.map((e) => e.title)).toEqual(['Dentist']);
    });

    it('counts the Inbox tasks and the unread mail, flagging cut pages', async () => {
      taskAnswers = () => Promise.resolve({ items: [task('i1'), task('i2')], nextCursor: 'next' });
      handlers.set(listMailMessages, () =>
        Promise.resolve({
          items: [{ seen: false }, { seen: true }, { seen: false }, { seen: false }],
          nextCursor: null,
        }),
      );

      data.show(['inbox']);
      await flush();

      expect(data.state('inbox')).toEqual({
        status: 'ready',
        value: { tasks: { count: 2, more: true }, unreadMail: { count: 3, more: false } },
      });
      expect(callsOf(listTasks)).toEqual([{ list: 'INBOX', limit: PAGE }]);
      expect(callsOf(listMailMessages)).toEqual([{ limit: PAGE }]);
    });

    it('counts no unread mail when the mail page has no items', async () => {
      handlers.set(listMailMessages, () => Promise.resolve({ nextCursor: 'more' }));

      data.show(['inbox']);
      await flush();

      expect(data.state('inbox')).toEqual({
        status: 'ready',
        value: { tasks: { count: 0, more: false }, unreadMail: { count: 0, more: true } },
      });
    });

    it('loads overdue Waiting For tasks, stalled projects, the review status, read-later and pinned notes', async () => {
      taskAnswers = () => Promise.resolve({ items: [task('w1')] });
      handlers.set(listProjects, () => Promise.resolve({ items: [{ id: 'p1', title: 'Garden' }] }));
      handlers.set(getWeeklyReviewStatus, () => Promise.resolve({ daysSinceLastReview: 4 }));
      handlers.set(listBookmarks, () =>
        Promise.resolve({ items: [bookmark('b1')], nextCursor: 'next' }),
      );
      handlers.set(listNotes, () => Promise.resolve({ items: [note('n1')] }));

      data.show(['waitingOverdue', 'stalledProjects', 'weeklyReview', 'readLater', 'pinnedNotes']);
      await flush();

      expect(callsOf(listTasks)).toEqual([{ list: 'WAITING', due: 'overdue', limit: PAGE }]);
      expect(callsOf(listProjects)).toEqual([{ status: 'ACTIVE', stalled: true, limit: PAGE }]);
      expect(callsOf(listBookmarks)).toEqual([{ readLater: true, read: false, limit: PAGE }]);
      expect(callsOf(listNotes)).toEqual([{ pinned: true, limit: PAGE }]);
      expect(data.state('waitingOverdue')).toMatchObject({
        status: 'ready',
        value: [{ id: 'w1' }],
      });
      expect(data.state('stalledProjects')).toEqual({
        status: 'ready',
        value: [{ id: 'p1', title: 'Garden' }],
      });
      expect(data.state('weeklyReview')).toEqual({
        status: 'ready',
        value: { daysSinceLastReview: 4 },
      });
      expect(data.state('readLater')).toMatchObject({
        status: 'ready',
        value: { items: [{ id: 'b1' }], more: true },
      });
      expect(data.state('pinnedNotes')).toMatchObject({
        status: 'ready',
        value: { items: [{ id: 'n1' }], more: false },
      });
    });

    it('sends every request with silent errors, so failures are not toasted', async () => {
      data.show([...WIDGET_IDS]);
      await flush();

      expect(invoke).toHaveBeenCalled();
      for (const [, , context] of invoke.mock.calls as [unknown, unknown, HttpContext][]) {
        expect(context.get(SILENT_ERRORS)).toBe(true);
      }
    });
  });

  describe('loading', () => {
    it('is loading before the first answer, and loads only the shown widgets', async () => {
      const notes = deferred<unknown>();
      handlers.set(listNotes, () => notes.promise);

      data.show(['pinnedNotes']);
      expect(data.state('pinnedNotes').status).toBe('loading');
      expect(data.state('today').status).toBe('loading');
      expect(invoke.mock.calls.map(([fn]) => fn)).toEqual([listNotes]);

      notes.resolve({ items: [] });
      await flush();
      expect(data.state('pinnedNotes').status).toBe('ready');
    });

    it('keeps one widget working while another fails', async () => {
      handlers.set(listNotes, () => Promise.reject(new Error('notes down')));

      data.show(['pinnedNotes', 'readLater']);
      await flush();

      expect(data.state('pinnedNotes').status).toBe('error');
      expect(data.state('readLater').status).toBe('ready');
    });

    it('does not load a ready widget again when shown again, but retries a failed one', async () => {
      let notesFail = true;
      handlers.set(listNotes, () =>
        notesFail ? Promise.reject(new Error('notes down')) : Promise.resolve({ items: [] }),
      );
      data.show(['pinnedNotes', 'readLater']);
      await flush();
      invoke.mockClear();

      notesFail = false;
      data.show(['readLater', 'pinnedNotes']);
      expect(data.state('pinnedNotes').status).toBe('loading');
      await flush();

      expect(invoke.mock.calls.map(([fn]) => fn)).toEqual([listNotes]);
      expect(data.state('pinnedNotes').status).toBe('ready');
    });

    it('drops an older answer that arrives after a newer one', async () => {
      const answers = [deferred<unknown>(), deferred<unknown>()];
      let call = 0;
      handlers.set(listNotes, () => answers[call++].promise);

      void data.load('pinnedNotes');
      void data.load('pinnedNotes');
      answers[1].resolve({ items: [note('new')] });
      await flush();
      answers[0].resolve({ items: [note('old')] });
      await flush();

      expect(data.state('pinnedNotes')).toMatchObject({
        status: 'ready',
        value: { items: [{ id: 'new' }] },
      });
    });

    it('drops an older failure that arrives after a newer answer', async () => {
      const answers = [deferred<unknown>(), deferred<unknown>()];
      let call = 0;
      handlers.set(listNotes, () => answers[call++].promise);

      void data.load('pinnedNotes');
      void data.load('pinnedNotes');
      answers[1].resolve({ items: [note('new')] });
      await flush();
      answers[0].reject(new Error('late failure'));
      await flush();

      expect(data.state('pinnedNotes')).toMatchObject({
        status: 'ready',
        value: { items: [{ id: 'new' }] },
      });
    });

    it('drops an older answer when the newer request fails', async () => {
      const answers = [deferred<unknown>(), deferred<unknown>()];
      let call = 0;
      handlers.set(listNotes, () => answers[call++].promise);

      void data.load('pinnedNotes');
      void data.load('pinnedNotes');
      answers[1].reject(new Error('newer failed'));
      await flush();
      answers[0].resolve({ items: [note('old')] });
      await flush();

      expect(data.state('pinnedNotes').status).toBe('error');
    });
  });

  describe('reloading', () => {
    async function showReady(ids: WidgetId[]): Promise<void> {
      data.show(ids);
      await flush();
      for (const id of ids) expect(data.state(id).status).toBe('ready');
      invoke.mockClear();
    }

    it('reloads a shown widget once after a burst of change events of its types', async () => {
      await showReady(['stalledProjects']);

      change('PROJECT');
      change('TASK');
      change('PROJECT');
      await vi.advanceTimersByTimeAsync(299);
      expect(callsOf(listProjects)).toHaveLength(0);
      await vi.advanceTimersByTimeAsync(1);

      expect(callsOf(listProjects)).toHaveLength(1);
    });

    it('keeps the shown data while reloading, and keeps it when the reload fails', async () => {
      handlers.set(listNotes, () => Promise.resolve({ items: [note('n1')] }));
      await showReady(['pinnedNotes']);
      const reload = deferred<unknown>();
      handlers.set(listNotes, () => reload.promise);

      change('NOTE');
      await vi.advanceTimersByTimeAsync(300);
      expect(callsOf(listNotes)).toHaveLength(1);
      expect(data.state('pinnedNotes')).toMatchObject({ status: 'ready' });
      reload.reject(new Error('notes down'));
      await flush();

      expect(data.state('pinnedNotes')).toMatchObject({
        status: 'ready',
        value: { items: [{ id: 'n1' }] },
      });
    });

    it('reloads a failed widget visibly after a change event', async () => {
      handlers.set(listNotes, () => Promise.reject(new Error('notes down')));
      data.show(['pinnedNotes']);
      await flush();
      expect(data.state('pinnedNotes').status).toBe('error');
      const reload = deferred<unknown>();
      handlers.set(listNotes, () => reload.promise);

      change('NOTE');
      await vi.advanceTimersByTimeAsync(300);
      expect(data.state('pinnedNotes').status).toBe('loading');
      reload.resolve({ items: [] });
      await flush();

      expect(data.state('pinnedNotes').status).toBe('ready');
    });

    it('does not reload hidden widgets or widgets of other types', async () => {
      await showReady(['readLater']);

      change('NOTE');
      change('WEEKLY_REVIEW');
      change('CONTACT');
      await vi.advanceTimersByTimeAsync(300);

      expect(invoke).not.toHaveBeenCalled();
    });

    it('does not reload a widget hidden since it was shown', async () => {
      await showReady(['readLater', 'pinnedNotes']);
      data.show(['readLater']);

      change('NOTE');
      await vi.advanceTimersByTimeAsync(300);

      expect(invoke).not.toHaveBeenCalled();
    });

    it.each([
      ['today', 'HABIT', listHabits],
      ['today', 'EVENT', listEventOccurrences],
      ['inbox', 'MAIL_FOLDER', listMailMessages],
      ['upcoming', 'CALENDAR', listEventOccurrences],
      ['waitingOverdue', 'TASK', listTasks],
      ['weeklyReview', 'WEEKLY_REVIEW', getWeeklyReviewStatus],
      ['readLater', 'BOOKMARK', listBookmarks],
    ] as [WidgetId, string, unknown][])('reloads %s after a %s change', async (id, type, fn) => {
      await showReady([id]);

      change(type);
      await vi.advanceTimersByTimeAsync(300);

      expect(callsOf(fn).length).toBeGreaterThan(0);
    });

    it('reloads every shown widget, and only those, after a resync', async () => {
      await showReady(['readLater', 'weeklyReview']);

      resyncs.next();
      await flush();

      const called = invoke.mock.calls.map(([fn]) => fn);
      expect(called).toHaveLength(2);
      expect(called).toEqual(expect.arrayContaining([listBookmarks, getWeeklyReviewStatus]));
      expect(data.state('readLater').status).toBe('ready');
    });

    it('stops reloading once destroyed', async () => {
      await showReady(['readLater']);
      TestBed.resetTestingModule();

      change('BOOKMARK');
      resyncs.next();
      await vi.advanceTimersByTimeAsync(300);

      expect(invoke).not.toHaveBeenCalled();
    });
  });
});
