import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { Subject } from 'rxjs';
import { BackupJobResponse, ChangeEvent, RestoreReportResponse } from '../../../api/generated';
import { AuthService } from '../../../core/auth';
import { ChangeEventsService } from '../../../core/events';
import { NotificationService, provideHttp } from '../../../core/http';
import BackupSettings from './backup-settings';
import { BackupService } from './backup.service';

const ID = '0192f0c4-0000-7000-8000-000000000001';
const UPLOAD = '0192f0c4-0000-7000-8000-0000000000aa';
const START = '/api/v1/backup';
const JOB = `/api/v1/backup/${ID}`;
const RESTORE = '/api/v1/restore';
const CONFIRM = `/api/v1/restore/${UPLOAD}/confirm`;

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

function job(overrides: Partial<BackupJobResponse> = {}): BackupJobResponse {
  return {
    id: ID,
    createdAt: '2026-10-20T15:30:00Z',
    state: 'RUNNING',
    step: 'gtd: data',
    stepsDone: 1,
    stepsTotal: 4,
    ...overrides,
  };
}

function report(overrides: Partial<RestoreReportResponse> = {}): RestoreReportResponse {
  return {
    id: UPLOAD,
    valid: true,
    formatVersion: 1,
    appVersion: '0.1.0',
    createdAt: '2026-10-20T15:30:00Z',
    counts: { tasks: 1234, notes: 7 },
    hasSecrets: false,
    problems: [],
    warnings: [],
    ...overrides,
  };
}

function changeEvent(id: string): ChangeEvent {
  return { type: 'BACKUP', id, op: 'UPDATED', at: '2026-10-20T15:30:01Z' };
}

describe('BackupSettings', () => {
  let fixture: ComponentFixture<BackupSettings>;
  let root: HTMLElement;
  let http: HttpTestingController;
  let changes: Subject<ChangeEvent>;
  let resyncs: Subject<void>;
  let auth: {
    loggedIn: ReturnType<typeof signal<boolean>>;
    checkSession: ReturnType<typeof vi.fn>;
    logout: ReturnType<typeof vi.fn>;
  };
  let notifications: { error: ReturnType<typeof vi.fn>; info: ReturnType<typeof vi.fn> };

  async function setup(lastJobId: string | null = null): Promise<void> {
    changes = new Subject();
    resyncs = new Subject();
    notifications = { error: vi.fn(), info: vi.fn() };
    const loggedIn = signal(true);
    auth = {
      loggedIn,
      checkSession: vi.fn(() => {
        loggedIn.set(false);
        return Promise.resolve(false);
      }),
      logout: vi.fn(() => Promise.resolve()),
    };
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: notifications },
        { provide: AuthService, useValue: auth },
        {
          provide: ChangeEventsService,
          useValue: {
            events$: (type: string) => {
              expect(type).toBe('BACKUP');
              return changes.asObservable();
            },
            resync$: resyncs.asObservable(),
          },
        },
      ],
    });
    TestBed.inject(BackupService).lastJobId = lastJobId;
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(BackupSettings);
    root = fixture.nativeElement as HTMLElement;
    await settle();
  }

  async function settle(): Promise<void> {
    await tick();
    await fixture.whenStable();
    await tick();
    fixture.detectChanges();
  }

  afterEach(() => http.verify());

  function button(text: string): HTMLButtonElement {
    return [...root.querySelectorAll('button')].find(
      (b) => b.textContent?.trim() === text,
    ) as HTMLButtonElement;
  }

  function type(testId: string, value: string): void {
    const input = root.querySelector(`[data-testid="${testId}"]`) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new Event('blur'));
  }

  function text(selector: string): string {
    return root.querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  }

  async function pick(): Promise<void> {
    const input = root.querySelector('[data-testid="restore-file"]') as HTMLInputElement;
    const file = new File(['zip'], 'cockpit-backup-2026-10-20T1530Z.zip', {
      type: 'application/zip',
    });
    Object.defineProperty(input, 'files', { value: [file], configurable: true });
    input.dispatchEvent(new Event('change'));
    await settle();
  }

  async function uploadWith(answer: RestoreReportResponse): Promise<void> {
    await pick();
    http.expectOne({ method: 'POST', url: RESTORE }).flush(answer);
    await settle();
  }

  describe('backup', () => {
    it('starts a backup and follows its progress from the SSE events', async () => {
      await setup();
      type('backup-passphrase', 'correct horse battery');
      button('Create backup').click();
      await settle();
      const start = http.expectOne({ method: 'POST', url: START });
      expect(start.request.body).toEqual({ passphrase: 'correct horse battery' });
      start.flush(job({ stepsDone: 0 }));
      await settle();
      // One catch-up fetch for events sent before the id was known.
      http.expectOne(JOB).flush(job({ stepsDone: 1 }));
      await settle();

      expect(text('.job [role="status"]')).toContain('1 of 4 steps (gtd: data)');
      expect(button('Create backup').disabled).toBe(true);

      changes.next(changeEvent('0192f0c4-0000-7000-8000-00000000ffff'));
      changes.next(changeEvent(ID));
      await settle();
      http.expectOne(JOB).flush(job({ stepsDone: 3, step: 'notes: data' }));
      await settle();
      expect(text('.job [role="status"]')).toContain('3 of 4 steps (notes: data)');

      changes.next(changeEvent(ID));
      await settle();
      http.expectOne(JOB).flush(
        job({
          state: 'COMPLETED',
          stepsDone: 4,
          fileName: 'cockpit-backup.zip',
          size: 2_097_152,
        }),
      );
      await settle();
      expect(text('.job .file')).toBe('cockpit-backup.zip');
      expect(text('.job .meta')).toBe('2 MB');
      expect(button('Download')).toBeTruthy();
      expect(button('Create backup').disabled).toBe(false);
    });

    it('refetches the job after an SSE reconnect', async () => {
      await setup(ID);
      http.expectOne(JOB).flush(job());
      await settle();

      resyncs.next();
      await settle();
      http.expectOne(JOB).flush(job({ state: 'FAILED' }));
      await settle();
      expect(text('.job [role="alert"]')).toContain('The backup failed');
    });

    it('does not start with a passphrase shorter than 12 characters', async () => {
      await setup();
      type('backup-passphrase', 'short');
      button('Create backup').click();
      await settle();

      http.expectNone(START);
      expect(text('mat-error')).toBe('Use at least 12 characters, or none.');
    });

    it('explains a backup that is already running', async () => {
      await setup();
      button('Create backup').click();
      await settle();
      http
        .expectOne(START)
        .flush({ status: 409, title: 'Conflict' }, { status: 409, statusText: 'Conflict' });
      await settle();
      expect(text('[role="alert"]')).toContain('A backup is already running');
      expect(notifications.error).not.toHaveBeenCalled();
    });

    it('downloads the completed backup', async () => {
      await setup(ID);
      http.expectOne(JOB).flush(job({ state: 'COMPLETED', fileName: 'cockpit-backup.zip' }));
      await settle();
      const download = vi
        .spyOn(TestBed.inject(BackupService), 'download')
        .mockResolvedValue(undefined);

      button('Download').click();
      await settle();
      expect(download).toHaveBeenCalledWith(expect.objectContaining({ id: ID }));
    });

    it('forgets a backup the server discarded', async () => {
      await setup(ID);
      http
        .expectOne(JOB)
        .flush({ status: 404, title: 'Not Found' }, { status: 404, statusText: 'Not Found' });
      await settle();
      expect(root.querySelector('.job')).toBeNull();
      expect(TestBed.inject(BackupService).lastJobId).toBeNull();

      // Later events for the forgotten job fetch nothing.
      changes.next(changeEvent(ID));
      resyncs.next();
      await settle();
      http.expectNone(JOB);
    });

    it('starts without a passphrase, clears the field and skips the catch-up of a finished job', async () => {
      await setup();
      type('backup-passphrase', 'correct horse battery');
      type('backup-passphrase', '');
      button('Create backup').click();
      await settle();
      const start = http.expectOne({ method: 'POST', url: START });
      expect(start.request.body).toEqual({});
      start.flush(job({ state: 'COMPLETED', stepsDone: 4, fileName: 'cockpit-backup.zip' }));
      await settle();

      http.expectNone(JOB);
      expect(button('Download')).toBeTruthy();
      expect(TestBed.inject(BackupService).lastJobId).toBe(ID);
    });

    it('resets the passphrase field after a backup started', async () => {
      await setup();
      type('backup-passphrase', 'correct horse battery');
      button('Create backup').click();
      await settle();
      http.expectOne(START).flush(job({ state: 'COMPLETED', stepsDone: 4 }));
      await settle();
      const input = root.querySelector('[data-testid="backup-passphrase"]') as HTMLInputElement;
      expect(input.value).toBe('');
    });

    it('accepts a passphrase of exactly 12 characters', async () => {
      await setup();
      type('backup-passphrase', 'abcdefghijkl');
      button('Create backup').click();
      await settle();
      expect(http.expectOne(START).request.body).toEqual({ passphrase: 'abcdefghijkl' });
    });

    it('rejects a passphrase longer than 256 characters', async () => {
      await setup();
      type('backup-passphrase', 'x'.repeat(257));
      button('Create backup').click();
      await settle();

      http.expectNone(START);
      expect(text('mat-error')).toBe('This passphrase is too long.');
    });

    it('sends a single start request when submitted twice while starting', async () => {
      await setup();
      const form = root.querySelector('form.start') as HTMLFormElement;
      form.dispatchEvent(new Event('submit'));
      form.dispatchEvent(new Event('submit'));
      await settle();
      expect(button('Create backup').disabled).toBe(true);
      http.expectOne(START).flush(job({ state: 'COMPLETED', stepsDone: 4 }));
      await settle();
    });

    it('does not start another backup while one is running', async () => {
      await setup(ID);
      http.expectOne(JOB).flush(job());
      await settle();
      (root.querySelector('form.start') as HTMLFormElement).dispatchEvent(new Event('submit'));
      await settle();
      http.expectNone(START);
    });

    it('shows the progress bar value from the steps done', async () => {
      await setup(ID);
      http.expectOne(JOB).flush(job({ stepsDone: 1, stepsTotal: 4 }));
      await settle();
      const bar = root.querySelector('.job mat-progress-bar') as HTMLElement;
      expect(bar.getAttribute('aria-valuenow')).toBe('25');
    });

    it('shows 0 % progress when the job has no steps yet', async () => {
      await setup(ID);
      http.expectOne(JOB).flush(job({ stepsDone: 0, stepsTotal: 0 }));
      await settle();
      const bar = root.querySelector('.job mat-progress-bar') as HTMLElement;
      expect(bar.getAttribute('aria-valuenow')).toBe('0');
    });

    it('ignores an older job answer that arrives after a newer one', async () => {
      await setup(ID);
      http.expectOne(JOB).flush(job({ stepsDone: 1 }));
      await settle();

      changes.next(changeEvent(ID));
      changes.next(changeEvent(ID));
      await settle();
      const [older, newer] = http.match(JOB);
      newer.flush(job({ state: 'COMPLETED', stepsDone: 4, fileName: 'cockpit-backup.zip' }));
      await settle();
      older.flush(job({ stepsDone: 2 }));
      await settle();

      expect(root.querySelector('.job')?.getAttribute('data-state')).toBe('COMPLETED');
      expect(button('Download')).toBeTruthy();
    });

    it('ignores an older job request that fails after a newer one answered', async () => {
      await setup(ID);
      http.expectOne(JOB).flush(job({ stepsDone: 1 }));
      await settle();

      changes.next(changeEvent(ID));
      changes.next(changeEvent(ID));
      await settle();
      const [older, newer] = http.match(JOB);
      newer.flush(job({ stepsDone: 3 }));
      await settle();
      older.flush({ status: 404, title: 'Not Found' }, { status: 404, statusText: 'Not Found' });
      await settle();

      expect(text('.job [role="status"]')).toContain('3 of 4 steps');
      expect(TestBed.inject(BackupService).lastJobId).toBe(ID);
      expect(root.querySelector('[role="alert"]')).toBeNull();
    });

    it('shows a failed progress fetch while the backup runs', async () => {
      await setup(ID);
      http.expectOne(JOB).flush(job());
      await settle();
      changes.next(changeEvent(ID));
      await settle();
      http
        .expectOne(JOB)
        .flush(
          { status: 503, title: 'Service Unavailable' },
          { status: 503, statusText: 'Unavailable' },
        );
      await settle();

      expect(text('[role="alert"]')).toBe('Service Unavailable');
      expect(notifications.error).not.toHaveBeenCalled();
      // The job stays, so a later event can still update it.
      expect(root.querySelector('.job')?.getAttribute('data-state')).toBe('RUNNING');
    });

    it('keeps a completed backup silently when a refetch fails', async () => {
      await setup(ID);
      http.expectOne(JOB).flush(job({ state: 'COMPLETED', stepsDone: 4 }));
      await settle();
      resyncs.next();
      await settle();
      http
        .expectOne(JOB)
        .flush({ status: 500, title: 'Server Error' }, { status: 500, statusText: 'Error' });
      await settle();

      expect(root.querySelector('[role="alert"]')).toBeNull();
      expect(button('Download')).toBeTruthy();
    });

    it('fetches nothing on an SSE reconnect without a backup', async () => {
      await setup();
      resyncs.next();
      changes.next(changeEvent(ID));
      await settle();
      http.expectNone(JOB);
    });

    it('forgets the backup when its download is gone', async () => {
      await setup(ID);
      http.expectOne(JOB).flush(job({ state: 'COMPLETED', fileName: 'cockpit-backup.zip' }));
      await settle();

      button('Download').click();
      await settle();
      http.expectOne(`${JOB}/download`).flush(null, { status: 404, statusText: 'Not Found' });
      await settle();

      expect(text('[role="alert"]')).toContain('This backup is no longer available');
      expect(root.querySelector('.job')).toBeNull();
      expect(TestBed.inject(BackupService).lastJobId).toBeNull();
    });

    it('shows the server message when a backup cannot start', async () => {
      await setup();
      button('Create backup').click();
      await settle();
      http
        .expectOne(START)
        .flush(
          { status: 500, title: 'Server Error', detail: 'Backup folder not writable' },
          { status: 500, statusText: 'Error' },
        );
      await settle();
      expect(text('[role="alert"]')).toBe('Backup folder not writable');
      expect(root.querySelector('.job')).toBeNull();
      expect(button('Create backup').disabled).toBe(false);
    });

    it('downloads once when Download is clicked twice', async () => {
      await setup(ID);
      http.expectOne(JOB).flush(job({ state: 'COMPLETED', fileName: 'cockpit-backup.zip' }));
      await settle();
      let finish: () => void = () => undefined;
      const download = vi
        .spyOn(TestBed.inject(BackupService), 'download')
        .mockReturnValue(new Promise<void>((resolve) => (finish = resolve)));

      const downloadButton = button('Download');
      downloadButton.click();
      downloadButton.click();
      await settle();
      expect(download).toHaveBeenCalledTimes(1);
      expect(button('Download').disabled).toBe(true);

      finish();
      await settle();
      expect(button('Download').disabled).toBe(false);
    });

    it('keeps the backup and explains another download failure', async () => {
      await setup(ID);
      http.expectOne(JOB).flush(job({ state: 'COMPLETED', fileName: 'cockpit-backup.zip' }));
      await settle();
      const download = vi
        .spyOn(TestBed.inject(BackupService), 'download')
        .mockRejectedValue(new Error('network down'));

      button('Download').click();
      await settle();

      expect(download).toHaveBeenCalledTimes(1);
      expect(text('[role="alert"]')).toBe('Something went wrong.');
      expect(button('Download').disabled).toBe(false);
      expect(TestBed.inject(BackupService).lastJobId).toBe(ID);
    });
  });

  describe('restore report', () => {
    it('renders format, versions, secrets, sorted counts and warnings', async () => {
      await setup();
      await uploadWith(report({ warnings: ['External calendar events are not included'] }));

      expect(text('.report-title')).toBe('cockpit-backup-2026-10-20T1530Z.zip can be restored');
      const facts = [...root.querySelectorAll('.facts dt')].map((dt) => [
        dt.textContent?.trim(),
        dt.nextElementSibling?.textContent?.trim(),
      ]);
      expect(facts).toContainEqual(['Format version', '1']);
      expect(facts).toContainEqual(['App version', '0.1.0']);
      expect(facts).toContainEqual(['Secrets', 'not included']);
      const rows = [...root.querySelectorAll('.counts tr')].map((tr) =>
        [...tr.children].map((cell) => cell.textContent?.trim()),
      );
      expect(rows).toEqual([
        ['notes', '7'],
        ['tasks', '1,234'],
      ]);
      expect(text('.warnings li')).toBe('External calendar events are not included');
      expect(root.querySelector('.problems')).toBeNull();
      expect(root.querySelector('[data-testid="restore-passphrase"]')).toBeNull();
    });

    it('shows the problems of an invalid backup and offers no restore', async () => {
      await setup();
      await uploadWith(
        report({
          id: undefined,
          valid: false,
          formatVersion: 2,
          appVersion: undefined,
          createdAt: undefined,
          counts: {},
          problems: ['Unsupported format version 2'],
        }),
      );

      expect(text('.report-title')).toContain('cannot be restored');
      expect(text('.problems li')).toBe('Unsupported format version 2');
      expect(root.querySelector('.counts')).toBeNull();
      expect(root.querySelector('[data-testid="restore-confirm"]')).toBeNull();
      expect(button('Replace all data')).toBeUndefined();

      button('Close').click();
      await settle();
      expect(root.querySelector('.report')).toBeNull();
    });

    it('asks for the passphrase of a backup with secrets', async () => {
      await setup();
      await uploadWith(report({ hasSecrets: true }));
      expect(root.querySelector('[data-testid="restore-passphrase"]')).not.toBeNull();
      expect(text('.facts')).toContain('included, encrypted');
    });

    it('shows a failed upload', async () => {
      await setup();
      await pick();
      http
        .expectOne(RESTORE)
        .flush(
          { status: 413, title: 'Payload Too Large' },
          { status: 413, statusText: 'Too Large' },
        );
      await settle();
      expect(text('[role="alert"]')).toBe('Payload Too Large');
      expect(root.querySelector('.report')).toBeNull();
    });
  });

  describe('confirmation gate', () => {
    it('enables the restore only once the confirmation word is typed exactly', async () => {
      await setup();
      await uploadWith(report());
      const replace = (): HTMLButtonElement => button('Replace all data');

      expect(replace().disabled).toBe(true);
      type('restore-confirm', 'restore');
      await settle();
      expect(replace().disabled).toBe(true);
      type('restore-confirm', 'RESTOR');
      await settle();
      expect(replace().disabled).toBe(true);

      // Submitting the form while the gate is closed sends nothing.
      (root.querySelector('form.confirm') as HTMLFormElement).dispatchEvent(new Event('submit'));
      await settle();
      http.expectNone(CONFIRM);

      type('restore-confirm', ' RESTORE ');
      await settle();
      expect(replace().disabled).toBe(false);
    });

    it('restores, shows progress, then forces a new login', async () => {
      await setup();
      await uploadWith(report({ hasSecrets: true }));
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
      type('restore-passphrase', 'correct horse battery');
      type('restore-confirm', 'RESTORE');
      await settle();

      button('Replace all data').click();
      await settle();
      const req = http.expectOne({ method: 'POST', url: CONFIRM });
      expect(req.request.body).toEqual({ passphrase: 'correct horse battery' });
      expect(text('.restoring [role="status"]')).toContain('Restoring');
      expect(root.querySelector('.report')).toBeNull();

      req.flush({ counts: { tasks: 1234 }, preRestoreBackup: 'pre-restore/x.zip' });
      await settle();
      expect(notifications.info).toHaveBeenCalledWith(
        'Restore complete. Sign in again to continue.',
      );
      expect(auth.checkSession).toHaveBeenCalledWith(true);
      expect(auth.logout).not.toHaveBeenCalled();
      expect(navigate).toHaveBeenCalledWith('/login');
    });

    it('ends the session itself when the server still knows it', async () => {
      await setup();
      await uploadWith(report());
      auth.checkSession.mockResolvedValue(true);
      vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
      type('restore-confirm', 'RESTORE');
      await settle();
      button('Replace all data').click();
      await settle();
      http.expectOne(CONFIRM).flush({ counts: {}, preRestoreBackup: 'pre-restore/x.zip' });
      await settle();
      expect(auth.logout).toHaveBeenCalled();
    });

    it('keeps the report and explains a failed restore', async () => {
      await setup();
      await uploadWith(report({ hasSecrets: true }));
      type('restore-confirm', 'RESTORE');
      await settle();
      button('Replace all data').click();
      await settle();
      http
        .expectOne(CONFIRM)
        .flush(
          { status: 422, title: 'Unprocessable', problems: ['wrong passphrase'] },
          { status: 422, statusText: 'Unprocessable' },
        );
      await settle();

      expect(text('.report [role="alert"]')).toContain('nothing was changed');
      expect(button('Replace all data').disabled).toBe(false);
      expect(auth.checkSession).not.toHaveBeenCalled();
    });

    it('asks for a new upload when the old one is gone', async () => {
      await setup();
      await uploadWith(report());
      type('restore-confirm', 'RESTORE');
      await settle();
      button('Replace all data').click();
      await settle();
      http
        .expectOne(CONFIRM)
        .flush({ status: 404, title: 'Not Found' }, { status: 404, statusText: 'Not Found' });
      await settle();

      expect(root.querySelector('.report')).toBeNull();
      expect(text('[role="alert"]')).toContain('Upload the backup again');
    });

    it('sends no passphrase for a backup without secrets', async () => {
      await setup();
      await uploadWith(report());
      vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
      type('restore-confirm', 'RESTORE');
      await settle();
      button('Replace all data').click();
      await settle();
      const req = http.expectOne(CONFIRM);
      expect(req.request.body).toEqual({});
      req.flush({ counts: {}, preRestoreBackup: 'pre-restore/x.zip' });
      await settle();
    });

    it('sends a single restore when submitted twice', async () => {
      await setup();
      await uploadWith(report());
      vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
      type('restore-confirm', 'RESTORE');
      await settle();
      const form = root.querySelector('form.confirm') as HTMLFormElement;
      form.dispatchEvent(new Event('submit'));
      form.dispatchEvent(new Event('submit'));
      await settle();
      http.expectOne(CONFIRM).flush({ counts: {}, preRestoreBackup: 'pre-restore/x.zip' });
      await settle();
    });

    it('does not restore with a passphrase longer than 256 characters', async () => {
      await setup();
      await uploadWith(report({ hasSecrets: true }));
      type('restore-passphrase', 'x'.repeat(257));
      type('restore-confirm', 'RESTORE');
      await settle();
      (root.querySelector('form.confirm') as HTMLFormElement).dispatchEvent(new Event('submit'));
      await settle();
      http.expectNone(CONFIRM);
    });

    it('explains a restore passphrase that is too long when Replace all data is clicked', async () => {
      await setup();
      await uploadWith(report({ hasSecrets: true }));
      // Typed without leaving the field: only the submit marks it touched.
      const input = root.querySelector('[data-testid="restore-passphrase"]') as HTMLInputElement;
      input.value = 'x'.repeat(257);
      input.dispatchEvent(new Event('input'));
      type('restore-confirm', 'RESTORE');
      await settle();
      expect(root.querySelector('mat-error')).toBeNull();
      expect(button('Replace all data').disabled).toBe(false);

      button('Replace all data').click();
      await settle();

      http.expectNone(CONFIRM);
      expect(text('mat-error')).toBe('This passphrase is too long.');
    });

    it('restores after a too-long passphrase is shortened to 256 characters', async () => {
      await setup();
      await uploadWith(report({ hasSecrets: true }));
      vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
      type('restore-passphrase', 'x'.repeat(257));
      type('restore-confirm', 'RESTORE');
      await settle();
      button('Replace all data').click();
      await settle();
      http.expectNone(CONFIRM);
      expect(text('mat-error')).toBe('This passphrase is too long.');

      type('restore-passphrase', 'y'.repeat(256));
      await settle();
      expect(root.querySelector('mat-error')).toBeNull();
      button('Replace all data').click();
      await settle();

      const req = http.expectOne(CONFIRM);
      expect(req.request.body).toEqual({ passphrase: 'y'.repeat(256) });
      req.flush({ counts: {}, preRestoreBackup: 'pre-restore/x.zip' });
      await settle();
    });

    it('closes the gate again after cancel and a new upload', async () => {
      await setup();
      await uploadWith(report());
      type('restore-confirm', 'RESTORE');
      await settle();
      expect(button('Replace all data').disabled).toBe(false);

      button('Cancel').click();
      await settle();
      expect(root.querySelector('.report')).toBeNull();
      expect(root.querySelector('[data-testid="restore-file"]')).not.toBeNull();

      await uploadWith(report());
      const confirm = root.querySelector('[data-testid="restore-confirm"]') as HTMLInputElement;
      expect(confirm.value).toBe('');
      expect(button('Replace all data').disabled).toBe(true);
    });

    it('explains a restore refused while a backup runs', async () => {
      await setup();
      await uploadWith(report());
      type('restore-confirm', 'RESTORE');
      await settle();
      button('Replace all data').click();
      await settle();
      http
        .expectOne(CONFIRM)
        .flush({ status: 409, title: 'Conflict' }, { status: 409, statusText: 'Conflict' });
      await settle();

      expect(text('.report [role="alert"]')).toContain('A backup is running');
      expect(root.querySelector('.restoring')).toBeNull();
      expect(notifications.error).not.toHaveBeenCalled();
    });

    it('shows the server message of another restore failure', async () => {
      await setup();
      await uploadWith(report());
      type('restore-confirm', 'RESTORE');
      await settle();
      button('Replace all data').click();
      await settle();
      http
        .expectOne(CONFIRM)
        .flush(
          { status: 500, title: 'Server Error', detail: 'Disk full' },
          { status: 500, statusText: 'Error' },
        );
      await settle();

      expect(text('.report [role="alert"]')).toBe('Disk full');
      expect(auth.checkSession).not.toHaveBeenCalled();
    });

    it('logs out and still shows the login page when the session check fails', async () => {
      await setup();
      await uploadWith(report());
      auth.checkSession.mockRejectedValue(new Error('offline'));
      auth.logout.mockRejectedValue(new Error('offline'));
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
      type('restore-confirm', 'RESTORE');
      await settle();
      button('Replace all data').click();
      await settle();
      http.expectOne(CONFIRM).flush({ counts: {}, preRestoreBackup: 'pre-restore/x.zip' });
      await settle();

      expect(auth.logout).toHaveBeenCalled();
      expect(navigate).toHaveBeenCalledWith('/login');
    });
  });

  describe('restore upload', () => {
    it('opens the file picker from the upload button', async () => {
      await setup();
      const input = root.querySelector('[data-testid="restore-file"]') as HTMLInputElement;
      const open = vi.spyOn(input, 'click').mockImplementation(() => undefined);
      button('Upload backup…').click();
      expect(open).toHaveBeenCalledTimes(1);
    });

    it('uploads a zip typed application/x-zip-compressed as application/zip', async () => {
      await setup();
      const input = root.querySelector('[data-testid="restore-file"]') as HTMLInputElement;
      const file = new File(['PK-fictitious'], 'cockpit-backup-windows.zip', {
        type: 'application/x-zip-compressed',
      });
      Object.defineProperty(input, 'files', { value: [file], configurable: true });
      input.dispatchEvent(new Event('change'));
      await settle();

      const req = http.expectOne({ method: 'POST', url: RESTORE });
      expect(req.request.headers.get('Content-Type')).toBe('application/zip');
      expect(await (req.request.body as Blob).text()).toBe('PK-fictitious');
      req.flush(report());
      await settle();
      expect(root.querySelector('.report')).not.toBeNull();
    });

    it('does nothing when no file was chosen', async () => {
      await setup();
      const input = root.querySelector('[data-testid="restore-file"]') as HTMLInputElement;
      Object.defineProperty(input, 'files', { value: [], configurable: true });
      input.dispatchEvent(new Event('change'));
      await settle();
      http.expectNone(RESTORE);
      expect(root.querySelector('.uploading')).toBeNull();
    });

    it('shows upload progress with the file name and disables the button', async () => {
      await setup();
      await pick();
      expect(text('.uploading [role="status"]')).toContain(
        'Uploading and checking cockpit-backup-2026-10-20T1530Z.zip',
      );
      expect(button('Upload backup…').disabled).toBe(true);
      http.expectOne(RESTORE).flush(report());
      await settle();
      expect(root.querySelector('.uploading')).toBeNull();
    });

    it('clears an earlier upload error when the next upload succeeds', async () => {
      await setup();
      await pick();
      http
        .expectOne(RESTORE)
        .flush({ status: 400, title: 'Not a zip' }, { status: 400, statusText: 'Bad Request' });
      await settle();
      expect(text('[role="alert"]')).toBe('Not a zip');

      await uploadWith(report());
      expect(root.querySelector('[role="alert"]')).toBeNull();
      expect(root.querySelector('.report')).not.toBeNull();
    });

    it('renders an unknown creation date and missing versions', async () => {
      await setup();
      await uploadWith(
        report({ createdAt: undefined, appVersion: undefined, formatVersion: undefined }),
      );
      const facts = [...root.querySelectorAll('.facts dt')].map((dt) => [
        dt.textContent?.trim(),
        dt.nextElementSibling?.textContent?.trim(),
      ]);
      expect(facts).toContainEqual(['Created', 'unknown']);
      expect(facts).toContainEqual(['Format version', '–']);
      expect(facts).toContainEqual(['App version', '–']);
    });

    it('offers no restore for a valid report without an upload id', async () => {
      await setup();
      await uploadWith(report({ id: undefined }));
      expect(text('.report-title')).toContain('can be restored');
      expect(button('Replace all data')).toBeUndefined();
      expect(button('Close')).toBeTruthy();
    });
  });
});
