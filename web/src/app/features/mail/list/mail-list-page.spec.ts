import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { ActivatedRoute, convertToParamMap, ParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, Observable, of, Subject } from 'rxjs';
import { MailAccountResponse, MailFolderResponse } from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { NotificationService } from '../../../core/http';
import MailListPage, { senderName, THREADED_KEY } from './mail-list-page';
import { FakeEvents, FakeMail, summary } from './mail-testing';
import { ApiError } from '../../../core/http';
import { MailListStore } from './mail-list-store';
import { MailPage, MailService, MailSummary } from './mail.service';

const flush = async () => {
  for (let i = 0; i < 10; i++) await Promise.resolve();
  await new Promise((resolve) => setTimeout(resolve));
};

function account(id: string, displayName: string): MailAccountResponse {
  return { id, displayName, emailAddress: `${id}@example.test` } as MailAccountResponse;
}

function folder(id: string, accountId: string, path: string, role?: MailFolderResponse['role']) {
  return {
    id,
    accountId,
    path,
    name: path.split('/').at(-1)!,
    delimiter: '/',
    role,
    roleOverridden: false,
    selectable: true,
    version: 0,
  } satisfies MailFolderResponse;
}

describe('MailListPage', () => {
  let fixture: ComponentFixture<MailListPage>;
  let mail: FakeMail;
  let params: BehaviorSubject<ParamMap>;
  let dialogResult: string | undefined;
  let dialogOpened: unknown[];
  /** When set, the picker stays open until the test emits on it. */
  let dialogClosed: Observable<string | undefined> | undefined;

  async function create(
    folderId: string | null = 'inbox-1',
    options: {
      /** Adjusts the fake before the page starts. */
      setup?: (mail: FakeMail) => void;
      /** What the first page request answers: the default three items, a page, an error, or nothing yet. */
      firstPage?: MailPage | Error | 'pending';
    } = {},
  ) {
    mail = new FakeMail();
    mail.accounts = () => Promise.resolve([account('acc-1', 'Work'), account('acc-2', 'Home')]);
    mail.folders = (id: string) =>
      Promise.resolve(
        id === 'acc-1'
          ? [
              folder('inbox-1', 'acc-1', 'INBOX', 'INBOX'),
              folder('arch-1', 'acc-1', 'Archive', 'ARCHIVE'),
              folder('proj-1', 'acc-1', 'Projects'),
            ]
          : [folder('inbox-2', 'acc-2', 'INBOX', 'INBOX')],
      );
    params = new BehaviorSubject(convertToParamMap(folderId ? { folderId } : {}));
    dialogOpened = [];
    dialogResult = undefined;
    dialogClosed = undefined;
    options.setup?.(mail);
    TestBed.configureTestingModule({
      imports: [MailListPage],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap: params } },
        { provide: MailService, useValue: mail },
        { provide: ChangeEventsService, useValue: new FakeEvents() },
        {
          provide: NotificationService,
          useValue: { error: () => undefined, info: () => undefined },
        },
        {
          provide: MatDialog,
          useValue: {
            openDialogs: [],
            open: (_: unknown, config: unknown) => {
              dialogOpened.push(config);
              return { afterClosed: () => dialogClosed ?? of(dialogResult) };
            },
          },
        },
      ],
    });
    fixture = TestBed.createComponent(MailListPage);
    await flush();
    const first = options.firstPage;
    if (first === 'pending') {
      fixture.detectChanges();
      return;
    }
    if (first instanceof Error) mail.lists[0].reject(first);
    else if (first) mail.lists[0].resolve(first);
    else
      mail.lists[0].resolve({
        items: [
          summary('a', { subject: 'Report', folderId: folderId ?? 'inbox-1' }),
          summary('b', { subject: 'Lunch', seen: true, folderId: folderId ?? 'inbox-1' }),
          summary('c', {
            subject: 'Invoice',
            hasAttachments: true,
            flagged: true,
            folderId: folderId ?? 'inbox-1',
          }),
        ],
        nextCursor: null,
      });
    await flush();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const rows = () => [...el().querySelectorAll<HTMLElement>('li.item')];
  const press = (
    key: string,
    init: KeyboardEventInit = {},
    target: EventTarget = document.body,
  ) => {
    const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init });
    target.dispatchEvent(event);
    fixture.detectChanges();
    return event;
  };
  const button = (name: string) =>
    [...el().querySelectorAll<HTMLButtonElement>('.toolbar button')].find(
      (b) => b.textContent?.trim() === name,
    )!;

  afterEach(() => localStorage.removeItem(THREADED_KEY));

  it('shows the folder with its title, account, unread and cursor state', async () => {
    await create();
    expect(el().querySelector('h1')?.textContent).toBe('Inbox');
    expect(el().querySelector('.heading .account')?.textContent).toBe('Work');
    expect(rows().map((row) => row.querySelector('.subject')?.textContent)).toEqual([
      'Report',
      'Lunch',
      'Invoice',
    ]);
    expect(rows()[0].classList).toContain('unread');
    expect(rows()[1].classList).not.toContain('unread');
    expect(rows()[0].getAttribute('aria-current')).toBe('true');
    expect(rows()[2].querySelector('[aria-label="Has attachments"]')).not.toBeNull();
    expect(rows()[2].querySelector('[aria-label="Starred"]')).not.toBeNull();
    expect(mail.lists[0].scope).toEqual({ folderId: 'inbox-1', threaded: false });
  });

  it('shows the unified Inbox with account names when there are several accounts', async () => {
    await create(null);
    expect(el().querySelector('h1')?.textContent).toBe('All inboxes');
    expect(rows()[0].querySelector('.account-chip')?.textContent).toBe('Work');
    const tree = el().querySelector('app-mail-folder-tree')!;
    expect(tree.querySelector('[aria-current="page"]')?.textContent?.trim()).toBe('All inboxes');
    expect([...tree.querySelectorAll('h2')].map((h) => h.textContent?.trim())).toEqual([
      'Work',
      'Home',
    ]);
  });

  it('moves the cursor with j/k and selects with x', async () => {
    await create();
    press('j');
    expect(rows()[1].getAttribute('aria-current')).toBe('true');
    press('x');
    expect(rows()[1].classList).toContain('selected');
    expect(el().querySelector('.count')?.textContent).toContain('1 selected');
    press('k');
    expect(rows()[0].getAttribute('aria-current')).toBe('true');
    press('Escape');
    expect(rows()[1].classList).not.toContain('selected');
  });

  it.each([
    ['e', {}, 'ARCHIVE'],
    ['#', { shiftKey: true }, 'DELETE'],
    ['u', {}, 'UNREAD'],
    ['I', { shiftKey: true }, 'READ'],
    ['s', {}, 'FLAG'],
    ['!', { shiftKey: true }, 'SPAM'],
  ])('runs %s as %s on the cursor item', async (key, init, action) => {
    await create();
    const event = press(key, init);
    await flush();
    expect(event.defaultPrevented).toBe(true);
    expect(mail.applies[0]).toMatchObject({ action, ids: ['a'] });
  });

  it('ignores keys while typing, with modifiers and while a dialog is open', async () => {
    await create();
    const input = document.createElement('input');
    document.body.appendChild(input);
    press('e', {}, input);
    press('e', { ctrlKey: true });
    const dialog = TestBed.inject(MatDialog) as unknown as { openDialogs: unknown[] };
    dialog.openDialogs.push({});
    press('e');
    await flush();
    expect(mail.applies).toEqual([]);
    input.remove();
  });

  it('archives the selection from the toolbar and removes it at once', async () => {
    await create();
    (rows()[1].querySelector('input[type=checkbox]') as HTMLInputElement).click();
    (rows()[2].querySelector('input[type=checkbox]') as HTMLInputElement).click();
    fixture.detectChanges();
    button('Archive').click();
    fixture.detectChanges();
    expect(rows()).toHaveLength(1);
    await flush();
    expect(mail.applies[0]).toMatchObject({ action: 'ARCHIVE', ids: ['b', 'c'] });
  });

  it('stars, or unstars when every target is starred', async () => {
    await create();
    press('j');
    press('j');
    expect(button('Unstar')).toBeDefined();
    button('Unstar').click();
    await flush();
    expect(mail.applies[0]).toMatchObject({ action: 'UNFLAG', ids: ['c'] });
  });

  it('moves through the folder picker to a folder of the same account', async () => {
    await create();
    dialogResult = 'proj-1';
    press('v');
    await flush();
    const data = (dialogOpened[0] as { data: { folders: { folder: { id: string } }[] } }).data;
    expect(data.folders.map((node) => node.folder.id)).toEqual(['arch-1', 'proj-1']);
    expect(mail.applies[0]).toMatchObject({ action: 'MOVE', ids: ['a'], target: 'proj-1' });
  });

  it('does not move when the picker is cancelled', async () => {
    await create();
    button('Move').click();
    await flush();
    expect(dialogOpened).toHaveLength(1);
    expect(mail.applies).toEqual([]);
  });

  describe('when a refetch changes the list while the folder picker is open', () => {
    let closed: Subject<string | undefined>;

    async function openPicker() {
      closed = new Subject<string | undefined>();
      dialogClosed = closed;
      press('v');
      await flush();
      expect(dialogOpened).toHaveLength(1);
    }

    async function refetch(items: MailSummary[]) {
      void listStore().refresh();
      mail.lists.at(-1)!.resolve({ items, nextCursor: null });
      await flush();
      fixture.detectChanges();
    }

    it('does not move the item the cursor moved to', async () => {
      await create();
      await openPicker();
      await refetch([summary('b'), summary('c')]);
      expect(listStore().cursorId()).toBe('b');
      closed.next('proj-1');
      closed.complete();
      await flush();
      expect(mail.applies).toEqual([]);
      expect(
        listStore()
          .items()!
          .map((item) => item.id),
      ).toEqual(['b', 'c']);
    });

    it('moves the picked items that are still there, not the cursor item', async () => {
      await create();
      listStore().toggleSelected('b');
      listStore().toggleSelected('c');
      fixture.detectChanges();
      await openPicker();
      await refetch([summary('a'), summary('b')]);
      closed.next('proj-1');
      closed.complete();
      await flush();
      expect(mail.applies[0]).toMatchObject({ action: 'MOVE', ids: ['b'], target: 'proj-1' });
    });
  });

  it('moves focus with the keyboard cursor to its row', async () => {
    await create();
    const open = (index: number) => rows()[index].querySelector('button.open');
    press('j');
    expect(document.activeElement).toBe(open(1));
    press('j', { repeat: true });
    expect(document.activeElement).toBe(open(2));
    press('k');
    expect(document.activeElement).toBe(open(1));
    press('e');
    // The archived row goes; focus is on the next message, which the cursor is on now.
    expect(rows().map((row) => row.dataset['id'])).toEqual(['a', 'c']);
    expect(document.activeElement).toBe(open(1));
    expect(listStore().cursorId()).toBe('c');
  });

  it('cannot move items of several accounts at once', async () => {
    await create(null);
    mail.lists.length = 0;
    const store = fixture.debugElement.injector.get(
      (await import('./mail-list-store')).MailListStore,
    );
    store.items.update((items) => [...items!, summary('z', { accountId: 'acc-2' })]);
    store.toggleAll();
    fixture.detectChanges();
    expect(button('Move').disabled).toBe(true);
  });

  it('switches to conversations and remembers the choice', async () => {
    await create();
    const toggle = [...el().querySelectorAll<HTMLButtonElement>('.views button')];
    toggle[1].click();
    fixture.detectChanges();
    expect(localStorage.getItem(THREADED_KEY)).toBe('true');
    expect(mail.lists[1].scope).toEqual({ folderId: 'inbox-1', threaded: true });
    expect(toggle[1].getAttribute('aria-pressed')).toBe('true');
  });

  it('loads older messages from the button', async () => {
    await create();
    const store = fixture.debugElement.injector.get(
      (await import('./mail-list-store')).MailListStore,
    );
    store.nextCursor.set('next');
    fixture.detectChanges();
    el().querySelector<HTMLButtonElement>('.more button')!.click();
    expect(mail.lists[1].cursor).toBe('next');
  });

  it('loads the folder of a new route', async () => {
    await create();
    params.next(convertToParamMap({ folderId: 'proj-1' }));
    await flush();
    expect(mail.lists[1].scope.folderId).toBe('proj-1');
  });

  const listStore = () => fixture.debugElement.injector.get(MailListStore);

  describe('states', () => {
    it('shows a loading note until the first page arrives', async () => {
      await create('inbox-1', { firstPage: 'pending' });
      expect(el().querySelector('.note[role="status"]')?.textContent?.trim()).toBe(
        'Loading messages…',
      );
      expect(rows()).toEqual([]);
    });

    it('shows a load error and loads again from its button', async () => {
      await create('inbox-1', {
        firstPage: new ApiError(502, 'Bad gateway', 'Mail server down'),
      });
      const alert = el().querySelector('.load-error[role="alert"]')!;
      expect(alert.textContent).toContain('Mail server down');
      alert.querySelector('button')!.click();
      expect(mail.lists[1].scope).toEqual({ folderId: 'inbox-1', threaded: false });
      fixture.detectChanges();
      expect(el().querySelector('.load-error')).toBeNull();
    });

    it('asks to add an account when none is set up', async () => {
      await create(null, {
        setup: (fake) => (fake.accounts = () => Promise.resolve([])),
        firstPage: { items: [], nextCursor: null },
      });
      const empty = el().querySelector('.empty')!;
      expect(empty.textContent).toContain('No mail account is set up yet.');
      expect(empty.querySelector('a')?.getAttribute('href')).toBe('/settings');
    });

    it('says so when the folder is empty and disables the actions', async () => {
      await create('inbox-1', { firstPage: { items: [], nextCursor: null } });
      expect(el().querySelector('p.empty')?.textContent?.trim()).toBe('No messages here.');
      expect(button('Archive').disabled).toBe(true);
      expect(button('Move').disabled).toBe(true);
      expect(el().querySelector<HTMLInputElement>('.select-all')!.disabled).toBe(true);
    });
  });

  it('moves the cursor to a clicked row without opening or selecting it', async () => {
    await create();
    rows()[2].querySelector<HTMLButtonElement>('button.open')!.click();
    fixture.detectChanges();
    expect(rows()[2].getAttribute('aria-current')).toBe('true');
    expect(rows()[2].classList).not.toContain('selected');
  });

  it('selects all from the toolbar checkbox, indeterminate while only some are selected', async () => {
    await create();
    const all = el().querySelector<HTMLInputElement>('.select-all')!;
    rows()[0].querySelector<HTMLInputElement>('input[type=checkbox]')!.click();
    fixture.detectChanges();
    expect(all.indeterminate).toBe(true);
    all.click();
    fixture.detectChanges();
    expect(all.checked).toBe(true);
    expect(all.indeterminate).toBe(false);
    expect(el().querySelector('.count')?.textContent).toContain('3 selected');
    all.click();
    fixture.detectChanges();
    expect(el().querySelector('.count')).toBeNull();
  });

  it.each([
    ['Delete', 'DELETE'],
    ['Mark read', 'READ'],
    ['Mark unread', 'UNREAD'],
    ['Star', 'FLAG'],
    ['Spam', 'SPAM'],
  ])('runs %s from the toolbar on the cursor item', async (name, action) => {
    await create();
    button(name).click();
    await flush();
    expect(mail.applies[0]).toMatchObject({ action, ids: ['a'] });
  });

  it('shows conversations with their size and unread state when that was the stored choice', async () => {
    localStorage.setItem(THREADED_KEY, 'true');
    await create('inbox-1', {
      firstPage: {
        items: [
          summary('t', { threadSize: 3, threadUnread: 1, seen: true, snippet: 'See you at noon' }),
          summary('s', { threadSize: 1, threadUnread: 0, seen: false }),
        ],
        nextCursor: null,
      },
    });
    expect(mail.lists[0].scope).toEqual({ folderId: 'inbox-1', threaded: true });
    expect(rows()[0].querySelector('.thread-size')?.textContent).toBe('3');
    expect(rows()[0].querySelector('.snippet')?.textContent).toBe('See you at noon');
    expect(rows()[1].querySelector('.thread-size')).toBeNull();
    // Threaded unread state comes from the conversation, not the newest message.
    expect(rows()[0].classList).toContain('unread');
    expect(rows()[1].classList).not.toContain('unread');
  });

  it('starts flat and still switches when storage is unavailable', async () => {
    const get = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });
    const set = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });
    try {
      await create();
      expect(mail.lists[0].scope.threaded).toBe(false);
      el().querySelectorAll<HTMLButtonElement>('.views button')[1].click();
      fixture.detectChanges();
      expect(mail.lists[1].scope.threaded).toBe(true);
    } finally {
      get.mockRestore();
      set.mockRestore();
    }
  });

  it('does not reload when the shown view is chosen again', async () => {
    await create();
    el().querySelectorAll<HTMLButtonElement>('.views button')[0].click();
    expect(mail.lists).toHaveLength(1);
  });

  it('opens no folder picker for items of several accounts, nor a second picker', async () => {
    await create(null);
    listStore().items.update((items) => [...items!, summary('z', { accountId: 'acc-2' })]);
    listStore().toggleAll();
    press('v');
    await flush();
    expect(dialogOpened).toEqual([]);

    listStore().clearSelection();
    fixture.detectChanges();
    const dialog = TestBed.inject(MatDialog) as unknown as { openDialogs: unknown[] };
    dialog.openDialogs.push({});
    button('Move').click();
    await flush();
    expect(dialogOpened).toEqual([]);
  });

  it('formats today as a time, this year as day and month, and older mail with the year', async () => {
    const now = new Date();
    await create('inbox-1', {
      firstPage: {
        items: [
          summary('today', { receivedAt: now.toISOString() }),
          summary('year', {
            receivedAt: new Date(now.getFullYear(), 0, 1, 12).toISOString(),
          }),
          summary('old', { receivedAt: '2020-03-15T12:00:00Z' }),
        ],
        nextCursor: null,
      },
    });
    const times = rows().map((row) => row.querySelector('time')?.textContent?.trim());
    expect(times[0]).toMatch(/^\d{1,2}:\d{2}/);
    expect(times[2]).toBe('Mar 15, 2020');
    if (now.getMonth() !== 0 || now.getDate() !== 1) expect(times[1]).toBe('Jan 1');
  });

  describe('infinite scroll', () => {
    let observers: {
      callback: (entries: { isIntersecting: boolean }[]) => void;
      observed: Element[];
      disconnected: boolean;
    }[];

    beforeEach(() => {
      observers = [];
      vi.stubGlobal(
        'IntersectionObserver',
        class {
          readonly record: (typeof observers)[number];
          constructor(callback: (entries: { isIntersecting: boolean }[]) => void) {
            this.record = { callback, observed: [], disconnected: false };
            observers.push(this.record);
          }
          observe(target: Element) {
            this.record.observed.push(target);
          }
          disconnect() {
            this.record.disconnected = true;
          }
        },
      );
    });
    afterEach(() => vi.unstubAllGlobals());

    it('loads older messages when the end of the list comes into view', async () => {
      await create();
      expect(observers).toEqual([]);
      listStore().nextCursor.set('next');
      fixture.detectChanges();
      await fixture.whenStable();
      expect(observers).toHaveLength(1);
      expect(observers[0].observed[0]).toBe(el().querySelector('.more'));

      observers[0].callback([{ isIntersecting: false }]);
      expect(mail.lists).toHaveLength(1);
      observers[0].callback([{ isIntersecting: true }]);
      expect(mail.lists[1].cursor).toBe('next');

      mail.lists[1].reject(new ApiError(500, 'Server error', 'Try later'));
      await flush();
      fixture.detectChanges();
      expect(el().querySelector('.more [role="alert"]')?.textContent).toContain('Try later');

      listStore().nextCursor.set(null);
      fixture.detectChanges();
      await fixture.whenStable();
      expect(observers[0].disconnected).toBe(true);
    });
  });
});

describe('senderName', () => {
  it('prefers names, falls back to addresses', () => {
    expect(senderName([{ name: 'Max' }, { name: '', address: 'erika@example.test' }])).toBe(
      'Max, erika@example.test',
    );
    expect(senderName(undefined)).toBe('(unknown sender)');
  });
});
