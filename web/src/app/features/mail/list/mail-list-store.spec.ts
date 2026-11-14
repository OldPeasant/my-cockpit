import { TestBed } from '@angular/core/testing';
import { ChangeEvent } from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { ApiError, NotificationService } from '../../../core/http';
import {
  MAIL_FOLDER_EVENT,
  MailListStore,
  REFRESH_DEBOUNCE_MS,
  THREAD_LOOKUP_CONCURRENCY,
  withAction,
} from './mail-list-store';
import { FakeEvents, FakeMail, INBOX, OTHER, summary, toTaskResult } from './mail-testing';
import {
  MAIL_MAX_ACTION_IDS,
  MAIL_MAX_PAGE_SIZE,
  MAIL_PAGE_SIZE,
  MailScope,
  MailService,
  MailSummary,
} from './mail.service';

const flush = async () => {
  for (let i = 0; i < 5; i++) await Promise.resolve();
};

describe('MailListStore', () => {
  let mail: FakeMail;
  let events: FakeEvents;
  let errors: string[];
  let store: MailListStore;

  beforeEach(() => {
    mail = new FakeMail();
    events = new FakeEvents();
    errors = [];
    TestBed.configureTestingModule({
      providers: [
        MailListStore,
        { provide: MailService, useValue: mail },
        { provide: ChangeEventsService, useValue: events },
        {
          provide: NotificationService,
          useValue: { error: (m: string) => errors.push(m), info: () => undefined },
        },
      ],
    });
    store = TestBed.inject(MailListStore);
  });

  async function loaded(
    items: MailSummary[],
    scope: MailScope = { folderId: INBOX, threaded: false },
    nextCursor: string | null = null,
  ) {
    const done = store.load(scope);
    mail.lists.at(-1)!.resolve({ items, nextCursor });
    await done;
  }

  const ids = () => (store.items() ?? []).map((item) => item.id);

  it('loads the first page and puts the cursor on the newest item', async () => {
    await loaded([summary('a'), summary('b')]);
    expect(ids()).toEqual(['a', 'b']);
    expect(store.cursorId()).toBe('a');
    expect(mail.lists[0].scope).toEqual({ folderId: INBOX, threaded: false });
  });

  it('shows a load error', async () => {
    const done = store.load({ folderId: null, threaded: false });
    mail.lists[0].reject(new ApiError(502, 'Bad gateway', 'Mail server down'));
    await done;
    expect(store.loadError()).toBe('Mail server down');
    expect(store.items()).toBeNull();
  });

  it('appends the next page without duplicates', async () => {
    await loaded([summary('a'), summary('b')], undefined, 'c1');
    const more = store.loadMore();
    expect(mail.lists[1].cursor).toBe('c1');
    mail.lists[1].resolve({ items: [summary('b'), summary('c')], nextCursor: null });
    await more;
    expect(ids()).toEqual(['a', 'b', 'c']);
    expect(store.nextCursor()).toBeNull();
  });

  describe('cursor and selection', () => {
    beforeEach(() => loaded([summary('a'), summary('b'), summary('c')]));

    it('moves the cursor with bounds', () => {
      store.moveCursor(-1);
      expect(store.cursorId()).toBe('a');
      store.moveCursor(1);
      store.moveCursor(1);
      store.moveCursor(1);
      expect(store.cursorId()).toBe('c');
    });

    it('targets the selection, else the cursor item', () => {
      expect(store.targets().map((item) => item.id)).toEqual(['a']);
      store.toggleSelected('c');
      store.toggleSelected('b');
      expect(store.targets().map((item) => item.id)).toEqual(['b', 'c']);
      store.toggleSelected('b');
      expect(store.targets().map((item) => item.id)).toEqual(['c']);
    });

    it('selects all and nothing', () => {
      store.toggleAll();
      expect(store.allSelected()).toBe(true);
      store.toggleAll();
      expect(store.selection()).toEqual([]);
      store.toggleSelected('a');
      store.clearSelection();
      expect(store.selection()).toEqual([]);
    });
  });

  describe('optimistic actions', () => {
    beforeEach(() => loaded([summary('a'), summary('b'), summary('c')]));

    it('marks read at once and keeps it on success', async () => {
      const done = store.apply('READ');
      await flush();
      expect(store.items()![0].seen).toBe(true);
      expect(mail.applies[0]).toMatchObject({ action: 'READ', ids: ['a'] });
      mail.succeed();
      expect(await done).toBe(true);
      expect(store.items()![0].seen).toBe(true);
      expect(errors).toEqual([]);
    });

    it('removes archived items, moves the cursor on and clears their selection', async () => {
      store.toggleSelected('a');
      store.toggleSelected('b');
      const done = store.apply('ARCHIVE');
      await flush();
      expect(ids()).toEqual(['c']);
      expect(store.cursorId()).toBe('c');
      expect(store.selection()).toEqual([]);
      mail.succeed();
      expect(await done).toBe(true);
    });

    it('rolls back only the failed items, at their old place, and shows the error', async () => {
      store.toggleSelected('a');
      store.toggleSelected('b');
      const done = store.apply('ARCHIVE');
      await flush();
      mail.succeed(0, ['a'], 'No Archive folder');
      expect(await done).toBe(false);
      expect(ids()).toEqual(['a', 'c']);
      expect(errors).toHaveLength(1);
      expect(errors[0]).toContain('No Archive folder');
    });

    it('rolls back everything when the request fails', async () => {
      store.toggleSelected('b');
      const done = store.apply('FLAG');
      await flush();
      expect(store.items()![1].flagged).toBe(true);
      mail.applies[0].reject(new Error('offline'));
      expect(await done).toBe(false);
      expect(store.items()![1].flagged).toBe(false);
      expect(errors).toHaveLength(1);
    });

    it('sends MOVE with the target folder', async () => {
      const done = store.apply('MOVE', OTHER);
      await flush();
      expect(mail.applies[0]).toMatchObject({ action: 'MOVE', ids: ['a'], target: OTHER });
      mail.succeed();
      await done;
      expect(ids()).toEqual(['b', 'c']);
    });

    it('does nothing without targets', async () => {
      await loaded([]);
      expect(await store.apply('READ')).toBe(false);
      expect(mail.applies).toEqual([]);
    });
  });

  describe('next action & archive', () => {
    beforeEach(() => loaded([summary('a'), summary('b'), summary('c'), summary('d')]));

    it('sends the selection in list order and removes the archived mails', async () => {
      store.toggleSelected('c');
      store.toggleSelected('a');
      const done = store.toNextActions();
      await flush();
      expect(mail.toTasks.map((call) => call.ids)).toEqual([['a', 'c']]);
      // Nothing leaves the list before the server answered.
      expect(ids()).toEqual(['a', 'b', 'c', 'd']);
      mail.toTasks[0].resolve([toTaskResult('c'), toTaskResult('a')]);
      const outcome = await done;
      expect(outcome!.map(({ item, result }) => [item.id, result.task.id])).toEqual([
        ['a', 'task-a'],
        ['c', 'task-c'],
      ]);
      expect(ids()).toEqual(['b', 'd']);
      expect(store.selection()).toEqual([]);
      expect(errors).toEqual([]);
    });

    it('targets the item under the cursor without a selection, and moves the cursor on', async () => {
      store.setCursor('b');
      const done = store.toNextActions();
      await flush();
      expect(mail.toTasks[0].ids).toEqual(['b']);
      mail.toTasks[0].resolve([toTaskResult('b')]);
      await done;
      expect(ids()).toEqual(['a', 'c', 'd']);
      expect(store.cursorId()).toBe('c');
    });

    it('keeps a mail whose archive failed in the list, still selected, and reports both', async () => {
      store.toggleSelected('a');
      store.toggleSelected('b');
      const done = store.toNextActions();
      await flush();
      mail.toTasks[0].resolve([toTaskResult('a'), toTaskResult('b', false, 'No Archive')]);
      const outcome = await done;
      expect(outcome!.map(({ result }) => [result.id, result.archived])).toEqual([
        ['a', true],
        ['b', false],
      ]);
      expect(ids()).toEqual(['b', 'c', 'd']);
      expect(store.selection().map((item) => item.id)).toEqual(['b']);
      // The page reports it, not the store.
      expect(errors).toEqual([]);
    });

    it('leaves the list unchanged when no archive succeeded', async () => {
      const done = store.toNextActions();
      await flush();
      mail.toTasks[0].resolve([toTaskResult('a', false)]);
      expect((await done)!.length).toBe(1);
      expect(ids()).toEqual(['a', 'b', 'c', 'd']);
    });

    it('drops targets the server returned no result for', async () => {
      store.toggleSelected('a');
      store.toggleSelected('b');
      const done = store.toNextActions();
      await flush();
      mail.toTasks[0].resolve([toTaskResult('b')]);
      expect((await done)!.map(({ item }) => item.id)).toEqual(['b']);
      expect(ids()).toEqual(['a', 'c', 'd']);
    });

    it('throws a failed request and leaves the mails in place', async () => {
      store.toggleSelected('a');
      store.toggleSelected('b');
      const done = store.toNextActions();
      await flush();
      const failure = new ApiError(404, 'Not Found', 'Message not found');
      mail.toTasks[0].reject(failure);
      await expect(done).rejects.toBe(failure);
      expect(ids()).toEqual(['a', 'b', 'c', 'd']);
      expect(store.selection().map((item) => item.id)).toEqual(['a', 'b']);
    });

    it('answers null without targets and sends nothing', async () => {
      await loaded([]);
      expect(await store.toNextActions()).toBeNull();
      expect(mail.toTasks).toEqual([]);
    });

    describe('with live updates', () => {
      beforeEach(() => vi.useFakeTimers());
      afterEach(() => vi.useRealTimers());

      async function emit() {
        events.changes.next({ at: '', op: 'UPDATED', id: INBOX, type: MAIL_FOLDER_EVENT });
        await vi.advanceTimersByTimeAsync(REFRESH_DEBOUNCE_MS);
      }

      it('waits with a refetch until it is done, then refetches', async () => {
        const done = store.toNextActions();
        await flush();
        await emit();
        expect(mail.lists).toHaveLength(1);
        mail.toTasks[0].resolve([toTaskResult('a')]);
        await done;
        expect(mail.lists).toHaveLength(2);
        mail.lists[1].resolve({ items: [summary('b'), summary('c')], nextCursor: null });
        await flush();
        expect(ids()).toEqual(['b', 'c']);
      });

      it('runs a waiting refetch also after a failed request', async () => {
        const done = store.toNextActions();
        await flush();
        await emit();
        mail.toTasks[0].reject(new Error('offline'));
        await expect(done).rejects.toThrow('offline');
        expect(mail.lists).toHaveLength(2);
      });

      it('drops a refetch answer that started before it, so the archived mail stays gone', async () => {
        await emit();
        expect(mail.lists).toHaveLength(2);
        const done = store.toNextActions();
        await flush();
        mail.toTasks[0].resolve([toTaskResult('a')]);
        await done;
        // The stale answer still has `a`.
        mail.lists[1].resolve({
          items: [summary('a'), summary('b'), summary('c'), summary('d')],
          nextCursor: null,
        });
        await flush();
        expect(ids()).toEqual(['b', 'c', 'd']);
        expect(mail.lists).toHaveLength(3);
      });
    });
  });

  it('applies a conversation action to its messages in the shown folder', async () => {
    await loaded([summary('a', { threadSize: 3, threadUnread: 2 })], {
      folderId: INBOX,
      threaded: true,
    });
    mail.threads.set('a', [summary('a0'), summary('sent', { folderId: OTHER }), summary('a')]);
    const done = store.apply('READ');
    await flush();
    expect(store.items()![0].threadUnread).toBe(0);
    await flush();
    expect(mail.applies[0].ids).toEqual(['a0', 'a']);
    mail.succeed();
    expect(await done).toBe(true);
  });

  describe('live updates', () => {
    beforeEach(() => vi.useFakeTimers());
    afterEach(() => vi.useRealTimers());

    async function emit(event: Partial<ChangeEvent>) {
      events.changes.next({ at: '', op: 'UPDATED', id: INBOX, type: MAIL_FOLDER_EVENT, ...event });
      await vi.advanceTimersByTimeAsync(REFRESH_DEBOUNCE_MS);
    }

    it('refetches the shown folder on its change event, keeping cursor and selection', async () => {
      await loaded([summary('a'), summary('b')]);
      store.setCursor('b');
      store.toggleSelected('b');
      await emit({ id: OTHER });
      expect(mail.lists).toHaveLength(1);
      await emit({ id: INBOX });
      expect(mail.lists).toHaveLength(2);
      expect(mail.lists[1].cursor).toBeUndefined();
      mail.lists[1].resolve({
        items: [summary('new'), summary('a'), summary('b')],
        nextCursor: null,
      });
      await flush();
      expect(ids()).toEqual(['new', 'a', 'b']);
      expect(store.cursorId()).toBe('b');
      expect(store.selection().map((item) => item.id)).toEqual(['b']);
    });

    it('refetches the unified Inbox on any folder event and on a reconnect', async () => {
      await loaded([summary('a')], { folderId: null, threaded: false });
      await emit({ id: OTHER });
      expect(mail.lists).toHaveLength(2);
      mail.lists[1].resolve({ items: [summary('a')], nextCursor: null });
      await flush();
      events.resync$.next();
      await vi.advanceTimersByTimeAsync(REFRESH_DEBOUNCE_MS);
      expect(mail.lists).toHaveLength(3);
    });

    it('moves the cursor when its item disappears remotely', async () => {
      await loaded([summary('a'), summary('b'), summary('c')]);
      store.setCursor('b');
      await emit({});
      mail.lists[1].resolve({ items: [summary('a'), summary('c')], nextCursor: null });
      await flush();
      expect(store.cursorId()).toBe('c');
    });

    it('waits with a refetch until the running action is done', async () => {
      await loaded([summary('a'), summary('b')]);
      const done = store.apply('ARCHIVE');
      await flush();
      await emit({});
      expect(mail.lists).toHaveLength(1);
      mail.succeed();
      await done;
      expect(mail.lists).toHaveLength(2);
      mail.lists[1].resolve({ items: [summary('b')], nextCursor: null });
      await flush();
      expect(ids()).toEqual(['b']);
    });

    it('drops a refetch that started before an action and fetches again', async () => {
      await loaded([summary('a'), summary('b')]);
      await emit({});
      expect(mail.lists).toHaveLength(2);
      const done = store.apply('ARCHIVE');
      await flush();
      // The stale answer still has `a`: it must not bring it back.
      mail.lists[1].resolve({ items: [summary('a'), summary('b')], nextCursor: null });
      await flush();
      expect(ids()).toEqual(['b']);
      mail.succeed();
      await done;
      expect(mail.lists).toHaveLength(3);
    });

    it('keeps the list as it is when a refetch fails', async () => {
      await loaded([summary('a'), summary('b')]);
      await emit({});
      mail.lists[1].reject(new Error('offline'));
      await flush();
      expect(ids()).toEqual(['a', 'b']);
      expect(store.loadError()).toBeNull();
    });

    it('drops the selection of items that disappeared remotely', async () => {
      await loaded([summary('a'), summary('b'), summary('c')]);
      store.toggleSelected('a');
      store.toggleSelected('c');
      await emit({});
      mail.lists[1].resolve({ items: [summary('a'), summary('b')], nextCursor: 'more' });
      await flush();
      expect([...store.selectedIds()]).toEqual(['a']);
      expect(store.nextCursor()).toBe('more');
    });

    it('refetches as many items as are loaded, between the page size and the server limit', async () => {
      const many = (n: number) => Array.from({ length: n }, (_, i) => summary(`m${i}`));
      await loaded(many(3));
      await emit({});
      expect(mail.lists[1].limit).toBe(MAIL_PAGE_SIZE);
      mail.lists[1].resolve({ items: many(120), nextCursor: null });
      await flush();
      await emit({});
      expect(mail.lists[2].limit).toBe(120);
      mail.lists[2].resolve({ items: many(250), nextCursor: null });
      await flush();
      await emit({});
      expect(mail.lists[3].limit).toBe(MAIL_MAX_PAGE_SIZE);
    });

    it('ignores a refetch answer that arrives after another folder was opened', async () => {
      await loaded([summary('a')]);
      await emit({});
      const next = store.load({ folderId: OTHER, threaded: false });
      mail.lists[2].resolve({ items: [summary('o')], nextCursor: null });
      await next;
      mail.lists[1].resolve({ items: [summary('a'), summary('stale')], nextCursor: null });
      await flush();
      expect(ids()).toEqual(['o']);
    });

    it('does not refetch before the first page is there', async () => {
      void store.load({ folderId: INBOX, threaded: false });
      await emit({});
      expect(mail.lists).toHaveLength(1);
    });

    it('runs a refetch queued during a failed action after its rollback', async () => {
      await loaded([summary('a'), summary('b')]);
      const done = store.apply('ARCHIVE');
      await flush();
      await emit({});
      mail.applies[0].reject(new Error('offline'));
      await done;
      expect(ids()).toEqual(['a', 'b']);
      expect(mail.lists).toHaveLength(2);
    });

    it('keeps a queued refetch waiting while another action still runs after a failure', async () => {
      await loaded([summary('a'), summary('b'), summary('c')]);
      const first = store.apply('ARCHIVE'); // a, cursor moves to b
      await flush();
      const second = store.apply('FLAG'); // b
      await flush();
      await emit({});
      mail.applies[0].reject(new Error('offline'));
      await first;
      expect(ids()).toEqual(['a', 'b', 'c']);
      expect(store.items()![1].flagged).toBe(true);
      expect(mail.lists).toHaveLength(1);
      mail.succeed(1);
      await second;
      expect(mail.lists).toHaveLength(2);
      mail.lists[1].resolve({
        items: [summary('a'), summary('b', { flagged: true }), summary('c')],
        nextCursor: null,
      });
      await flush();
      expect(ids()).toEqual(['a', 'b', 'c']);
      expect(store.items()![1].flagged).toBe(true);
      expect(mail.lists).toHaveLength(2);
    });

    it('drops a refetch that overlaps a load more and refetches the longer list', async () => {
      await loaded([summary('a'), summary('b')], undefined, 'c1');
      await emit({});
      expect(mail.lists).toHaveLength(2);
      const more = store.loadMore();
      expect(mail.lists[2].cursor).toBe('c1');
      const page = Array.from({ length: MAIL_PAGE_SIZE }, (_, i) => summary(`p${i}`));
      mail.lists[2].resolve({ items: page, nextCursor: 'c2' });
      await more;
      expect(store.items()).toHaveLength(2 + MAIL_PAGE_SIZE);
      // The refetch was sized for two items: its shorter answer must not replace the list.
      mail.lists[1].resolve({ items: [summary('a'), summary('b')], nextCursor: 'c1' });
      await flush();
      expect(store.items()).toHaveLength(2 + MAIL_PAGE_SIZE);
      expect(store.nextCursor()).toBe('c2');
      expect(mail.lists).toHaveLength(4);
      expect(mail.lists[3].limit).toBe(2 + MAIL_PAGE_SIZE);
    });

    it('drops a next page that arrives after a refetch replaced the list, leaving no gap', async () => {
      await loaded([summary('a'), summary('b')], undefined, 'c-b');
      await emit({});
      const more = store.loadMore();
      expect(mail.lists[2].cursor).toBe('c-b');
      // New mail arrived: the refetch (sized for two items) ends one item earlier.
      mail.lists[1].resolve({ items: [summary('new'), summary('a')], nextCursor: 'c-a' });
      await flush();
      expect(ids()).toEqual(['new', 'a']);
      // Appending the page after b would leave b out of the list.
      mail.lists[2].resolve({ items: [summary('c'), summary('d')], nextCursor: 'c-d' });
      await more;
      expect(ids()).toEqual(['new', 'a']);
      expect(store.nextCursor()).toBe('c-a');
      expect(store.loadingMore()).toBe(false);
      expect(store.moreError()).toBeNull();
      // The next scroll continues from the refetch's cursor.
      void store.loadMore();
      expect(mail.lists[3].cursor).toBe('c-a');
      mail.lists[3].resolve({ items: [summary('b'), summary('c')], nextCursor: null });
      await flush();
      expect(ids()).toEqual(['new', 'a', 'b', 'c']);
    });

    it('appends a next page when the overlapping refetch failed and left the list as it was', async () => {
      await loaded([summary('a'), summary('b')], undefined, 'c-b');
      await emit({});
      const more = store.loadMore();
      mail.lists[1].reject(new Error('offline'));
      await flush();
      mail.lists[2].resolve({ items: [summary('c')], nextCursor: null });
      await more;
      expect(ids()).toEqual(['a', 'b', 'c']);
      expect(store.nextCursor()).toBeNull();
    });

    it('appends a next page when the overlapping refetch was dropped for a running action', async () => {
      await loaded([summary('a'), summary('b')], undefined, 'c-b');
      await emit({});
      const more = store.loadMore();
      const flag = store.apply('FLAG'); // a, still running
      await flush();
      // Stale (started before the action): dropped, not applied, so the list was not replaced.
      mail.lists[1].resolve({ items: [summary('new'), summary('a')], nextCursor: 'c-a' });
      await flush();
      expect(ids()).toEqual(['a', 'b']);
      mail.lists[2].resolve({ items: [summary('c')], nextCursor: null });
      await more;
      expect(ids()).toEqual(['a', 'b', 'c']);
      mail.succeed();
      await flag;
      expect(store.items()![0].flagged).toBe(true);
    });
  });

  describe('loading edge cases', () => {
    it('ignores the first page of an earlier load that arrives last', async () => {
      const first = store.load({ folderId: INBOX, threaded: false });
      const second = store.load({ folderId: OTHER, threaded: false });
      mail.lists[1].resolve({ items: [summary('o')], nextCursor: null });
      await second;
      mail.lists[0].resolve({ items: [summary('stale')], nextCursor: 'c' });
      await first;
      expect(ids()).toEqual(['o']);
      expect(store.nextCursor()).toBeNull();
      expect(store.scope().folderId).toBe(OTHER);
    });

    it('ignores the error of an earlier load that fails last', async () => {
      const first = store.load({ folderId: INBOX, threaded: false });
      const second = store.load({ folderId: OTHER, threaded: false });
      mail.lists[1].resolve({ items: [summary('o')], nextCursor: null });
      await second;
      mail.lists[0].reject(new Error('late'));
      await first;
      expect(store.loadError()).toBeNull();
      expect(ids()).toEqual(['o']);
    });

    it('resets selection and cursor, and has no cursor on an empty folder', async () => {
      await loaded([summary('a'), summary('b')]);
      store.toggleSelected('b');
      await loaded([]);
      expect(store.selection()).toEqual([]);
      expect(store.cursorId()).toBeNull();
      expect(store.targets()).toEqual([]);
      expect(store.allSelected()).toBe(false);
    });

    it('reloads the same scope', async () => {
      await loaded([summary('a')], { folderId: OTHER, threaded: true });
      void store.reload();
      expect(mail.lists[1].scope).toEqual({ folderId: OTHER, threaded: true });
    });

    it('shows a loadMore error, keeps the items and clears the error on retry', async () => {
      await loaded([summary('a')], undefined, 'c1');
      const more = store.loadMore();
      expect(store.loadingMore()).toBe(true);
      mail.lists[1].reject(new ApiError(500, 'Server error', 'Try again later'));
      await more;
      expect(store.moreError()).toBe('Try again later');
      expect(store.loadingMore()).toBe(false);
      expect(ids()).toEqual(['a']);
      expect(store.nextCursor()).toBe('c1');

      const retry = store.loadMore();
      expect(store.moreError()).toBeNull();
      mail.lists[2].resolve({ items: [summary('b')], nextCursor: null });
      await retry;
      expect(ids()).toEqual(['a', 'b']);
    });

    it('uses the generic message for a loadMore failure without server detail', async () => {
      await loaded([summary('a')], undefined, 'c1');
      const more = store.loadMore();
      mail.lists[1].reject(new TypeError('Failed to fetch'));
      await more;
      expect(store.moreError()).toBe('Something went wrong.');
    });

    it('does not load more without a cursor, while loading more or before the first page', async () => {
      void store.load({ folderId: INBOX, threaded: false });
      await store.loadMore();
      expect(mail.lists).toHaveLength(1);
      mail.lists[0].resolve({ items: [summary('a')], nextCursor: null });
      await flush();
      await store.loadMore();
      expect(mail.lists).toHaveLength(1);

      await loaded([summary('a')], undefined, 'c1');
      const more = store.loadMore();
      void store.loadMore();
      expect(mail.lists).toHaveLength(3);
      mail.lists[2].resolve({ items: [], nextCursor: null });
      await more;
    });

    it('ignores a next page (or its error) that arrives after another folder was opened', async () => {
      await loaded([summary('a')], undefined, 'c1');
      const more = store.loadMore();
      await loaded([summary('o')], { folderId: OTHER, threaded: false });
      mail.lists[1].resolve({ items: [summary('stale')], nextCursor: 'c2' });
      await more;
      expect(ids()).toEqual(['o']);
      expect(store.nextCursor()).toBeNull();

      await loaded([summary('a')], undefined, 'c1');
      const failing = store.loadMore();
      await loaded([summary('o')], { folderId: OTHER, threaded: false });
      expect(mail.lists[4].cursor).toBe('c1');
      mail.lists[4].reject(new Error('late'));
      await failing;
      expect(store.moreError()).toBeNull();
    });

    it('loads the next page when j reaches the last item', async () => {
      await loaded([summary('a'), summary('b')], undefined, 'c1');
      store.moveCursor(1);
      expect(store.cursorId()).toBe('b');
      expect(mail.lists[1]?.cursor).toBe('c1');
    });

    it('puts the cursor on the first item when it is not in the list, and ignores j on an empty list', async () => {
      await loaded([summary('a'), summary('b')]);
      store.setCursor('gone');
      store.moveCursor(1);
      expect(store.cursorId()).toBe('a');
      await loaded([]);
      store.moveCursor(1);
      expect(store.cursorId()).toBeNull();
    });
  });

  describe('action edge cases', () => {
    it('uses the generic message when a failed result has no detail', async () => {
      await loaded([summary('a')]);
      const done = store.apply('DELETE');
      await flush();
      mail.applies[0].resolve([{ id: 'a', ok: false, detail: null, errorCode: 'X' }]);
      expect(await done).toBe(false);
      expect(ids()).toEqual(['a']);
      expect(errors[0]).toContain('Something went wrong.');
    });

    it('shows the server message of a rejected request', async () => {
      await loaded([summary('a')]);
      const done = store.apply('SPAM');
      await flush();
      mail.applies[0].reject(new ApiError(409, 'Conflict', 'Folder is read-only'));
      await done;
      expect(errors[0]).toContain('Folder is read-only');
    });

    it('moves the cursor to the item before when the last item is removed', async () => {
      await loaded([summary('a'), summary('b')]);
      store.setCursor('b');
      void store.apply('ARCHIVE');
      expect(store.cursorId()).toBe('a');
    });

    it('clears the cursor when the only item is removed', async () => {
      await loaded([summary('a')]);
      void store.apply('ARCHIVE');
      expect(store.cursorId()).toBeNull();
      expect(store.items()).toEqual([]);
    });

    it('keeps the cursor when removing selected items that are not under it', async () => {
      await loaded([summary('a'), summary('b'), summary('c')]);
      store.toggleSelected('b');
      void store.apply('DELETE');
      expect(store.cursorId()).toBe('a');
      expect(ids()).toEqual(['a', 'c']);
    });

    it('does not roll back into another folder opened while the action ran', async () => {
      await loaded([summary('a'), summary('b')]);
      const done = store.apply('ARCHIVE');
      await flush();
      await loaded([summary('o')], { folderId: OTHER, threaded: false });
      mail.applies[0].reject(new Error('offline'));
      expect(await done).toBe(false);
      expect(ids()).toEqual(['o']);
      expect(errors).toEqual([]);
    });

    it('rolls back only its own items when overlapping actions finish out of order', async () => {
      await loaded([summary('a'), summary('b'), summary('c')]);
      const first = store.apply('ARCHIVE'); // a (cursor), cursor moves to b
      await flush();
      const second = store.apply('ARCHIVE'); // b
      await flush();
      expect(ids()).toEqual(['c']);
      mail.succeed(1);
      expect(await second).toBe(true);
      mail.applies[0].reject(new Error('offline'));
      expect(await first).toBe(false);
      expect(ids()).toEqual(['a', 'c']);
    });

    it('keeps an earlier failure rolled back when a later action on other items succeeds', async () => {
      await loaded([summary('a'), summary('b'), summary('c')]);
      const first = store.apply('ARCHIVE'); // a
      await flush();
      const second = store.apply('ARCHIVE'); // b
      await flush();
      mail.applies[0].reject(new Error('offline'));
      await first;
      expect(ids()).toEqual(['a', 'c']);
      mail.succeed(1);
      await second;
      expect(ids()).toEqual(['a', 'c']);
    });

    it('applies to the given ids that are still loaded, in list order', async () => {
      await loaded([summary('a'), summary('b'), summary('c')]);
      const done = store.apply('MOVE', OTHER, ['c', 'gone', 'b']);
      await flush();
      expect(mail.applies[0]).toMatchObject({ action: 'MOVE', ids: ['b', 'c'], target: OTHER });
      expect(ids()).toEqual(['a']);
      mail.succeed();
      expect(await done).toBe(true);
    });

    it('does nothing when none of the given ids is loaded any more', async () => {
      await loaded([summary('a')]);
      expect(await store.apply('MOVE', OTHER, ['gone'])).toBe(false);
      expect(mail.applies).toEqual([]);
      expect(ids()).toEqual(['a']);
    });

    it('does not bring back an item a later action removed when an earlier flag action fails', async () => {
      await loaded([summary('a'), summary('b')]);
      const flag = store.apply('FLAG'); // a
      await flush();
      const archive = store.apply('ARCHIVE'); // a again: it is still under the cursor
      await flush();
      expect(mail.applies.map((call) => call.ids)).toEqual([['a'], ['a']]);
      mail.succeed(1);
      expect(await archive).toBe(true);
      mail.applies[0].reject(new Error('offline'));
      expect(await flag).toBe(false);
      expect(ids()).toEqual(['b']);
      expect(errors).toHaveLength(1);
    });

    it('keeps a later successful flag change when an earlier action on the same item fails', async () => {
      await loaded([summary('a', { seen: false, flagged: false })]);
      const flag = store.apply('FLAG');
      await flush();
      const read = store.apply('READ');
      await flush();
      mail.succeed(1);
      expect(await read).toBe(true);
      mail.applies[0].reject(new Error('offline'));
      expect(await flag).toBe(false);
      expect(store.items()![0]).toMatchObject({ flagged: false, seen: true });
    });

    it('keeps a field a later action changed again when the earlier action on it fails', async () => {
      await loaded([summary('a', { seen: false })]);
      const read = store.apply('READ');
      await flush();
      const unread = store.apply('UNREAD');
      await flush();
      mail.succeed(1);
      await unread;
      mail.applies[0].reject(new Error('offline'));
      expect(await read).toBe(false);
      expect(store.items()![0].seen).toBe(false);
    });

    it('restores the removed item as it was after an earlier action changed it', async () => {
      await loaded([summary('a'), summary('b')]);
      const flag = store.apply('FLAG');
      await flush();
      const archive = store.apply('ARCHIVE');
      await flush();
      mail.succeed(0);
      await flag;
      mail.applies[1].reject(new Error('offline'));
      expect(await archive).toBe(false);
      expect(ids()).toEqual(['a', 'b']);
      expect(store.items()![0].flagged).toBe(true);
    });

    it('refetches the list after a rollback, once no action runs', async () => {
      await loaded([summary('a'), summary('b')]);
      const first = store.apply('READ'); // a
      await flush();
      store.setCursor('b');
      const second = store.apply('FLAG'); // b
      await flush();
      mail.applies[0].reject(new Error('offline'));
      await first;
      expect(mail.lists).toHaveLength(1);
      mail.succeed(1);
      await second;
      expect(mail.lists).toHaveLength(2);
      expect(mail.lists[1].cursor).toBeUndefined();
      mail.lists[1].resolve({
        items: [summary('a', { seen: true }), summary('b', { flagged: true })],
        nextCursor: null,
      });
      await flush();
      expect(store.items()!.map((item) => [item.seen, item.flagged])).toEqual([
        [true, false],
        [false, true],
      ]);
    });

    it('sends at most 1000 ids per request and merges the results', async () => {
      const many = Array.from({ length: 2500 }, (_, i) => summary(`m${i}`));
      await loaded(many);
      store.toggleAll();
      const done = store.apply('ARCHIVE');
      await flush();
      expect(mail.applies.map((call) => call.ids.length)).toEqual([
        MAIL_MAX_ACTION_IDS,
        MAIL_MAX_ACTION_IDS,
        500,
      ]);
      expect(new Set(mail.applies.flatMap((call) => call.ids)).size).toBe(2500);
      mail.succeed(0, ['m3'], 'Locked');
      mail.applies[1].reject(new ApiError(503, 'Unavailable', 'Try later'));
      mail.succeed(2);
      expect(await done).toBe(false);
      expect(ids()).toEqual(['m3', ...many.slice(1000, 2000).map((item) => item.id)]);
      expect(errors).toHaveLength(1);
      expect(errors[0]).toContain('Locked');
    });

    it('marks a conversation unread with at least one unread message', async () => {
      await loaded([summary('a', { threadSize: 1, threadUnread: 0, seen: true })], {
        folderId: INBOX,
        threaded: true,
      });
      void store.apply('UNREAD');
      expect(store.items()![0]).toMatchObject({ seen: false, threadUnread: 1 });
    });
  });

  describe('snapshot and restore', () => {
    const report: MailScope = {
      folderId: null,
      threaded: false,
      search: { query: 'report', server: false },
    };

    it('takes no snapshot before the first page, after a failed load or during a server search', async () => {
      expect(store.snapshot()).toBeNull();
      void store.load({ folderId: INBOX, threaded: false });
      expect(store.snapshot()).toBeNull();
      mail.lists[0].reject(new ApiError(502, 'Bad gateway', 'Mail server down'));
      await flush();
      expect(store.snapshot()).toBeNull();

      const first = store.load(report);
      mail.searches[0].resolve({ items: [summary('a')], hasMore: false, failedAccountIds: [] });
      await first;
      expect(store.snapshot()).not.toBeNull();
      // The cached results stay shown while the server is searched: they are not the final list.
      void store.load({ ...report, search: { query: 'report', server: true } });
      expect(store.searchingServer()).toBe(true);
      expect(store.snapshot()).toBeNull();
    });

    it('takes the shown list with its scope, next page cursor, list cursor and search extras', async () => {
      await loaded([summary('a'), summary('b')], { folderId: OTHER, threaded: true }, 'c1');
      store.setCursor('b');
      expect(store.snapshot()).toEqual({
        scope: { folderId: OTHER, threaded: true },
        items: [summary('a'), summary('b')],
        nextCursor: 'c1',
        cursorId: 'b',
        searchResult: null,
      });

      const done = store.load(report);
      mail.searches[0].resolve({ items: [summary('n')], hasMore: true, failedAccountIds: ['x'] });
      await done;
      expect(store.snapshot()).toMatchObject({
        scope: report,
        nextCursor: null,
        cursorId: 'n',
        searchResult: { hasMore: true, failedAccountIds: ['x'] },
      });
    });

    it('shows a snapshot again without loading, with nothing selected and no errors', async () => {
      await loaded([summary('a'), summary('b')], undefined, 'c1');
      store.setCursor('b');
      const snapshot = store.snapshot()!;
      const failing = store.load({ folderId: OTHER, threaded: false });
      mail.lists[1].reject(new ApiError(500, 'Error', 'Broken'));
      await failing;
      store.toggleSelected('zzz');
      expect(store.loadError()).toBe('Broken');

      store.restore(snapshot);
      expect(mail.lists).toHaveLength(2);
      expect(ids()).toEqual(['a', 'b']);
      expect(store.scope()).toEqual({ folderId: INBOX, threaded: false });
      expect(store.cursorId()).toBe('b');
      expect(store.nextCursor()).toBe('c1');
      expect(store.loadError()).toBeNull();
      expect(store.moreError()).toBeNull();
      expect(store.selection()).toEqual([]);
      expect(store.searchingServer()).toBe(false);
    });

    it('restores search results with their extras, and refresh leaves them alone', async () => {
      store.restore({
        scope: report,
        items: [summary('n'), summary('o')],
        nextCursor: null,
        cursorId: 'o',
        searchResult: { hasMore: true, failedAccountIds: [] },
      });
      expect(ids()).toEqual(['n', 'o']);
      expect(store.searchResult()).toEqual({ hasMore: true, failedAccountIds: [] });
      await store.refresh();
      expect(mail.lists).toEqual([]);
      expect(mail.searches).toEqual([]);
    });

    it('does not share its items with the snapshot', async () => {
      await loaded([summary('a'), summary('b')]);
      const snapshot = store.snapshot()!;
      store.restore(snapshot);
      const done = store.apply('ARCHIVE');
      await flush();
      expect(ids()).toEqual(['b']);
      expect(snapshot.items.map((item) => item.id)).toEqual(['a', 'b']);
      mail.applies[0].resolve([{ id: 'a', ok: true, detail: null, errorCode: null }]);
      await done;
    });

    it('drops a first page that arrives after a restore', async () => {
      const pending = store.load({ folderId: OTHER, threaded: false });
      store.restore({
        scope: { folderId: INBOX, threaded: false },
        items: [summary('a')],
        nextCursor: null,
        cursorId: 'a',
        searchResult: null,
      });
      mail.lists[0].resolve({ items: [summary('stale')], nextCursor: 'c9' });
      await pending;
      expect(ids()).toEqual(['a']);
      expect(store.nextCursor()).toBeNull();
      expect(store.scope().folderId).toBe(INBOX);
    });

    it('drops a next page and a refetch that arrive after a restore', async () => {
      await loaded([summary('a')], undefined, 'c1');
      const more = store.loadMore();
      const refetch = store.refresh();
      store.restore({
        scope: { folderId: INBOX, threaded: false },
        items: [summary('x'), summary('y')],
        nextCursor: 'cx',
        cursorId: 'y',
        searchResult: null,
      });
      mail.lists[1].resolve({ items: [summary('stale-more')], nextCursor: 'c2' });
      mail.lists[2].resolve({ items: [summary('stale-refetch')], nextCursor: 'c3' });
      await more;
      await refetch;
      expect(ids()).toEqual(['x', 'y']);
      expect(store.nextCursor()).toBe('cx');
      expect(store.cursorId()).toBe('y');
    });

    it('keeps a restored folder list, its cursor and selection current on the next refetch', async () => {
      store.restore({
        scope: { folderId: INBOX, threaded: false },
        items: [summary('a'), summary('b')],
        nextCursor: null,
        cursorId: 'b',
        searchResult: null,
      });
      const refetch = store.refresh();
      expect(mail.lists[0]).toMatchObject({ scope: { folderId: INBOX, threaded: false } });
      mail.lists[0].resolve({ items: [summary('a'), summary('c')], nextCursor: null });
      await refetch;
      expect(ids()).toEqual(['a', 'c']);
      // The restored cursor's message is gone: the cursor moves to its neighbour that is left.
      expect(store.cursorId()).toBe('a');
    });
  });

  describe('search', () => {
    const searchScope = (
      query: string,
      server = false,
      folderId: string | null = INBOX,
      threaded = false,
    ): MailScope => ({ folderId, threaded, search: { query, server } });

    async function searched(
      scope: MailScope,
      items: MailSummary[],
      extra: { hasMore?: boolean; failedAccountIds?: string[] } = {},
    ) {
      const done = store.load(scope);
      mail.searches.at(-1)!.resolve({
        items,
        hasMore: extra.hasMore ?? false,
        failedAccountIds: extra.failedAccountIds ?? [],
      });
      await done;
    }

    it('searches the folder of the scope flat, in one page, and lists the results', async () => {
      await searched(searchScope('report', false, INBOX, true), [summary('n'), summary('o')], {
        hasMore: true,
      });
      expect(mail.lists).toEqual([]);
      expect(mail.searches[0]).toMatchObject({ query: 'report', folderId: INBOX, server: false });
      expect(store.scope().threaded).toBe(false);
      expect(ids()).toEqual(['n', 'o']);
      expect(store.cursorId()).toBe('n');
      expect(store.nextCursor()).toBeNull();
      expect(store.searchResult()).toEqual({ hasMore: true, failedAccountIds: [] });
      expect(store.searchingServer()).toBe(false);
    });

    it('searches all folders for the unified Inbox', async () => {
      await searched(searchScope('lunch', false, null), []);
      expect(mail.searches[0]).toMatchObject({ query: 'lunch', folderId: null });
      expect(ids()).toEqual([]);
    });

    it('keeps the cached results shown while the same query is searched on the server', async () => {
      await searched(searchScope('report'), [summary('a'), summary('b')]);
      store.setCursor('b');
      const done = store.load(searchScope('report', true));
      expect(store.searchingServer()).toBe(true);
      expect(ids()).toEqual(['a', 'b']);
      expect(store.searchResult()).toEqual({ hasMore: false, failedAccountIds: [] });
      expect(mail.searches[1]).toMatchObject({ query: 'report', server: true });
      mail.searches[1].resolve({
        items: [summary('a'), summary('b'), summary('old')],
        hasMore: false,
        failedAccountIds: ['acc-2'],
      });
      await done;
      expect(store.searchingServer()).toBe(false);
      expect(ids()).toEqual(['a', 'b', 'old']);
      expect(store.cursorId()).toBe('b');
      expect(store.searchResult()).toEqual({ hasMore: false, failedAccountIds: ['acc-2'] });
    });

    it('does not keep results of another query, folder or a failed search', async () => {
      await searched(searchScope('report'), [summary('a')]);
      void store.load(searchScope('lunch', true));
      expect(store.searchingServer()).toBe(false);
      expect(store.items()).toBeNull();
      expect(store.searchResult()).toBeNull();

      await searched(searchScope('report'), [summary('a')]);
      void store.load(searchScope('report', true, OTHER));
      expect(store.items()).toBeNull();

      const failing = store.load(searchScope('report'));
      mail.searches.at(-1)!.reject(new ApiError(500, 'Error', 'Search broke'));
      await failing;
      void store.load(searchScope('report', true));
      expect(store.searchingServer()).toBe(false);
      expect(store.loadError()).toBeNull();
      expect(store.items()).toBeNull();
    });

    it('replaces the kept results with the load error when the server search fails', async () => {
      await searched(searchScope('report'), [summary('a')]);
      const done = store.load(searchScope('report', true));
      mail.searches[1].reject(new ApiError(504, 'Gateway Timeout', 'Mail server too slow'));
      await done;
      expect(store.items()).toBeNull();
      expect(store.searchResult()).toBeNull();
      expect(store.loadError()).toBe('Mail server too slow');
      expect(store.searchingServer()).toBe(false);
    });

    it('reloads the search after an error (Try again)', async () => {
      const failing = store.load(searchScope('report'));
      mail.searches[0].reject(new ApiError(500, 'Error', 'Search broke'));
      await failing;
      const again = store.reload();
      expect(store.loadError()).toBeNull();
      expect(mail.searches[1]).toMatchObject({ query: 'report', folderId: INBOX });
      mail.searches[1].resolve({ items: [summary('a')], hasMore: false, failedAccountIds: [] });
      await again;
      expect(ids()).toEqual(['a']);
    });

    it('ignores the answer of a search left before it arrived', async () => {
      const search = store.load(searchScope('report', true));
      const list = store.load({ folderId: INBOX, threaded: false });
      expect(store.searchingServer()).toBe(false);
      mail.lists[0].resolve({ items: [summary('x')], nextCursor: null });
      await list;
      mail.searches[0].resolve({
        items: [summary('a')],
        hasMore: true,
        failedAccountIds: ['acc-2'],
      });
      await search;
      expect(ids()).toEqual(['x']);
      expect(store.searchResult()).toBeNull();
      expect(store.searchingServer()).toBe(false);
    });

    it('returns to the folder list with no search extras', async () => {
      await searched(searchScope('report', true), [summary('a')], {
        hasMore: true,
        failedAccountIds: ['acc-2'],
      });
      await loaded([summary('x'), summary('y')]);
      expect(ids()).toEqual(['x', 'y']);
      expect(store.searchResult()).toBeNull();
      expect(store.scope().search).toBeUndefined();
    });

    it('does not refetch search results on refresh() nor on change events', async () => {
      await searched(searchScope('report'), [summary('a')]);
      await store.refresh();
      expect(mail.lists).toEqual([]);
      expect(mail.searches).toHaveLength(1);

      vi.useFakeTimers();
      try {
        events.changes.next({ at: '', op: 'UPDATED', id: INBOX, type: MAIL_FOLDER_EVENT });
        events.resync$.next();
        await vi.advanceTimersByTimeAsync(REFRESH_DEBOUNCE_MS);
      } finally {
        vi.useRealTimers();
      }
      expect(mail.lists).toEqual([]);
      expect(mail.searches).toHaveLength(1);
    });

    it('changes search results optimistically on an action, without refetching on failure', async () => {
      await searched(searchScope('report'), [summary('a'), summary('b')]);
      const done = store.apply('ARCHIVE');
      await flush();
      expect(ids()).toEqual(['b']);
      mail.applies[0].reject(new ApiError(503, 'Service Unavailable', 'Unreachable'));
      await done;
      await flush();
      expect(ids()).toEqual(['a', 'b']);
      expect(mail.lists).toEqual([]);
      expect(mail.searches).toHaveLength(1);
    });
  });

  describe('conversation actions', () => {
    const threaded: MailScope = { folderId: INBOX, threaded: true };

    it('sends a single-message conversation without loading its thread', async () => {
      await loaded([summary('a', { threadSize: 1 }), summary('b')], threaded);
      const thread = vi.spyOn(mail, 'thread');
      store.toggleAll();
      void store.apply('READ');
      await flush();
      expect(thread).not.toHaveBeenCalled();
      expect(mail.applies[0].ids).toEqual(['a', 'b']);
    });

    it('keeps the item itself when its thread does not list it, and sends shared ids once', async () => {
      await loaded([summary('a', { threadSize: 2 }), summary('b', { threadSize: 2 })], threaded);
      mail.threads.set('a', [summary('x')]);
      mail.threads.set('b', [summary('x'), summary('b')]);
      store.toggleAll();
      void store.apply('ARCHIVE');
      await flush();
      expect(mail.applies[0].ids).toEqual(['a', 'x', 'b']);
    });

    it('rolls back a conversation when one of its messages fails', async () => {
      await loaded([summary('a', { threadSize: 2 }), summary('b')], threaded);
      mail.threads.set('a', [summary('a0'), summary('a')]);
      store.toggleAll();
      const done = store.apply('ARCHIVE');
      await flush();
      expect(ids()).toEqual([]);
      mail.succeed(0, ['a0']);
      expect(await done).toBe(false);
      expect(ids()).toEqual(['a']);
    });

    it('rolls back every item when loading a thread fails', async () => {
      await loaded([summary('a', { threadSize: 2 })], threaded);
      vi.spyOn(mail, 'thread').mockRejectedValue(new ApiError(404, 'Not Found', 'Gone'));
      const done = store.apply('ARCHIVE');
      expect(await done).toBe(false);
      expect(mail.applies).toEqual([]);
      expect(ids()).toEqual(['a']);
      expect(errors[0]).toContain('Gone');
    });

    it(`looks up at most ${THREAD_LOOKUP_CONCURRENCY} conversations at a time`, async () => {
      const count = THREAD_LOOKUP_CONCURRENCY * 3 + 1;
      const items = Array.from({ length: count }, (_, i) => summary(`t${i}`, { threadSize: 2 }));
      await loaded(items, threaded);
      const pending: (() => void)[] = [];
      let running = 0;
      let peak = 0;
      const thread = vi.spyOn(mail, 'thread').mockImplementation(
        (id) =>
          new Promise<MailSummary[]>((resolve) => {
            running++;
            peak = Math.max(peak, running);
            pending.push(() => {
              running--;
              resolve([summary(`${id}-0`), summary(id)]);
            });
          }),
      );
      store.toggleAll();
      void store.apply('ARCHIVE');
      await flush();
      expect(running).toBe(THREAD_LOOKUP_CONCURRENCY);
      while (pending.length) {
        pending.splice(0).forEach((finish) => finish());
        await flush();
      }
      expect(peak).toBe(THREAD_LOOKUP_CONCURRENCY);
      expect(thread).toHaveBeenCalledTimes(count);
      expect(mail.applies[0].ids).toEqual(items.flatMap((item) => [`${item.id}-0`, item.id]));
    });

    it('sends conversation ids in list order when their lookups finish in reverse order', async () => {
      const items = [
        summary('t0', { threadSize: 2 }),
        summary('single', { threadSize: 1 }),
        summary('t1', { threadSize: 2 }),
        summary('t2', { threadSize: 2 }),
      ];
      await loaded(items, threaded);
      const pending = new Map<string, () => void>();
      vi.spyOn(mail, 'thread').mockImplementation(
        (id) =>
          new Promise<MailSummary[]>((resolve) =>
            pending.set(id, () => resolve([summary(`${id}-0`), summary(id)])),
          ),
      );
      store.toggleAll();
      const done = store.apply('ARCHIVE');
      await flush();
      expect([...pending.keys()]).toEqual(['t0', 't1', 't2']);
      for (const id of ['t2', 't1', 't0']) {
        pending.get(id)!();
        await flush();
      }
      expect(mail.applies[0].ids).toEqual(['t0-0', 't0', 'single', 't1-0', 't1', 't2-0', 't2']);
      mail.succeed();
      expect(await done).toBe(true);
    });

    it('stops looking up conversations after a failed lookup and rolls back every item', async () => {
      const count = THREAD_LOOKUP_CONCURRENCY * 2;
      const items = Array.from({ length: count }, (_, i) => summary(`t${i}`, { threadSize: 2 }));
      await loaded(items, threaded);
      const thread = vi
        .spyOn(mail, 'thread')
        .mockRejectedValueOnce(new ApiError(503, 'Unavailable', 'Try later'))
        .mockResolvedValue([]);
      store.toggleAll();
      expect(await store.apply('ARCHIVE')).toBe(false);
      expect(thread.mock.calls.length).toBeLessThan(count);
      expect(mail.applies).toEqual([]);
      expect(ids()).toEqual(items.map((item) => item.id));
      expect(errors[0]).toContain('Try later');
    });

    it('sends flat items as they are, without loading threads', async () => {
      await loaded([summary('a', { threadSize: 3 })]);
      const thread = vi.spyOn(mail, 'thread');
      void store.apply('READ');
      await flush();
      expect(thread).not.toHaveBeenCalled();
      expect(mail.applies[0].ids).toEqual(['a']);
    });
  });

  describe('load older from server (SPEC.md §7.2)', () => {
    const folder: MailScope = { folderId: INBOX, threaded: false };

    it('is offered at the end of a fully loaded folder only', async () => {
      expect(store.canLoadOlder()).toBe(false);
      await loaded([summary('a')], folder);
      expect(store.canLoadOlder()).toBe(true);

      // More cached pages follow: infinite scroll comes first.
      await loaded([summary('a')], folder, 'c1');
      expect(store.canLoadOlder()).toBe(false);

      // The unified Inbox has no single folder to load into.
      await loaded([summary('a')], { folderId: null, threaded: false });
      expect(store.canLoadOlder()).toBe(false);
    });

    it('is offered for an empty folder', async () => {
      await loaded([], folder);
      expect(store.canLoadOlder()).toBe(true);
    });

    it('is not offered for search results, while loading, or after a failed load', async () => {
      const search = store.load({ ...folder, search: { query: 'invoice', server: false } });
      mail.searches[0].resolve({ items: [summary('a')], hasMore: false, failedAccountIds: [] });
      await search;
      expect(store.canLoadOlder()).toBe(false);

      void store.load(folder);
      expect(store.canLoadOlder()).toBe(false);
      mail.lists.at(-1)!.reject(new ApiError(502, 'Bad gateway', 'Mail server down'));
      await flush();
      expect(store.loadError()).toBe('Mail server down');
      expect(store.canLoadOlder()).toBe(false);
    });

    it('calls load-older for the shown folder, then shows the newly cached messages at the end', async () => {
      await loaded([summary('a'), summary('b')], folder);
      store.setCursor('b');
      store.toggleSelected('b');
      const done = store.loadOlder();
      expect(store.loadingOlder()).toBe(true);
      expect(mail.loadOlders.map((call) => call.folderId)).toEqual([INBOX]);

      mail.loadOlders[0].resolve({ loaded: 2, hasMore: true });
      await flush();
      expect(store.loadingOlder()).toBe(true);
      // The list up to the old end plus the newly cached messages, from the start.
      expect(mail.lists.at(-1)).toMatchObject({ scope: folder, cursor: undefined, limit: 4 });
      mail.lists.at(-1)!.resolve({
        items: [summary('a'), summary('b'), summary('c'), summary('d')],
        nextCursor: null,
      });
      await done;

      expect(ids()).toEqual(['a', 'b', 'c', 'd']);
      expect(store.loadingOlder()).toBe(false);
      expect(store.olderError()).toBeNull();
      // The cursor and the selection stay where they were.
      expect(store.cursorId()).toBe('b');
      expect([...store.selectedIds()]).toEqual(['b']);
      expect(store.canLoadOlder()).toBe(true);
    });

    it('reads more than one page when more messages were cached than one page holds', async () => {
      const first = Array.from({ length: MAIL_MAX_PAGE_SIZE }, (_, i) => summary(`m${i}`));
      await loaded(first.slice(0, 150), folder);
      const done = store.loadOlder();
      mail.loadOlders[0].resolve({ loaded: 100, hasMore: false });
      await flush();

      expect(mail.lists.at(-1)!.limit).toBe(MAIL_MAX_PAGE_SIZE);
      mail.lists.at(-1)!.resolve({ items: first, nextCursor: 'p2' });
      await flush();
      expect(mail.lists.at(-1)).toMatchObject({ cursor: 'p2', limit: 50 });
      const rest = Array.from({ length: 50 }, (_, i) => summary(`n${i}`));
      // A duplicate of the first page (the list moved meanwhile) is shown once.
      mail.lists.at(-1)!.resolve({ items: [first[199], ...rest], nextCursor: null });
      await done;

      expect(ids()).toHaveLength(250);
      expect(new Set(ids()).size).toBe(250);
      expect(store.nextCursor()).toBeNull();
    });

    it('keeps infinite scroll going when the server has more cached mail than was asked for', async () => {
      await loaded([summary('a')], folder);
      const done = store.loadOlder();
      mail.loadOlders[0].resolve({ loaded: 1, hasMore: true });
      await flush();
      mail.lists.at(-1)!.resolve({ items: [summary('a'), summary('b')], nextCursor: 'more' });
      await done;
      expect(ids()).toEqual(['a', 'b']);
      expect(store.nextCursor()).toBe('more');
      expect(store.canLoadOlder()).toBe(false);
    });

    it('says so when the server has nothing older, without reloading the list', async () => {
      await loaded([summary('a')], folder);
      const lists = mail.lists.length;
      const done = store.loadOlder();
      mail.loadOlders[0].resolve({ loaded: 0, hasMore: false });
      await done;

      expect(store.olderNote()).toBe('There are no older messages on the server.');
      expect(store.olderError()).toBeNull();
      expect(store.loadingOlder()).toBe(false);
      expect(mail.lists).toHaveLength(lists);
      expect(ids()).toEqual(['a']);
      expect(errors).toEqual([]);
    });

    it('shows a failed load-older without a toast and keeps the list; a retry clears it', async () => {
      await loaded([summary('a')], folder);
      const failed = store.loadOlder();
      mail.loadOlders[0].reject(new ApiError(503, 'Unavailable', 'Mail server unreachable'));
      await failed;
      expect(store.olderError()).toBe('Mail server unreachable');
      expect(store.loadingOlder()).toBe(false);
      expect(ids()).toEqual(['a']);
      expect(errors).toEqual([]);

      const retry = store.loadOlder();
      expect(store.olderError()).toBeNull();
      mail.loadOlders[1].resolve({ loaded: 0, hasMore: false });
      await retry;
      expect(store.olderError()).toBeNull();
      expect(store.olderNote()).not.toBeNull();

      // The next try clears the note of the one before.
      void store.loadOlder();
      expect(store.olderNote()).toBeNull();
    });

    it('shows a generic message when the connection fails', async () => {
      await loaded([summary('a')], folder);
      const done = store.loadOlder();
      mail.loadOlders[0].reject(new TypeError('Failed to fetch'));
      await done;
      expect(store.olderError()).toBe('Something went wrong.');
    });

    it('shows a failure of reading the list afterwards and keeps the list', async () => {
      await loaded([summary('a')], folder);
      const done = store.loadOlder();
      mail.loadOlders[0].resolve({ loaded: 3, hasMore: false });
      await flush();
      mail.lists.at(-1)!.reject(new ApiError(500, 'Server error', 'Try later'));
      await done;
      expect(store.olderError()).toBe('Try later');
      expect(store.loadingOlder()).toBe(false);
      expect(ids()).toEqual(['a']);
    });

    it('starts one request at a time', async () => {
      await loaded([summary('a')], folder);
      void store.loadOlder();
      void store.loadOlder();
      expect(mail.loadOlders).toHaveLength(1);
    });

    it('does nothing where it is not offered', async () => {
      await loaded([summary('a')], { folderId: null, threaded: false });
      await store.loadOlder();
      expect(mail.loadOlders).toEqual([]);
      expect(store.loadingOlder()).toBe(false);
    });

    it('drops the answer when another folder was opened meanwhile', async () => {
      await loaded([summary('a')], folder);
      const done = store.loadOlder();
      await loaded([summary('x', { folderId: OTHER })], { folderId: OTHER, threaded: false });
      mail.loadOlders[0].resolve({ loaded: 5, hasMore: false });
      await done;
      expect(ids()).toEqual(['x']);
      expect(store.olderError()).toBeNull();
    });

    it('can load older in the next folder after leaving one while it loaded older', async () => {
      await loaded([summary('a')], folder);
      const done = store.loadOlder();
      await loaded([summary('x', { folderId: OTHER })], { folderId: OTHER, threaded: false });
      mail.loadOlders[0].reject(new ApiError(503, 'Unavailable', 'Mail server unreachable'));
      await done;

      // The progress of the left folder must not stick to the new one.
      expect(store.loadingOlder()).toBe(false);
      void store.loadOlder();
      expect(mail.loadOlders.map((call) => call.folderId)).toEqual([INBOX, OTHER]);
    });

    it('clears the message when the folder is loaded again', async () => {
      await loaded([summary('a')], folder);
      const done = store.loadOlder();
      mail.loadOlders[0].resolve({ loaded: 0, hasMore: false });
      await done;
      expect(store.olderNote()).not.toBeNull();
      await loaded([summary('a')], folder);
      expect(store.olderNote()).toBeNull();
    });

    it('ends the progress when the list is reloaded or restored while it loads older', async () => {
      await loaded([summary('a')], folder);
      const snapshot = store.snapshot()!;
      void store.loadOlder();
      void store.reload();
      expect(store.loadingOlder()).toBe(false);
      mail.lists.at(-1)!.resolve({ items: [summary('a')], nextCursor: null });
      await flush();

      void store.loadOlder();
      expect(store.loadingOlder()).toBe(true);
      store.restore(snapshot);
      expect(store.loadingOlder()).toBe(false);
      // The dropped requests' answers change nothing.
      mail.loadOlders[0].resolve({ loaded: 0, hasMore: false });
      mail.loadOlders[1].reject(new ApiError(503, 'Unavailable', 'Mail server unreachable'));
      await flush();
      expect(store.loadingOlder()).toBe(false);
      expect(store.olderNote()).toBeNull();
      expect(store.olderError()).toBeNull();
      expect(ids()).toEqual(['a']);
    });

    it('does not undo an action that ran while it read the list; it reads the list again after it', async () => {
      await loaded([summary('a'), summary('b')], folder);
      const done = store.loadOlder();
      mail.loadOlders[0].resolve({ loaded: 2, hasMore: false });
      await flush();
      const stale = mail.lists.at(-1)!;

      // Archive 'a' while the list is read: the read may be from before the archive.
      const archived = store.apply('ARCHIVE', undefined, ['a']);
      await flush();
      expect(ids()).toEqual(['b']);
      stale.resolve({
        items: [summary('a'), summary('b'), summary('c'), summary('d')],
        nextCursor: null,
      });
      await flush();
      expect(ids()).toEqual(['b']);
      expect(store.loadingOlder()).toBe(true);
      expect(mail.lists.at(-1)).toBe(stale);

      // Once the archive is done, the list is read again and shown.
      mail.succeed();
      expect(await archived).toBe(true);
      await flush();
      expect(mail.lists.at(-1)).not.toBe(stale);
      mail.lists.at(-1)!.resolve({
        items: [summary('b'), summary('c'), summary('d')],
        nextCursor: null,
      });
      await done;
      expect(ids()).toEqual(['b', 'c', 'd']);
      expect(store.loadingOlder()).toBe(false);
    });

    it('ends when the list is reloaded while it waits for an action', async () => {
      await loaded([summary('a'), summary('b')], folder);
      const archived = store.apply('ARCHIVE', undefined, ['a']);
      await flush();
      const done = store.loadOlder();
      mail.loadOlders[0].resolve({ loaded: 2, hasMore: false });
      await flush();
      const lists = mail.lists.length;

      void store.reload();
      expect(store.loadingOlder()).toBe(false);
      mail.lists.at(-1)!.resolve({ items: [summary('b')], nextCursor: null });
      await flush();

      // The action ends: the dropped load-older finishes without reading or showing anything.
      mail.succeed();
      expect(await archived).toBe(true);
      await done;
      expect(mail.lists).toHaveLength(lists + 1);
      expect(store.loadingOlder()).toBe(false);
      expect(ids()).toEqual(['b']);
    });

    it('reads the list again when an action changed it while it was read', async () => {
      await loaded([summary('a'), summary('b')], folder);
      const done = store.loadOlder();
      mail.loadOlders[0].resolve({ loaded: 1, hasMore: false });
      await flush();
      const stale = mail.lists.at(-1)!;

      const starred = store.apply('FLAG', undefined, ['b']);
      await flush();
      mail.succeed();
      await starred;
      // The action is done, but the read started before it.
      stale.resolve({ items: [summary('a'), summary('b'), summary('c')], nextCursor: null });
      await flush();
      expect(store.items()!.find((item) => item.id === 'b')?.flagged).toBe(true);
      expect(mail.lists.at(-1)).not.toBe(stale);

      mail.lists.at(-1)!.resolve({
        items: [summary('a'), summary('b', { flagged: true }), summary('c')],
        nextCursor: null,
      });
      await done;
      expect(ids()).toEqual(['a', 'b', 'c']);
      expect(store.items()![1].flagged).toBe(true);
    });

    it('drops the list it read when the list is reloaded while it reads it', async () => {
      await loaded([summary('a')], folder);
      const done = store.loadOlder();
      mail.loadOlders[0].resolve({ loaded: 1, hasMore: false });
      await flush();
      const stale = mail.lists.at(-1)!;

      void store.reload();
      const fresh = mail.lists.at(-1)!;
      expect(fresh).not.toBe(stale);
      fresh.resolve({ items: [summary('x')], nextCursor: null });
      await flush();
      // The older read answers last: it must not replace the reloaded list.
      stale.resolve({ items: [summary('a'), summary('b')], nextCursor: null });
      await done;

      expect(ids()).toEqual(['x']);
      expect(store.loadingOlder()).toBe(false);
      expect(store.olderError()).toBeNull();
      expect(store.olderNote()).toBeNull();
    });

    it('puts the cursor on the first message when its message is gone after loading older', async () => {
      await loaded([summary('a'), summary('b')], folder);
      store.setCursor('b');
      const done = store.loadOlder();
      mail.loadOlders[0].resolve({ loaded: 1, hasMore: false });
      await flush();
      mail.lists.at(-1)!.resolve({
        items: [summary('a'), summary('c'), summary('d')],
        nextCursor: null,
      });
      await done;

      expect(ids()).toEqual(['a', 'c', 'd']);
      expect(store.cursorId()).toBe('a');
    });

    it('puts the cursor on the first newly cached message of an empty folder', async () => {
      await loaded([], folder);
      expect(store.cursorId()).toBeNull();
      const done = store.loadOlder();
      mail.loadOlders[0].resolve({ loaded: 2, hasMore: false });
      await flush();
      expect(mail.lists.at(-1)).toMatchObject({ limit: 2 });
      mail.lists.at(-1)!.resolve({ items: [summary('c'), summary('d')], nextCursor: null });
      await done;

      expect(ids()).toEqual(['c', 'd']);
      expect(store.cursorId()).toBe('c');
    });
  });
});

describe('withAction', () => {
  it('changes the flags of an item', () => {
    const item = summary('a', { threadUnread: 0, seen: true });
    expect(withAction(item, 'UNREAD')).toMatchObject({ seen: false, threadUnread: 1 });
    expect(withAction(item, 'FLAG').flagged).toBe(true);
    expect(withAction({ ...item, flagged: true }, 'UNFLAG').flagged).toBe(false);
    expect(withAction(item, 'ARCHIVE')).toBe(item);
  });
});
