import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NotificationService, provideHttp, SILENT_ERRORS } from '../../core/http';
import { CONTACTS_PAGE_SIZE, ContactsService, syncsContacts } from './contacts.service';
import { connection, contact, noRelated } from './testing';

const LIST = '/api/v1/contacts';
const ID = '0192f0c4-0000-7000-8000-000000000001';

describe('ContactsService', () => {
  let service: ContactsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: { error: vi.fn(), info: vi.fn() } },
      ],
    });
    service = TestBed.inject(ContactsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists a page of all contacts without a search or cursor, without toasting errors', async () => {
    const page = service.list('   ');
    const req = http.expectOne((r) => r.method === 'GET' && r.url === LIST);
    expect(req.request.params.keys()).toEqual(['limit']);
    expect(req.request.params.get('limit')).toBe(String(CONTACTS_PAGE_SIZE));
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush({ items: [contact(ID)] });
    await expect(page).resolves.toEqual({ items: [contact(ID)] });
  });

  it('searches with the trimmed query from a cursor', async () => {
    const page = service.list('  ada ', 'c1');
    const req = http.expectOne((r) => r.method === 'GET' && r.url === LIST);
    expect(req.request.params.keys().sort()).toEqual(['cursor', 'limit', 'q']);
    expect(req.request.params.get('q')).toBe('ada');
    expect(req.request.params.get('cursor')).toBe('c1');
    req.flush({ items: [], nextCursor: 'c2' });
    await expect(page).resolves.toEqual({ items: [], nextCursor: 'c2' });
  });

  it('loads one contact and its related items silently', async () => {
    const one = service.get(ID);
    const get = http.expectOne((r) => r.method === 'GET' && r.url === `${LIST}/${ID}`);
    expect(get.request.context.get(SILENT_ERRORS)).toBe(true);
    get.flush(contact(ID));
    await expect(one).resolves.toEqual(contact(ID));

    const related = service.related(ID);
    const rel = http.expectOne((r) => r.method === 'GET' && r.url === `${LIST}/${ID}/related`);
    expect(rel.request.context.get(SILENT_ERRORS)).toBe(true);
    rel.flush(noRelated(ID));
    await expect(related).resolves.toEqual(noRelated(ID));
  });

  it('creates a contact with the request as body', async () => {
    const body = { givenName: 'Grace', emails: [{ address: 'grace@example.org' }] };
    const created = service.create(body);
    const req = http.expectOne((r) => r.method === 'POST' && r.url === LIST);
    expect(req.request.body).toEqual(body);
    expect(req.request.headers.has('If-Match')).toBe(false);
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush(contact(ID, { displayName: 'Grace' }), { status: 201, statusText: 'Created' });
    await expect(created).resolves.toMatchObject({ id: ID, displayName: 'Grace' });
  });

  it('updates and moves to the Trash with the version as If-Match', async () => {
    const updated = service.update(contact(ID, { version: 7 }), { title: 'Engineer' });
    const patch = http.expectOne((r) => r.method === 'PATCH' && r.url === `${LIST}/${ID}`);
    expect(patch.request.headers.get('If-Match')).toBe('"7"');
    expect(patch.request.body).toEqual({ title: 'Engineer' });
    expect(patch.request.context.get(SILENT_ERRORS)).toBe(true);
    patch.flush(contact(ID, { title: 'Engineer', version: 8 }));
    await expect(updated).resolves.toMatchObject({ version: 8 });

    const removed = service.remove(contact(ID, { version: 8 }));
    const del = http.expectOne((r) => r.method === 'DELETE' && r.url === `${LIST}/${ID}`);
    expect(del.request.headers.get('If-Match')).toBe('"8"');
    expect(del.request.context.get(SILENT_ERRORS)).toBe(true);
    del.flush(null, { status: 204, statusText: 'No Content' });
    await removed;
  });

  it('imports a vCard file as multipart form data', async () => {
    const file = new File(['BEGIN:VCARD\r\nEND:VCARD\r\n'], 'friends.vcf', { type: 'text/vcard' });
    const result = service.import(file);
    const req = http.expectOne((r) => r.method === 'POST' && r.url === `${LIST}/import`);
    expect(req.request.body).toBeInstanceOf(FormData);
    expect((req.request.body as FormData).get('file')).toBeInstanceOf(Blob);
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush({ imported: [contact(ID)], skipped: [] });
    await expect(result).resolves.toEqual({ imported: [contact(ID)], skipped: [] });
  });

  it('builds the export URL of all contacts, or of the given ids, encoded', () => {
    expect(service.exportUrl()).toBe(`${LIST}/export`);
    expect(service.exportUrl([])).toBe(`${LIST}/export`);
    expect(service.exportUrl([ID])).toBe(`${LIST}/export?ids=${ID}`);
    expect(service.exportUrl(['a b', 'c&d', 'e/f'])).toBe(
      `${LIST}/export?ids=a%20b&ids=c%26d&ids=e%2Ff`,
    );
  });

  it('lists only the connected accounts that were granted contacts', async () => {
    const google = connection('GOOGLE');
    const microsoft = connection('MICROSOFT', { status: 'RECONNECT_REQUIRED' });
    const mailOnly = connection('GOOGLE', {
      id: 'mail-only',
      scopes: ['openid', 'https://mail.google.com/'],
    });
    const accounts = service.syncAccounts();
    const req = http.expectOne((r) => r.method === 'GET' && r.url === '/api/v1/oauth/connections');
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush([google, mailOnly, microsoft]);
    await expect(accounts).resolves.toEqual([google, microsoft]);
  });

  it('rejects when the accounts cannot be loaded', async () => {
    const accounts = service.syncAccounts();
    http
      .expectOne('/api/v1/oauth/connections')
      .flush(null, { status: 503, statusText: 'Service Unavailable' });
    await expect(accounts).rejects.toBeTruthy();
  });
});

describe('syncsContacts', () => {
  it('matches a contacts scope of either provider, case-insensitively', () => {
    expect(syncsContacts(connection('GOOGLE'))).toBe(true);
    expect(syncsContacts(connection('MICROSOFT'))).toBe(true);
    expect(
      syncsContacts(
        connection('GOOGLE', { scopes: ['https://www.googleapis.com/auth/contacts.readonly'] }),
      ),
    ).toBe(true);
  });

  it('does not match an account without a contacts scope', () => {
    expect(syncsContacts(connection('GOOGLE', { scopes: [] }))).toBe(false);
    expect(
      syncsContacts(connection('MICROSOFT', { scopes: ['Calendars.ReadWrite', 'Mail.ReadWrite'] })),
    ).toBe(false);
  });
});
