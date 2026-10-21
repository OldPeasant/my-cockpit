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
      http
        .expectOne(JOB)
        .flush(
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
  });
});
