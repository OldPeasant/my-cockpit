import { HttpEventType } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FileResponse } from '../../../api/generated';
import { NotificationService, provideHttp } from '../../../core/http';
import { formatBytes } from './format-bytes';
import { UploadPanel } from './upload-panel';
import { UploadJob, UploadService } from './upload.service';

const FOLDER = '0192f0c4-0000-7000-8000-00000000000d';

function stored(name: string): FileResponse {
  return {
    id: '0192f0c4-0000-7000-8000-0000000000f1',
    name,
    folderId: FOLDER,
    mime: 'text/plain',
    size: 2048,
    sha256: 'ab'.repeat(32),
    version: 0,
    createdAt: '2026-10-21T08:00:00Z',
    updatedAt: '2026-10-21T08:00:00Z',
  };
}

describe('UploadPanel', () => {
  let fixture: ComponentFixture<UploadPanel>;
  let http: HttpTestingController;
  let uploads: UploadService;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        { provide: NotificationService, useValue: { error: vi.fn(), info: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    uploads = TestBed.inject(UploadService);
    fixture = TestBed.createComponent(UploadPanel);
    await render();
  });

  afterEach(() => http.verify());

  const el = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const heading = (): string => el().querySelector('h2')?.textContent?.trim() ?? '';
  const row = (name: string): HTMLElement =>
    [...el().querySelectorAll<HTMLElement>('li.job')].find(
      (li) => li.querySelector('.name')?.textContent === name,
    )!;
  const byLabel = (label: string): HTMLButtonElement =>
    el().querySelector<HTMLButtonElement>(`button[aria-label="${label}"]`)!;

  async function render(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  function request(name: string): Promise<TestRequest> {
    return vi.waitFor(() =>
      http.expectOne((r) => r.url === '/api/v1/files' && r.params.get('name') === name),
    );
  }

  async function until(job: UploadJob, state: string): Promise<void> {
    await vi.waitFor(() => expect(job.state()).toBe(state));
    await render();
  }

  it('shows nothing while there are no uploads', () => {
    expect(el().querySelector('section')).toBeNull();
  });

  it('shows each upload with its progress, and the count of running ones', async () => {
    const [a, b, c] = uploads.upload(
      [new File(['x'.repeat(2048)], 'a.txt'), new File(['y'], 'b.txt'), new File(['z'], 'c.txt')],
      FOLDER,
    );
    await render();
    expect(heading()).toBe('Uploading 3 files');
    // Only two run at a time; the third waits.
    expect(row('c.txt').getAttribute('data-state')).toBe('queued');
    expect(row('c.txt').querySelector('.status')?.textContent?.trim()).toBe('Waiting');

    const reqA = await request('a.txt');
    reqA.event({ type: HttpEventType.UploadProgress, loaded: 1024, total: 2048 });
    await render();
    expect(row('a.txt').querySelector('.status')?.textContent?.trim()).toBe(
      `${formatBytes(1024)} / ${formatBytes(2048)}`,
    );
    // The byte counter stays out of the live region: only state changes are announced.
    const live = (name: string) =>
      row(name).querySelector('.status [role="status"]')?.textContent?.trim();
    expect(row('a.txt').querySelector('.status')?.getAttribute('role')).toBeNull();
    expect(live('a.txt')).toBe('');
    expect(live('c.txt')).toBe('Waiting');
    const bar = row('a.txt').querySelector('[role="progressbar"]')!;
    expect(bar.getAttribute('aria-valuenow')).toBe('50');
    expect(bar.getAttribute('aria-label')).toBe('Upload of a.txt');

    reqA.flush(stored('a.txt'));
    await until(a, 'done');
    expect(heading()).toBe('Uploading 2 files');
    expect(row('a.txt').querySelector('.status')?.textContent?.trim()).toBe('Uploaded');
    expect(live('a.txt')).toBe('Uploaded');

    (await request('b.txt')).flush(stored('b.txt'));
    await until(b, 'done');
    expect(heading()).toBe('Uploading 1 file');
    (await request('c.txt')).flush(stored('c.txt'));
    await until(c, 'done');
    expect(heading()).toBe('Uploads finished');
  });

  it('a failed upload shows the error and Retry; Cancel discards it', async () => {
    const [job] = uploads.upload([new File(['x'], 'scan.pdf')], FOLDER);
    (await request('scan.pdf')).flush(
      { status: 507, title: 'Insufficient Storage', detail: 'The drive is full.' },
      { status: 507, statusText: 'Insufficient Storage' },
    );
    await until(job, 'failed');
    expect(row('scan.pdf').querySelector('.status')?.textContent?.trim()).toBe(
      'Failed: The drive is full.',
    );

    byLabel('Retry upload of scan.pdf').click();
    await render();
    expect(row('scan.pdf').getAttribute('data-state')).toBe('uploading');
    (await request('scan.pdf')).flush(
      { status: 507, title: 'Insufficient Storage' },
      { status: 507, statusText: 'Insufficient Storage' },
    );
    await until(job, 'failed');

    byLabel('Cancel upload of scan.pdf').click();
    await render();
    expect(row('scan.pdf').querySelector('.status')?.textContent?.trim()).toBe('Cancelled');
    expect(byLabel('Retry upload of scan.pdf')).toBeNull();
  });

  it('Cancel stops a running upload; Remove and Clear finished empty the list', async () => {
    const [running, done] = uploads.upload(
      [new File(['x'], 'big.iso'), new File(['y'], 'notes.txt')],
      FOLDER,
    );
    const req = await request('big.iso');
    (await request('notes.txt')).flush(stored('notes.txt'));
    await until(done, 'done');
    // A finished upload offers Remove instead of Cancel.
    expect(byLabel('Cancel upload of notes.txt')).toBeNull();

    byLabel('Cancel upload of big.iso').click();
    await render();
    expect(req.cancelled).toBe(true);
    expect(running.state()).toBe('cancelled');

    byLabel('Remove notes.txt from the list').click();
    await render();
    expect(row('notes.txt')).toBeUndefined();

    [...el().querySelectorAll<HTMLButtonElement>('button')]
      .find((b) => b.textContent?.trim() === 'Clear finished')!
      .click();
    await render();
    expect(el().querySelector('section')).toBeNull();
  });
});
