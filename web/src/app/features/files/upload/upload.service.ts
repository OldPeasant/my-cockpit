import {
  HttpClient,
  HttpContext,
  HttpEventType,
  HttpHeaders,
  HttpParams,
  HttpRequest,
  HttpResponse,
} from '@angular/common/http';
import { computed, inject, Injectable, InjectionToken, signal } from '@angular/core';
import { Subject } from 'rxjs';
import {
  abortUpload,
  Api,
  completeUpload,
  createUpload,
  FileResponse,
  getUpload,
  putUploadChunk,
  uploadFile,
  UploadResponse,
} from '../../../api/generated';
import { ApiError, SILENT_ERRORS } from '../../../core/http';
import { Sha256 } from './sha256';

/** Files above this size go through the resumable chunked upload (SPEC.md §8.2). */
export const CHUNKED_THRESHOLD = 50 * 1024 * 1024;

/** How many files upload at the same time. */
export const PARALLEL_UPLOADS = 2;

/**
 * Pauses before the retries of a failed chunk, in ms: one retry per entry, so a chunk is sent at most
 * `length + 1` times before its upload fails (and can be resumed by hand).
 */
export const CHUNK_RETRY_DELAYS = new InjectionToken<readonly number[]>('CHUNK_RETRY_DELAYS', {
  factory: () => [1000, 3000, 9000],
});

export type UploadState = 'queued' | 'uploading' | 'done' | 'failed' | 'cancelled';

let nextJobId = 0;

/** One file on its way to the drive. */
export class UploadJob {
  readonly id = ++nextJobId;
  readonly state = signal<UploadState>('queued');
  /** Bytes sent so far. */
  readonly loaded = signal(0);
  readonly error = signal<string | null>(null);
  /** The stored file, once done. */
  readonly result = signal<FileResponse | null>(null);
  /** 0-100. */
  readonly percent = computed(() =>
    this.file.size ? Math.min(100, Math.floor((this.loaded() / this.file.size) * 100)) : 100,
  );
  readonly finished = computed(() => this.state() !== 'queued' && this.state() !== 'uploading');

  /** The resumable upload on the server, kept after a failure so a retry resumes it. */
  uploadId: string | null = null;
  /** Stops the request in flight, set while one runs. */
  stop: (() => void) | null = null;

  constructor(
    readonly file: File,
    /** Target folder; `null`: the drive root. */
    readonly folderId: string | null,
  ) {}

  get name(): string {
    return this.file.name;
  }

  get chunked(): boolean {
    return this.file.size > CHUNKED_THRESHOLD;
  }
}

/** Thrown into a running upload when it is cancelled. */
class Cancelled extends Error {}

/**
 * Uploads to the drive (SPEC.md §8.2), any number of files, {@link PARALLEL_UPLOADS} at a time, with
 * progress. Files up to {@link CHUNKED_THRESHOLD} go in one multipart request; larger ones through a
 * resumable upload in chunks, each retried after a network or server error, with the SHA-256 the
 * server checks computed along the way. A failed upload can be retried: a chunked one resumes with
 * the chunks the server is missing. Cancelling stops the request and discards the server's chunks.
 *
 * The jobs live in this root service, so uploads go on while the user moves between pages.
 */
@Injectable({ providedIn: 'root' })
export class UploadService {
  private readonly http = inject(HttpClient);
  private readonly rootUrl = inject(Api).rootUrl;
  private readonly retryDelays = inject(CHUNK_RETRY_DELAYS);

  private readonly jobList = signal<readonly UploadJob[]>([]);
  readonly jobs = this.jobList.asReadonly();
  /** Uploads that are queued or running. */
  readonly active = computed(() => this.jobList().filter((job) => !job.finished()).length);

  private readonly uploadedFiles = new Subject<FileResponse>();
  /** Each file as soon as it is stored. */
  readonly uploaded = this.uploadedFiles.asObservable();

  /** Queues `files` for upload into `folderId` (`null`: the drive root). */
  upload(files: Iterable<File>, folderId: string | null): UploadJob[] {
    const jobs = Array.from(files, (file) => new UploadJob(file, folderId));
    if (!jobs.length) return jobs;
    this.jobList.update((list) => [...list, ...jobs]);
    this.pump();
    return jobs;
  }

  /** Stops a queued or running upload and discards what the server received of it. */
  cancel(job: UploadJob): void {
    if (job.finished() && job.state() !== 'failed') return;
    job.state.set('cancelled');
    job.error.set(null);
    job.stop?.();
    this.discard(job);
  }

  /** Queues a failed upload again; a chunked one resumes where it stopped. */
  retry(job: UploadJob): void {
    if (job.state() !== 'failed') return;
    job.state.set('queued');
    job.error.set(null);
    this.pump();
  }

  /** Removes a job from the list, cancelling it first when it has not finished. */
  dismiss(job: UploadJob): void {
    this.cancel(job);
    this.jobList.update((list) => list.filter((other) => other !== job));
  }

  /** Removes the done and cancelled uploads from the list. */
  clearFinished(): void {
    this.jobList.update((list) =>
      list.filter((job) => job.state() !== 'done' && job.state() !== 'cancelled'),
    );
  }

  private pump(): void {
    const jobs = this.jobList();
    let running = jobs.filter((job) => job.state() === 'uploading').length;
    for (const job of jobs) {
      if (running >= PARALLEL_UPLOADS) return;
      if (job.state() !== 'queued') continue;
      running++;
      void this.run(job);
    }
  }

  private async run(job: UploadJob): Promise<void> {
    job.state.set('uploading');
    job.loaded.set(0);
    try {
      const file = job.chunked ? await this.sendChunked(job) : await this.sendWhole(job);
      if (job.state() !== 'uploading') return;
      job.loaded.set(job.file.size);
      job.result.set(file);
      job.state.set('done');
      this.uploadedFiles.next(file);
    } catch (error) {
      if (job.state() !== 'uploading') return;
      job.error.set(messageOf(error));
      job.state.set('failed');
    } finally {
      job.stop = null;
      this.pump();
    }
  }

  private async sendWhole(job: UploadJob): Promise<FileResponse> {
    const body = new FormData();
    body.append('file', job.file, job.file.name);
    let params = new HttpParams().set('name', job.file.name);
    if (job.folderId) params = params.set('folderId', job.folderId);
    const response = await this.send<FileResponse>(
      job,
      new HttpRequest('POST', this.url(uploadFile.PATH), body, {
        params,
        reportProgress: true,
        context: silent(),
      }),
      (loaded) => job.loaded.set(Math.min(loaded, job.file.size)),
    );
    return response.body!;
  }

  private async sendChunked(job: UploadJob): Promise<FileResponse> {
    const upload = await this.session(job);
    const received = new Set(upload.receivedChunks ?? []);
    const hash = new Sha256();
    let done = 0;
    for (let n = 0; n < upload.chunkCount; n++) {
      const start = n * upload.chunkSize;
      const blob = job.file.slice(start, Math.min(job.file.size, start + upload.chunkSize));
      const bytes = new Uint8Array(await blob.arrayBuffer());
      this.check(job);
      hash.update(bytes);
      if (!received.has(n)) await this.putChunk(job, upload.id, n, bytes, done);
      done += bytes.length;
      job.loaded.set(done);
    }
    const response = await this.send<FileResponse>(
      job,
      new HttpRequest(
        'POST',
        this.url(completeUpload.PATH, upload.id),
        { sha256: hash.hex() },
        { context: silent() },
      ),
    );
    job.uploadId = null;
    return response.body!;
  }

  /** The job's resumable upload: the one a failed attempt left behind, or a new one. */
  private async session(job: UploadJob): Promise<UploadResponse> {
    if (job.uploadId) {
      try {
        const response = await this.send<UploadResponse>(
          job,
          new HttpRequest('GET', this.url(getUpload.PATH, job.uploadId), { context: silent() }),
        );
        return response.body!;
      } catch (error) {
        // Expired or deleted on the server: start over.
        if (!(error instanceof ApiError && error.status === 404)) throw error;
        job.uploadId = null;
      }
    }
    const response = await this.send<UploadResponse>(
      job,
      new HttpRequest(
        'POST',
        this.url(createUpload.PATH),
        {
          name: job.file.name,
          folderId: job.folderId,
          size: job.file.size,
          ...(job.file.type ? { mime: job.file.type } : {}),
        },
        { context: silent() },
      ),
    );
    const upload = response.body!;
    job.uploadId = upload.id;
    return upload;
  }

  /** Sends one chunk, retrying after network and server errors with the configured pauses. */
  private async putChunk(
    job: UploadJob,
    uploadId: string,
    n: number,
    bytes: Uint8Array,
    before: number,
  ): Promise<void> {
    const url = this.url(putUploadChunk.PATH, uploadId).replace('{n}', String(n));
    for (let attempt = 0; ; attempt++) {
      try {
        await this.send(
          job,
          new HttpRequest('PUT', url, bytes.buffer, {
            headers: new HttpHeaders({ 'Content-Type': 'application/octet-stream' }),
            reportProgress: true,
            context: silent(),
          }),
          (loaded) => job.loaded.set(before + Math.min(loaded, bytes.length)),
        );
        return;
      } catch (error) {
        if (attempt >= this.retryDelays.length || !retryable(error)) throw error;
        job.loaded.set(before);
        await this.pause(job, this.retryDelays[attempt]);
      }
    }
  }

  /** Runs `request`, reporting the bytes sent; {@link cancel} stops it. */
  private send<T>(
    job: UploadJob,
    request: HttpRequest<unknown>,
    progress?: (loaded: number) => void,
  ): Promise<HttpResponse<T>> {
    this.check(job);
    return new Promise((resolve, reject) => {
      const subscription = this.http.request<T>(request).subscribe({
        next: (event) => {
          if (event.type === HttpEventType.UploadProgress) progress?.(event.loaded);
          else if (event.type === HttpEventType.Response) resolve(event);
        },
        error: (error: unknown) => {
          job.stop = null;
          reject(error);
        },
        complete: () => (job.stop = null),
      });
      job.stop = () => {
        subscription.unsubscribe();
        reject(new Cancelled());
      };
    });
  }

  private pause(job: UploadJob, ms: number): Promise<void> {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        job.stop = null;
        resolve();
      }, ms);
      job.stop = () => {
        clearTimeout(timer);
        reject(new Cancelled());
      };
    });
  }

  private check(job: UploadJob): void {
    if (job.state() !== 'uploading') throw new Cancelled();
  }

  /** Deletes the server's chunks of a cancelled upload; a failure leaves them to expire. */
  private discard(job: UploadJob): void {
    const id = job.uploadId;
    if (!id) return;
    job.uploadId = null;
    this.http
      .request(new HttpRequest('DELETE', this.url(abortUpload.PATH, id), { context: silent() }))
      .subscribe({ error: () => undefined });
  }

  private url(path: string, id?: string): string {
    return this.rootUrl + (id ? path.replace('{id}', encodeURIComponent(id)) : path);
  }
}

/** Network errors (status 0), timeouts, rate limits and server errors may pass on a retry. */
function retryable(error: unknown): boolean {
  if (!(error instanceof ApiError)) return false;
  return error.status === 0 || error.status === 408 || error.status === 429 || error.status >= 500;
}

function messageOf(error: unknown): string {
  return error instanceof ApiError
    ? error.userMessage
    : $localize`:@@files.upload.error:The upload failed.`;
}

function silent(): HttpContext {
  return new HttpContext().set(SILENT_ERRORS, true);
}
