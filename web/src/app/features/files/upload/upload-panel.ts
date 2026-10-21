import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { formatBytes } from './format-bytes';
import { UploadJob, UploadService } from './upload.service';

/**
 * The uploads of {@link UploadService} with their progress, and cancel, retry and dismiss per file.
 * Shows nothing while there are no uploads.
 */
@Component({
  selector: 'app-upload-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './upload-panel.html',
  styleUrl: './upload-panel.scss',
})
export class UploadPanel {
  protected readonly uploads = inject(UploadService);

  protected readonly shown = this.uploads.jobs;
  protected readonly running = computed(() => this.shown().filter((job) => !job.finished()).length);
  protected readonly anyFinished = computed(() =>
    this.shown().some((job) => job.state() === 'done' || job.state() === 'cancelled'),
  );

  protected readonly formatBytes = formatBytes;

  protected cancelLabel(job: UploadJob): string {
    const name = job.name;
    return $localize`:@@files.upload.cancelFile:Cancel upload of ${name}:name:`;
  }

  protected retryLabel(job: UploadJob): string {
    const name = job.name;
    return $localize`:@@files.upload.retryFile:Retry upload of ${name}:name:`;
  }

  protected dismissLabel(job: UploadJob): string {
    const name = job.name;
    return $localize`:@@files.upload.dismissFile:Remove ${name}:name: from the list`;
  }

  protected progressLabel(job: UploadJob): string {
    const name = job.name;
    return $localize`:@@files.upload.progress:Upload of ${name}:name:`;
  }
}
