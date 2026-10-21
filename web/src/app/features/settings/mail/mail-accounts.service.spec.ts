import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ApiError, NotificationService, provideHttp } from '../../../core/http';
import { BROWSER_NAVIGATE } from '../calendars/calendar-sources.service';
import { MailAccountsService } from './mail-accounts.service';
import { fakeAccount, fakeFolder, TEST_SMTP_FAILED } from './testing';

const ACCOUNTS = '/api/v1/mail/accounts';

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

describe('MailAccountsService', () => {
  let service: MailAccountsService;
  let http: HttpTestingController;
  let notify: ReturnType<typeof vi.fn>;
  let navigate: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    notify = vi.fn();
    navigate = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: { error: notify } },
        { provide: BROWSER_NAVIGATE, useValue: navigate },
      ],
    });
    service = TestBed.inject(MailAccountsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists the accounts from their item envelope', async () => {
    const accounts = service.accounts();
    await tick();
    http.expectOne({ method: 'GET', url: ACCOUNTS }).flush({ items: [fakeAccount()] });

    expect((await accounts).map((a) => a.emailAddress)).toEqual(['jane@mail.example.test']);
  });

  it('adds an account with the given body', async () => {
    const created = service.create({
      type: 'GOOGLE',
      emailAddress: 'jane@gmail.example',
      oauthConnectionId: 'o1',
    });
    await tick();
    const req = http.expectOne({ method: 'POST', url: ACCOUNTS });
    expect(req.request.body).toEqual({
      type: 'GOOGLE',
      emailAddress: 'jane@gmail.example',
      oauthConnectionId: 'o1',
    });
    req.flush(fakeAccount({ id: 'a2', type: 'GOOGLE' }), { status: 201, statusText: 'Created' });

    expect((await created).id).toBe('a2');
  });

  it('changes and removes an account at the version it was read with', async () => {
    const account = fakeAccount({ version: 4 });
    const saved = service.update(account, { notifyNewMail: false });
    await tick();
    const patch = http.expectOne({ method: 'PATCH', url: `${ACCOUNTS}/a1` });
    expect(patch.request.headers.get('If-Match')).toBe('"4"');
    expect(patch.request.body).toEqual({ notifyNewMail: false });
    patch.flush(fakeAccount({ notifyNewMail: false, version: 5 }));
    expect((await saved).version).toBe(5);

    const removed = service.remove(account);
    await tick();
    const del = http.expectOne({ method: 'DELETE', url: `${ACCOUNTS}/a1` });
    expect(del.request.headers.get('If-Match')).toBe('"4"');
    del.flush(null, { status: 204, statusText: 'No Content' });
    await removed;
  });

  it('tests the connection and returns the result per protocol', async () => {
    const result = service.test('a1');
    await tick();
    http.expectOne({ method: 'POST', url: `${ACCOUNTS}/a1/test` }).flush(TEST_SMTP_FAILED);

    expect(await result).toEqual(TEST_SMTP_FAILED);
  });

  it('lists and detects folders', async () => {
    const listed = service.folders('a1');
    await tick();
    http.expectOne({ method: 'GET', url: `${ACCOUNTS}/a1/folders` }).flush({ items: [] });
    expect(await listed).toEqual([]);

    const synced = service.syncFolders('a1');
    await tick();
    http
      .expectOne({ method: 'POST', url: `${ACCOUNTS}/a1/folders/sync` })
      .flush({ items: [fakeFolder()] });
    expect((await synced).map((f) => f.role)).toEqual(['INBOX']);
  });

  it.each([
    ['AUTOMATIC', { automatic: true }],
    ['NONE', {}],
    ['ARCHIVE', { role: 'ARCHIVE' }],
  ] as const)('sets the folder role %s as %j', async (choice, body) => {
    const folder = fakeFolder({ id: 'f9', version: 2 });
    const saved = service.setRole(folder, choice);
    await tick();
    const req = http.expectOne({ method: 'PATCH', url: `${ACCOUNTS}/a1/folders/f9` });
    expect(req.request.headers.get('If-Match')).toBe('"2"');
    expect(req.request.body).toEqual(body);
    req.flush(fakeFolder({ id: 'f9', version: 3 }));
    expect((await saved).version).toBe(3);
  });

  it('rejects with the API error and does not toast it', async () => {
    const created = service.create({ type: 'IMAP_SMTP', emailAddress: 'x' });
    const failure = expect(created).rejects.toBeInstanceOf(ApiError);
    await tick();
    http.expectOne({ method: 'POST', url: ACCOUNTS }).flush(
      {
        status: 400,
        title: 'Bad Request',
        errors: [{ field: 'emailAddress', message: 'invalid' }],
      },
      {
        status: 400,
        statusText: 'Bad Request',
        headers: { 'Content-Type': 'application/problem+json' },
      },
    );

    await failure;
    expect(notify).not.toHaveBeenCalled();
  });

  it('leaves for the consent screen with purpose MAIL', () => {
    service.connect('google');
    service.connect('microsoft');

    expect(navigate.mock.calls).toEqual([
      ['/api/v1/oauth/google/start?purpose=MAIL'],
      ['/api/v1/oauth/microsoft/start?purpose=MAIL'],
    ]);
  });
});
