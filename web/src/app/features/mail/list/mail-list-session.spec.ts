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

  it('knows nothing before a list was saved', () => {
    expect(session.position('a')).toBeNull();
    expect(session.remove('a')).toBeNull();
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

  it('removes a message and names the next one, else the one before', () => {
    session.save(saved({ cursorId: 'b' }));
    expect(session.remove('b')).toBe('c');
    expect(session.saved()?.items.map((item) => item.id)).toEqual(['a', 'c']);
    expect(session.saved()?.cursorId).toBe('c');
    expect(session.remove('c')).toBe('a');
    expect(session.remove('a')).toBeNull();
    expect(session.saved()?.items).toEqual([]);
  });

  it('removes a message from the saved list also when it was not opened from it', () => {
    session.save(saved({ cursorId: 'a' }));
    previous.set({ url: '/new' });
    expect(session.remove('a')).toBeNull();
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
