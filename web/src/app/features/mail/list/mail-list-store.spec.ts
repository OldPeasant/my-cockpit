import { TestBed } from '@angular/core/testing';
import { ChangeEvent } from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { ApiError, NotificationService } from '../../../core/http';
import {
  MAIL_FOLDER_EVENT,
  MailListStore,
  REFRESH_DEBOUNCE_MS,
  withAction,
} from './mail-list-store';
import { FakeEvents, FakeMail, INBOX, OTHER, summary } from './mail-testing';
import {
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

    it('marks a conversation unread with at least one unread message', async () => {
      await loaded([summary('a', { threadSize: 1, threadUnread: 0, seen: true })], {
        folderId: INBOX,
        threaded: true,
      });
      void store.apply('UNREAD');
      expect(store.items()![0]).toMatchObject({ seen: false, threadUnread: 1 });
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

    it('sends flat items as they are, without loading threads', async () => {
      await loaded([summary('a', { threadSize: 3 })]);
      const thread = vi.spyOn(mail, 'thread');
      void store.apply('READ');
      await flush();
      expect(thread).not.toHaveBeenCalled();
      expect(mail.applies[0].ids).toEqual(['a']);
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
