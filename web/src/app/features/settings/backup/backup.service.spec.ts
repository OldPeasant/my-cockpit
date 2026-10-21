import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { BackupJobResponse } from '../../../api/generated';
import { NotificationService, provideHttp, SILENT_ERRORS } from '../../../core/http';
import { BackupService } from './backup.service';

const ID = '0192f0c4-0000-7000-8000-000000000001';

describe('BackupService', () => {
  let service: BackupService;
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
    service = TestBed.inject(BackupService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('starts a backup with the passphrase, silently, and remembers it', async () => {
    const started = service.start('correct horse battery');
    const req = http.expectOne({ method: 'POST', url: '/api/v1/backup' });
    expect(req.request.body).toEqual({ passphrase: 'correct horse battery' });
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush(job());

    expect((await started).id).toBe(ID);
    expect(service.lastJobId).toBe(ID);
  });

  it('sends no passphrase when it is empty', async () => {
    const started = service.start('');
    const req = http.expectOne({ method: 'POST', url: '/api/v1/backup' });
    expect(req.request.body).toEqual({});
    req.flush(job());
    await started;
  });

  it('downloads a completed backup under its file name', async () => {
    const createObjectURL = vi.fn(() => 'blob:zip');
    URL.createObjectURL = createObjectURL;
    URL.revokeObjectURL = vi.fn();
    const click = vi
      .spyOn(HTMLAnchorElement.prototype, 'click')
      .mockImplementation(() => undefined);

    const done = service.download(job({ state: 'COMPLETED', fileName: 'cockpit-backup.zip' }));
    http
      .expectOne({ method: 'GET', url: `/api/v1/backup/${ID}/download` })
      .flush(new Blob(['zip']));
    await done;

    expect(createObjectURL).toHaveBeenCalled();
    const link = click.mock.contexts[0] as HTMLAnchorElement;
    expect(link.download).toBe('cockpit-backup.zip');
    click.mockRestore();
  });

  it('does not remember a backup that failed to start', async () => {
    const started = service.start('');
    http
      .expectOne('/api/v1/backup')
      .flush({ status: 409, title: 'Conflict' }, { status: 409, statusText: 'Conflict' });
    await expect(started).rejects.toMatchObject({ status: 409 });
    expect(service.lastJobId).toBeNull();
  });

  it('fetches the state of a backup silently', async () => {
    const state = service.job(ID);
    const req = http.expectOne({ method: 'GET', url: `/api/v1/backup/${ID}` });
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush(job({ stepsDone: 2 }));
    expect((await state).stepsDone).toBe(2);
  });

  describe('download file handling', () => {
    const originalCreate = URL.createObjectURL;
    const originalRevoke = URL.revokeObjectURL;

    afterEach(() => {
      URL.createObjectURL = originalCreate;
      URL.revokeObjectURL = originalRevoke;
      vi.useRealTimers();
      vi.restoreAllMocks();
    });

    it('falls back to a default file name and revokes the object URL later', async () => {
      vi.useFakeTimers();
      URL.createObjectURL = vi.fn(() => 'blob:zip');
      const revoke = vi.fn();
      URL.revokeObjectURL = revoke;
      const click = vi
        .spyOn(HTMLAnchorElement.prototype, 'click')
        .mockImplementation(() => undefined);

      const done = service.download(job({ state: 'COMPLETED' }));
      const req = http.expectOne(`/api/v1/backup/${ID}/download`);
      expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
      req.flush(new Blob(['zip']));
      await done;

      const link = click.mock.contexts[0] as HTMLAnchorElement;
      expect(link.download).toBe('cockpit-backup.zip');
      expect(link.href).toBe('blob:zip');
      expect(revoke).not.toHaveBeenCalled();
      vi.advanceTimersByTime(1000);
      expect(revoke).toHaveBeenCalledWith('blob:zip');
    });

    it('saves nothing when the download fails', async () => {
      URL.createObjectURL = vi.fn(() => 'blob:zip');
      const done = service.download(job({ state: 'COMPLETED' }));
      http
        .expectOne(`/api/v1/backup/${ID}/download`)
        .flush(null, { status: 404, statusText: 'Not Found' });
      await expect(done).rejects.toMatchObject({ status: 404 });
      expect(URL.createObjectURL).not.toHaveBeenCalled();
    });
  });

  it('uploads the zip as application/zip', async () => {
    const file = new Blob(['zip'], { type: 'application/zip' });
    const report = service.upload(file);
    const req = http.expectOne({ method: 'POST', url: '/api/v1/restore' });
    expect(req.request.headers.get('Content-Type')).toBe('application/zip');
    expect(await (req.request.body as Blob).text()).toBe('zip');
    req.flush({ valid: true, id: ID, counts: {}, problems: [], warnings: [], hasSecrets: false });

    expect((await report).valid).toBe(true);
  });

  it.each(['application/x-zip-compressed', ''])(
    'uploads a zip the browser typed as "%s" as application/zip',
    async (type) => {
      const file = new File(['zip'], 'cockpit-backup.zip', { type });
      const report = service.upload(file);
      const req = http.expectOne({ method: 'POST', url: '/api/v1/restore' });
      expect(req.request.headers.get('Content-Type')).toBe('application/zip');
      expect((req.request.body as Blob).type).toBe('application/zip');
      expect(await (req.request.body as Blob).text()).toBe('zip');
      req.flush({ valid: true, id: ID, counts: {}, problems: [], warnings: [], hasSecrets: false });
      await report;
    },
  );

  it('confirms a restore with the passphrase only when given', async () => {
    const withSecret = service.confirm(ID, 'correct horse battery');
    const first = http.expectOne({ method: 'POST', url: `/api/v1/restore/${ID}/confirm` });
    expect(first.request.body).toEqual({ passphrase: 'correct horse battery' });
    expect(first.request.context.get(SILENT_ERRORS)).toBe(true);
    first.flush({ counts: {}, preRestoreBackup: 'pre-restore/x.zip' });
    await withSecret;

    const without = service.confirm(ID, '');
    const req = http.expectOne({ method: 'POST', url: `/api/v1/restore/${ID}/confirm` });
    expect(req.request.body).toEqual({});
    req.flush({ counts: {}, preRestoreBackup: 'pre-restore/y.zip' });
    await without;
  });
});

function job(overrides: Partial<BackupJobResponse> = {}): BackupJobResponse {
  return {
    id: ID,
    createdAt: '2026-10-20T15:30:00Z',
    state: 'RUNNING',
    step: 'gtd: data',
    stepsDone: 0,
    stepsTotal: 4,
    ...overrides,
  };
}
