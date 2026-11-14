import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import {
  ActivatedRoute,
  convertToParamMap,
  ParamMap,
  provideRouter,
  Router,
} from '@angular/router';
import { BehaviorSubject, Observable, of, Subject } from 'rxjs';
import { ChangeEvent, MailAccountResponse, MailFolderResponse } from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { NotificationService } from '../../../core/http';
import MailListPage, { senderName, THREADED_KEY } from './mail-list-page';
import { FakeEvents, FakeMail, summary, toTaskResult } from './mail-testing';
import { ApiError } from '../../../core/http';
import { PageShortcuts } from '../../../shell/page-shortcuts';
import { MailListSession } from './mail-list-session';
import { MAIL_LIST_HELP } from './mail-shortcuts';
import { MAIL_FOLDER_EVENT, MailListStore, REFRESH_DEBOUNCE_MS } from './mail-list-store';
import { MAIL_MAX_PAGE_SIZE, MailPage, MailService, MailSummary } from './mail.service';

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
  let events: FakeEvents;
  let errors: string[];
  let params: BehaviorSubject<ParamMap>;
  let query: BehaviorSubject<ParamMap>;
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
      /** The URL's query parameters (the search). */
      queryParams?: Record<string, string>;
      /** Adjusts the root providers (e.g. the saved list) before the page starts. */
      before?: () => void;
    } = {},
  ) {
    mail = new FakeMail();
    // Same sender name on both accounts: the screens must label them by email address.
    mail.accounts = () =>
      Promise.resolve([account('acc-1', 'Ann Example'), account('acc-2', 'Ann Example')]);
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
    query = new BehaviorSubject(convertToParamMap(options.queryParams ?? {}));
    dialogOpened = [];
    dialogResult = undefined;
    dialogClosed = undefined;
    events = new FakeEvents();
    errors = [];
    options.setup?.(mail);
    TestBed.configureTestingModule({
      imports: [MailListPage],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: {
            paramMap: params,
            queryParamMap: query,
            get snapshot() {
              return { paramMap: params.value, queryParamMap: query.value };
            },
          },
        },
        { provide: MailService, useValue: mail },
        { provide: ChangeEventsService, useValue: events },
        {
          provide: NotificationService,
          useValue: { error: (message: string) => errors.push(message), info: () => undefined },
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
    options.before?.();
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
    expect(el().querySelector('.heading .account')?.textContent).toBe('acc-1@example.test');
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

  it('offers a Compose link to a new message', async () => {
    await create();
    const compose = [...el().querySelectorAll('a')].find(
      (a) => a.textContent?.trim() === 'Compose',
    );
    expect(compose?.getAttribute('href')).toBe('/mail/compose');
    expect(compose?.getAttribute('title')).toBe('c');
  });

  describe('compose, reply and forward keys', () => {
    let navigate: ReturnType<typeof vi.spyOn>;

    beforeEach(async () => {
      await create();
      navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    });

    it('opens a new message with c', async () => {
      const event = press('c');
      await flush();
      expect(event.defaultPrevented).toBe(true);
      expect(navigate).toHaveBeenCalledExactlyOnceWith(['/mail/compose']);
      expect(mail.applies).toEqual([]);
    });

    it('ignores c while typing, with modifiers, repeated or while a dialog is open', async () => {
      const input = document.createElement('input');
      document.body.appendChild(input);
      try {
        expect(press('c', {}, input).defaultPrevented).toBe(false);
        expect(press('c', { ctrlKey: true }).defaultPrevented).toBe(false);
        expect(press('c', { metaKey: true }).defaultPrevented).toBe(false);
        expect(press('c', { repeat: true }).defaultPrevented).toBe(false);
        const dialog = TestBed.inject(MatDialog) as unknown as { openDialogs: unknown[] };
        dialog.openDialogs.push({});
        expect(press('c').defaultPrevented).toBe(false);
        await flush();
        expect(navigate).not.toHaveBeenCalled();
      } finally {
        input.remove();
      }
    });

    it.each(['r', 'a', 'f'])(
      'leaves %s alone in the list (a read view key): no action, no navigation',
      async (key) => {
        const event = press(key);
        await flush();
        expect(event.defaultPrevented).toBe(false);
        expect(navigate).not.toHaveBeenCalled();
        expect(mail.applies).toEqual([]);
        expect(rows()[0].getAttribute('aria-current')).toBe('true');
      },
    );
  });

  it('lists its keys in the ? overview while it is shown', async () => {
    await create();
    const sections = TestBed.inject(PageShortcuts).sections;
    expect(sections()).toEqual([{ title: 'Mail', shortcuts: MAIL_LIST_HELP }]);
    fixture.destroy();
    expect(sections()).toEqual([]);
  });

  it('shows the unified Inbox with account names when there are several accounts', async () => {
    await create(null);
    expect(el().querySelector('h1')?.textContent).toBe('All inboxes');
    expect(rows()[0].querySelector('.account-chip')?.textContent).toBe('acc-1@example.test');
    const tree = el().querySelector('app-mail-folder-tree')!;
    expect(tree.querySelector('[aria-current="page"]')?.textContent?.trim()).toBe('All inboxes');
    expect([...tree.querySelectorAll('h2')].map((h) => h.textContent?.trim())).toEqual([
      'acc-1@example.test',
      'acc-2@example.test',
    ]);
  });

  it('tells accounts with the same sender name apart by email address', async () => {
    await create(null, {
      firstPage: {
        items: [
          summary('a', { subject: 'Report', accountId: 'acc-1', folderId: 'inbox-1' }),
          summary('h', { subject: 'Dinner', accountId: 'acc-2', folderId: 'inbox-2' }),
        ],
        nextCursor: null,
      },
    });
    expect(rows().map((row) => row.querySelector('.account-chip')?.textContent)).toEqual([
      'acc-1@example.test',
      'acc-2@example.test',
    ]);
    expect(el().textContent).not.toContain('Ann Example');

    params.next(convertToParamMap({ folderId: 'inbox-2' }));
    await flush();
    fixture.detectChanges();
    expect(el().querySelector('.heading .account')?.textContent).toBe('acc-2@example.test');
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

  it('deletes with # typed with AltGr (Ctrl+Alt), but ignores letters typed with it', async () => {
    await create();
    const letter = press('e', { ctrlKey: true, altKey: true });
    await flush();
    expect(letter.defaultPrevented).toBe(false);
    expect(mail.applies).toEqual([]);
    const symbol = press('#', { ctrlKey: true, altKey: true });
    await flush();
    expect(symbol.defaultPrevented).toBe(true);
    expect(mail.applies[0]).toMatchObject({ action: 'DELETE', ids: ['a'] });
    expect(rows().map((row) => row.dataset['id'])).toEqual(['b', 'c']);
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
    const open = (index: number) => rows()[index].querySelector('a.open');
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

  describe('opening a message', () => {
    const navigations = () => {
      const router = TestBed.inject(Router);
      return vi.spyOn(router, 'navigate').mockResolvedValue(true);
    };
    const urlNavigations = () => {
      const router = TestBed.inject(Router);
      return vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    };
    const link = (index: number) => rows()[index].querySelector<HTMLAnchorElement>('a.open')!;
    const navigatedTo = (spy: ReturnType<typeof urlNavigations>) =>
      spy.mock.calls.map(([url]) => TestBed.inject(Router).serializeUrl(url as never));

    it('makes each row a link to its message', async () => {
      await create();
      expect(rows().map((_, index) => link(index).getAttribute('href'))).toEqual([
        '/mail/messages/a',
        '/mail/messages/b',
        '/mail/messages/c',
      ]);
      expect(el().querySelector('li.item button')).toBeNull();
    });

    it('opens a clicked row and moves the cursor to it without selecting it', async () => {
      await create();
      const navigate = urlNavigations();
      const event = new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 });
      link(2).dispatchEvent(event);
      fixture.detectChanges();
      expect(event.defaultPrevented).toBe(true);
      expect(navigatedTo(navigate)).toEqual(['/mail/messages/c']);
      expect(rows()[2].getAttribute('aria-current')).toBe('true');
      expect(rows()[2].classList).not.toContain('selected');
      expect(mail.applies).toEqual([]);
    });

    it('leaves Ctrl/Cmd-click and middle-click to the browser (new tab)', async () => {
      await create();
      const navigate = urlNavigations();
      const clicks = [
        new MouseEvent('click', { bubbles: true, cancelable: true, ctrlKey: true }),
        new MouseEvent('click', { bubbles: true, cancelable: true, metaKey: true }),
        new MouseEvent('click', { bubbles: true, cancelable: true, button: 1 }),
      ];
      // Records whether the page let the browser act, then stops jsdom from following the link.
      const leftToBrowser: boolean[] = [];
      const record = (event: Event) => {
        leftToBrowser.push(!event.defaultPrevented);
        event.preventDefault();
      };
      document.addEventListener('click', record);
      try {
        for (const click of clicks) link(1).dispatchEvent(click);
      } finally {
        document.removeEventListener('click', record);
      }
      expect(navigate).not.toHaveBeenCalled();
      expect(leftToBrowser).toEqual([true, true, true]);
    });

    it('only selects on a checkbox click, without opening', async () => {
      await create();
      const navigate = navigations();
      const byUrl = urlNavigations();
      rows()[1].querySelector<HTMLInputElement>('input[type=checkbox]')!.click();
      fixture.detectChanges();
      expect(rows()[1].classList).toContain('selected');
      expect(navigate).not.toHaveBeenCalled();
      expect(byUrl).not.toHaveBeenCalled();
    });

    it('opens the cursor message with o', async () => {
      await create();
      const navigate = navigations();
      press('j');
      const event = press('o');
      expect(event.defaultPrevented).toBe(true);
      expect(navigate).toHaveBeenCalledExactlyOnceWith(['/mail/messages', 'b']);
      expect(mail.applies).toEqual([]);
    });

    it('opens the focused row with Enter, once', async () => {
      await create();
      const navigate = navigations();
      const byUrl = urlNavigations();
      press('j');
      press('j');
      const event = press('Enter', {}, document.activeElement!);
      fixture.detectChanges();
      expect(event.defaultPrevented).toBe(true);
      expect(navigate).toHaveBeenCalledExactlyOnceWith(['/mail/messages', 'c']);
      expect(byUrl).not.toHaveBeenCalled();
      expect(rows()[2].getAttribute('aria-current')).toBe('true');
    });

    it('opens a row focused with Tab on Enter, moving the cursor to it', async () => {
      await create();
      const navigate = navigations();
      link(1).focus();
      const event = press('Enter', {}, link(1));
      expect(event.defaultPrevented).toBe(true);
      expect(navigate).toHaveBeenCalledExactlyOnceWith(['/mail/messages', 'b']);
      expect(rows()[1].getAttribute('aria-current')).toBe('true');
      expect(rows()[0].getAttribute('aria-current')).not.toBe('true');
    });

    it('leaves Ctrl/Cmd/Shift+Enter on a row to the browser', async () => {
      await create();
      const navigate = navigations();
      const events = [{ ctrlKey: true }, { metaKey: true }, { shiftKey: true }].map((init) =>
        press('Enter', init, link(0)),
      );
      expect(events.map((event) => event.defaultPrevented)).toEqual([false, false, false]);
      expect(navigate).not.toHaveBeenCalled();
    });

    it('keeps the keyboard shortcuts working on a clicked row', async () => {
      await create();
      urlNavigations();
      link(2).dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
      fixture.detectChanges();
      press('x');
      expect(rows()[2].classList).toContain('selected');
      press('x');
      expect(rows()[2].classList).not.toContain('selected');
      press('e');
      await flush();
      expect(mail.applies.map(({ action, ids }) => ({ action, ids }))).toEqual([
        { action: 'ARCHIVE', ids: ['c'] },
      ]);
      expect(rows().map((row) => row.dataset['id'])).toEqual(['a', 'b']);
    });

    it('opens the clicked row with o afterwards', async () => {
      await create();
      urlNavigations();
      const navigate = navigations();
      link(1).dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
      fixture.detectChanges();
      press('o');
      expect(navigate).toHaveBeenCalledExactlyOnceWith(['/mail/messages', 'b']);
    });

    it('opens nothing on a double-click beyond what its clicks open', async () => {
      await create();
      const navigate = navigations();
      link(2).dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
      expect(navigate).not.toHaveBeenCalled();
    });

    it('opens nothing with o in an empty folder, nor while typing', async () => {
      await create('inbox-1', { firstPage: { items: [], nextCursor: null } });
      const navigate = navigations();
      press('o');
      const input = document.createElement('input');
      document.body.appendChild(input);
      press('o', {}, input);
      input.remove();
      expect(navigate).not.toHaveBeenCalled();
    });

    describe('in a Drafts folder', () => {
      async function createDrafts(folderId: string | null = 'drafts-1') {
        await create(folderId, {
          setup: (fake) => {
            fake.folders = (id: string) =>
              Promise.resolve(
                id === 'acc-1'
                  ? [
                      folder('inbox-1', 'acc-1', 'INBOX', 'INBOX'),
                      folder('drafts-1', 'acc-1', 'Drafts', 'DRAFTS'),
                      folder('proj-1', 'acc-1', 'Projects'),
                    ]
                  : [folder('inbox-2', 'acc-2', 'INBOX', 'INBOX')],
              );
          },
          firstPage: {
            items: [
              summary('d1', { subject: 'Unfinished', folderId: 'drafts-1' }),
              summary('m1', { subject: 'Other', folderId: 'proj-1' }),
            ],
            nextCursor: null,
          },
        });
        fixture.detectChanges();
      }

      it('links a draft to compose with its id, other mail to the read view', async () => {
        await createDrafts();
        expect(link(0).getAttribute('href')).toBe('/mail/compose?draft=d1');
        expect(link(1).getAttribute('href')).toBe('/mail/messages/m1');
      });

      it('opens a clicked draft in compose', async () => {
        await createDrafts();
        const navigate = urlNavigations();
        link(0).dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
        fixture.detectChanges();
        expect(navigatedTo(navigate)).toEqual(['/mail/compose?draft=d1']);
      });

      it('opens the cursor draft in compose with o and Enter', async () => {
        await createDrafts();
        const navigate = navigations();
        press('o');
        press('Enter', {}, link(0));
        expect(navigate.mock.calls).toEqual([
          [['/mail/compose'], { queryParams: { draft: 'd1' } }],
          [['/mail/compose'], { queryParams: { draft: 'd1' } }],
        ]);
      });

      it('opens a mail of another folder from the same list in the read view', async () => {
        await createDrafts();
        const navigate = navigations();
        press('j');
        press('o');
        expect(navigate).toHaveBeenCalledExactlyOnceWith(['/mail/messages', 'm1']);
      });

      it('treats a draft listed in the unified inbox like one in its folder', async () => {
        await createDrafts(null);
        expect(link(0).getAttribute('href')).toBe('/mail/compose?draft=d1');
      });
    });

    it('names o and click in the key hint', async () => {
      await create();
      expect(el().querySelector('.keys')?.textContent).toContain('o or click open');
    });
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

  describe('search', () => {
    const field = () =>
      el().querySelector<HTMLInputElement>('form[role="search"] input[type="search"]')!;
    const serverBox = () =>
      el().querySelector<HTMLInputElement>('form[role="search"] input[type="checkbox"]')!;
    const subjects = () => rows().map((row) => row.querySelector('.subject')?.textContent);
    const info = () => el().querySelector('.search-info');

    /** Types `text` into the field and presses Enter (submits the form). */
    async function submit(text: string) {
      field().value = text;
      field().dispatchEvent(new Event('input'));
      const event = new Event('submit', { cancelable: true });
      field().form!.dispatchEvent(event);
      await flush();
      fixture.detectChanges();
      return event;
    }

    async function answer(
      items: MailSummary[],
      extra: { hasMore?: boolean; failedAccountIds?: string[] } = {},
      index = mail.searches.length - 1,
    ) {
      mail.searches[index].resolve({
        items,
        hasMore: extra.hasMore ?? false,
        failedAccountIds: extra.failedAccountIds ?? [],
      });
      await flush();
      fixture.detectChanges();
    }

    const found = [
      summary('n', { subject: 'Newer report', receivedAt: '2026-10-22T10:00:00Z' }),
      summary('o', { subject: 'Older report', receivedAt: '2026-10-01T10:00:00Z' }),
    ];

    it('searches the shown folder on Enter and lists the results in its order', async () => {
      await create();
      expect(field().getAttribute('aria-label')).toBe('Search mail');
      const event = await submit('  report ');
      expect(event.defaultPrevented).toBe(true);
      expect(mail.searches).toHaveLength(1);
      expect(mail.searches[0]).toMatchObject({
        query: 'report',
        folderId: 'inbox-1',
        server: false,
      });
      // The list is loading the results, not showing the folder's messages.
      expect(rows()).toEqual([]);
      expect(el().querySelector('.note[role="status"]')?.textContent?.trim()).toBe(
        'Loading messages…',
      );
      await answer(found);
      expect(subjects()).toEqual(['Newer report', 'Older report']);
      expect(info()?.querySelector('.search-for')?.textContent).toBe('Results for “report”');
      expect(info()?.querySelector('.search-warning')).toBeNull();
      expect(info()?.textContent).not.toContain('Only the newest results');
      expect(rows()[0].getAttribute('aria-current')).toBe('true');
    });

    it('searches all folders from the unified Inbox', async () => {
      await create(null);
      await submit('report');
      expect(mail.searches[0]).toMatchObject({ query: 'report', folderId: null, server: false });
    });

    it('shows results flat even in the Conversations view', async () => {
      localStorage.setItem(THREADED_KEY, 'true');
      await create();
      await submit('report');
      await answer([summary('t', { threadSize: 3, threadUnread: 0, seen: false })]);
      expect(rows()[0].querySelector('.thread-size')).toBeNull();
      // Unread comes from the message itself, not from its conversation.
      expect(rows()[0].classList).toContain('unread');
    });

    it('keeps a server search on a view switch and lists conversations after clearing it', async () => {
      await create();
      serverBox().click();
      await submit('report');
      await answer(found);
      const toggle = [...el().querySelectorAll<HTMLButtonElement>('.views button')];
      toggle[1].click();
      await flush();
      fixture.detectChanges();
      expect(localStorage.getItem(THREADED_KEY)).toBe('true');
      // No new search, no folder list: the shown results stay as they are.
      expect(mail.searches).toHaveLength(1);
      expect(mail.lists).toHaveLength(1);
      expect(subjects()).toEqual(['Newer report', 'Older report']);
      expect(info()?.textContent).not.toContain('Searching on the server…');

      field().value = '';
      field().dispatchEvent(new Event('input'));
      await flush();
      fixture.detectChanges();
      expect(mail.lists).toHaveLength(2);
      expect(mail.lists[1].scope).toEqual({ folderId: 'inbox-1', threaded: true });
      expect(mail.searches).toHaveLength(1);
    });

    it('opens a result in the read view', async () => {
      await create();
      await submit('report');
      await answer(found);
      const link = rows()[1].querySelector<HTMLAnchorElement>('a.open')!;
      expect(link.getAttribute('href')).toBe('/mail/messages/o');
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
      link.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }));
      expect(
        navigate.mock.calls.map(([url]) => TestBed.inject(Router).serializeUrl(url as never)),
      ).toEqual(['/mail/messages/o']);
    });

    it('says "No messages found" when nothing matches', async () => {
      await create();
      await submit('zzz');
      await answer([]);
      expect(el().querySelector('p.empty')?.textContent?.trim()).toBe('No messages found');
      expect(rows()).toEqual([]);
    });

    it('shows a failed search with Try again, which searches again', async () => {
      await create();
      await submit('report');
      mail.searches[0].reject(new ApiError(500, 'Server error', 'Search index unavailable'));
      await flush();
      fixture.detectChanges();
      const alert = el().querySelector('.load-error[role="alert"]')!;
      expect(alert.textContent).toContain('Search index unavailable');
      alert.querySelector('button')!.click();
      fixture.detectChanges();
      expect(el().querySelector('.load-error')).toBeNull();
      expect(mail.searches).toHaveLength(2);
      expect(mail.searches[1]).toMatchObject({ query: 'report', folderId: 'inbox-1' });
      expect(mail.lists).toHaveLength(1);
      await answer(found);
      expect(subjects()).toEqual(['Newer report', 'Older report']);
    });

    it('hints that only the newest results are listed when there are more', async () => {
      await create();
      await submit('report');
      await answer(found, { hasMore: true });
      expect(info()?.textContent).toContain(
        'Only the newest results are listed. Refine the search to find older ones.',
      );
    });

    it('repeats the search on the server, keeping the cached results until it answers', async () => {
      await create();
      await submit('report');
      await answer(found);
      serverBox().click();
      fixture.detectChanges();
      expect(mail.searches[1]).toMatchObject({
        query: 'report',
        folderId: 'inbox-1',
        server: true,
      });
      expect(subjects()).toEqual(['Newer report', 'Older report']);
      expect(info()?.textContent).toContain('Searching on the server…');

      await answer([...found, summary('old', { subject: 'Ancient report' })], {
        failedAccountIds: ['acc-2'],
      });
      expect(subjects()).toEqual(['Newer report', 'Older report', 'Ancient report']);
      expect(info()?.textContent).not.toContain('Searching on the server…');
      const warning = info()?.querySelector('.search-warning[role="status"]');
      expect(warning?.textContent).toContain('acc-2@example.test');
      expect(warning?.textContent).toContain('could not be searched');
      // Non-blocking: no alert, the results stay usable.
      expect(el().querySelector('[role="alert"]')).toBeNull();
    });

    it('names several failed accounts, and an unknown one generically', async () => {
      await create(null);
      serverBox().click();
      fixture.detectChanges();
      expect(mail.searches).toEqual([]);
      await submit('report');
      expect(mail.searches[0]).toMatchObject({ server: true, folderId: null });
      await answer(found, { failedAccountIds: ['acc-1', 'acc-gone'] });
      expect(info()?.querySelector('.search-warning')?.textContent).toContain(
        'acc-1@example.test, an account',
      );
    });

    it('shows the load error when the server search fails', async () => {
      await create();
      await submit('report');
      await answer(found);
      serverBox().click();
      fixture.detectChanges();
      mail.searches[1].reject(new ApiError(504, 'Gateway Timeout', 'Mail server too slow'));
      await flush();
      fixture.detectChanges();
      expect(el().querySelector('.load-error')?.textContent).toContain('Mail server too slow');
      expect(rows()).toEqual([]);
    });

    it('searches cached mail only again when the server option is turned off', async () => {
      await create();
      serverBox().click();
      await submit('report');
      await answer(found);
      serverBox().click();
      fixture.detectChanges();
      expect(mail.searches[1]).toMatchObject({ query: 'report', server: false });
      expect(rows()).toEqual([]);
    });

    it('returns to the folder list when the field is emptied', async () => {
      await create();
      await submit('report');
      await answer(found);
      field().value = '';
      field().dispatchEvent(new Event('input'));
      await flush();
      fixture.detectChanges();
      expect(info()).toBeNull();
      expect(mail.lists[1].scope).toEqual({ folderId: 'inbox-1', threaded: false });
      mail.lists[1].resolve({ items: [summary('a', { subject: 'Report' })], nextCursor: null });
      await flush();
      fixture.detectChanges();
      expect(subjects()).toEqual(['Report']);
    });

    it('returns to the folder list when a blank query is submitted', async () => {
      await create();
      await submit('report');
      await answer(found);
      await submit('   ');
      expect(mail.searches).toHaveLength(1);
      expect(mail.lists).toHaveLength(2);
      expect(info()).toBeNull();
    });

    it('does nothing for a blank query without a search', async () => {
      await create();
      await submit('  ');
      expect(mail.searches).toEqual([]);
      expect(mail.lists).toHaveLength(1);
    });

    it('empties the field and returns to the folder list on Escape', async () => {
      await create();
      await submit('report');
      await answer(found);
      listStore().toggleSelected('n');
      fixture.detectChanges();
      const event = press('Escape', {}, field());
      await flush();
      fixture.detectChanges();
      expect(event.defaultPrevented).toBe(true);
      expect(field().value).toBe('');
      expect(info()).toBeNull();
      expect(mail.lists[1].scope).toEqual({ folderId: 'inbox-1', threaded: false });
    });

    it('focuses the field with /, without typing it', async () => {
      await create();
      const event = press('/');
      expect(event.defaultPrevented).toBe(true);
      expect(document.activeElement).toBe(field());
      expect(el().querySelector('.keys')?.textContent).toContain('/ search');
    });

    it('leaves / with modifiers, in other fields and while a dialog is open alone', async () => {
      await create();
      const other = document.createElement('input');
      document.body.appendChild(other);
      try {
        other.focus();
        expect(press('/', {}, other).defaultPrevented).toBe(false);
        expect(document.activeElement).toBe(other);
        expect(press('/', { ctrlKey: true }).defaultPrevented).toBe(false);
        const dialog = TestBed.inject(MatDialog) as unknown as { openDialogs: unknown[] };
        dialog.openDialogs.push({});
        expect(press('/').defaultPrevented).toBe(false);
        expect(document.activeElement).toBe(other);
      } finally {
        other.remove();
      }
    });

    it('does not run list shortcuts while typing in the field', async () => {
      await create();
      press('/');
      for (const key of ['j', 'k', 'x', 'e', 's', 'u', 'v', 'o', '/']) {
        expect(press(key, {}, field()).defaultPrevented).toBe(false);
      }
      expect(press('#', { shiftKey: true }, field()).defaultPrevented).toBe(false);
      await flush();
      fixture.detectChanges();
      expect(mail.applies).toEqual([]);
      expect(dialogOpened).toEqual([]);
      expect(rows()[0].getAttribute('aria-current')).toBe('true');
      expect(rows().some((row) => row.classList.contains('selected'))).toBe(false);
    });

    it('clears the search when another folder is opened, keeping the server option', async () => {
      await create();
      serverBox().click();
      await submit('report');
      await answer(found);
      params.next(convertToParamMap({ folderId: 'proj-1' }));
      await flush();
      fixture.detectChanges();
      expect(field().value).toBe('');
      expect(info()).toBeNull();
      expect(mail.lists[1].scope).toEqual({ folderId: 'proj-1', threaded: false });
      expect(serverBox().checked).toBe(true);
      await submit('lunch');
      expect(mail.searches[1]).toMatchObject({ query: 'lunch', folderId: 'proj-1', server: true });
    });

    describe('in the URL', () => {
      let navigated: { url: string; replaceUrl: boolean }[];
      const spyRouter = () => {
        navigated = [];
        vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockImplementation((url, extras) => {
          navigated.push({ url: String(url), replaceUrl: !!extras?.replaceUrl });
          return Promise.resolve(true);
        });
      };

      it('puts a submitted search and the server option into the URL', async () => {
        await create('inbox-1', { before: spyRouter });
        await submit('my report');
        expect(navigated).toEqual([
          { url: '/mail/folders/inbox-1?q=my%20report', replaceUrl: false },
        ]);
        await answer(found);
        serverBox().click();
        expect(navigated[1]).toEqual({
          url: '/mail/folders/inbox-1?q=my%20report&server=1',
          replaceUrl: true,
        });
        // The same search again searches again without a new history entry.
        await submit('my report');
        expect(mail.searches).toHaveLength(3);
        expect(navigated).toHaveLength(2);
      });

      it('takes the search out of the URL when it is cleared', async () => {
        await create(null, { before: spyRouter });
        await submit('report');
        await submit('');
        expect(navigated.at(-1)).toEqual({ url: '/mail', replaceUrl: false });
      });

      it('shows the search of the URL in the field and searches for it', async () => {
        await create(null, { queryParams: { q: 'report', server: '1' }, firstPage: 'pending' });
        expect(mail.lists).toEqual([]);
        expect(mail.searches).toEqual([
          expect.objectContaining({ query: 'report', folderId: null, server: true }),
        ]);
        await answer(found);
        expect(field().value).toBe('report');
        expect(serverBox().checked).toBe(true);
        expect(subjects()).toEqual(['Newer report', 'Older report']);
      });

      it('goes back to the list when the URL loses the search (browser Back)', async () => {
        await create('inbox-1', { before: spyRouter });
        await submit('report');
        await answer(found);
        // The router confirms the page's own URL change: nothing is searched again.
        query.next(convertToParamMap({ q: 'report' }));
        await flush();
        expect(mail.searches).toHaveLength(1);
        query.next(convertToParamMap({}));
        await flush();
        fixture.detectChanges();
        expect(field().value).toBe('');
        expect(info()).toBeNull();
        expect(mail.lists.at(-1)?.scope).toEqual({ folderId: 'inbox-1', threaded: false });
      });
    });

    describe('when the page is left and shown again', () => {
      function leave() {
        fixture.destroy();
        return TestBed.inject(MailListSession).saved();
      }

      it('saves the shown list with its URL, cursor and search results', async () => {
        await create(null, { queryParams: { q: 'report' }, firstPage: 'pending' });
        await answer(found);
        press('j');
        const saved = leave();
        expect(saved).toMatchObject({
          url: '/mail?q=report',
          cursorId: 'o',
          scope: { folderId: null, search: { query: 'report', server: false } },
          searchResult: { hasMore: false, failedAccountIds: [] },
        });
        expect(saved?.items.map((item) => item.id)).toEqual(['n', 'o']);
      });

      it('saves nothing while the list still loads', async () => {
        await create('inbox-1', { firstPage: 'pending' });
        expect(leave()).toBeNull();
      });

      it('shows saved search results again without searching again', async () => {
        await create(null, {
          queryParams: { q: 'report' },
          firstPage: 'pending',
          before: () =>
            TestBed.inject(MailListSession).save({
              url: '/mail?q=report',
              scrollTop: 0,
              scope: {
                folderId: null,
                threaded: false,
                search: { query: 'report', server: false },
              },
              items: found,
              nextCursor: null,
              cursorId: 'o',
              searchResult: { hasMore: true, failedAccountIds: [] },
            }),
        });
        expect(mail.searches).toEqual([]);
        expect(mail.lists).toEqual([]);
        expect(field().value).toBe('report');
        expect(subjects()).toEqual(['Newer report', 'Older report']);
        expect(rows()[1].getAttribute('aria-current')).toBe('true');
        expect(info()?.textContent).toContain('Only the newest results are listed.');
      });

      it('shows a saved folder list again and refetches it in the background', async () => {
        await create('inbox-1', {
          firstPage: 'pending',
          before: () =>
            TestBed.inject(MailListSession).save({
              url: '/mail/folders/inbox-1',
              scrollTop: 0,
              scope: { folderId: 'inbox-1', threaded: false },
              items: [summary('a'), summary('b')],
              nextCursor: null,
              cursorId: 'b',
              searchResult: null,
            }),
        });
        expect(rows().map((row) => row.dataset['id'])).toEqual(['a', 'b']);
        expect(mail.lists).toEqual([expect.objectContaining({ cursor: undefined, limit: 50 })]);
        mail.lists[0].resolve({ items: [summary('b'), summary('c')], nextCursor: null });
        await flush();
        fixture.detectChanges();
        expect(rows().map((row) => row.dataset['id'])).toEqual(['b', 'c']);
        expect(rows()[0].getAttribute('aria-current')).toBe('true');
      });

      it('keeps a saved folder list longer than one server page without refetching it', async () => {
        const items = Array.from({ length: MAIL_MAX_PAGE_SIZE + 30 }, (_, i) => summary(`m${i}`));
        const cursorId = `m${MAIL_MAX_PAGE_SIZE + 20}`;
        await create('inbox-1', {
          firstPage: 'pending',
          before: () =>
            TestBed.inject(MailListSession).save({
              url: '/mail/folders/inbox-1',
              scrollTop: 0,
              scope: { folderId: 'inbox-1', threaded: false },
              items,
              nextCursor: 'cur-5',
              cursorId,
              searchResult: null,
            }),
        });
        expect(mail.lists).toEqual([]);
        expect(rows()).toHaveLength(MAIL_MAX_PAGE_SIZE + 30);
        expect(listStore().cursorId()).toBe(cursorId);
        expect(leave()).toMatchObject({ nextCursor: 'cur-5', cursorId });
      });

      it('does not refetch a saved folder list one item longer than a server page', async () => {
        const items = Array.from({ length: MAIL_MAX_PAGE_SIZE + 1 }, (_, i) => summary(`m${i}`));
        await create('inbox-1', {
          firstPage: 'pending',
          before: () =>
            TestBed.inject(MailListSession).save({
              url: '/mail/folders/inbox-1',
              scrollTop: 0,
              scope: { folderId: 'inbox-1', threaded: false },
              items,
              nextCursor: null,
              cursorId: `m${MAIL_MAX_PAGE_SIZE}`,
              searchResult: null,
            }),
        });
        expect(mail.lists).toEqual([]);
        expect(rows()).toHaveLength(MAIL_MAX_PAGE_SIZE + 1);
        expect(listStore().cursorId()).toBe(`m${MAIL_MAX_PAGE_SIZE}`);
      });

      it('still refetches a saved folder list of exactly one server page', async () => {
        const items = Array.from({ length: MAIL_MAX_PAGE_SIZE }, (_, i) => summary(`m${i}`));
        await create('inbox-1', {
          firstPage: 'pending',
          before: () =>
            TestBed.inject(MailListSession).save({
              url: '/mail/folders/inbox-1',
              scrollTop: 0,
              scope: { folderId: 'inbox-1', threaded: false },
              items,
              nextCursor: 'cur-4',
              cursorId: 'm150',
              searchResult: null,
            }),
        });
        expect(rows()).toHaveLength(MAIL_MAX_PAGE_SIZE);
        expect(mail.lists).toEqual([
          expect.objectContaining({ cursor: undefined, limit: MAIL_MAX_PAGE_SIZE }),
        ]);
      });

      it('loads anew when the saved list is another one', async () => {
        await create('proj-1', {
          firstPage: 'pending',
          before: () =>
            TestBed.inject(MailListSession).save({
              url: '/mail/folders/inbox-1',
              scrollTop: 0,
              scope: { folderId: 'inbox-1', threaded: false },
              items: [summary('a')],
              nextCursor: null,
              cursorId: 'a',
              searchResult: null,
            }),
        });
        expect(rows()).toEqual([]);
        expect(mail.lists[0].scope).toEqual({ folderId: 'proj-1', threaded: false });
      });

      const savedInbox =
        (extra: Record<string, unknown> = {}) =>
        () =>
          TestBed.inject(MailListSession).save({
            url: '/mail/folders/inbox-1',
            scrollTop: 0,
            scope: { folderId: 'inbox-1', threaded: false },
            items: [summary('a'), summary('b')],
            nextCursor: null,
            cursorId: 'b',
            searchResult: null,
            ...extra,
          });

      it('restores the saved list only when the page is shown, not when the folder changes back', async () => {
        await create('inbox-1', { firstPage: 'pending', before: savedInbox() });
        expect(mail.lists).toHaveLength(1); // the background refetch
        params.next(convertToParamMap({ folderId: 'proj-1' }));
        await flush();
        fixture.detectChanges();
        expect(rows()).toEqual([]);
        expect(mail.lists[1].scope).toEqual({ folderId: 'proj-1', threaded: false });
        params.next(convertToParamMap({ folderId: 'inbox-1' }));
        await flush();
        fixture.detectChanges();
        expect(rows()).toEqual([]);
        expect(mail.lists[2].scope).toEqual({ folderId: 'inbox-1', threaded: false });
        expect(mail.lists[2].cursor).toBeUndefined();
      });

      it('does not restore a folder list saved for the other view (by conversation)', async () => {
        localStorage.setItem(THREADED_KEY, 'true');
        await create('inbox-1', { firstPage: 'pending', before: savedInbox() });
        expect(rows()).toEqual([]);
        expect(mail.lists[0].scope).toEqual({ folderId: 'inbox-1', threaded: true });
      });

      it('saves nothing after the list failed to load', async () => {
        await create('inbox-1', { firstPage: new ApiError(502, 'Bad gateway', 'Down') });
        expect(leave()).toBeNull();
      });

      it('saves nothing while the server search runs', async () => {
        await create(null, { queryParams: { q: 'report' }, firstPage: 'pending' });
        await answer(found);
        serverBox().click();
        await flush();
        expect(listStore().searchingServer()).toBe(true);
        expect(leave()).toBeNull();
      });

      it('saves the server option and the folder of a search in the URL', async () => {
        await create('proj-1', {
          queryParams: { q: ' lunch ', server: '1' },
          firstPage: 'pending',
        });
        expect(mail.searches[0]).toMatchObject({
          query: 'lunch',
          folderId: 'proj-1',
          server: true,
        });
        mail.searches[0].resolve({ items: [summary('l')], hasMore: false, failedAccountIds: [] });
        await flush();
        expect(leave()).toMatchObject({
          url: '/mail/folders/proj-1?q=lunch&server=1',
          scope: { folderId: 'proj-1', search: { query: 'lunch', server: true } },
        });
      });

      describe('scroll position', () => {
        // jsdom has no layout: the body stands in for the scrolling container of the shell.
        let scrollIntoView: ReturnType<
          typeof vi.fn<(arg?: boolean | ScrollIntoViewOptions) => void>
        >;
        beforeEach(() => {
          document.body.style.overflowY = 'auto';
          Object.defineProperty(document.body, 'scrollTop', {
            value: 0,
            writable: true,
            configurable: true,
          });
          scrollIntoView = vi.fn();
          Element.prototype.scrollIntoView = scrollIntoView;
        });
        afterEach(() => {
          document.body.style.overflowY = '';
          delete (document.body as { scrollTop?: number }).scrollTop;
          delete (Element.prototype as { scrollIntoView?: unknown }).scrollIntoView;
        });

        it('saves how far the list was scrolled when it is left', async () => {
          await create('inbox-1');
          fixture.detectChanges();
          await fixture.whenStable();
          document.body.scrollTop = 340;
          document.body.dispatchEvent(new Event('scroll'));
          expect(leave()?.scrollTop).toBe(340);
        });

        it('scrolls back to where the list was and brings the cursor row into view', async () => {
          await create('inbox-1', { firstPage: 'pending', before: savedInbox({ scrollTop: 250 }) });
          await fixture.whenStable();
          fixture.detectChanges();
          expect(document.body.scrollTop).toBe(250);
          expect(scrollIntoView).toHaveBeenCalledWith({ block: 'nearest' });
          expect(scrollIntoView.mock.contexts.at(-1)).toBe(rows()[1]);
          // Left again without scrolling: the restored position is kept.
          expect(leave()?.scrollTop).toBe(250);
        });

        it('scrolls back also for search results that found nothing (no cursor row)', async () => {
          await create(null, {
            queryParams: { q: 'nothing' },
            firstPage: 'pending',
            before: savedInbox({
              url: '/mail?q=nothing',
              scope: {
                folderId: null,
                threaded: false,
                search: { query: 'nothing', server: false },
              },
              items: [],
              cursorId: null,
              searchResult: { hasMore: false, failedAccountIds: [] },
              scrollTop: 40,
            }),
          });
          await fixture.whenStable();
          fixture.detectChanges();
          expect(mail.searches).toEqual([]);
          expect(rows()).toEqual([]);
          expect(document.body.scrollTop).toBe(40);
          expect(scrollIntoView).not.toHaveBeenCalled();
        });

        it('tracks the window scroll when no ancestor scrolls but the document does', async () => {
          const root = document.documentElement;
          document.body.style.overflowY = '';
          Object.defineProperty(document, 'scrollingElement', { value: root, configurable: true });
          Object.defineProperty(root, 'scrollTop', {
            value: 0,
            writable: true,
            configurable: true,
          });
          try {
            await create('inbox-1');
            fixture.detectChanges();
            await fixture.whenStable();
            root.scrollTop = 180;
            window.dispatchEvent(new Event('scroll'));
            expect(leave()?.scrollTop).toBe(180);
          } finally {
            delete (document as { scrollingElement?: unknown }).scrollingElement;
            delete (root as { scrollTop?: number }).scrollTop;
          }
        });

        it('does not scroll a list that was loaded anew', async () => {
          document.body.scrollTop = 0;
          await create('proj-1', { before: savedInbox({ scrollTop: 250 }) });
          await fixture.whenStable();
          fixture.detectChanges();
          expect(document.body.scrollTop).toBe(0);
          expect(scrollIntoView).not.toHaveBeenCalled();
        });
      });
    });

    it('does not refetch results on a live change event', async () => {
      await create();
      await submit('report');
      await answer(found);
      vi.useFakeTimers();
      try {
        events.changes.next({
          at: '2026-10-21T08:00:00Z',
          op: 'UPDATED',
          id: 'inbox-1',
          type: MAIL_FOLDER_EVENT,
        });
        await vi.advanceTimersByTimeAsync(REFRESH_DEBOUNCE_MS);
      } finally {
        vi.useRealTimers();
      }
      expect(mail.lists).toHaveLength(1);
      expect(mail.searches).toHaveLength(1);
    });
  });

  describe('live updates (SSE)', () => {
    // Fake timers only after the page has loaded: the debounce of change events is then
    // controlled by the test instead of waiting for real time.
    const advance = async (ms = REFRESH_DEBOUNCE_MS) => {
      await vi.advanceTimersByTimeAsync(ms);
      fixture.detectChanges();
    };
    const folderEvent = (id: string): ChangeEvent => ({
      at: '2026-10-21T08:00:00Z',
      op: 'UPDATED',
      id,
      type: MAIL_FOLDER_EVENT,
    });
    const subjects = () => rows().map((row) => row.querySelector('.subject')?.textContent);

    afterEach(() => vi.useRealTimers());

    it('shows new mail of the shown folder without a reload, and ignores other folders', async () => {
      await create();
      vi.useFakeTimers();
      events.changes.next(folderEvent('proj-1'));
      await advance();
      expect(mail.lists).toHaveLength(1);

      events.changes.next(folderEvent('inbox-1'));
      await advance();
      expect(mail.lists).toHaveLength(2);
      expect(mail.lists[1]).toMatchObject({
        scope: { folderId: 'inbox-1', threaded: false },
        cursor: undefined,
      });
      mail.lists[1].resolve({
        items: [
          summary('new', { subject: 'Late arrival' }),
          summary('a', { subject: 'Report' }),
          summary('b', { subject: 'Lunch', seen: true }),
          summary('c', { subject: 'Invoice' }),
        ],
        nextCursor: null,
      });
      await advance(0);
      expect(subjects()).toEqual(['Late arrival', 'Report', 'Lunch', 'Invoice']);
      // The keyboard cursor stays on the message it was on.
      expect(rows()[1].getAttribute('aria-current')).toBe('true');
    });

    it('refetches after an SSE reconnect and drops a message removed elsewhere', async () => {
      await create();
      vi.useFakeTimers();
      events.resync$.next();
      await advance();
      expect(mail.lists).toHaveLength(2);
      mail.lists[1].resolve({
        items: [summary('b', { subject: 'Lunch' }), summary('c', { subject: 'Invoice' })],
        nextCursor: null,
      });
      await advance(0);
      expect(subjects()).toEqual(['Lunch', 'Invoice']);
      expect(rows()[0].getAttribute('aria-current')).toBe('true');
    });
  });

  describe('optimistic actions in the view', () => {
    it('shows a message read at once, and unread again with the error when marking read fails', async () => {
      await create();
      expect(rows()[0].classList).toContain('unread');
      press('I', { shiftKey: true });
      expect(rows()[0].classList).not.toContain('unread');
      await flush();
      mail.succeed(0, ['a'], 'Mailbox is read-only');
      await flush();
      fixture.detectChanges();
      expect(rows()[0].classList).toContain('unread');
      expect(errors).toHaveLength(1);
      expect(errors[0]).toContain('Mailbox is read-only');
    });

    it('brings an archived message back at its old place when archiving fails, then refetches', async () => {
      await create();
      press('j');
      press('e');
      expect(rows().map((row) => row.dataset['id'])).toEqual(['a', 'c']);
      await flush();
      mail.applies[0].reject(new ApiError(503, 'Service Unavailable', 'Mail server unreachable'));
      await flush();
      fixture.detectChanges();
      expect(rows().map((row) => row.dataset['id'])).toEqual(['a', 'b', 'c']);
      expect(errors).toHaveLength(1);
      expect(errors[0]).toContain('Mail server unreachable');
      // The server's state settles what overlapping actions did.
      expect(mail.lists).toHaveLength(2);
      expect(mail.lists[1].cursor).toBeUndefined();
    });

    it('archives the only message with e and shows the empty folder', async () => {
      await create('inbox-1', {
        firstPage: { items: [summary('only', { subject: '' })], nextCursor: null },
      });
      expect(rows()[0].querySelector('.subject')?.textContent).toBe('(no subject)');
      const event = press('e');
      expect(event.defaultPrevented).toBe(true);
      expect(rows()).toEqual([]);
      expect(el().querySelector('p.empty')?.textContent?.trim()).toBe('No messages here.');
      expect(listStore().cursorId()).toBeNull();
      await flush();
      expect(mail.applies[0]).toMatchObject({ action: 'ARCHIVE', ids: ['only'] });
    });
  });

  describe('→ Next action & archive', () => {
    const LABEL = '→ Next action & archive';
    const ids = () => rows().map((row) => row.dataset['id']);
    let navigate: ReturnType<typeof vi.spyOn>;
    let snackOpen: ReturnType<typeof vi.spyOn>;
    let snackAction: Subject<void>;

    async function open(options: Parameters<typeof create>[1] = {}) {
      await create('inbox-1', options);
      navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
      snackAction = new Subject<void>();
      snackOpen = vi
        .spyOn(TestBed.inject(MatSnackBar), 'open')
        .mockReturnValue({ onAction: () => snackAction.asObservable() } as never);
    }

    async function answer(results: ReturnType<typeof toTaskResult>[] | Error, index = 0) {
      await flush();
      if (results instanceof Error) mail.toTasks[index].reject(results);
      else mail.toTasks[index].resolve(results);
      await flush();
      fixture.detectChanges();
    }

    it('is a toolbar button with Shift+T in its title, and listed in the key overview', async () => {
      await open();
      expect(button(LABEL).getAttribute('title')).toBe('Shift+T');
      expect(button(LABEL).disabled).toBe(false);
      expect(MAIL_LIST_HELP.some((info) => info.keys.includes('T'))).toBe(true);
    });

    it('is disabled in an empty folder', async () => {
      await open({ firstPage: { items: [], nextCursor: null } });
      expect(button(LABEL).disabled).toBe(true);
      press('T', { shiftKey: true });
      await flush();
      expect(mail.toTasks).toEqual([]);
    });

    it('creates one Next action per selected mail, archives them and stays in the list', async () => {
      await open();
      listStore().toggleSelected('a');
      listStore().toggleSelected('c');
      fixture.detectChanges();
      button(LABEL).click();
      await answer([toTaskResult('a'), toTaskResult('c')]);
      expect(mail.toTasks.map((call) => call.ids)).toEqual([['a', 'c']]);
      expect(ids()).toEqual(['b']);
      expect(navigate).not.toHaveBeenCalled();
      expect(errors).toEqual([]);
      expect(snackOpen).toHaveBeenCalledExactlyOnceWith('2 next actions created', 'Next Actions', {
        duration: 8000,
      });
    });

    it('opens Next Actions from the message', async () => {
      await open();
      listStore().toggleAll();
      fixture.detectChanges();
      press('T', { shiftKey: true });
      await answer([toTaskResult('a'), toTaskResult('b'), toTaskResult('c')]);
      expect(snackOpen).toHaveBeenCalledWith('3 next actions created', 'Next Actions', {
        duration: 8000,
      });
      expect(navigate).not.toHaveBeenCalled();
      snackAction.next();
      expect(navigate).toHaveBeenCalledExactlyOnceWith(['/next']);
    });

    it('opens the task of a single mail (the one under the cursor) with Shift+T', async () => {
      await open();
      press('j');
      const event = press('T', { shiftKey: true });
      expect(event.defaultPrevented).toBe(true);
      await answer([toTaskResult('b')]);
      expect(mail.toTasks.map((call) => call.ids)).toEqual([['b']]);
      expect(navigate).toHaveBeenCalledExactlyOnceWith(['/tasks', 'task-b']);
      expect(snackOpen).not.toHaveBeenCalled();
      expect(ids()).toEqual(['a', 'c']);
    });

    it('does not run on t, as a chord, repeated or while typing', async () => {
      await open();
      const input = document.createElement('input');
      document.body.appendChild(input);
      try {
        press('t');
        press('T', { shiftKey: true, metaKey: true });
        press('T', { shiftKey: true, repeat: true });
        press('T', { shiftKey: true }, input);
        await flush();
        expect(mail.toTasks).toEqual([]);
      } finally {
        input.remove();
      }
    });

    it('keeps a mail whose archive failed in the list, with an error naming it', async () => {
      await open();
      listStore().toggleSelected('a');
      listStore().toggleSelected('b');
      fixture.detectChanges();
      button(LABEL).click();
      await answer([toTaskResult('a'), toTaskResult('b', false, 'No Archive folder')]);
      // The archived mail left the list; the other one stays (its task is kept).
      expect(ids()).toEqual(['b', 'c']);
      expect(navigate).not.toHaveBeenCalled();
      // One error names the mail that was not archived, and only that one.
      expect(errors).toHaveLength(1);
      expect(errors[0]).toContain('“Lunch”');
      expect(errors[0]).not.toContain('Report');
    });

    it('still opens the task of a single mail whose archive failed', async () => {
      await open();
      button(LABEL).click();
      await answer([toTaskResult('a', false, 'No Archive folder')]);
      expect(errors).toEqual([
        'The next action was created, but “Report” could not be archived: No Archive folder',
      ]);
      expect(navigate).toHaveBeenCalledExactlyOnceWith(['/tasks', 'task-a']);
      expect(ids()).toEqual(['a', 'b', 'c']);
    });

    it('leaves the mails in place with the error when the request fails', async () => {
      await open();
      listStore().toggleSelected('a');
      listStore().toggleSelected('b');
      fixture.detectChanges();
      button(LABEL).click();
      await answer(new ApiError(404, 'Not Found', 'Message not found'));
      expect(ids()).toEqual(['a', 'b', 'c']);
      expect(errors).toEqual(['The next action could not be created: Message not found']);
      expect(navigate).not.toHaveBeenCalled();
      expect(snackOpen).not.toHaveBeenCalled();
      expect(button(LABEL).disabled).toBe(false);
    });

    it('reports an unexpected failure in general words', async () => {
      await open();
      button(LABEL).click();
      await answer(new TypeError('Failed to fetch'));
      expect(errors).toEqual(['The next action could not be created: Something went wrong.']);
    });

    it('does nothing more when the server returned no result', async () => {
      await open();
      button(LABEL).click();
      await answer([]);
      expect(navigate).not.toHaveBeenCalled();
      expect(snackOpen).not.toHaveBeenCalled();
      expect(errors).toEqual([]);
      expect(button(LABEL).disabled).toBe(false);
    });

    it('is disabled while it runs, so a second click or Shift+T does not run it twice', async () => {
      await open();
      button(LABEL).click();
      fixture.detectChanges();
      expect(button(LABEL).disabled).toBe(true);
      button(LABEL).click();
      press('T', { shiftKey: true });
      await flush();
      expect(mail.toTasks).toHaveLength(1);
      await answer([toTaskResult('a')]);
      expect(navigate).toHaveBeenCalledTimes(1);
      expect(button(LABEL).disabled).toBe(false);
    });
  });

  describe('load older from server', () => {
    const older = () => el().querySelector<HTMLElement>('.more.older');
    const olderButton = () => older()?.querySelector<HTMLButtonElement>('button') ?? null;

    it('offers it at the end of a folder, shows the progress, then the newly cached messages', async () => {
      await create();
      expect(olderButton()?.textContent?.trim()).toBe('Load older from server');
      expect(olderButton()?.disabled).toBe(false);

      olderButton()!.click();
      fixture.detectChanges();
      expect(mail.loadOlders.map((call) => call.folderId)).toEqual(['inbox-1']);
      expect(olderButton()?.disabled).toBe(true);
      expect(older()?.querySelector('[role="status"]')?.textContent?.trim()).toBe(
        'Loading older messages from the server…',
      );

      mail.loadOlders[0].resolve({ loaded: 1, hasMore: false });
      await flush();
      mail.lists.at(-1)!.resolve({
        items: [
          summary('a', { subject: 'Report', folderId: 'inbox-1' }),
          summary('b', { subject: 'Lunch', folderId: 'inbox-1' }),
          summary('c', { subject: 'Invoice', folderId: 'inbox-1' }),
          summary('d', { subject: 'Old newsletter', folderId: 'inbox-1' }),
        ],
        nextCursor: null,
      });
      await flush();
      fixture.detectChanges();

      expect(rows().map((row) => row.querySelector('.subject')?.textContent)).toEqual([
        'Report',
        'Lunch',
        'Invoice',
        'Old newsletter',
      ]);
      expect(olderButton()?.disabled).toBe(false);
      expect(older()?.querySelector('[role="status"]')).toBeNull();
    });

    it('says non-blocking that nothing older exists, and keeps the list and the button', async () => {
      await create();
      olderButton()!.click();
      mail.loadOlders[0].resolve({ loaded: 0, hasMore: false });
      await flush();
      fixture.detectChanges();

      // A normal outcome, not a failure: a neutral note without the failure's prefix.
      expect(older()?.querySelector('.error')).toBeNull();
      const note = older()?.querySelector('p.note');
      expect(note?.getAttribute('role')).toBe('status');
      expect(note?.textContent?.trim()).toBe('There are no older messages on the server.');
      expect(rows()).toHaveLength(3);
      expect(olderButton()?.disabled).toBe(false);
      expect(errors).toEqual([]);
    });

    it('shows a connection error at the end of the list without a toast', async () => {
      await create();
      olderButton()!.click();
      mail.loadOlders[0].reject(new ApiError(503, 'Unavailable', 'Mail server unreachable'));
      await flush();
      fixture.detectChanges();

      expect(older()?.querySelector('.error')?.textContent?.trim()).toBe(
        'Nothing older loaded: Mail server unreachable',
      );
      expect(rows()).toHaveLength(3);
      expect(errors).toEqual([]);
    });

    it('is offered under an empty folder', async () => {
      await create('inbox-1', { firstPage: { items: [], nextCursor: null } });
      expect(el().querySelector('.empty')?.textContent).toContain('No messages here.');
      expect(olderButton()).not.toBeNull();
    });

    it('is not offered in the unified Inbox', async () => {
      await create(null);
      expect(older()).toBeNull();
    });

    it('is not offered while more cached pages follow', async () => {
      await create('inbox-1', {
        firstPage: { items: [summary('a', { folderId: 'inbox-1' })], nextCursor: 'next' },
      });
      expect(older()).toBeNull();
    });

    it('is not offered for search results', async () => {
      await create();
      expect(older()).not.toBeNull();
      const field = el().querySelector<HTMLInputElement>(
        'form[role="search"] input[type="search"]',
      )!;
      field.value = 'invoice';
      field.dispatchEvent(new Event('input'));
      field.form!.dispatchEvent(new Event('submit', { cancelable: true }));
      await flush();
      mail.searches.at(-1)!.resolve({
        items: [summary('a', { folderId: 'inbox-1' })],
        hasMore: false,
        failedAccountIds: [],
      });
      await flush();
      fixture.detectChanges();
      expect(mail.searches).toHaveLength(1);
      expect(rows()).toHaveLength(1);
      expect(older()).toBeNull();
    });
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
