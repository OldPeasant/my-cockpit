import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideHttp } from '../../../core/http';
import { MailComposeService, SUGGESTION_LIMIT } from './mail-compose.service';

const DRAFT = '0192f0c4-0000-7000-8000-00000000d001';
const MESSAGE = '0192f0c4-0000-7000-8000-00000000a001';

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

  it('asks for recent correspondents and drops entries without address', async () => {
    const result = service.suggestions('ann');
    await flush();
    const req = http.expectOne((r) => r.url === '/api/v1/mail/correspondents');
    expect(req.request.params.get('q')).toBe('ann');
    expect(req.request.params.get('limit')).toBe(String(SUGGESTION_LIMIT));
    req.flush({
      items: [
        { address: 'ann@example.test', name: 'Ann', lastAt: '2026-10-20T10:00:00Z' },
        { name: 'Nobody' },
        { address: 'anna@example.test', name: null },
      ],
    });
    expect(await result).toEqual([
      { address: 'ann@example.test', name: 'Ann' },
      { address: 'anna@example.test', name: '' },
    ]);
  });

  it('lists a drive folder (the root without folderId)', async () => {
    const root = service.files(null);
    await flush();
    const req = http.expectOne((r) => r.url === '/api/v1/drive');
    expect(req.request.params.has('folderId')).toBe(false);
    req.flush({ items: [{ type: 'FILE', file: { id: 'f1', name: 'a.pdf' } }] });
    expect(await root).toHaveLength(1);
  });
});
