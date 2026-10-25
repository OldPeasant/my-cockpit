import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideHttp } from '../../../core/http';
import { SILENT_ERRORS } from '../../../core/http/interceptors';
import { FILES_LIMIT, MailComposeService, SUGGESTION_LIMIT } from './mail-compose.service';

const DRAFT = '0192f0c4-0000-7000-8000-00000000d001';
const MESSAGE = '0192f0c4-0000-7000-8000-00000000a001';
const CONTACT = '0192f0c4-0000-7000-8000-00000000c001';
const OTHER = '0192f0c4-0000-7000-8000-00000000c002';

const draft = { id: DRAFT, accountId: 'acc-1', subject: 'Hi', attachments: [] };

describe('MailComposeService', () => {
  let service: MailComposeService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttp(), provideHttpClientTesting()] });
    service = TestBed.inject(MailComposeService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const flush = async () => {
    for (let i = 0; i < 5; i++) await Promise.resolve();
  };

  it('lists the mail accounts to send from', async () => {
    const result = service.accounts();
    await flush();
    const req = http.expectOne('/api/v1/mail/accounts');
    expect(req.request.method).toBe('GET');
    const account = { id: 'acc-1', displayName: 'Jane Doe', emailAddress: 'jane@example.test' };
    req.flush({ items: [account] });
    expect(await result).toEqual([account]);
  });

  it('fails on a draft answer without id', async () => {
    const result = service.create({ accountId: 'acc-1' });
    await flush();
    http
      .expectOne('/api/v1/mail/drafts')
      .flush({ subject: 'Hi' }, { status: 201, statusText: 'Created', headers: { ETag: '"1"' } });
    await expect(result).rejects.toThrow('id');
  });

  it('creates a draft and keeps its ETag', async () => {
    const result = service.create({ accountId: 'acc-1', subject: 'Hi' });
    await flush();
    const req = http.expectOne('/api/v1/mail/drafts');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ accountId: 'acc-1', subject: 'Hi' });
    req.flush(draft, { status: 201, statusText: 'Created', headers: { ETag: '"7"' } });
    expect(await result).toEqual({ draft, etag: '"7"' });
  });

  it('saves with If-Match and returns the new ETag', async () => {
    const result = service.update(DRAFT, '"7"', { subject: 'Hi again' });
    await flush();
    const req = http.expectOne(`/api/v1/mail/drafts/${DRAFT}`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.headers.get('If-Match')).toBe('"7"');
    req.flush(draft, { headers: { ETag: '"8"' } });
    expect((await result).etag).toBe('"8"');
  });

  it('uploads an attachment as multipart with If-Match', async () => {
    const file = new File(['x'], 'agenda.txt');
    const result = service.upload(DRAFT, '"8"', file);
    await flush();
    const req = http.expectOne(`/api/v1/mail/drafts/${DRAFT}/attachments`);
    expect(req.request.headers.get('If-Match')).toBe('"8"');
    expect((req.request.body as FormData).get('file')).toBe(file);
    req.flush(draft, { headers: { ETag: '"9"' } });
    expect((await result).etag).toBe('"9"');
  });

  it('fails on a draft answer without ETag', async () => {
    const result = service.get(DRAFT);
    await flush();
    http.expectOne(`/api/v1/mail/drafts/${DRAFT}`).flush(draft);
    await expect(result).rejects.toThrow('ETag');
  });

  it('sends and discards with If-Match', async () => {
    const sent = service.send(DRAFT, '"9"');
    await flush();
    const send = http.expectOne(`/api/v1/mail/drafts/${DRAFT}/send`);
    expect(send.request.method).toBe('POST');
    expect(send.request.headers.get('If-Match')).toBe('"9"');
    send.flush({ sentMessageId: 's', appendedToSent: true, draftRemoved: true });
    expect((await sent).appendedToSent).toBe(true);

    const discarded = service.discard(DRAFT, '"9"');
    await flush();
    const del = http.expectOne(`/api/v1/mail/drafts/${DRAFT}`);
    expect(del.request.method).toBe('DELETE');
    expect(del.request.headers.get('If-Match')).toBe('"9"');
    del.flush(null, { status: 204, statusText: 'No Content' });
    await discarded;
  });

  it.each([
    ['reply', 'reply'],
    ['replyAll', 'reply-all'],
    ['forward', 'forward'],
  ] as const)('starts a %s draft', async (mode, path) => {
    const result = service.respond(MESSAGE, mode);
    await flush();
    const req = http.expectOne(`/api/v1/mail/messages/${MESSAGE}/${path}`);
    expect(req.request.method).toBe('POST');
    req.flush(draft, { status: 201, statusText: 'Created', headers: { ETag: '"1"' } });
    expect((await result).draft.id).toBe(DRAFT);
  });

  it('asks the contacts autocomplete and keeps its merged order, contacts first', async () => {
    const result = service.suggestions('ann');
    await flush();
    const req = http.expectOne((r) => r.url === '/api/v1/contacts/autocomplete');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('q')).toBe('ann');
    expect(req.request.params.get('limit')).toBe(String(SUGGESTION_LIMIT));
    req.flush([
      { contactId: CONTACT, email: 'ann@example.test', name: 'Ann Example' },
      { contactId: CONTACT, email: 'ann@work.example.test', name: 'Ann Example' },
      { email: 'anna@example.test', name: 'Anna' },
      { email: 'annika@example.test' },
    ]);
    expect(await result).toEqual([
      { address: 'ann@example.test', name: 'Ann Example', contactId: CONTACT },
      { address: 'ann@work.example.test', name: 'Ann Example', contactId: CONTACT },
      { address: 'anna@example.test', name: 'Anna' },
      { address: 'annika@example.test', name: '' },
    ]);
  });

  it('drops suggestions without address and keeps the first of a repeated address', async () => {
    const result = service.suggestions('ann');
    await flush();
    http
      .expectOne((r) => r.url === '/api/v1/contacts/autocomplete')
      .flush([
        { contactId: CONTACT, name: 'Ann without mail' },
        { contactId: CONTACT, email: '  ', name: 'Blank' },
        { contactId: CONTACT, email: 'ann@example.test', name: 'Ann Example' },
        { contactId: OTHER, email: 'ANN@example.test', name: 'Ann Twin' },
        { email: 'ann@example.test', name: 'ann (old)' },
      ]);
    expect(await result).toEqual([
      { address: 'ann@example.test', name: 'Ann Example', contactId: CONTACT },
    ]);
  });

  it('has no suggestions when the autocomplete answers nothing', async () => {
    const result = service.suggestions('zz');
    await flush();
    http.expectOne((r) => r.url === '/api/v1/contacts/autocomplete').flush([]);
    expect(await result).toEqual([]);
  });

  it('has no suggestions when the autocomplete answers an empty body', async () => {
    const result = service.suggestions('zz');
    await flush();
    http.expectOne((r) => r.url === '/api/v1/contacts/autocomplete').flush(null);
    expect(await result).toEqual([]);
  });

  it('trims suggested addresses, keeps a contact without name and dedupes after trimming', async () => {
    const result = service.suggestions('bo');
    await flush();
    http
      .expectOne((r) => r.url === '/api/v1/contacts/autocomplete')
      .flush([
        { contactId: CONTACT, email: '  bo@example.test ' },
        { email: 'Bo@Example.test', name: 'Bo (recent)' },
        { email: 'bob@example.test', name: null },
      ]);
    expect(await result).toEqual([
      { address: 'bo@example.test', name: '', contactId: CONTACT },
      { address: 'bob@example.test', name: '' },
    ]);
  });

  it('looks up suggestions silently and rejects when the autocomplete fails', async () => {
    const result = service.suggestions('ann');
    await flush();
    const req = http.expectOne((r) => r.url === '/api/v1/contacts/autocomplete');
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush(
      { status: 500, title: 'Internal Server Error' },
      { status: 500, statusText: 'Server Error' },
    );
    await expect(result).rejects.toBeTruthy();
  });

  it('no longer asks the mail correspondents endpoint for suggestions', async () => {
    const result = service.suggestions('ann');
    await flush();
    http.expectNone((r) => r.url === '/api/v1/mail/correspondents');
    http.expectOne((r) => r.url === '/api/v1/contacts/autocomplete').flush([]);
    await result;
  });

  it('lists a drive folder (the root without folderId)', async () => {
    const root = service.files(null);
    await flush();
    const req = http.expectOne((r) => r.url === '/api/v1/drive');
    expect(req.request.params.has('folderId')).toBe(false);
    expect(req.request.params.get('limit')).toBe(String(FILES_LIMIT));
    req.flush({ items: [{ type: 'FILE', file: { id: 'f1', name: 'a.pdf' } }] });
    const listing = await root;
    expect(listing.entries).toHaveLength(1);
    expect(listing.more).toBe(false);
  });

  it('says when a drive folder holds more entries than listed', async () => {
    const listing = service.files('folder-1');
    await flush();
    const req = http.expectOne((r) => r.url === '/api/v1/drive');
    expect(req.request.params.get('folderId')).toBe('folder-1');
    req.flush({ items: [], nextCursor: 'next-page' });
    expect((await listing).more).toBe(true);
  });
});
