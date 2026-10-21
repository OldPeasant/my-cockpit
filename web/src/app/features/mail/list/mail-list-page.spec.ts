import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { ActivatedRoute, convertToParamMap, ParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, of } from 'rxjs';
import { MailAccountResponse, MailFolderResponse } from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { NotificationService } from '../../../core/http';
import MailListPage, { senderName, THREADED_KEY } from './mail-list-page';
import { FakeEvents, FakeMail, summary } from './mail-testing';
import { MailService } from './mail.service';

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

  async function create(folderId: string | null = 'inbox-1') {
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
              return { afterClosed: () => of(dialogResult) };
            },
          },
        },
      ],
    });
    fixture = TestBed.createComponent(MailListPage);
    await flush();
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
});

describe('senderName', () => {
  it('prefers names, falls back to addresses', () => {
    expect(senderName([{ name: 'Max' }, { name: '', address: 'erika@example.test' }])).toBe(
      'Max, erika@example.test',
    );
    expect(senderName(undefined)).toBe('(unknown sender)');
  });
});
