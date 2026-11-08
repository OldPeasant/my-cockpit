import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { BackNavigation } from '../../../shared/navigation';
import { MailListSession, SavedMailList, sameScope } from './mail-list-session';
import { FakeMail, summary } from './mail-testing';
import { MailService } from './mail.service';

const flush = async () => {
  for (let i = 0; i < 10; i++) await Promise.resolve();
};

describe('MailListSession', () => {
  let session: MailListSession;
  let mail: FakeMail;
  let previous: ReturnType<typeof signal<{ url: string } | null>>;

  const LIST_URL = '/mail?q=report';

  function saved(extra: Partial<SavedMailList> = {}): SavedMailList {
    return {
      url: LIST_URL,
      scrollTop: 120,
      scope: { folderId: null, threaded: false, search: { query: 'report', server: false } },
      items: [summary('a'), summary('b', { seen: true }), summary('c')],
      nextCursor: null,
      cursorId: 'a',
      searchResult: { hasMore: false, failedAccountIds: [] },
      ...extra,
    };
  }

  beforeEach(() => {
    mail = new FakeMail();
    previous = signal<{ url: string } | null>({ url: LIST_URL });
    TestBed.configureTestingModule({
      providers: [
        { provide: MailService, useValue: mail },
        { provide: BackNavigation, useValue: { previous } },
      ],
    });
    session = TestBed.inject(MailListSession);
  });

  it('knows nothing before a list was saved', async () => {
    expect(session.position('a')).toBeNull();
    expect(await session.remove('a')).toBeNull();
    session.visited('a');
    expect(session.saved()).toBeNull();
  });

  it('gives the position of a message opened from the saved list', () => {
    session.save(saved());
    expect(session.position('a')).toEqual({
      index: 0,
      total: 3,
      more: false,
      previousId: null,
      nextId: 'b',
    });
    expect(session.position('c')).toMatchObject({ index: 2, previousId: 'b', nextId: null });
    expect(session.position('zzz')).toBeNull();
  });

  it('gives no position when another page lies behind the message', () => {
    session.save(saved());
    previous.set({ url: '/new' });
    expect(session.position('a')).toBeNull();
    previous.set(null);
    expect(session.position('a')).toBeNull();
  });

  it('steps to the previous and next message, and stops at the ends', async () => {
    session.save(saved());
    expect(await session.step('b', 1)).toBe('c');
    expect(await session.step('b', -1)).toBe('a');
    expect(await session.step('a', -1)).toBeNull();
    expect(await session.step('c', 1)).toBeNull();
    expect(mail.lists).toHaveLength(0);
  });

  it('loads the next page of a folder list at its end', async () => {
    const scope = { folderId: 'f-inbox', threaded: false };
    session.save(saved({ url: LIST_URL, scope, searchResult: null, nextCursor: 'cur-1' }));
    expect(session.position('c')).toMatchObject({ nextId: null, more: true });
    const step = session.step('c', 1);
    await flush();
    expect(mail.lists).toEqual([expect.objectContaining({ scope, cursor: 'cur-1' })]);
    mail.lists[0].resolve({ items: [summary('c'), summary('d')], nextCursor: null });
    expect(await step).toBe('d');
    expect(session.saved()?.items.map((item) => item.id)).toEqual(['a', 'b', 'c', 'd']);
    expect(session.position('d')).toMatchObject({ more: false, nextId: null });
  });

  it('stays at the last message when the next page fails', async () => {
    session.save(saved({ scope: { folderId: null, threaded: false }, nextCursor: 'cur-1' }));
    const step = session.step('c', 1);
    await flush();
    mail.lists[0].reject(new Error('offline'));
    expect(await step).toBeNull();
    expect(session.saved()?.nextCursor).toBe('cur-1');
  });

  it('drops a next page that arrives after another list was saved', async () => {
    session.save(saved({ scope: { folderId: null, threaded: false }, nextCursor: 'cur-1' }));
    const step = session.step('c', 1);
    await flush();
    session.save(saved({ url: '/mail/folders/x', items: [summary('x')] }));
    mail.lists[0].resolve({ items: [summary('d')], nextCursor: null });
    expect(await step).toBeNull();
    expect(session.saved()?.items.map((item) => item.id)).toEqual(['x']);
  });

  it('asks for the next page once when next is pressed again while it loads', async () => {
    session.save(saved({ scope: { folderId: null, threaded: false }, nextCursor: 'cur-1' }));
    const first = session.step('c', 1);
    const second = session.step('c', 1);
    await flush();
    expect(mail.lists).toHaveLength(1);
    mail.lists[0].resolve({ items: [summary('d'), summary('e')], nextCursor: 'cur-2' });
    expect(await first).toBe('d');
    expect(await second).toBe('d');
    expect(session.saved()?.items.map((item) => item.id)).toEqual(['a', 'b', 'c', 'd', 'e']);
    expect(session.position('e')).toMatchObject({ more: true, nextId: null });
  });

  it('drops a next page when the same list was saved anew with another cursor meanwhile', async () => {
    const scope = { folderId: null, threaded: false };
    session.save(saved({ scope, nextCursor: 'cur-1' }));
    const step = session.step('c', 1);
    await flush();
    // The list page was shown again, loaded further and left again.
    session.save(saved({ scope, nextCursor: 'cur-5', items: [summary('a'), summary('c')] }));
    mail.lists[0].resolve({ items: [summary('d')], nextCursor: 'cur-2' });
    expect(await step).toBeNull();
    expect(session.saved()?.items.map((item) => item.id)).toEqual(['a', 'c']);
    expect(session.saved()?.nextCursor).toBe('cur-5');
  });

  it('gives no step for a message that is not in the list or not opened from it', async () => {
    session.save(saved());
    expect(await session.step('zzz', 1)).toBeNull();
    previous.set({ url: '/new' });
    expect(await session.step('a', 1)).toBeNull();
  });

  it('keeps a read message and the counts of a message without thread counts as they are', () => {
    session.save(saved({ items: [summary('a', { seen: true, threadUnread: 3 }), summary('b')] }));
    session.visited('a');
    expect(session.saved()?.items[0]).toMatchObject({ seen: true, threadUnread: 3 });
    session.save(saved({ items: [summary('a', { threadUnread: undefined })] }));
    session.visited('a');
    expect(session.saved()?.items[0]).toMatchObject({ seen: true, threadUnread: 0 });
  });

  it('keeps the list cursor when another message than the cursor is removed', async () => {
    session.save(saved({ cursorId: 'a' }));
    expect(await session.remove('c')).toBe('b');
    expect(session.saved()?.cursorId).toBe('a');
    expect(await session.remove('unknown')).toBeNull();
    expect(session.saved()?.items.map((item) => item.id)).toEqual(['a', 'b']);
  });

  it('never loads more for search results', async () => {
    session.save(saved({ nextCursor: 'cur-1' }));
    expect(session.position('c')?.more).toBe(false);
    expect(await session.step('c', 1)).toBeNull();
    expect(mail.lists).toHaveLength(0);
  });

  it('moves the cursor to a visited message and shows it as read', () => {
    session.save(saved({ items: [summary('a', { threadUnread: 2 }), summary('b')] }));
    session.visited('a');
    expect(session.saved()?.cursorId).toBe('a');
    expect(session.saved()?.items[0]).toMatchObject({ seen: true, threadUnread: 1 });
    session.visited('unknown');
    expect(session.saved()?.cursorId).toBe('a');
  });

  it('removes a message and names the next one, else the one before', async () => {
    session.save(saved({ cursorId: 'b' }));
    expect(await session.remove('b')).toBe('c');
    expect(session.saved()?.items.map((item) => item.id)).toEqual(['a', 'c']);
    expect(session.saved()?.cursorId).toBe('c');
    expect(await session.remove('c')).toBe('a');
    expect(await session.remove('a')).toBeNull();
    expect(session.saved()?.items).toEqual([]);
  });

  it('loads the next page of a folder list before removing its last loaded message', async () => {
    const scope = { folderId: 'f-inbox', threaded: false };
    session.save(saved({ scope, searchResult: null, nextCursor: 'cur-1', cursorId: 'c' }));
    const removed = session.remove('c');
    await flush();
    expect(mail.lists).toEqual([expect.objectContaining({ scope, cursor: 'cur-1' })]);
    mail.lists[0].resolve({ items: [summary('d'), summary('e')], nextCursor: 'cur-2' });
    expect(await removed).toBe('d');
    expect(session.saved()?.items.map((item) => item.id)).toEqual(['a', 'b', 'd', 'e']);
    expect(session.saved()?.cursorId).toBe('d');
    expect(session.saved()?.nextCursor).toBe('cur-2');
  });

  it('removes the last loaded message and names the one before when the next page fails', async () => {
    session.save(saved({ scope: { folderId: null, threaded: false }, nextCursor: 'cur-1' }));
    const removed = session.remove('c');
    await flush();
    mail.lists[0].reject(new Error('offline'));
    expect(await removed).toBe('b');
    expect(session.saved()?.items.map((item) => item.id)).toEqual(['a', 'b']);
  });

  it('removes the last message of a folder list without loading more when it has no next page', async () => {
    session.save(saved({ scope: { folderId: null, threaded: false }, nextCursor: null }));
    expect(await session.remove('c')).toBe('b');
    expect(mail.lists).toHaveLength(0);
  });

  it('removes a message from the saved list also when it was not opened from it', async () => {
    session.save(saved({ cursorId: 'a' }));
    previous.set({ url: '/new' });
    expect(await session.remove('a')).toBeNull();
    expect(session.saved()?.items.map((item) => item.id)).toEqual(['b', 'c']);
    expect(session.saved()?.cursorId).toBe('b');
  });

  it('restores only the list of the same URL and scope', () => {
    session.save(saved());
    const scope = { folderId: null, threaded: true, search: { query: 'report', server: false } };
    expect(session.restorable(LIST_URL, scope)).not.toBeNull();
    expect(session.restorable('/mail', scope)).toBeNull();
    expect(
      session.restorable(LIST_URL, { ...scope, search: { query: 'report', server: true } }),
    ).toBeNull();
  });

  it('compares scopes by folder, search and the view of a folder list', () => {
    const search = { query: 'q', server: false };
    expect(sameScope({ folderId: 'f', threaded: false }, { folderId: 'f', threaded: false })).toBe(
      true,
    );
    expect(sameScope({ folderId: 'f', threaded: false }, { folderId: 'f', threaded: true })).toBe(
      false,
    );
    expect(sameScope({ folderId: 'f', threaded: false }, { folderId: 'g', threaded: false })).toBe(
      false,
    );
    expect(
      sameScope({ folderId: 'f', threaded: false, search }, { folderId: 'f', threaded: true }),
    ).toBe(false);
    expect(
      sameScope(
        { folderId: 'f', threaded: false, search },
        { folderId: 'f', threaded: true, search: { ...search } },
      ),
    ).toBe(true);
  });
});
