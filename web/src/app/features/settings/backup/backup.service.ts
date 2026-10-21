import { HttpContext } from '@angular/common/http';
import { DOCUMENT, inject, Injectable } from '@angular/core';
import {
  Api,
  BackupJobResponse,
  confirmRestore,
  downloadBackup,
  getBackup,
  RestoreReportResponse,
  RestoreResultResponse,
  startBackup,
  uploadRestore,
} from '../../../api/generated';
import { SILENT_ERRORS } from '../../../core/http';

/**
 * Backups and restores (SPEC.md §10.1). Errors are not toasted: the backup section shows them next
 * to the action that failed. Remembers the last started backup for this browser tab, so its
 * progress or download shows again when the owner comes back to the section (the server has no
 * list of backups).
 */
@Injectable({ providedIn: 'root' })
export class BackupService {
  private readonly api = inject(Api);
  private readonly document = inject(DOCUMENT);

  /** Id of the backup started last in this tab. */
  lastJobId: string | null = null;

  /** Starts a backup; an empty passphrase counts as none (secrets left out). */
  async start(passphrase: string): Promise<BackupJobResponse> {
    const job = await this.api.invoke(
      startBackup,
      { body: passphrase ? { passphrase } : {} },
      silent(),
    );
    this.lastJobId = job.id;
    return job;
  }

  /** State and progress of a backup. */
  job(id: string): Promise<BackupJobResponse> {
    return this.api.invoke(getBackup, { id }, silent());
  }

  /** Downloads a completed backup and hands it to the browser as a file. */
  async download(job: BackupJobResponse): Promise<void> {
    const blob = await this.api.invoke(downloadBackup, { id: job.id }, silent());
    this.save(blob, job.fileName ?? 'cockpit-backup.zip');
  }

  /** Uploads a backup zip; the server validates it and changes no data. */
  upload(file: Blob): Promise<RestoreReportResponse> {
    return this.api.invoke(uploadRestore, { body: file }, silent());
  }

  /** Replaces all data with the uploaded backup; all sessions end. */
  confirm(id: string, passphrase: string): Promise<RestoreResultResponse> {
    return this.api.invoke(
      confirmRestore,
      { id, body: passphrase ? { passphrase } : {} },
      silent(),
    );
  }

  private save(blob: Blob, fileName: string): void {
    const url = URL.createObjectURL(blob);
    const link = this.document.createElement('a');
    link.href = url;
    link.download = fileName;
    link.click();
    // Revoked later: some browsers start the download only after the click handler returns.
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
}

function silent(): HttpContext {
  return new HttpContext().set(SILENT_ERRORS, true);
}
