import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NotificationService, provideHttp, SILENT_ERRORS } from '../../../core/http';
import { attachmentUrl, MailReadService } from './mail-read.service';

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));
const ID = '0192f0c4-0000-7000-8000-00000000a001';

describe('MailReadService', () => {
  let service: MailReadService;
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
    service = TestBed.inject(MailReadService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function request(url: string) {
    await tick();
    return http.expectOne((req) => req.url === url);
  }

  it.each([
    [false, 'BLOCK'],
    [true, 'PROXY'],
  ])('loads a message with loadImages=%s as remoteImages=%s, silently', async (load, policy) => {
    const result = service.get(ID, load);
    const req = await request(`/api/v1/mail/messages/${ID}`);
    expect(req.request.params.get('remoteImages')).toBe(policy);
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush({ id: ID });
    expect((await result).id).toBe(ID);
  });

  it('marks a message read', async () => {
    const result = service.markRead(ID);
    const req = await request('/api/v1/mail/messages/actions');
    expect(req.request.body).toEqual({ action: 'READ', ids: [ID] });
    req.flush({ results: [{ id: ID, ok: true }] });
    await result;
  });

  it.each([
    ['task', 'to-task'],
    ['waiting', 'to-waiting'],
    ['note', 'to-note'],
  ] as const)('runs the GTD action %s', async (action, path) => {
    const result = service.gtd(ID, action);
    const req = await request(`/api/v1/mail/messages/${ID}/${path}`);
    expect(req.request.method).toBe('POST');
    req.flush(
      { id: 'n', linkId: 'l', messageId: ID, title: 'T', type: 'TASK' },
      { status: 201, statusText: 'Created' },
    );
    expect((await result).title).toBe('T');
  });

  it('copies an attachment into Files under its name', async () => {
    const result = service.saveToFiles(ID, '2', 'report.pdf');
    const download = await request(`/api/v1/mail/messages/${ID}/attachments/2`);
    expect(download.request.responseType).toBe('blob');
    download.flush(new Blob(['pdf']));
    const upload = await request('/api/v1/files');
    expect(upload.request.params.get('name')).toBe('report.pdf');
    expect(upload.request.params.has('folderId')).toBe(false);
    expect(upload.request.body instanceof FormData).toBe(true);
    expect((upload.request.body as FormData).get('file')).toBeInstanceOf(Blob);
    upload.flush({ id: 'file-1', name: 'report.pdf' }, { status: 201, statusText: 'Created' });
    expect((await result).name).toBe('report.pdf');
  });

  it('builds attachment URLs', () => {
    expect(attachmentUrl(ID, '1.2')).toBe(`/api/v1/mail/messages/${ID}/attachments/1.2`);
    expect(attachmentUrl(ID, '3', true)).toBe(
      `/api/v1/mail/messages/${ID}/attachments/3?inline=true`,
    );
  });
});
