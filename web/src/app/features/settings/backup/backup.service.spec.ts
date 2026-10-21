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

  it('uploads the zip as application/zip', async () => {
    const file = new Blob(['zip'], { type: 'application/zip' });
    const report = service.upload(file);
    const req = http.expectOne({ method: 'POST', url: '/api/v1/restore' });
    expect(req.request.body).toBe(file);
    expect(req.request.headers.get('Content-Type')).toBe('application/zip');
    req.flush({ valid: true, id: ID, counts: {}, problems: [], warnings: [], hasSecrets: false });

    expect((await report).valid).toBe(true);
  });

  it('confirms a restore with the passphrase only when given', async () => {
    const withSecret = service.confirm(ID, 'correct horse battery');
    http
      .expectOne({ method: 'POST', url: `/api/v1/restore/${ID}/confirm` })
      .flush({ counts: {}, preRestoreBackup: 'pre-restore/x.zip' });
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
