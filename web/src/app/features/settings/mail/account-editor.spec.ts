import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MailAccountResponse } from '../../../api/generated';
import { NotificationService, provideHttp } from '../../../core/http';
import { BROWSER_NAVIGATE } from '../calendars/calendar-sources.service';
import { AccountEditor } from './account-editor';
import { fakeAccount } from './testing';

const ACCOUNT = '/api/v1/mail/accounts/a1';

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

const problem = (status: number, title: string, errors: unknown[] = []) => ({
  body: { status, title, errors },
  options: {
    status,
    statusText: title,
    headers: { 'Content-Type': 'application/problem+json' },
  },
});

describe('AccountEditor', () => {
  let fixture: ComponentFixture<AccountEditor>;
  let root: HTMLElement;
  let http: HttpTestingController;
  let saved: MailAccountResponse[];
  let cancelled: number;

  async function setup(account: MailAccountResponse = fakeAccount()): Promise<void> {
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: { error: vi.fn() } },
        { provide: BROWSER_NAVIGATE, useValue: vi.fn() },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AccountEditor);
    fixture.componentRef.setInput('account', account);
    saved = [];
    cancelled = 0;
    fixture.componentInstance.saved.subscribe((a) => saved.push(a));
    fixture.componentInstance.cancelled.subscribe(() => cancelled++);
    root = fixture.nativeElement as HTMLElement;
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

  function button(label: string): HTMLButtonElement {
    const found = [...root.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === label,
    );
    if (!found) throw new Error(`No button "${label}"`);
    return found;
  }

  function field(selector: string): HTMLInputElement {
    const found = root.querySelector<HTMLInputElement>(selector);
    if (!found) throw new Error(`No field ${selector}`);
    return found;
  }

  function type(selector: string, value: string): void {
    const input = field(selector);
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  async function save(): Promise<void> {
    button('Save changes').click();
    await settle();
  }

  it('shows the stored identity, login, servers and sync window, but not the password', async () => {
    await setup(fakeAccount({ signatureMd: '**Jane**', syncWindowMonths: 24 }));

    expect(field('[formControlName="displayName"]').value).toBe('Jane Doe');
    expect(field('[formControlName="emailAddress"]').value).toBe('jane@mail.example.test');
    expect(field('[formControlName="username"]').value).toBe('jane');
    expect(field('[formControlName="password"]').value).toBe('');
    expect(field('[formControlName="signatureMd"]').value).toBe('**Jane**');
    expect(field('[formControlName="syncWindowMonths"]').value).toBe('24');
    expect(field('app-server-fields[protocol="imap"] [formControlName="host"]').value).toBe(
      'imap.mail.example.test',
    );
    expect(field('app-server-fields[protocol="smtp"] [formControlName="port"]').value).toBe('587');
  });

  it('sends a new password only when one was typed, and the signature Markdown as typed', async () => {
    await setup();

    type('[formControlName="password"]', 'n3w-app-pass');
    type('[formControlName="signatureMd"]', '_Jane_\n[site](https://example.org)');
    await save();

    const patch = http.expectOne({ method: 'PATCH', url: ACCOUNT });
    expect(patch.request.headers.get('If-Match')).toBe('"0"');
    expect(patch.request.body).toMatchObject({
      password: 'n3w-app-pass',
      signatureMd: '_Jane_\n[site](https://example.org)',
    });
    const updated = fakeAccount({ version: 1 });
    patch.flush(updated);
    await settle();

    expect(saved).toEqual([updated]);
  });

  it('trims the identity and login, and uses the address as name when the name is blank', async () => {
    await setup();

    type('[formControlName="displayName"]', '   ');
    type('[formControlName="emailAddress"]', '  jane.d@mail.example.test ');
    type('[formControlName="username"]', ' jane.d ');
    type('[formControlName="signatureMd"]', '   ');
    await save();

    expect(http.expectOne({ method: 'PATCH', url: ACCOUNT }).request.body).toMatchObject({
      displayName: 'jane.d@mail.example.test',
      emailAddress: 'jane.d@mail.example.test',
      username: 'jane.d',
      signatureMd: '',
    });
  });

  it.each([
    ['', 'Enter an email address.'],
    ['not-an-address', 'Enter an email address.'],
    ['two@words @example.test', 'Enter an email address.'],
  ])('refuses the address %j without a request', async (address, message) => {
    await setup();

    type('[formControlName="emailAddress"]', address);
    await save();

    http.expectNone({ method: 'PATCH', url: ACCOUNT });
    expect(text()).toContain(message);
    expect(saved).toEqual([]);
  });

  it('needs a user name for an IMAP/SMTP account', async () => {
    await setup();

    type('[formControlName="username"]', '');
    await save();

    http.expectNone({ method: 'PATCH', url: ACCOUNT });
    expect(text()).toContain('Enter the user name.');
  });

  it('needs the server host and a port from 1 to 65535', async () => {
    await setup();

    type('app-server-fields[protocol="imap"] [formControlName="host"]', '');
    type('app-server-fields[protocol="smtp"] [formControlName="port"]', '65536');
    await save();

    http.expectNone({ method: 'PATCH', url: ACCOUNT });
    expect(text()).toContain('Enter the server name.');
    expect(text()).toContain('1 to 65535.');
  });

  it.each(['0', '601', ''])('refuses %j months of mail kept offline', async (months) => {
    await setup();

    type('[formControlName="syncWindowMonths"]', months);
    await save();

    http.expectNone({ method: 'PATCH', url: ACCOUNT });
    expect(text()).toContain('1 to 600 months.');
  });

  it.each([
    ['1', 1],
    ['600', 600],
  ])('accepts %s months of mail kept offline', async (months, expected) => {
    await setup();

    type('[formControlName="syncWindowMonths"]', months);
    await save();

    expect(http.expectOne({ method: 'PATCH', url: ACCOUNT }).request.body.syncWindowMonths).toBe(
      expected,
    );
  });

  it('does not check login or servers of a Google account', async () => {
    await setup(
      fakeAccount({
        type: 'GOOGLE',
        username: undefined,
        imap: undefined,
        smtp: undefined,
        hasPassword: false,
        oauthConnectionId: 'o1',
      }),
    );

    expect(root.querySelector('[formControlName="username"]')).toBeNull();
    await save();

    expect(http.expectOne({ method: 'PATCH', url: ACCOUNT }).request.body).toEqual({
      displayName: 'Jane Doe',
      emailAddress: 'jane@mail.example.test',
      signatureMd: '',
      syncWindowMonths: 12,
    });
  });

  it('keeps the form and shows why the save failed', async () => {
    await setup();

    type('[formControlName="displayName"]', 'Jane D.');
    await save();
    const { body, options } = problem(400, 'Bad Request', [
      { field: 'imap.host', message: 'must not be blank' },
    ]);
    http.expectOne({ method: 'PATCH', url: ACCOUNT }).flush(body, options);
    await settle();

    expect(root.querySelector('[role="alert"]')?.textContent).toContain(
      'The account could not be saved: imap.host: must not be blank',
    );
    expect(field('[formControlName="displayName"]').value).toBe('Jane D.');
    expect(button('Save changes').disabled).toBe(false);
    expect(saved).toEqual([]);
  });

  it('explains a save that lost against a change made elsewhere', async () => {
    await setup();

    await save();
    const { body, options } = problem(412, 'Precondition Failed');
    http.expectOne({ method: 'PATCH', url: ACCOUNT }).flush(body, options);
    await settle();

    expect(text()).toContain('The account could not be saved: It was changed elsewhere.');
  });

  it('saves only once while a save is on its way', async () => {
    await setup();

    button('Save changes').click();
    button('Save changes').click();
    await settle();
    expect(button('Save changes').disabled).toBe(true);

    http.expectOne({ method: 'PATCH', url: ACCOUNT }).flush(fakeAccount({ version: 1 }));
    await settle();
    expect(saved.length).toBe(1);
  });

  it('cancels without a request', async () => {
    await setup();

    type('[formControlName="displayName"]', 'Changed');
    button('Cancel').click();
    await settle();

    http.expectNone({ method: 'PATCH', url: ACCOUNT });
    expect(cancelled).toBe(1);
  });
});
