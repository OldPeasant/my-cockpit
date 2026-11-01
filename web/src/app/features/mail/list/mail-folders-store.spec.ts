import { TestBed } from '@angular/core/testing';
import { ChangeEvent, MailAccountResponse, MailFolderResponse } from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { ApiError } from '../../../core/http';
import { folderTree, MailFoldersStore } from './mail-folders-store';
import { MAIL_ACCOUNT_EVENT, MAIL_FOLDER_EVENT, REFRESH_DEBOUNCE_MS } from './mail-list-store';
import { FakeEvents } from './mail-testing';
import { MailService } from './mail.service';

function account(id: string, displayName: string): MailAccountResponse {
  return { id, displayName, emailAddress: `${id}@example.test` } as MailAccountResponse;
}

function folder(
  id: string,
  path: string,
  extra: Partial<MailFolderResponse> = {},
): MailFolderResponse {
  return {
    id,
    accountId: 'acc-1',
    path,
    name: path.split('/').at(-1)!,
    delimiter: '/',
    roleOverridden: false,
    selectable: true,
    version: 0,
    ...extra,
  };
}

const tree = (folders: MailFolderResponse[]) =>
  folderTree(folders).map((node) => `${'  '.repeat(node.depth)}${node.folder.path}`);

describe('folderTree', () => {
  it('puts role folders first in role order, then the others by name', () => {
    expect(
      tree([
        folder('z', 'Zeta'),
        folder('t', 'Trash', { role: 'TRASH' }),
        folder('a', 'Alpha'),
        folder('s', 'Sent', { role: 'SENT' }),
        folder('i', 'INBOX', { role: 'INBOX' }),
        folder('sp', 'Junk', { role: 'SPAM' }),
        folder('ar', 'Archive', { role: 'ARCHIVE' }),
        folder('d', 'Drafts', { role: 'DRAFTS' }),
      ]),
    ).toEqual(['INBOX', 'Drafts', 'Sent', 'Archive', 'Junk', 'Trash', 'Alpha', 'Zeta']);
  });

  it('nests children under their parent path, by name, at any depth', () => {
    expect(
      tree([
        folder('p2', 'Projects/Beta'),
        folder('p', 'Projects'),
        folder('p1', 'Projects/Alpha'),
        folder('p11', 'Projects/Alpha/Old'),
        folder('i', 'INBOX', { role: 'INBOX' }),
        folder('i1', 'INBOX/Receipts'),
      ]),
    ).toEqual([
      'INBOX',
      '  INBOX/Receipts',
      'Projects',
      '  Projects/Alpha',
      '    Projects/Alpha/Old',
      '  Projects/Beta',
    ]);
    const depths = folderTree([folder('p', 'Projects'), folder('p1', 'Projects/Alpha')]).map(
      (node) => node.depth,
    );
    expect(depths).toEqual([0, 1]);
  });

  it('does not rank role folders among children: they sort by name', () => {
    expect(
      tree([
        folder('g', '[Gmail]'),
        folder('gt', '[Gmail]/Trash', { role: 'TRASH' }),
        folder('ga', '[Gmail]/All Mail', { role: 'ARCHIVE' }),
      ]),
    ).toEqual(['[Gmail]', '  [Gmail]/All Mail', '  [Gmail]/Trash']);
  });

  it('shows a folder whose parent is not cached at the top level', () => {
    expect(tree([folder('o', 'Lost/Orphan'), folder('a', 'Alpha')])).toEqual([
      'Alpha',
      'Lost/Orphan',
    ]);
  });

  it('honours the delimiter of the folder and treats folders without one as top level', () => {
    const dotted = [
      folder('p', 'Projects', { delimiter: '.' }),
      folder('p1', 'Projects.Alpha', { delimiter: '.', name: 'Alpha' }),
      folder('n', 'Projects/NoDelimiter', { delimiter: undefined }),
    ];
    // "NoDelimiter" sorts before "Projects" by name, and has no parent.
    expect(tree(dotted)).toEqual(['Projects/NoDelimiter', 'Projects', '  Projects.Alpha']);
  });

  it('breaks name ties by path and handles an empty list', () => {
    expect(
      tree([folder('b', 'B/Same', { name: 'Same' }), folder('a', 'A/Same', { name: 'Same' })]),
    ).toEqual(['A/Same', 'B/Same']);
    expect(folderTree([])).toEqual([]);
  });
});

describe('MailFoldersStore', () => {
  let events: FakeEvents;
  let mail: {
    accounts: ReturnType<typeof vi.fn>;
    folders: ReturnType<typeof vi.fn>;
  };
  let store: MailFoldersStore;

  const WORK = [
    folder('w-in', 'INBOX', { role: 'INBOX' }),
    folder('w-proj', 'Projects'),
    folder('w-group', '[Gmail]', { selectable: false }),
    folder('w-arch', 'Archive', { role: 'ARCHIVE' }),
  ];
  const HOME = [folder('h-in', 'INBOX', { role: 'INBOX', accountId: 'acc-2' })];

  beforeEach(() => {
    events = new FakeEvents();
    mail = {
      accounts: vi.fn(() => Promise.resolve([account('acc-1', 'Work'), account('acc-2', 'Home')])),
      folders: vi.fn((id: string) => Promise.resolve(id === 'acc-1' ? WORK : HOME)),
    };
    TestBed.configureTestingModule({
      providers: [
        MailFoldersStore,
        { provide: MailService, useValue: mail },
        { provide: ChangeEventsService, useValue: events },
      ],
    });
    store = TestBed.inject(MailFoldersStore);
  });

  it('loads every account with its folders in tree order and indexes them', async () => {
    expect(store.trees()).toBeNull();
    await store.load();
    expect(store.trees()!.map((t) => t.account.displayName)).toEqual(['Work', 'Home']);
    expect(store.trees()![0].folders.map((node) => node.folder.id)).toEqual([
      'w-in',
      'w-arch',
      'w-group',
      'w-proj',
    ]);
    expect(store.folderById().get('h-in')?.accountId).toBe('acc-2');
    expect(store.accountById().get('acc-2')?.displayName).toBe('Home');
    expect(store.error()).toBeNull();
  });

  it('offers selectable folders of the account except the current one as move targets', async () => {
    await store.load();
    expect(store.moveTargets('acc-1', 'w-in').map((node) => node.folder.id)).toEqual([
      'w-arch',
      'w-proj',
    ]);
    expect(store.moveTargets('acc-1').map((node) => node.folder.id)).toEqual([
      'w-in',
      'w-arch',
      'w-proj',
    ]);
    expect(store.moveTargets('no-such-account')).toEqual([]);
  });

  it('has no move targets before loading', () => {
    expect(store.moveTargets('acc-1')).toEqual([]);
  });

  it('reports the server message of a failed load, and a generic one otherwise', async () => {
    mail.accounts.mockRejectedValueOnce(new ApiError(503, 'Unavailable', 'Mail is down'));
    await store.load();
    expect(store.error()).toBe('Mail is down');
    expect(store.trees()).toBeNull();

    mail.folders.mockRejectedValueOnce(new Error('offline'));
    await store.load();
    expect(store.error()).toBe('The folders could not be loaded.');

    await store.load();
    expect(store.error()).toBeNull();
    expect(store.trees()).toHaveLength(2);
  });

  it('ignores the answer of an earlier load that arrives last', async () => {
    let releaseFirst!: (value: MailAccountResponse[]) => void;
    mail.accounts.mockImplementationOnce(
      () => new Promise<MailAccountResponse[]>((resolve) => (releaseFirst = resolve)),
    );
    const first = store.load();
    await store.load();
    expect(store.trees()).toHaveLength(2);
    releaseFirst([account('acc-old', 'Stale')]);
    await first;
    expect(store.trees()!.map((t) => t.account.id)).toEqual(['acc-1', 'acc-2']);
  });

  it('ignores the error of an earlier load that fails last', async () => {
    let failFirst!: (error: unknown) => void;
    mail.accounts.mockImplementationOnce(
      () => new Promise<MailAccountResponse[]>((_, reject) => (failFirst = reject)),
    );
    const first = store.load();
    await store.load();
    failFirst(new Error('late failure'));
    await first;
    expect(store.error()).toBeNull();
    expect(store.trees()).toHaveLength(2);
  });

  describe('local folder changes', () => {
    const NESTED = [
      folder('n-in', 'INBOX', { role: 'INBOX' }),
      folder('n-p', 'Projects'),
      folder('n-pa', 'Projects/Alpha'),
      folder('n-pa1', 'Projects/Alpha/Old'),
      // Shares the prefix "Projects" but is a sibling, not a child.
      folder('n-p2', 'Projects2'),
      folder('n-p2a', 'Projects2/Alpha'),
    ];
    // The other account has a folder with the same path: it must never be touched.
    const OTHER = [
      folder('o-in', 'INBOX', { role: 'INBOX', accountId: 'acc-2' }),
      folder('o-p', 'Projects', { accountId: 'acc-2' }),
      folder('o-pa', 'Projects/Alpha', { accountId: 'acc-2' }),
    ];

    const paths = (accountIndex = 0) =>
      store.trees()![accountIndex].folders.map((n) => `${'  '.repeat(n.depth)}${n.folder.path}`);
    const otherPaths = () => paths(1);
    const byId = (id: string) => NESTED.find((f) => f.id === id)!;

    beforeEach(async () => {
      mail.folders.mockImplementation((id: string) =>
        Promise.resolve(id === 'acc-1' ? NESTED : OTHER),
      );
      await store.load();
      // The background reload after a change never answers: the tests see the local update alone.
      mail.accounts.mockReset();
      mail.accounts.mockReturnValue(new Promise(() => undefined));
    });

    it('subtree returns the folder and every folder below it, not a prefix sibling', () => {
      const ids = store.subtree(byId('n-p')).map((f) => f.id);
      expect(ids.sort()).toEqual(['n-p', 'n-pa', 'n-pa1']);
      expect(store.subtree(byId('n-pa1')).map((f) => f.id)).toEqual(['n-pa1']);
    });

    it('subtree stays within the account of the folder', () => {
      expect(
        store
          .subtree(OTHER[1])
          .map((f) => f.id)
          .sort(),
      ).toEqual(['o-p', 'o-pa']);
    });

    it('subtree of a folder without delimiter (flat namespace) is the folder alone', () => {
      expect(store.subtree({ ...byId('n-p'), delimiter: undefined }).map((f) => f.id)).toEqual([
        'n-p',
      ]);
    });

    it('subtree is empty for an unknown account', () => {
      expect(store.subtree({ ...byId('n-p'), accountId: 'acc-x' })).toEqual([]);
    });

    it('added shows a new folder at once in tree order and reloads in the background', () => {
      store.added(folder('n-pb', 'Projects/Beta', { version: 0 }));
      expect(paths()).toEqual([
        'INBOX',
        'Projects',
        '  Projects/Alpha',
        '    Projects/Alpha/Old',
        '  Projects/Beta',
        'Projects2',
        '  Projects2/Alpha',
      ]);
      expect(store.folderById().get('n-pb')?.path).toBe('Projects/Beta');
      expect(otherPaths()).toEqual(['INBOX', 'Projects', '  Projects/Alpha']);
      expect(mail.accounts).toHaveBeenCalledTimes(1);
    });

    it('added replaces a folder it already knows instead of listing it twice', () => {
      store.added({ ...byId('n-p2'), version: 7 });
      const ids = store.trees()![0].folders.map((n) => n.folder.id);
      expect(ids.filter((id) => id === 'n-p2')).toHaveLength(1);
      expect(store.folderById().get('n-p2')?.version).toBe(7);
    });

    it('added puts the folder into its own account only', () => {
      store.added(folder('o-new', 'Receipts', { accountId: 'acc-2' }));
      expect(otherPaths()).toEqual(['INBOX', 'Projects', '  Projects/Alpha', 'Receipts']);
      expect(paths()).not.toContain('Receipts');
    });

    it('renamed moves the subfolders along with new paths and the same ids', () => {
      store.renamed(byId('n-p'), folder('n-p', 'Clients', { version: 1 }));
      expect(paths()).toEqual([
        'INBOX',
        'Clients',
        '  Clients/Alpha',
        '    Clients/Alpha/Old',
        'Projects2',
        '  Projects2/Alpha',
      ]);
      expect(store.folderById().get('n-pa1')?.path).toBe('Clients/Alpha/Old');
      expect(store.folderById().get('n-p')?.version).toBe(1);
      expect(mail.accounts).toHaveBeenCalledTimes(1);
    });

    it('renamed leaves a sibling that only shares the prefix, and the other account, alone', () => {
      store.renamed(byId('n-p'), folder('n-p', 'Clients'));
      expect(store.folderById().get('n-p2')?.path).toBe('Projects2');
      expect(store.folderById().get('n-p2a')?.path).toBe('Projects2/Alpha');
      expect(otherPaths()).toEqual(['INBOX', 'Projects', '  Projects/Alpha']);
    });

    it('renamed of a nested folder keeps it under its parent', () => {
      store.renamed(byId('n-pa'), folder('n-pa', 'Projects/Zulu'));
      expect(paths()).toEqual([
        'INBOX',
        'Projects',
        '  Projects/Zulu',
        '    Projects/Zulu/Old',
        'Projects2',
        '  Projects2/Alpha',
      ]);
    });

    it('removed drops the folder and its subfolders, returns their ids, and reloads', () => {
      const ids = store.removed(byId('n-p'));
      expect([...ids].sort()).toEqual(['n-p', 'n-pa', 'n-pa1']);
      expect(paths()).toEqual(['INBOX', 'Projects2', '  Projects2/Alpha']);
      expect(otherPaths()).toEqual(['INBOX', 'Projects', '  Projects/Alpha']);
      expect(mail.accounts).toHaveBeenCalledTimes(1);
    });

    it('removed of a leaf drops only the leaf', () => {
      expect([...store.removed(byId('n-p2a'))]).toEqual(['n-p2a']);
      expect(paths()).toContain('Projects2');
      expect(paths()).not.toContain('  Projects2/Alpha');
    });

    it('a local change before the first load keeps the tree empty', () => {
      store.trees.set(null);
      store.added(folder('x', 'Receipts'));
      expect(store.trees()).toBeNull();
      expect(store.removed(byId('n-p')).size).toBe(0);
    });
  });

  describe('live reloads', () => {
    beforeEach(async () => {
      vi.useFakeTimers();
      await store.load();
      mail.accounts.mockClear();
    });
    afterEach(() => vi.useRealTimers());

    async function emit(event: Partial<ChangeEvent>) {
      events.changes.next({ at: '', op: 'UPDATED', id: 'w-in', type: MAIL_FOLDER_EVENT, ...event });
      await vi.advanceTimersByTimeAsync(REFRESH_DEBOUNCE_MS);
    }

    it('does not reload for message changes in a known folder', async () => {
      await emit({ id: 'w-in' });
      expect(mail.accounts).not.toHaveBeenCalled();
    });

    it('reloads for an unknown folder, a created or deleted folder and an account change', async () => {
      await emit({ id: 'new-folder' });
      expect(mail.accounts).toHaveBeenCalledTimes(1);
      await emit({ id: 'w-in', op: 'CREATED' });
      expect(mail.accounts).toHaveBeenCalledTimes(2);
      await emit({ id: 'w-proj', op: 'DELETED' });
      expect(mail.accounts).toHaveBeenCalledTimes(3);
      await emit({ id: 'acc-3', op: 'CREATED', type: MAIL_ACCOUNT_EVENT });
      expect(mail.accounts).toHaveBeenCalledTimes(4);
    });

    it('reloads after an SSE reconnect', async () => {
      events.resync$.next();
      await vi.advanceTimersByTimeAsync(REFRESH_DEBOUNCE_MS);
      expect(mail.accounts).toHaveBeenCalledTimes(1);
    });

    it('reloads once for a burst of events', async () => {
      events.changes.next({ at: '', op: 'CREATED', id: 'x', type: MAIL_ACCOUNT_EVENT });
      events.changes.next({ at: '', op: 'UPDATED', id: 'y', type: MAIL_FOLDER_EVENT });
      events.resync$.next();
      await vi.advanceTimersByTimeAsync(REFRESH_DEBOUNCE_MS - 1);
      expect(mail.accounts).not.toHaveBeenCalled();
      await vi.advanceTimersByTimeAsync(1);
      expect(mail.accounts).toHaveBeenCalledTimes(1);
    });

    it('ignores other event types', async () => {
      await emit({ type: 'TASK', id: 'whatever', op: 'CREATED' });
      expect(mail.accounts).not.toHaveBeenCalled();
    });
  });
});
