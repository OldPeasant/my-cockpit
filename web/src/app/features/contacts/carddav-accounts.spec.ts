import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CardDavAccountResponse } from '../../api/generated';
import { NotificationService, provideHttp } from '../../core/http';
import { CardDavAccounts } from './carddav-accounts';

const LIST = '/api/v1/contacts/carddav-accounts';
const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

const HOME_ID = '0192f0c4-0000-7000-8000-0000000000d1';
const WORK_ID = '0192f0c4-0000-7000-8000-0000000000d2';
const OPEN_ID = '0192f0c4-0000-7000-8000-0000000000d3';

/** A fictitious CardDAV account; never synced unless `sync` says otherwise. */
function account(id: string, fields: Partial<CardDavAccountResponse> = {}): CardDavAccountResponse {
  return {
    id,
    name: 'Home server',
    url: 'https://dav.example.test/dav/',
    username: 'alice',
    hasPassword: true,
    sync: { state: 'NEVER' },
    ...fields,
  };
}

const HOME = account(HOME_ID, {
  sync: {
    state: 'OK',
    lastAttemptAt: '2026-10-20T08:00:00Z',
    lastSuccessAt: '2026-10-20T08:00:05Z',
  },
  addressbookUrl: 'https://dav.example.test/dav/alice/contacts/',
});
const WORK = account(WORK_ID, {
  name: 'Work server',
  url: 'https://cards.example.test:8443/remote.php/dav/',
  username: 'bob',
  sync: {
    state: 'ERROR',
    lastAttemptAt: '2026-10-20T09:00:00Z',
    lastError: 'Finding the address book: the CardDAV server could not be reached',
  },
});
const OPEN = account(OPEN_ID, {
  name: 'Open server',
  url: 'http://localhost:5232/',
  username: undefined,
  hasPassword: false,
});

describe('CardDavAccounts', () => {
  let fixture: ComponentFixture<CardDavAccounts>;
  let root: HTMLElement;
  let http: HttpTestingController;
  let notifications: { error: ReturnType<typeof vi.fn>; info: ReturnType<typeof vi.fn> };

  function create(): void {
    notifications = { error: vi.fn(), info: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        { provide: NotificationService, useValue: notifications },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(CardDavAccounts);
    root = fixture.nativeElement as HTMLElement;
  }

  /** Creates the panel and answers its list request with `accounts`. */
  async function open(accounts: CardDavAccountResponse[]): Promise<void> {
    create();
    await settle();
    http.expectOne((r) => r.method === 'GET' && r.url === LIST).flush({ items: accounts });
    await settle();
  }

  async function settle(): Promise<void> {
    await tick();
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function text(el: Element | null): string {
    return el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  }

  /** The ids of the listed accounts, in order. */
  function ids(): (string | null)[] {
    return [...root.querySelectorAll('li.account')].map((el) => el.getAttribute('data-id'));
  }

  function row(id: string): HTMLElement {
    return root.querySelector(`li.account[data-id="${id}"]`) as HTMLElement;
  }

  async function click(el: Element | null): Promise<void> {
    (el as HTMLButtonElement).click();
    await settle();
  }

  async function type(name: string, value: string): Promise<void> {
    const input = root.querySelector(`.add-form input[name="${name}"]`) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await settle();
  }

  async function submit(): Promise<void> {
    (root.querySelector('.add-form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    await settle();
  }

  afterEach(() => {
    http.verify();
    // The panel shows its errors itself (silent requests): no global error toast.
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('shows a loading state until the accounts arrive', async () => {
    create();
    await settle();
    expect(text(root.querySelector('[role="status"]'))).toBe('Loading CardDAV accounts…');
    http.expectOne(LIST).flush({ items: [] });
    await settle();
    expect(root.querySelector('[role="status"]')).toBeNull();
  });

  it('lists the accounts with where they sync and their state NEVER, OK and ERROR', async () => {
    await open([HOME, WORK, OPEN]);

    expect([...root.querySelectorAll('li.account .name')].map((el) => text(el))).toEqual([
      'Home server',
      'Work server',
      'Open server',
    ]);
    expect(text(row(HOME_ID).querySelector('.where'))).toBe('alice@dav.example.test');
    expect(text(row(WORK_ID).querySelector('.where'))).toBe('bob@cards.example.test:8443');
    expect(text(row(OPEN_ID).querySelector('.where'))).toBe('localhost:5232');

    expect(row(HOME_ID).getAttribute('data-state')).toBe('OK');
    expect(text(row(HOME_ID).querySelector('.state'))).toMatch(/^Synced \S/);
    expect(row(HOME_ID).querySelector('.state.failed')).toBeNull();

    expect(row(WORK_ID).getAttribute('data-state')).toBe('ERROR');
    expect(text(row(WORK_ID).querySelector('.state'))).toBe(
      'Sync failed: Finding the address book: the CardDAV server could not be reached',
    );
    expect(row(WORK_ID).querySelector('.state.failed')).not.toBeNull();

    expect(row(OPEN_ID).getAttribute('data-state')).toBe('NEVER');
    expect(text(row(OPEN_ID).querySelector('.state'))).toBe('Not synced yet');

    expect(root.querySelector('.none')).toBeNull();
    expect(ids()).toEqual([HOME_ID, WORK_ID, OPEN_ID]);
  });

  it('says so when there is no account', async () => {
    await open([]);
    expect(root.querySelector('li.account')).toBeNull();
    expect(text(root.querySelector('.none'))).toContain('No CardDAV account.');
  });

  it('shows a load error and loads again with Try again', async () => {
    create();
    await settle();
    http
      .expectOne(LIST)
      .flush(
        { status: 503, title: 'Service Unavailable', detail: 'The server is busy' },
        { status: 503, statusText: 'Service Unavailable' },
      );
    await settle();
    const error = root.querySelector('.carddav > .error[role="alert"]');
    expect(text(error)).toContain('The CardDAV accounts could not be loaded: The server is busy');
    expect(root.querySelector('li.account')).toBeNull();

    await click(error!.querySelector('button'));
    http.expectOne(LIST).flush({ items: [HOME] });
    await settle();
    expect(root.querySelector('.carddav > .error')).toBeNull();
    expect(text(row(HOME_ID).querySelector('.name'))).toBe('Home server');
  });

  it('syncs an account now, shows Syncing… meanwhile and the new state afterwards', async () => {
    await open([WORK, OPEN]);

    await click(row(OPEN_ID).querySelector('button.sync-now'));
    const req = http.expectOne(`${LIST}/${OPEN_ID}/sync`);
    expect(req.request.method).toBe('POST');
    expect(text(row(OPEN_ID).querySelector('.state'))).toBe('Syncing…');
    expect((row(OPEN_ID).querySelector('button.sync-now') as HTMLButtonElement).disabled).toBe(
      true,
    );
    expect((row(OPEN_ID).querySelector('button.remove') as HTMLButtonElement).disabled).toBe(true);
    // The other account stays usable.
    expect((row(WORK_ID).querySelector('button.sync-now') as HTMLButtonElement).disabled).toBe(
      false,
    );

    // A second click while it runs sends nothing.
    (row(OPEN_ID).querySelector('button.sync-now') as HTMLButtonElement).click();
    http.expectNone(`${LIST}/${OPEN_ID}/sync`);

    const synced = account(OPEN_ID, {
      ...OPEN,
      sync: { state: 'OK', lastSuccessAt: '2026-10-21T10:00:00Z' },
    });
    req.flush(synced);
    await settle();
    expect(row(OPEN_ID).getAttribute('data-state')).toBe('OK');
    expect(text(row(OPEN_ID).querySelector('.state'))).toMatch(/^Synced \S/);
    expect((row(OPEN_ID).querySelector('button.sync-now') as HTMLButtonElement).disabled).toBe(
      false,
    );
    expect(row(WORK_ID).getAttribute('data-state')).toBe('ERROR');
    // The synced account is replaced in place.
    expect(ids()).toEqual([WORK_ID, OPEN_ID]);
  });

  it('shows a failing server reported by sync now as the account state', async () => {
    await open([OPEN]);
    await click(row(OPEN_ID).querySelector('button.sync-now'));
    http.expectOne(`${LIST}/${OPEN_ID}/sync`).flush({
      ...OPEN,
      sync: {
        state: 'ERROR',
        lastAttemptAt: '2026-10-21T10:00:00Z',
        lastError:
          'Finding the address book: the CardDAV server rejected the user name or password',
      },
    });
    await settle();
    expect(text(row(OPEN_ID).querySelector('.state'))).toBe(
      'Sync failed: Finding the address book: the CardDAV server rejected the user name or password',
    );
    expect(row(OPEN_ID).querySelector('.error[role="alert"]')).toBeNull();
  });

  it('shows an error response of sync now on the account and re-enables it', async () => {
    await open([HOME, OPEN]);
    await click(row(OPEN_ID).querySelector('button.sync-now'));
    http
      .expectOne(`${LIST}/${OPEN_ID}/sync`)
      .flush(
        { status: 404, title: 'Not Found', detail: 'CardDAV account not found' },
        { status: 404, statusText: 'Not Found' },
      );
    await settle();
    expect(text(row(OPEN_ID).querySelector('.error[role="alert"]'))).toBe(
      'CardDAV account not found',
    );
    expect(row(HOME_ID).querySelector('.error')).toBeNull();
    expect((row(OPEN_ID).querySelector('button.sync-now') as HTMLButtonElement).disabled).toBe(
      false,
    );
    expect(row(OPEN_ID).getAttribute('data-state')).toBe('NEVER');
  });

  it('removes an account only after confirmation; Cancel keeps it', async () => {
    await open([HOME, OPEN]);

    await click(row(HOME_ID).querySelector('button.remove'));
    http.expectNone((r) => r.method === 'DELETE');
    expect(text(row(HOME_ID).querySelector('.confirm'))).toBe(
      'Stop syncing? Its contacts stay here.',
    );
    expect(row(HOME_ID).querySelector('button.sync-now')).toBeNull();
    expect(row(OPEN_ID).querySelector('.confirm')).toBeNull();

    await click(row(HOME_ID).querySelector('button.remove-cancel'));
    expect(row(HOME_ID).querySelector('.confirm')).toBeNull();
    expect(row(HOME_ID).querySelector('button.sync-now')).not.toBeNull();
    http.expectNone((r) => r.method === 'DELETE');

    await click(row(HOME_ID).querySelector('button.remove'));
    await click(row(HOME_ID).querySelector('button.remove-confirm'));
    const req = http.expectOne(`${LIST}/${HOME_ID}`);
    expect(req.request.method).toBe('DELETE');
    // While it is removed, the state does not say Syncing… and the confirm button is disabled.
    expect(text(row(HOME_ID).querySelector('.state'))).toMatch(/^Synced/);
    expect(
      (row(HOME_ID).querySelector('button.remove-confirm') as HTMLButtonElement).disabled,
    ).toBe(true);
    req.flush(null, { status: 204, statusText: 'No Content' });
    await settle();
    expect(row(HOME_ID)).toBeNull();
    expect(row(OPEN_ID)).not.toBeNull();
    expect(ids()).toEqual([OPEN_ID]);
  });

  it('keeps the account and shows the error when removing fails', async () => {
    await open([HOME]);
    await click(row(HOME_ID).querySelector('button.remove'));
    await click(row(HOME_ID).querySelector('button.remove-confirm'));
    http
      .expectOne(`${LIST}/${HOME_ID}`)
      .flush(
        { status: 500, title: 'Internal Server Error', detail: 'An unexpected error occurred' },
        { status: 500, statusText: 'Internal Server Error' },
      );
    await settle();
    expect(row(HOME_ID)).not.toBeNull();
    expect(text(row(HOME_ID).querySelector('.error[role="alert"]'))).toBe(
      'An unexpected error occurred',
    );
    // Still waiting for confirmation, and it can be tried again.
    expect(
      (row(HOME_ID).querySelector('button.remove-confirm') as HTMLButtonElement).disabled,
    ).toBe(false);
  });

  it('adds an account, sends only the filled fields and syncs it at once', async () => {
    await open([HOME]);
    await click(root.querySelector('button.add'));
    expect(root.querySelector('button.add')).toBeNull();
    await type('name', '  Family server ');
    await type('url', ' https://family.example.test/dav/ ');
    await type('username', '   ');
    await submit();

    const add = http.expectOne((r) => r.method === 'POST' && r.url === LIST);
    expect(add.request.body).toEqual({
      name: 'Family server',
      url: 'https://family.example.test/dav/',
    });
    const NEW = '0192f0c4-0000-7000-8000-0000000000d9';
    const added = account(NEW, {
      name: 'Family server',
      url: 'https://family.example.test/dav/',
      username: undefined,
      hasPassword: false,
    });
    add.flush(added, { status: 201, statusText: 'Created' });
    await settle();

    expect(root.querySelector('.add-form')).toBeNull();
    expect(root.querySelector('button.add')).not.toBeNull();
    expect(text(row(NEW).querySelector('.name'))).toBe('Family server');
    expect(text(row(NEW).querySelector('.state'))).toBe('Syncing…');

    const sync = http.expectOne(`${LIST}/${NEW}/sync`);
    expect(sync.request.method).toBe('POST');
    sync.flush({ ...added, sync: { state: 'OK', lastSuccessAt: '2026-10-21T10:00:00Z' } });
    await settle();
    expect(row(NEW).getAttribute('data-state')).toBe('OK');
    expect(ids()).toEqual([HOME_ID, NEW]);
  });

  it('sends the user name and password when given', async () => {
    await open([]);
    await click(root.querySelector('button.add'));
    expect(root.querySelector('.none')).toBeNull();
    await type('name', 'Home server');
    await type('url', 'https://dav.example.test/dav/');
    await type('username', ' alice ');
    await type('password', ' fictitious pw ');
    await submit();
    const add = http.expectOne((r) => r.method === 'POST' && r.url === LIST);
    expect(add.request.body).toEqual({
      name: 'Home server',
      url: 'https://dav.example.test/dav/',
      username: 'alice',
      password: ' fictitious pw ',
    });
    add.flush(account(HOME_ID), { status: 201, statusText: 'Created' });
    await settle();
    http.expectOne(`${LIST}/${HOME_ID}/sync`).flush(account(HOME_ID));
    await settle();
    expect(root.querySelector('input[name="password"]') as HTMLInputElement | null).toBeNull();
  });

  it('asks for a name and URL before sending anything', async () => {
    await open([]);
    await click(root.querySelector('button.add'));
    await type('name', '   ');
    await type('url', 'https://dav.example.test/');
    await submit();
    expect(text(root.querySelector('.add-form .error'))).toBe(
      'The account could not be added: Enter a name and the server URL.',
    );
    await type('name', 'Home');
    await type('url', ' ');
    await submit();
    expect(text(root.querySelector('.add-form .error'))).toContain(
      'Enter a name and the server URL.',
    );
    http.expectNone((r) => r.method === 'POST');
  });

  it('shows the field errors of a 400 with readable field names and keeps the form', async () => {
    await open([]);
    await click(root.querySelector('button.add'));
    await type('name', 'Home server');
    await type('url', 'ftp://dav.example.test/');
    await submit();
    http
      .expectOne((r) => r.method === 'POST' && r.url === LIST)
      .flush(
        {
          status: 400,
          title: 'Bad Request',
          detail: 'Request validation failed',
          errors: [
            { field: 'url', message: 'must be an http or https URL without credentials' },
            { field: 'name', message: 'must not be blank' },
            { field: 'other', message: 'is wrong' },
          ],
        },
        { status: 400, statusText: 'Bad Request' },
      );
    await settle();
    expect(text(root.querySelector('.add-form .error[role="alert"]'))).toBe(
      'The account could not be added: Server URL must be an http or https URL without credentials; ' +
        'Name must not be blank; other is wrong',
    );
    expect((root.querySelector('input[name="url"]') as HTMLInputElement).value).toBe(
      'ftp://dav.example.test/',
    );
    expect((root.querySelector('.add-form button.save') as HTMLButtonElement).disabled).toBe(false);
    http.expectNone((r) => r.url.endsWith('/sync'));
  });

  it('disables the form while it is sent and Cancel closes it without sending', async () => {
    await open([]);
    await click(root.querySelector('button.add'));
    await type('name', 'Home server');
    await type('url', 'https://dav.example.test/');
    await submit();
    expect((root.querySelector('.add-form button.save') as HTMLButtonElement).disabled).toBe(true);
    expect((root.querySelector('.add-form button.cancel') as HTMLButtonElement).disabled).toBe(
      true,
    );
    // A second submit while sending sends nothing more.
    await submit();
    const add = http.expectOne((r) => r.method === 'POST' && r.url === LIST);
    add.flush(
      { status: 409, title: 'Conflict', detail: 'Something changed' },
      { status: 409, statusText: 'Conflict' },
    );
    await settle();
    expect(text(root.querySelector('.add-form .error'))).toBe(
      'The account could not be added: Something changed',
    );

    await click(root.querySelector('.add-form button.cancel'));
    expect(root.querySelector('.add-form')).toBeNull();
    expect(text(root.querySelector('.none'))).toContain('No CardDAV account.');

    // Opening it again starts empty, without the old error.
    await click(root.querySelector('button.add'));
    expect((root.querySelector('input[name="name"]') as HTMLInputElement).value).toBe('');
    expect(root.querySelector('.add-form .error')).toBeNull();
  });
});
