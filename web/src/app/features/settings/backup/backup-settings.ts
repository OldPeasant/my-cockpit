import { DatePipe, DecimalPipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Router } from '@angular/router';
import { filter, map, merge } from 'rxjs';
import { BackupJobResponse, RestoreReportResponse } from '../../../api/generated';
import { AuthService } from '../../../core/auth';
import { ChangeEventsService } from '../../../core/events';
import { ApiError, LOGIN_PATH, NotificationService } from '../../../core/http';
import { BackupService } from './backup.service';

/** Passphrase length the server accepts (`BackupController.PASSPHRASE_MIN`/`MAX`). */
export const PASSPHRASE_MIN = 12;
export const PASSPHRASE_MAX = 256;

/** SSE change-event type of backup progress (server: `BackupJobService.EVENT_TYPE`). */
const BACKUP_EVENT = 'BACKUP';

/** The word the owner types to confirm that a restore replaces all data. */
export const CONFIRM_WORD = $localize`:@@backup.restore.confirmWord:RESTORE`;

/**
 * Settings → Backup & restore (SPEC.md §10.1).
 *
 * - Backup: optional passphrase (secrets included, encrypted), progress from the `BACKUP` change
 *   events of the SSE stream (the job is refetched on each), then download.
 * - Restore: upload a zip, show the server's validation report, confirm by typing
 *   {@link CONFIRM_WORD}, wait for the full replace, then sign in again (all sessions ended).
 */
@Component({
  selector: 'app-backup-settings',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    DecimalPipe,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    ReactiveFormsModule,
  ],
  templateUrl: './backup-settings.html',
  styleUrl: './backup-settings.scss',
})
export default class BackupSettings implements OnInit {
  private readonly service = inject(BackupService);
  private readonly events = inject(ChangeEventsService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly notifications = inject(NotificationService);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly confirmWord = CONFIRM_WORD;
  protected readonly passphraseMin = PASSPHRASE_MIN;

  // Backup
  protected readonly passphrase = this.fb.control('', [
    optionalMinLength(PASSPHRASE_MIN),
    Validators.maxLength(PASSPHRASE_MAX),
  ]);
  protected readonly job = signal<BackupJobResponse | null>(null);
  protected readonly starting = signal(false);
  protected readonly backupError = signal<string | null>(null);
  protected readonly downloading = signal(false);
  protected readonly downloadError = signal<string | null>(null);
  protected readonly running = computed(() => this.job()?.state === 'RUNNING');
  protected readonly percent = computed(() => {
    const job = this.job();
    if (!job || job.stepsTotal <= 0) return 0;
    return Math.min(100, Math.round((job.stepsDone / job.stepsTotal) * 100));
  });
  /** Answers older than the newest job request are dropped (events can overtake each other). */
  private refreshSeq = 0;

  // Restore
  protected readonly uploading = signal(false);
  protected readonly uploadError = signal<string | null>(null);
  protected readonly fileName = signal<string | null>(null);
  protected readonly report = signal<RestoreReportResponse | null>(null);
  protected readonly counts = computed(() =>
    Object.entries(this.report()?.counts ?? {}).sort(([a], [b]) => a.localeCompare(b)),
  );
  protected readonly restorePassphrase = this.fb.control('', Validators.maxLength(PASSPHRASE_MAX));
  protected readonly confirmText = this.fb.control('');
  private readonly typed = toSignal(this.confirmText.valueChanges, { initialValue: '' });
  /** The confirmation gate: the restore button works only once the word is typed exactly. */
  protected readonly confirmed = computed(() => this.typed().trim() === CONFIRM_WORD);
  protected readonly restoring = signal(false);
  protected readonly restoreError = signal<string | null>(null);

  constructor() {
    merge(
      this.events.events$(BACKUP_EVENT).pipe(map((event) => event.id)),
      this.events.resync$.pipe(map(() => this.job()?.id)),
    )
      .pipe(
        filter((id): id is string => !!id && id === this.job()?.id),
        takeUntilDestroyed(),
      )
      .subscribe((id) => void this.refresh(id));
  }

  ngOnInit(): void {
    const id = this.service.lastJobId;
    if (id) void this.refresh(id);
  }

  protected async startBackup(): Promise<void> {
    if (this.starting() || this.running()) return;
    if (this.passphrase.invalid) {
      this.passphrase.markAsTouched();
      return;
    }
    this.starting.set(true);
    this.backupError.set(null);
    this.downloadError.set(null);
    try {
      const job = await this.service.start(this.passphrase.value);
      this.refreshSeq++;
      this.job.set(job);
      this.passphrase.reset();
      // Progress events sent before the id was known were missed: catch up once.
      if (job.state === 'RUNNING') void this.refresh(job.id);
    } catch (error) {
      this.backupError.set(backupMessage(error));
    } finally {
      this.starting.set(false);
    }
  }

  protected async download(): Promise<void> {
    const job = this.job();
    if (!job || this.downloading()) return;
    this.downloading.set(true);
    this.downloadError.set(null);
    try {
      await this.service.download(job);
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) this.forgetJob();
      this.downloadError.set(downloadMessage(error));
    } finally {
      this.downloading.set(false);
    }
  }

  protected async upload(input: HTMLInputElement): Promise<void> {
    const file = input.files?.[0];
    input.value = '';
    if (!file || this.uploading() || this.restoring()) return;
    this.uploading.set(true);
    this.uploadError.set(null);
    this.restoreError.set(null);
    this.report.set(null);
    this.fileName.set(file.name);
    this.restorePassphrase.reset();
    this.confirmText.reset();
    try {
      this.report.set(await this.service.upload(file));
    } catch (error) {
      this.uploadError.set(message(error));
    } finally {
      this.uploading.set(false);
    }
  }

  protected cancelRestore(): void {
    this.report.set(null);
    this.fileName.set(null);
    this.restoreError.set(null);
    this.restorePassphrase.reset();
    this.confirmText.reset();
  }

  protected async restore(): Promise<void> {
    const id = this.report()?.id;
    if (!id || !this.confirmed() || this.restoring()) return;
    if (this.restorePassphrase.invalid) {
      this.restorePassphrase.markAsTouched();
      return;
    }
    this.restoring.set(true);
    this.restoreError.set(null);
    try {
      await this.service.confirm(id, this.restorePassphrase.value);
    } catch (error) {
      this.restoring.set(false);
      if (error instanceof ApiError && error.status === 404) {
        // Used up or replaced by a newer upload: the report no longer applies.
        this.cancelRestore();
        this.uploadError.set(
          $localize`:@@backup.restore.gone:This upload is no longer available. Upload the backup again.`,
        );
      } else {
        this.restoreError.set(restoreMessage(error));
      }
      return;
    }
    await this.signInAgain();
  }

  /** The restore replaced the sessions: drop the local one and show the login page. */
  private async signInAgain(): Promise<void> {
    this.notifications.info(
      $localize`:@@backup.restore.done:Restore complete. Sign in again to continue.`,
    );
    // Normally answers 401 (the session is gone) and clears it; anything else ends it explicitly.
    const stillIn = await this.auth.checkSession(true).catch(() => true);
    if (stillIn) await this.auth.logout().catch(() => undefined);
    await this.router.navigateByUrl(LOGIN_PATH).catch(() => false);
  }

  private async refresh(id: string): Promise<void> {
    const seq = ++this.refreshSeq;
    try {
      const job = await this.service.job(id);
      if (seq === this.refreshSeq) this.job.set(job);
    } catch (error) {
      if (seq !== this.refreshSeq) return;
      if (error instanceof ApiError && error.status === 404) this.forgetJob();
      else if (this.running()) this.backupError.set(message(error));
    }
  }

  private forgetJob(): void {
    this.job.set(null);
    this.service.lastJobId = null;
  }
}

/** Like `minLength`, but an empty value is valid (no passphrase). */
function optionalMinLength(min: number) {
  return (control: AbstractControl<string>): ValidationErrors | null =>
    control.value && control.value.length < min
      ? { minlength: { requiredLength: min, actualLength: control.value.length } }
      : null;
}

function message(error: unknown): string {
  return error instanceof ApiError
    ? error.userMessage
    : $localize`:@@backup.error:Something went wrong.`;
}

function backupMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 409) {
    return $localize`:@@backup.create.busy:A backup is already running. Wait until it is finished.`;
  }
  return message(error);
}

function downloadMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 404) {
    return $localize`:@@backup.download.gone:This backup is no longer available. Create a new one.`;
  }
  return message(error);
}

function restoreMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 409) {
    return $localize`:@@backup.restore.busy:A backup is running. Restore when it is finished.`;
  }
  if (error instanceof ApiError && error.status === 422) {
    return $localize`:@@backup.restore.failed:The backup could not be restored; nothing was changed. If it has secrets, check the passphrase.`;
  }
  return message(error);
}
