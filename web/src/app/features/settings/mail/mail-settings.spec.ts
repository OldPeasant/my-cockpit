import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import {
  MailAccountResponse,
  OAuthConnectionResponse,
  OAuthProviderResponse,
} from '../../../api/generated';
import { NotificationService, provideHttp } from '../../../core/http';
import { BROWSER_NAVIGATE } from '../calendars/calendar-sources.service';
import { fakeConnection } from '../calendars/testing';
import MailSettings from './mail-settings';
import { fakeAccount, fakeFolder, TEST_OK, TEST_SMTP_FAILED } from './testing';

const ACCOUNTS = '/api/v1/mail/accounts';

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

const problem = (status: number, title: string, errors: unknown[] = []) => ({
  status,
  statusText: title,
  headers: { 'Content-Type': 'application/problem+json' },
  body: { status, title, errors },
});

const BOTH_CONFIGURED: OAuthProviderResponse[] = [
  { provider: 'GOOGLE', slug: 'google', configured: true },
  { provider: 'MICROSOFT', slug: 'microsoft', configured: false },
];

describe('MailSettings', () => {
  let fixture: ComponentFixture<MailSettings>;
  let root: HTMLElement;
  let http: HttpTestingController;
  let navigate: ReturnType<typeof vi.fn>;

  async function setup(
    accounts: MailAccountResponse[] = [fakeAccount()],
    connections: OAuthConnectionResponse[] = [],
    providers: OAuthProviderResponse[] = BOTH_CONFIGURED,
  ): Promise<void> {
    navigate = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: { error: vi.fn() } },
        { provide: BROWSER_NAVIGATE, useValue: navigate },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(MailSettings);
    root = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    await tick();
    http.expectOne({ method: 'GET', url: ACCOUNTS }).flush({ items: accounts });
    http.expectOne('/api/v1/oauth/providers').flush(providers);
    http.expectOne('/api/v1/oauth/connections').flush(connections);
    await settle();
  }

  async function settle(): Promise<void> {
    await tick();
    fixture.detectChanges();
    await fixture.whenStable();
  }

  afterEach(() => http.verify());

  function text(): string {
    return root.textContent?.replace(/\s+/g, ' ') ?? '';
  }

  function button(label: string, scope: ParentNode = root): HTMLButtonElement {
    const found = [...scope.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === label,
    );
    if (!found) throw new Error(`No button "${label}"`);
    return found;
  }

  function type(selector: string, value: string, scope: ParentNode = root): void {
    const field = scope.querySelector<HTMLInputElement | HTMLTextAreaElement>(selector);
    if (!field) throw new Error(`No field ${selector}`);
    field.value = value;
    field.dispatchEvent(new Event('input'));
  }

  async function chooseType(label: string): Promise<void> {
    const toggle = [...root.querySelectorAll('mat-button-toggle')].find(
      (t) => t.textContent?.trim() === label,
    );
    toggle!.querySelector('button')!.click();
    await settle();
  }

  async function chooseOption(trigger: HTMLElement, label: string): Promise<void> {
    trigger.click();
    await settle();
    const option = [...document.querySelectorAll<HTMLElement>('mat-option')].find(
      (o) => o.textContent?.trim() === label,
    );
    if (!option) throw new Error(`No option "${label}"`);
    option.click();
    await settle();
  }

  function addForm(): HTMLFormElement {
    return root.querySelectorAll<HTMLFormElement>('form.mail-form')[0];
  }

  function fillImap(): void {
    const form = addForm();
    type('[formControlName="emailAddress"]', 'john@mail.example.test', form);
    type('[formControlName="username"]', 'john', form);
    type('[formControlName="password"]', 's3cret-app-pass', form);
    type(
      'app-server-fields[protocol="imap"] [formControlName="host"]',
      'imap.mail.example.test',
      form,
    );
    type(
      'app-server-fields[protocol="smtp"] [formControlName="host"]',
      'smtp.mail.example.test',
      form,
    );
    type('[formControlName="signatureMd"]', '**John**', form);
  }

  it('lists the accounts with address, type and notification', async () => {
    await setup([
      fakeAccount(),
      fakeAccount({
        id: 'a2',
        type: 'GOOGLE',
        displayName: 'John',
        emailAddress: 'john@gmail.example',
        notifyNewMail: false,
      }),
    ]);

    const items = root.querySelectorAll('li.account');
    expect(items.length).toBe(2);
    expect(items[0].textContent).toContain('jane@mail.example.test');
    expect(items[0].textContent).toContain('IMAP/SMTP');
    expect(items[1].textContent).toContain('Google');
    const toggles = root.querySelectorAll<HTMLButtonElement>('li.account mat-slide-toggle button');
    expect(toggles[0].getAttribute('aria-checked')).toBe('true');
    expect(toggles[1].getAttribute('aria-checked')).toBe('false');
  });

  it('invites to add an account when there is none', async () => {
    await setup([]);

    expect(text()).toContain('No mail accounts yet. Add one below.');
  });

  it('shows a load error with a retry', async () => {
    navigate = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: { error: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(MailSettings);
    root = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    await tick();
    const { body, ...options } = problem(500, 'Server error');
    http.expectOne(ACCOUNTS).flush(body, options);
    http.expectOne('/api/v1/oauth/providers').flush([]);
    http.expectOne('/api/v1/oauth/connections').flush([]);
    await settle();

    expect(text()).toContain('Mail accounts could not be loaded: Server error');
    button('Try again').click();
    await settle();
    http.expectOne(ACCOUNTS).flush({ items: [fakeAccount()] });
    http.expectOne('/api/v1/oauth/providers').flush([]);
    http.expectOne('/api/v1/oauth/connections').flush([]);
    await settle();
    expect(root.querySelectorAll('li.account').length).toBe(1);
  });

  describe('test connection', () => {
    it('shows that it runs, then the result per protocol', async () => {
      await setup();

      button('Test connection').click();
      await settle();
      expect(text()).toContain('Logging in to the mail servers…');
      expect(button('Test connection').disabled).toBe(true);

      http.expectOne({ method: 'POST', url: `${ACCOUNTS}/a1/test` }).flush(TEST_SMTP_FAILED);
      await settle();

      const lines = [...root.querySelectorAll('.protocols > div')];
      expect(lines.map((l) => l.className.trim())).toEqual(['ok', 'failed']);
      expect(lines[0].textContent).toContain('Connected');
      expect(lines[1].textContent).toContain(
        'The server did not accept the user name or password.',
      );
      expect(button('Test connection').disabled).toBe(false);
    });

    it('shows both servers connected', async () => {
      await setup();

      button('Test connection').click();
      await settle();
      http.expectOne(`${ACCOUNTS}/a1/test`).flush(TEST_OK);
      await settle();

      expect(root.querySelectorAll('.protocols .ok').length).toBe(2);
    });

    it('says when the test itself could not run', async () => {
      await setup();

      button('Test connection').click();
      await settle();
      const { body, ...options } = problem(503, 'Service Unavailable');
      http.expectOne(`${ACCOUNTS}/a1/test`).flush(body, options);
      await settle();

      expect(text()).toContain('The test could not run: Service Unavailable');
    });
  });

  describe('add IMAP/SMTP', () => {
    it('does not send an incomplete form', async () => {
      await setup([]);

      button('Add and test').click();
      await settle();

      http.expectNone({ method: 'POST', url: ACCOUNTS });
      expect(text()).toContain('Enter an email address.');
      expect(text()).toContain('Enter the password.');
    });

    it('adds the account, tests it and detects its folders', async () => {
      await setup([]);
      fillImap();

      button('Add and test').click();
      await settle();

      const create = http.expectOne({ method: 'POST', url: ACCOUNTS });
      expect(create.request.body).toEqual({
        type: 'IMAP_SMTP',
        displayName: undefined,
        emailAddress: 'john@mail.example.test',
        username: 'john',
        password: 's3cret-app-pass',
        imap: { host: 'imap.mail.example.test', port: 993, security: 'SSL_TLS' },
        smtp: { host: 'smtp.mail.example.test', port: 587, security: 'STARTTLS' },
        signatureMd: '**John**',
        notifyNewMail: true,
      });
      create.flush(fakeAccount({ id: 'a7', emailAddress: 'john@mail.example.test' }), {
        status: 201,
        statusText: 'Created',
      });
      await settle();
      expect(text()).toContain('john@mail.example.test added. Testing the connection…');

      http.expectOne({ method: 'POST', url: `${ACCOUNTS}/a7/test` }).flush(TEST_OK);
      await settle();
      http
        .expectOne({ method: 'POST', url: `${ACCOUNTS}/a7/folders/sync` })
        .flush({ items: [fakeFolder(), fakeFolder({ id: 'f2', path: 'Sent', role: 'SENT' })] });
      await settle();

      expect(text()).toContain('john@mail.example.test added and connected.');
      expect(text()).toContain('2 folders found.');
      expect(root.querySelectorAll('li.account').length).toBe(1);
      expect(addForm().querySelector<HTMLInputElement>('[formControlName="password"]')!.value).toBe(
        '',
      );
    });

    it('keeps a new account whose login fails and says how to fix it', async () => {
      await setup([]);
      fillImap();

      button('Add and test').click();
      await settle();
      http
        .expectOne({ method: 'POST', url: ACCOUNTS })
        .flush(fakeAccount({ id: 'a7' }), { status: 201, statusText: 'Created' });
      await settle();
      http.expectOne(`${ACCOUNTS}/a7/test`).flush({
        imap: { ok: false, errorCode: 'TIMEOUT', message: 'The server did not answer in time.' },
        smtp: { ok: true },
      });
      await settle();

      http.expectNone(`${ACCOUNTS}/a7/folders/sync`);
      expect(text()).toContain('added, but the connection does not work yet');
      expect(text()).toContain('The server did not answer in time.');
      expect(root.querySelectorAll('li.account').length).toBe(1);
    });

    it('shows the field errors of a rejected account', async () => {
      await setup([]);
      fillImap();

      button('Add and test').click();
      await settle();
      const { body, ...options } = problem(400, 'Bad Request', [
        { field: 'emailAddress', message: 'must be a well-formed email address' },
      ]);
      http.expectOne({ method: 'POST', url: ACCOUNTS }).flush(body, options);
      await settle();

      expect(root.querySelector('[role="alert"]')?.textContent).toContain(
        'The account could not be added: emailAddress: must be a well-formed email address',
      );
    });

    it('moves a usual port along with the security', async () => {
      await setup([]);
      const smtp = addForm().querySelector<HTMLElement>('app-server-fields[protocol="smtp"]')!;

      await chooseOption(smtp.querySelector('mat-select')!, 'SSL/TLS');

      expect(smtp.querySelector<HTMLInputElement>('[formControlName="port"]')!.value).toBe('465');
    });
  });

  describe('add Google/Microsoft', () => {
    it('adds a Google account with a connected login', async () => {
      await setup([], [fakeConnection(), fakeConnection({ id: 'o2', provider: 'MICROSOFT' })]);

      await chooseType('Google');
      expect(text()).toContain('jane@gmail.example');
      type('[formControlName="displayName"]', 'Jane', addForm());
      button('Add and test').click();
      await settle();

      const create = http.expectOne({ method: 'POST', url: ACCOUNTS });
      expect(create.request.body).toEqual({
        type: 'GOOGLE',
        oauthConnectionId: 'o1',
        emailAddress: 'jane@gmail.example',
        displayName: 'Jane',
        signatureMd: undefined,
        notifyNewMail: true,
      });
      create.flush(fakeAccount({ id: 'a3', type: 'GOOGLE' }), {
        status: 201,
        statusText: 'Created',
      });
      await settle();
      http.expectOne(`${ACCOUNTS}/a3/test`).flush(TEST_OK);
      await settle();
      http.expectOne(`${ACCOUNTS}/a3/folders/sync`).flush({ items: [] });
      await settle();
    });

    it('connects a Google account first when there is none, for mail', async () => {
      await setup([]);

      await chooseType('Google');
      button('Connect Google account').click();

      expect(navigate).toHaveBeenCalledWith('/api/v1/oauth/google/start?purpose=MAIL');
    });

    it('says when the provider is not set up on the server', async () => {
      await setup([]);

      await chooseType('Microsoft');

      expect(text()).toContain('Microsoft is not set up on this server.');
      expect(root.querySelector('form.mail-form')).toBeNull();
    });
  });

  it('switches the new-mail notification of an account', async () => {
    await setup();

    root.querySelector<HTMLButtonElement>('li.account mat-slide-toggle button')!.click();
    await settle();

    const patch = http.expectOne({ method: 'PATCH', url: `${ACCOUNTS}/a1` });
    expect(patch.request.body).toEqual({ notifyNewMail: false });
    expect(patch.request.headers.get('If-Match')).toBe('"0"');
    patch.flush(fakeAccount({ notifyNewMail: false, version: 1 }));
    await settle();
    expect(
      root.querySelector('li.account mat-slide-toggle button')!.getAttribute('aria-checked'),
    ).toBe('false');
  });

  it('switches the notification back when the change fails', async () => {
    await setup();

    root.querySelector<HTMLButtonElement>('li.account mat-slide-toggle button')!.click();
    await settle();
    const { body, ...options } = problem(412, 'Precondition Failed');
    http.expectOne({ method: 'PATCH', url: `${ACCOUNTS}/a1` }).flush(body, options);
    await settle();

    expect(text()).toContain('It was changed elsewhere.');
    expect(
      root.querySelector('li.account mat-slide-toggle button')!.getAttribute('aria-checked'),
    ).toBe('true');
  });

  it('edits identity, signature and login; an empty password keeps the stored one', async () => {
    await setup();

    button('Edit').click();
    await settle();
    const editor = root.querySelector<HTMLElement>('app-mail-account-editor')!;
    expect(editor.querySelector<HTMLInputElement>('[formControlName="emailAddress"]')!.value).toBe(
      'jane@mail.example.test',
    );
    type('[formControlName="displayName"]', 'Jane D.', editor);
    type('[formControlName="signatureMd"]', '', editor);
    button('Save changes', editor).click();
    await settle();

    const patch = http.expectOne({ method: 'PATCH', url: `${ACCOUNTS}/a1` });
    expect(patch.request.body).toEqual({
      displayName: 'Jane D.',
      emailAddress: 'jane@mail.example.test',
      signatureMd: '',
      syncWindowMonths: 12,
      username: 'jane',
      imap: { host: 'imap.mail.example.test', port: 993, security: 'SSL_TLS' },
      smtp: { host: 'smtp.mail.example.test', port: 587, security: 'STARTTLS' },
    });
    patch.flush(fakeAccount({ displayName: 'Jane D.', version: 1 }));
    await settle();

    expect(root.querySelector('app-mail-account-editor')).toBeNull();
    expect(root.querySelector('li.account .name')?.textContent).toBe('Jane D.');
  });

  it('drops the old connection test result when the account is edited', async () => {
    await setup();

    button('Test connection').click();
    await settle();
    http.expectOne(`${ACCOUNTS}/a1/test`).flush(TEST_SMTP_FAILED);
    await settle();
    expect(root.querySelector('.protocols')).not.toBeNull();

    button('Edit').click();
    await settle();
    const editor = root.querySelector<HTMLElement>('app-mail-account-editor')!;
    type('[formControlName="password"]', 'n3w-app-pass', editor);
    button('Save changes', editor).click();
    await settle();
    http.expectOne({ method: 'PATCH', url: `${ACCOUNTS}/a1` }).flush(fakeAccount({ version: 1 }));
    await settle();

    expect(root.querySelector('app-mail-account-editor')).toBeNull();
    expect(root.querySelector('.protocols')).toBeNull();
    expect(text()).not.toContain('The server did not accept the user name or password.');
    expect(button('Test connection').disabled).toBe(false);
  });

  it('keeps a test that still runs when the account is saved, and shows its answer', async () => {
    await setup();

    button('Test connection').click();
    await settle();
    const running = http.expectOne(`${ACCOUNTS}/a1/test`);

    button('Edit').click();
    await settle();
    const editor = root.querySelector<HTMLElement>('app-mail-account-editor')!;
    type('[formControlName="displayName"]', 'Jane D.', editor);
    button('Save changes', editor).click();
    await settle();
    http
      .expectOne({ method: 'PATCH', url: `${ACCOUNTS}/a1` })
      .flush(fakeAccount({ displayName: 'Jane D.', version: 1 }));
    await settle();

    // The save does not end the running test: it still runs and still guards the button.
    expect(text()).toContain('Logging in to the mail servers…');
    expect(button('Test connection').disabled).toBe(true);
    running.flush(TEST_OK);
    await settle();

    expect(root.querySelectorAll('.protocols .ok').length).toBe(2);
    expect(button('Test connection').disabled).toBe(false);
  });

  it('keeps the test result when the edit is cancelled or refused', async () => {
    await setup();

    button('Test connection').click();
    await settle();
    http.expectOne(`${ACCOUNTS}/a1/test`).flush(TEST_SMTP_FAILED);
    await settle();

    button('Edit').click();
    await settle();
    button('Cancel', root.querySelector<HTMLElement>('app-mail-account-editor')!).click();
    await settle();
    expect(text()).toContain('The server did not accept the user name or password.');

    button('Edit').click();
    await settle();
    const editor = root.querySelector<HTMLElement>('app-mail-account-editor')!;
    type('[formControlName="password"]', 'n3w-app-pass', editor);
    button('Save changes', editor).click();
    await settle();
    const { body, ...options } = problem(412, 'Precondition Failed');
    http.expectOne({ method: 'PATCH', url: `${ACCOUNTS}/a1` }).flush(body, options);
    await settle();

    // Nothing was saved, so the result still describes the stored settings.
    expect(root.querySelector('app-mail-account-editor')).not.toBeNull();
    expect(text()).toContain('The server did not accept the user name or password.');
  });

  it('drops only the test result of the edited account', async () => {
    await setup([fakeAccount(), fakeAccount({ id: 'a2', emailAddress: 'jane@work.example' })]);
    const items = (): NodeListOf<HTMLElement> => root.querySelectorAll<HTMLElement>('li.account');

    button('Test connection', items()[0]).click();
    button('Test connection', items()[1]).click();
    await settle();
    http.expectOne(`${ACCOUNTS}/a1/test`).flush(TEST_SMTP_FAILED);
    http.expectOne(`${ACCOUNTS}/a2/test`).flush(TEST_SMTP_FAILED);
    await settle();

    button('Edit', items()[0]).click();
    await settle();
    const editor = root.querySelector<HTMLElement>('app-mail-account-editor')!;
    type('[formControlName="password"]', 'n3w-app-pass', editor);
    button('Save changes', editor).click();
    await settle();
    http.expectOne({ method: 'PATCH', url: `${ACCOUNTS}/a1` }).flush(fakeAccount({ version: 1 }));
    await settle();

    expect(items()[0].querySelector('.protocols')).toBeNull();
    expect(items()[1].querySelector('.protocols')).not.toBeNull();
  });

  it('edits a Google account without login or servers', async () => {
    await setup([fakeAccount({ type: 'GOOGLE', oauthConnectionId: 'o1', hasPassword: false })]);

    button('Edit').click();
    await settle();
    const editor = root.querySelector<HTMLElement>('app-mail-account-editor')!;
    expect(editor.querySelector('app-server-fields')).toBeNull();
    expect(editor.querySelector('[formControlName="password"]')).toBeNull();
    type('[formControlName="signatureMd"]', 'Jane', editor);
    button('Save changes', editor).click();
    await settle();

    expect(http.expectOne({ method: 'PATCH', url: `${ACCOUNTS}/a1` }).request.body).toEqual({
      displayName: 'Jane Doe',
      emailAddress: 'jane@mail.example.test',
      signatureMd: 'Jane',
      syncWindowMonths: 12,
    });
  });

  it('maps a folder role by hand', async () => {
    await setup();

    button('Folders').click();
    await settle();
    http.expectOne({ method: 'GET', url: `${ACCOUNTS}/a1/folders` }).flush({
      items: [
        fakeFolder(),
        fakeFolder({ id: 'f2', path: 'Old', name: 'Old', role: undefined }),
        fakeFolder({
          id: 'f3',
          path: '[Gmail]',
          name: '[Gmail]',
          role: undefined,
          selectable: false,
        }),
      ],
    });
    await settle();
    // The select shows its value once its options are known.
    await settle();

    const rows = root.querySelectorAll('app-mail-folder-roles li');
    expect([...rows].map((r) => r.querySelector('.path')?.textContent)).toEqual(['INBOX', 'Old']);
    expect(rows[0].textContent).toContain('Automatic (Inbox)');
    expect(text()).toContain('No folder for: Sent, Drafts, Trash, Archive, Spam.');

    await chooseOption(rows[1].querySelector('mat-select')!, 'Archive');
    const patch = http.expectOne({ method: 'PATCH', url: `${ACCOUNTS}/a1/folders/f2` });
    expect(patch.request.body).toEqual({ role: 'ARCHIVE' });
    patch.flush(fakeFolder({ id: 'f2', path: 'Old', role: 'ARCHIVE', roleOverridden: true }));
    await settle();
    http.expectOne({ method: 'GET', url: `${ACCOUNTS}/a1/folders` }).flush({
      items: [
        fakeFolder(),
        fakeFolder({ id: 'f2', path: 'Old', role: 'ARCHIVE', roleOverridden: true }),
      ],
    });
    await settle();

    expect(text()).toContain('No folder for: Sent, Drafts, Trash, Spam.');
  });

  it('detects the folders again on request', async () => {
    await setup();

    button('Folders').click();
    await settle();
    http.expectOne(`${ACCOUNTS}/a1/folders`).flush({ items: [] });
    await settle();
    expect(text()).toContain('No folders yet.');

    button('Detect folders').click();
    await settle();
    expect(text()).toContain('Reading the folders from the server…');
    http
      .expectOne({ method: 'POST', url: `${ACCOUNTS}/a1/folders/sync` })
      .flush({ items: [fakeFolder()] });
    await settle();

    expect(root.querySelectorAll('app-mail-folder-roles li').length).toBe(1);
  });

  it('removes an account after confirmation', async () => {
    await setup();

    button('Remove').click();
    await settle();
    expect(text()).toContain('Remove jane@mail.example.test from the cockpit?');
    button('Remove account').click();
    await settle();

    const del = http.expectOne({ method: 'DELETE', url: `${ACCOUNTS}/a1` });
    expect(del.request.headers.get('If-Match')).toBe('"0"');
    del.flush(null, { status: 204, statusText: 'No Content' });
    await settle();

    expect(root.querySelectorAll('li.account').length).toBe(0);
    expect(text()).toContain('jane@mail.example.test removed.');
  });

  it('keeps an account whose removal failed and says why', async () => {
    await setup();

    button('Remove').click();
    await settle();
    button('Remove account').click();
    await settle();
    expect(button('Remove account').disabled).toBe(true);
    const { body, ...options } = problem(412, 'Precondition Failed');
    http.expectOne({ method: 'DELETE', url: `${ACCOUNTS}/a1` }).flush(body, options);
    await settle();

    expect(root.querySelectorAll('li.account').length).toBe(1);
    expect(root.querySelector('li.account [role="alert"]')?.textContent).toContain(
      'jane@mail.example.test could not be changed: It was changed elsewhere.',
    );
    expect(button('Remove account').disabled).toBe(false);
  });

  it('still lists the accounts when providers and connections cannot be read', async () => {
    navigate = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: { error: vi.fn() } },
        { provide: BROWSER_NAVIGATE, useValue: navigate },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(MailSettings);
    root = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    expect(text()).toContain('Loading mail accounts…');
    await tick();
    http.expectOne(ACCOUNTS).flush({ items: [fakeAccount()] });
    const { body, ...options } = problem(500, 'Server error');
    http.expectOne('/api/v1/oauth/providers').flush(body, options);
    http.expectOne('/api/v1/oauth/connections').flush(body, options);
    await settle();

    expect(root.querySelectorAll('li.account').length).toBe(1);
    expect(root.querySelector('[role="alert"]')).toBeNull();
    await chooseType('Google');
    expect(text()).toContain('Google is not set up on this server.');
  });

  describe('panels', () => {
    it('opens one panel at a time and closes it again', async () => {
      await setup();

      button('Edit').click();
      await settle();
      expect(root.querySelector('app-mail-account-editor')).not.toBeNull();
      expect(button('Edit').getAttribute('aria-expanded')).toBe('true');

      button('Folders').click();
      await settle();
      http.expectOne(`${ACCOUNTS}/a1/folders`).flush({ items: [] });
      await settle();
      expect(root.querySelector('app-mail-account-editor')).toBeNull();
      expect(root.querySelector('app-mail-folder-roles')).not.toBeNull();

      button('Folders').click();
      await settle();
      expect(root.querySelector('app-mail-folder-roles')).toBeNull();
      expect(button('Folders').getAttribute('aria-expanded')).toBe('false');
    });

    it('closes the editor with Cancel without saving', async () => {
      await setup();

      button('Edit').click();
      await settle();
      const editor = root.querySelector<HTMLElement>('app-mail-account-editor')!;
      button('Cancel', editor).click();
      await settle();

      expect(root.querySelector('app-mail-account-editor')).toBeNull();
      http.expectNone({ method: 'PATCH', url: `${ACCOUNTS}/a1` });
    });

    it('closes the removal question with Cancel', async () => {
      await setup();

      button('Remove').click();
      await settle();
      button('Cancel', root.querySelector('.confirm')!).click();
      await settle();

      expect(root.querySelector('.confirm')).toBeNull();
      http.expectNone({ method: 'DELETE', url: `${ACCOUNTS}/a1` });
    });
  });

  it('changes the notification twice with the version of the first answer', async () => {
    await setup();
    const toggle = (): HTMLButtonElement =>
      root.querySelector<HTMLButtonElement>('li.account mat-slide-toggle button')!;

    toggle().click();
    await settle();
    expect(toggle().disabled).toBe(true);
    http
      .expectOne({ method: 'PATCH', url: `${ACCOUNTS}/a1` })
      .flush(fakeAccount({ notifyNewMail: false, version: 1 }));
    await settle();

    toggle().click();
    await settle();
    const second = http.expectOne({ method: 'PATCH', url: `${ACCOUNTS}/a1` });
    expect(second.request.headers.get('If-Match')).toBe('"1"');
    expect(second.request.body).toEqual({ notifyNewMail: true });
    second.flush(fakeAccount({ notifyNewMail: true, version: 2 }));
    await settle();
    expect(toggle().getAttribute('aria-checked')).toBe('true');
  });

  describe('test connection, more states', () => {
    it('tests each account on its own', async () => {
      await setup([fakeAccount(), fakeAccount({ id: 'a2', emailAddress: 'jane@work.example' })]);
      const items = root.querySelectorAll<HTMLElement>('li.account');

      button('Test connection', items[0]).click();
      await settle();

      expect(button('Test connection', items[0]).disabled).toBe(true);
      expect(button('Test connection', items[1]).disabled).toBe(false);
      expect(items[1].querySelector('.test')).toBeNull();
      http.expectOne(`${ACCOUNTS}/a1/test`).flush(TEST_OK);
      await settle();
    });

    it('shows Failed for a protocol that failed without a message', async () => {
      await setup();

      button('Test connection').click();
      await settle();
      http.expectOne(`${ACCOUNTS}/a1/test`).flush({ imap: { ok: false }, smtp: { ok: true } });
      await settle();

      const lines = [...root.querySelectorAll('.protocols > div')];
      expect(lines[0].className.trim()).toBe('failed');
      expect(lines[0].textContent).toContain('Failed');
    });

    it('runs again after a result and replaces it', async () => {
      await setup();

      button('Test connection').click();
      await settle();
      http.expectOne(`${ACCOUNTS}/a1/test`).flush(TEST_SMTP_FAILED);
      await settle();
      button('Test connection').click();
      await settle();
      expect(root.querySelector('.protocols')).toBeNull();
      http.expectOne(`${ACCOUNTS}/a1/test`).flush(TEST_OK);
      await settle();

      expect(root.querySelectorAll('.protocols .ok').length).toBe(2);
    });
  });

  describe('add IMAP/SMTP, more cases', () => {
    async function submitAndCreate(id = 'a7'): Promise<void> {
      button('Add and test').click();
      await settle();
      http
        .expectOne({ method: 'POST', url: ACCOUNTS })
        .flush(fakeAccount({ id, emailAddress: 'john@mail.example.test' }), {
          status: 201,
          statusText: 'Created',
        });
      await settle();
    }

    it('sends a blank name and signature as unset and the address trimmed', async () => {
      await setup([]);
      fillImap();
      type('[formControlName="emailAddress"]', '  john@mail.example.test  ', addForm());
      type('[formControlName="displayName"]', '   ', addForm());
      type('[formControlName="signatureMd"]', '  \n ', addForm());

      button('Add and test').click();
      await settle();

      const body = http.expectOne({ method: 'POST', url: ACCOUNTS }).request.body;
      expect(body.emailAddress).toBe('john@mail.example.test');
      expect(body.displayName).toBeUndefined();
      expect(body.signatureMd).toBeUndefined();
    });

    it('sends the notification choice', async () => {
      await setup([]);
      fillImap();

      addForm().querySelector<HTMLButtonElement>('mat-slide-toggle button')!.click();
      await settle();
      button('Add and test').click();
      await settle();

      expect(http.expectOne({ method: 'POST', url: ACCOUNTS }).request.body.notifyNewMail).toBe(
        false,
      );
    });

    it('refuses a malformed address and a port out of range without a request', async () => {
      await setup([]);
      fillImap();
      type('[formControlName="emailAddress"]', 'john.mail.example.test', addForm());
      type('app-server-fields[protocol="imap"] [formControlName="port"]', '0', addForm());

      button('Add and test').click();
      await settle();

      http.expectNone({ method: 'POST', url: ACCOUNTS });
      expect(text()).toContain('Enter an email address.');
      expect(text()).toContain('1 to 65535.');
    });

    it('adds only once while the account is being added', async () => {
      await setup([]);
      fillImap();

      button('Add and test').click();
      button('Add and test').click();
      await settle();

      expect(button('Add and test').disabled).toBe(true);
      http.expectOne({ method: 'POST', url: ACCOUNTS }).flush(fakeAccount({ id: 'a7' }), {
        status: 201,
        statusText: 'Created',
      });
      await settle();
      expect(button('Add and test').disabled).toBe(true);
      http.expectOne(`${ACCOUNTS}/a7/test`).flush(TEST_SMTP_FAILED);
      await settle();
      http.expectOne(`${ACCOUNTS}/a7/folders/sync`).flush({ items: [] });
      await settle();
      expect(button('Add and test').disabled).toBe(false);
    });

    it('detects the folders when only SMTP fails, and says the account does not work yet', async () => {
      await setup([]);
      fillImap();

      await submitAndCreate();
      http.expectOne(`${ACCOUNTS}/a7/test`).flush(TEST_SMTP_FAILED);
      await settle();
      http.expectOne(`${ACCOUNTS}/a7/folders/sync`).flush({ items: [fakeFolder()] });
      await settle();

      expect(text()).toContain('added, but the connection does not work yet');
      expect(text()).toContain('1 folder found.');
      expect(text()).not.toContain('1 folders found.');
      expect(text()).toContain('The server did not accept the user name or password.');
    });

    it('keeps the new account when the test could not run', async () => {
      await setup([]);
      fillImap();

      await submitAndCreate();
      const { body, ...options } = problem(503, 'Service Unavailable');
      http.expectOne(`${ACCOUNTS}/a7/test`).flush(body, options);
      await settle();

      http.expectNone(`${ACCOUNTS}/a7/folders/sync`);
      expect(root.querySelectorAll('li.account').length).toBe(1);
      expect(text()).toContain('The test could not run: Service Unavailable');
      expect(text()).toContain('added, but the connection does not work yet');
    });

    it('says when the folders of a new account could not be read', async () => {
      await setup([]);
      fillImap();

      await submitAndCreate();
      http.expectOne(`${ACCOUNTS}/a7/test`).flush(TEST_OK);
      await settle();
      const { body, ...options } = problem(502, 'Bad Gateway');
      http.expectOne(`${ACCOUNTS}/a7/folders/sync`).flush(body, options);
      await settle();

      expect(text()).toContain('Folders could not be read: Bad Gateway');
      expect(text()).toContain('john@mail.example.test added and connected.');
    });

    it('keeps what was typed when the account is refused', async () => {
      await setup([]);
      fillImap();

      button('Add and test').click();
      await settle();
      const { body, ...options } = problem(409, 'Conflict');
      http.expectOne({ method: 'POST', url: ACCOUNTS }).flush(body, options);
      await settle();

      expect(text()).toContain('The account could not be added: It was changed elsewhere.');
      const form = addForm();
      expect(form.querySelector<HTMLInputElement>('[formControlName="password"]')!.value).toBe(
        's3cret-app-pass',
      );
      expect(form.querySelector<HTMLInputElement>('[formControlName="emailAddress"]')!.value).toBe(
        'john@mail.example.test',
      );
      expect(root.querySelectorAll('li.account').length).toBe(0);
    });
  });

  describe('add Google/Microsoft, more cases', () => {
    const MICROSOFT_CONFIGURED: OAuthProviderResponse[] = [
      { provider: 'GOOGLE', slug: 'google', configured: true },
      { provider: 'MICROSOFT', slug: 'microsoft', configured: true },
    ];

    it('needs an address when the connection did not share one', async () => {
      await setup([], [fakeConnection({ email: undefined, displayName: 'Jane at Google' })]);

      await chooseType('Google');
      expect(text()).toContain('Jane at Google');
      button('Add and test').click();
      await settle();

      http.expectNone({ method: 'POST', url: ACCOUNTS });
      expect(text()).toContain('Enter an email address.');

      type('[formControlName="emailAddress"]', ' jane@gmail.example ', addForm());
      button('Add and test').click();
      await settle();

      const create = http.expectOne({ method: 'POST', url: ACCOUNTS });
      expect(create.request.body).toMatchObject({
        type: 'GOOGLE',
        oauthConnectionId: 'o1',
        emailAddress: 'jane@gmail.example',
      });
      create.flush(fakeAccount({ id: 'a3', type: 'GOOGLE' }), {
        status: 201,
        statusText: 'Created',
      });
      await settle();
      http.expectOne(`${ACCOUNTS}/a3/test`).flush(TEST_OK);
      await settle();
      http.expectOne(`${ACCOUNTS}/a3/folders/sync`).flush({ items: [] });
      await settle();
    });

    it('prefers a typed address over the address of the connection', async () => {
      await setup([], [fakeConnection()]);

      await chooseType('Google');
      type('[formControlName="emailAddress"]', 'jane.alias@gmail.example', addForm());
      button('Add and test').click();
      await settle();

      expect(http.expectOne({ method: 'POST', url: ACCOUNTS }).request.body.emailAddress).toBe(
        'jane.alias@gmail.example',
      );
    });

    it('preselects a working connection and marks one that needs a reconnect', async () => {
      await setup(
        [],
        [
          fakeConnection({ id: 'o1', email: 'old@gmail.example', status: 'RECONNECT_REQUIRED' }),
          fakeConnection({ id: 'o2', email: 'jane@gmail.example' }),
        ],
      );

      await chooseType('Google');
      button('Add and test').click();
      await settle();

      expect(http.expectOne({ method: 'POST', url: ACCOUNTS }).request.body.oauthConnectionId).toBe(
        'o2',
      );
      root.querySelector<HTMLElement>('mat-select[formControlName="connectionId"]')!.click();
      await settle();
      const options = [...document.querySelectorAll('mat-option')].map((o) =>
        o.textContent?.trim(),
      );
      expect(options).toEqual([
        'old@gmail.example (reconnect it under Integrations)',
        'jane@gmail.example',
      ]);
    });

    it('asks for a connection when none of them works', async () => {
      await setup([], [fakeConnection({ status: 'RECONNECT_REQUIRED' })]);

      await chooseType('Google');
      button('Add and test').click();
      await settle();

      http.expectNone({ method: 'POST', url: ACCOUNTS });
      expect(text()).toContain('Choose the account to log in with.');
    });

    it('offers only the connections of the chosen provider and connects another one', async () => {
      await setup(
        [],
        [
          fakeConnection(),
          fakeConnection({ id: 'o2', provider: 'MICROSOFT', email: 'jane@outlook.example' }),
        ],
        MICROSOFT_CONFIGURED,
      );

      await chooseType('Microsoft');
      expect(text()).toContain('jane@outlook.example');
      expect(text()).not.toContain('jane@gmail.example');
      button('Connect another Microsoft account').click();

      expect(navigate).toHaveBeenCalledWith('/api/v1/oauth/microsoft/start?purpose=MAIL');
    });

    it('adds a Microsoft account with its signature and notification', async () => {
      await setup(
        [],
        [fakeConnection({ id: 'o2', provider: 'MICROSOFT', email: 'jane@outlook.example' })],
        MICROSOFT_CONFIGURED,
      );

      await chooseType('Microsoft');
      type('[formControlName="signatureMd"]', '**Jane**', addForm());
      addForm().querySelector<HTMLButtonElement>('mat-slide-toggle button')!.click();
      await settle();
      button('Add and test').click();
      await settle();

      const create = http.expectOne({ method: 'POST', url: ACCOUNTS });
      expect(create.request.body).toEqual({
        type: 'MICROSOFT',
        oauthConnectionId: 'o2',
        emailAddress: 'jane@outlook.example',
        displayName: undefined,
        signatureMd: '**Jane**',
        notifyNewMail: false,
      });
      create.flush(fakeAccount({ id: 'a4', type: 'MICROSOFT' }), {
        status: 201,
        statusText: 'Created',
      });
      await settle();
      http.expectOne(`${ACCOUNTS}/a4/test`).flush(TEST_OK);
      await settle();
      http.expectOne(`${ACCOUNTS}/a4/folders/sync`).flush({ items: [] });
      await settle();

      // The form starts over with the working connection preselected.
      expect(
        addForm().querySelector<HTMLTextAreaElement>('[formControlName="signatureMd"]')!.value,
      ).toBe('');
      expect(text()).toContain('added and connected.');
      expect(root.querySelector('li.account')?.textContent).toContain('Microsoft');
    });

    it('clears an add result when another kind is chosen', async () => {
      await setup([], [fakeConnection()]);
      fillImap();

      button('Add and test').click();
      await settle();
      const { body, ...options } = problem(500, 'Server error');
      http.expectOne({ method: 'POST', url: ACCOUNTS }).flush(body, options);
      await settle();
      expect(text()).toContain('The account could not be added');

      await chooseType('Google');

      expect(text()).not.toContain('The account could not be added');
    });
  });
});
