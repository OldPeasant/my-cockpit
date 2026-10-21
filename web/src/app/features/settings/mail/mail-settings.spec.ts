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
});
