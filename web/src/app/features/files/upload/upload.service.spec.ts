import { HttpEventType } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { FileResponse, UploadResponse } from '../../../api/generated';
import { NotificationService, provideHttp, SILENT_ERRORS } from '../../../core/http';
import {
  CHUNK_RETRY_DELAYS,
  CHUNKED_THRESHOLD,
  PARALLEL_UPLOADS,
  UploadJob,
  UploadService,
} from './upload.service';

const STAMP = '2026-10-21T08:00:00Z';
const FOLDER = '0192f0c4-0000-7000-8000-00000000000d';
const UPLOAD_ID = '0192f0c4-0000-7000-8000-0000000000c1';
const MB = 1024 * 1024;

/** One byte above the threshold: the smallest file that goes through the chunked upload. */
const BIG_SIZE = CHUNKED_THRESHOLD + 1;

function pattern(length: number): Uint8Array<ArrayBuffer> {
  const data = new Uint8Array(length);
  for (let i = 0; i < length; i++) data[i] = (i * 31 + (i >>> 12)) & 0xff;
  return data;
}

async function sha256Hex(data: Uint8Array): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new Uint8Array(data));
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('');
}

/** The contents of the "big" file of a test and the server's chunk size for it. */
interface Payload {
  data: Uint8Array<ArrayBuffer>;
  chunk: number;
  digest: string;
  /** Whether the File really has the bytes (else a small File claims {@link BIG_SIZE}). */
  real: boolean;
}

/** A real 50 MB + 1 byte file in 3 chunks of 20 MB: used where the full size matters. */
let REAL: Payload;
/**
 * A 2007-byte file reporting {@link BIG_SIZE} as its size, in 3 chunks of 1000 bytes: it takes the
 * chunked path like a real one, but keeps the retry/cancel tests fast.
 */
let LIGHT: Payload;
let payload: Payload;

/** The time budget of the test with the real 50 MB file (hashing it is slow under coverage). */
const REAL_FILE_TIMEOUT = 60_000;

function bigFile(name = 'holiday.mp4', type = 'video/mp4'): File {
  const file = new File([payload.data], name, { type });
  if (!payload.real) Object.defineProperty(file, 'size', { value: BIG_SIZE });
  return file;
}

function stored(name: string, id = '0192f0c4-0000-7000-8000-0000000000f1'): FileResponse {
  return {
    id,
    name,
    folderId: FOLDER,
    mime: 'application/octet-stream',
    size: 1,
    sha256: 'ab'.repeat(32),
    version: 0,
    createdAt: STAMP,
    updatedAt: STAMP,
  };
}

function session(receivedChunks: number[] = [], id = UPLOAD_ID): UploadResponse {
  return {
    id,
    name: 'holiday.mp4',
    folderId: FOLDER,
    mime: 'video/mp4',
    size: BIG_SIZE,
    chunkSize: payload.chunk,
    chunkCount: Math.ceil(payload.data.length / payload.chunk),
    receivedChunks,
    createdAt: STAMP,
  };
}

const problem = (status: number, title: string, detail?: string) => ({ status, title, detail });

beforeAll(async () => {
  const real = pattern(BIG_SIZE);
  REAL = { data: real, chunk: 20 * MB, digest: await sha256Hex(real), real: true };
  const light = pattern(2007);
  LIGHT = { data: light, chunk: 1000, digest: await sha256Hex(light), real: false };
});

beforeEach(() => (payload = LIGHT));

describe('UploadJob', () => {
  const sized = (size: number) => ({ name: 'x.bin', size }) as File;

  it('uses the chunked upload only above 50 MB', () => {
    expect(CHUNKED_THRESHOLD).toBe(50 * MB);
    expect(new UploadJob(sized(CHUNKED_THRESHOLD), null).chunked).toBe(false);
    expect(new UploadJob(sized(CHUNKED_THRESHOLD + 1), null).chunked).toBe(true);
    expect(new UploadJob(sized(0), null).chunked).toBe(false);
  });

  it('reports progress in whole percent, 100 for an empty file', () => {
    const job = new UploadJob(sized(200), null);
    expect(job.percent()).toBe(0);
    job.loaded.set(99);
    expect(job.percent()).toBe(49);
    job.loaded.set(500);
    expect(job.percent()).toBe(100);
    expect(new UploadJob(sized(0), null).percent()).toBe(100);
  });
});

describe('UploadService', () => {
  let service: UploadService;
  let http: HttpTestingController;
  let notifications: { error: ReturnType<typeof vi.fn>; info: ReturnType<typeof vi.fn> };
  /**
   * How long {@link next} and {@link until} wait. Hashing the real 50 MB file takes seconds when the
   * code is instrumented for coverage, so that test raises it (see {@link REAL_FILE_TIMEOUT}).
   */
  let waitTimeout = 5000;

  beforeEach(() => (waitTimeout = 5000));

  function setup(delays: readonly number[] = [0, 0, 0]): void {
    notifications = { error: vi.fn(), info: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        { provide: CHUNK_RETRY_DELAYS, useValue: delays },
        { provide: NotificationService, useValue: notifications },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    service = TestBed.inject(UploadService);
  }

  afterEach(() => {
    http.verify();
    // Upload errors are shown in the upload list, never as toasts.
    expect(notifications.error).not.toHaveBeenCalled();
  });

  /**
   * The next request matching method and URL (and the `name` parameter, when given), once the
   * upload's promises have sent it.
   */
  function next(method: string, url: string, name?: string): Promise<TestRequest> {
    return vi.waitFor(
      () =>
        http.expectOne(
          (r) =>
            r.method === method &&
            r.url === url &&
            (name === undefined || r.params.get('name') === name),
        ),
      { timeout: waitTimeout, interval: 1 },
    );
  }

  /** Waits until the job reached `state`. */
  async function until(job: UploadJob, state: string): Promise<void> {
    await vi.waitFor(() => expect(job.state()).toBe(state), { timeout: waitTimeout, interval: 1 });
  }

  const chunkUrl = (n: number, id = UPLOAD_ID) => `/api/v1/uploads/${id}/chunks/${n}`;

  function chunkBytes(n: number): Uint8Array {
    const { data, chunk } = payload;
    return data.subarray(n * chunk, Math.min(data.length, (n + 1) * chunk));
  }

  /** Answers the PUT of chunk `n`, checking its bytes. */
  async function acceptChunk(n: number, id = UPLOAD_ID): Promise<void> {
    const req = await next('PUT', chunkUrl(n, id));
    expect(req.request.headers.get('Content-Type')).toBe('application/octet-stream');
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    const body = new Uint8Array(req.request.body as ArrayBuffer);
    const expected = chunkBytes(n);
    expect(body.length).toBe(expected.length);
    if (payload.real) {
      expect(body[0]).toBe(expected[0]);
      expect(body[body.length - 1]).toBe(expected[expected.length - 1]);
    } else {
      expect(Array.from(body)).toEqual(Array.from(expected));
    }
    req.flush(null, { status: 204, statusText: 'No Content' });
  }

  async function complete(id = UPLOAD_ID): Promise<TestRequest> {
    return next('POST', `/api/v1/uploads/${id}/complete`);
  }

  describe('small files', () => {
    beforeEach(() => setup());

    it('sends a file up to 50 MB in one multipart request with progress', async () => {
      const file = new File(['Fictitious receipt'], 'receipt.txt', { type: 'text/plain' });
      const uploaded: FileResponse[] = [];
      service.uploaded.subscribe((f) => uploaded.push(f));

      const [job] = service.upload([file], FOLDER);
      expect(service.jobs()).toEqual([job]);
      expect(service.active()).toBe(1);
      const req = await next('POST', '/api/v1/files');
      expect(job.state()).toBe('uploading');
      expect(req.request.params.get('name')).toBe('receipt.txt');
      expect(req.request.params.get('folderId')).toBe(FOLDER);
      expect(req.request.reportProgress).toBe(true);
      expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
      const body = req.request.body as FormData;
      expect((body.get('file') as File).name).toBe('receipt.txt');

      req.event({ type: HttpEventType.UploadProgress, loaded: 9, total: 18 });
      expect(job.loaded()).toBe(9);
      expect(job.percent()).toBe(50);
      // The multipart overhead can exceed the file size: progress stops at the file size.
      req.event({ type: HttpEventType.UploadProgress, loaded: 400, total: 400 });
      expect(job.loaded()).toBe(file.size);

      const result = stored('receipt.txt');
      req.flush(result);
      await until(job, 'done');
      expect(job.result()).toEqual(result);
      expect(job.percent()).toBe(100);
      expect(service.active()).toBe(0);
      expect(uploaded).toEqual([result]);
    });

    it('uploads to the drive root without a folderId', async () => {
      const [job] = service.upload([new File(['x'], 'a.txt')], null);
      const req = await next('POST', '/api/v1/files');
      expect(req.request.params.has('folderId')).toBe(false);
      req.flush(stored('a.txt'));
      await until(job, 'done');
    });

    it('does nothing for an empty selection', () => {
      expect(service.upload([], FOLDER)).toEqual([]);
      expect(service.jobs()).toEqual([]);
    });

    it(`runs ${PARALLEL_UPLOADS} uploads at a time and starts the next when one finishes`, async () => {
      const jobs = service.upload(
        ['a.txt', 'b.txt', 'c.txt'].map((name) => new File(['x'], name)),
        FOLDER,
      );
      expect(jobs.map((j) => j.state())).toEqual(['uploading', 'uploading', 'queued']);
      expect(service.active()).toBe(3);
      await new Promise((resolve) => setTimeout(resolve, 20));
      const open = http.match((r) => r.url === '/api/v1/files');
      expect(open.map((r) => r.request.params.get('name'))).toEqual(['a.txt', 'b.txt']);

      open[0].flush(stored('a.txt'));
      await until(jobs[0], 'done');
      const third = await next('POST', '/api/v1/files');
      expect(third.request.params.get('name')).toBe('c.txt');
      third.flush(stored('c.txt'));
      open[1].flush(stored('b.txt'));
      await until(jobs[1], 'done');
      await until(jobs[2], 'done');
      expect(service.active()).toBe(0);
    });

    it('shows a failed upload with the server message, and retry sends it again', async () => {
      const [job] = service.upload([new File(['x'], 'big.txt')], FOLDER);
      (await next('POST', '/api/v1/files')).flush(
        problem(413, 'Payload Too Large', 'The file is larger than the drive allows.'),
        { status: 413, statusText: 'Payload Too Large' },
      );
      await until(job, 'failed');
      expect(job.error()).toBe('The file is larger than the drive allows.');
      expect(job.finished()).toBe(true);
      expect(service.active()).toBe(0);

      service.retry(job);
      // Queued again and started at once: no other upload is running.
      expect(job.state()).toBe('uploading');
      expect(job.error()).toBeNull();
      (await next('POST', '/api/v1/files')).flush(stored('big.txt'));
      await until(job, 'done');
    });

    it('a network failure of a small file fails it without automatic retries', async () => {
      const [job] = service.upload([new File(['x'], 'a.txt')], FOLDER);
      (await next('POST', '/api/v1/files')).error(new ProgressEvent('error'));
      await until(job, 'failed');
      expect(job.error()).toBeTruthy();
      http.expectNone('/api/v1/files?name=a.txt');
    });

    it('cancel stops a running request; a cancelled job ignores retry', async () => {
      const [job] = service.upload([new File(['x'], 'a.txt')], FOLDER);
      const req = await next('POST', '/api/v1/files');
      service.cancel(job);
      expect(req.cancelled).toBe(true);
      expect(job.state()).toBe('cancelled');
      expect(service.active()).toBe(0);
      service.retry(job);
      expect(job.state()).toBe('cancelled');
      // Nothing to discard on the server for a multipart upload.
      http.expectNone((r) => r.method === 'DELETE');
    });

    it('cancel of a queued upload means it is never sent', async () => {
      const jobs = service.upload(
        ['a.txt', 'b.txt', 'c.txt'].map((name) => new File(['x'], name)),
        FOLDER,
      );
      service.cancel(jobs[2]);
      expect(jobs[2].state()).toBe('cancelled');
      (await next('POST', '/api/v1/files', 'a.txt')).flush(stored('a.txt'));
      (await next('POST', '/api/v1/files', 'b.txt')).flush(stored('b.txt'));
      await until(jobs[0], 'done');
      await until(jobs[1], 'done');
      http.expectNone('/api/v1/files?name=c.txt');
    });

    it('cancelling a running upload starts the next queued one', async () => {
      const jobs = service.upload(
        ['a.txt', 'b.txt', 'c.txt'].map((name) => new File(['x'], name)),
        FOLDER,
      );
      const a = await next('POST', '/api/v1/files', 'a.txt');
      service.cancel(jobs[0]);
      expect(a.cancelled).toBe(true);
      (await next('POST', '/api/v1/files', 'c.txt')).flush(stored('c.txt'));
      (await next('POST', '/api/v1/files', 'b.txt')).flush(stored('b.txt'));
      await until(jobs[1], 'done');
      await until(jobs[2], 'done');
      expect(jobs[0].state()).toBe('cancelled');
    });

    it('cancel of a done upload changes nothing', async () => {
      const [job] = service.upload([new File(['x'], 'a.txt')], FOLDER);
      (await next('POST', '/api/v1/files')).flush(stored('a.txt'));
      await until(job, 'done');
      service.cancel(job);
      expect(job.state()).toBe('done');
    });

    it('dismiss removes a job (cancelling it), clearFinished keeps running and failed ones', async () => {
      const jobs = service.upload(
        ['done.txt', 'failed.txt', 'running.txt', 'gone.txt'].map((n) => new File(['x'], n)),
        FOLDER,
      );
      (await next('POST', '/api/v1/files', 'done.txt')).flush(stored('done.txt'));
      (await next('POST', '/api/v1/files', 'failed.txt')).flush(problem(400, 'Bad Request'), {
        status: 400,
        statusText: 'Bad Request',
      });
      await until(jobs[0], 'done');
      await until(jobs[1], 'failed');
      const running = await next('POST', '/api/v1/files', 'running.txt');
      const gone = await next('POST', '/api/v1/files', 'gone.txt');

      service.dismiss(jobs[3]);
      expect(gone.cancelled).toBe(true);
      expect(jobs[3].state()).toBe('cancelled');
      expect(service.jobs()).not.toContain(jobs[3]);

      service.clearFinished();
      expect(service.jobs()).toEqual([jobs[1], jobs[2]]);
      running.flush(stored('running.txt'));
      await until(jobs[2], 'done');
    });
  });

  describe('chunked files', () => {
    beforeEach(() => setup());

    it('creates a resumable upload, sends every chunk and completes with the real SHA-256', async () => {
      payload = REAL;
      waitTimeout = REAL_FILE_TIMEOUT;
      const CHUNK = REAL.chunk;
      const uploaded: FileResponse[] = [];
      service.uploaded.subscribe((f) => uploaded.push(f));
      const [job] = service.upload([bigFile()], FOLDER);
      expect(job.chunked).toBe(true);

      const create = await next('POST', '/api/v1/uploads');
      expect(create.request.body).toEqual({
        name: 'holiday.mp4',
        folderId: FOLDER,
        size: BIG_SIZE,
        mime: 'video/mp4',
      });
      create.flush(session());
      expect(session().chunkCount).toBe(3);

      const first = await next('PUT', chunkUrl(0));
      first.event({ type: HttpEventType.UploadProgress, loaded: 5 * MB, total: CHUNK });
      expect(job.loaded()).toBe(5 * MB);
      first.flush(null, { status: 204, statusText: 'No Content' });
      await acceptChunk(1);
      const last = await next('PUT', chunkUrl(2));
      last.event({ type: HttpEventType.UploadProgress, loaded: MB, total: MB });
      expect(job.loaded()).toBe(2 * CHUNK + MB);
      last.flush(null, { status: 204, statusText: 'No Content' });

      const done = await complete();
      expect(done.request.body).toEqual({ sha256: REAL.digest });
      const result = stored('holiday.mp4');
      done.flush(result);
      await until(job, 'done');
      expect(job.result()).toEqual(result);
      expect(job.uploadId).toBeNull();
      expect(uploaded).toEqual([result]);
    }, REAL_FILE_TIMEOUT);

    it('leaves out an unknown mime type when creating the upload', async () => {
      const [job] = service.upload([bigFile('backup.bin', '')], null);
      const create = await next('POST', '/api/v1/uploads');
      expect(create.request.body).toEqual({ name: 'backup.bin', folderId: null, size: BIG_SIZE });
      service.cancel(job);
      create.flush(session());
      (await next('DELETE', `/api/v1/uploads/${UPLOAD_ID}`)).flush(null, {
        status: 204,
        statusText: 'No Content',
      });
    });

    it.each<[string, (req: TestRequest) => void]>([
      ['a 503', (req) => req.flush(null, { status: 503, statusText: 'Service Unavailable' })],
      ['a 500', (req) => req.flush(null, { status: 500, statusText: 'Server Error' })],
      ['a network error', (req) => req.error(new ProgressEvent('error'))],
      ['a 408', (req) => req.flush(null, { status: 408, statusText: 'Request Timeout' })],
      ['a 429', (req) => req.flush(null, { status: 429, statusText: 'Too Many Requests' })],
    ])('retries a chunk after %s and completes the upload', async (_, fail) => {
      const [job] = service.upload([bigFile()], FOLDER);
      (await next('POST', '/api/v1/uploads')).flush(session());
      await acceptChunk(0);
      const failing = await next('PUT', chunkUrl(1));
      failing.event({ type: HttpEventType.UploadProgress, loaded: 700, total: payload.chunk });
      expect(job.loaded()).toBe(payload.chunk + 700);
      fail(failing);
      // The progress of the failed attempt is taken back.
      await vi.waitFor(() => expect(job.loaded()).toBe(payload.chunk));
      expect(job.state()).toBe('uploading');
      await acceptChunk(1);
      await acceptChunk(2);
      const done = await complete();
      expect(done.request.body).toEqual({ sha256: payload.digest });
      done.flush(stored('holiday.mp4'));
      await until(job, 'done');
    });

    it('retries a chunk up to the configured number of times, mixing failure kinds', async () => {
      const [job] = service.upload([bigFile()], FOLDER);
      (await next('POST', '/api/v1/uploads')).flush(session());
      (await next('PUT', chunkUrl(0))).flush(null, { status: 502, statusText: 'Bad Gateway' });
      (await next('PUT', chunkUrl(0))).error(new ProgressEvent('error'));
      (await next('PUT', chunkUrl(0))).flush(null, { status: 504, statusText: 'Timeout' });
      await acceptChunk(0);
      await acceptChunk(1);
      await acceptChunk(2);
      (await complete()).flush(stored('holiday.mp4'));
      await until(job, 'done');
    });

    it('fails after the last retry and keeps the upload for a resume', async () => {
      const [job] = service.upload([bigFile()], FOLDER);
      (await next('POST', '/api/v1/uploads')).flush(session());
      for (let attempt = 0; attempt < 4; attempt++) {
        (await next('PUT', chunkUrl(0))).flush(problem(503, 'Service Unavailable', 'Try later'), {
          status: 503,
          statusText: 'Service Unavailable',
        });
      }
      await until(job, 'failed');
      expect(job.error()).toBe('Try later');
      expect(job.uploadId).toBe(UPLOAD_ID);
      http.expectNone(chunkUrl(0));
      http.expectNone((r) => r.method === 'DELETE');
    });

    it.each([400, 403, 404, 409, 413])(
      'does not retry a chunk that fails with %i',
      async (status) => {
        const [job] = service.upload([bigFile()], FOLDER);
        (await next('POST', '/api/v1/uploads')).flush(session());
        await acceptChunk(0);
        (await next('PUT', chunkUrl(1))).flush(problem(status, 'Refused', `Chunk refused`), {
          status,
          statusText: 'Refused',
        });
        await until(job, 'failed');
        expect(job.error()).toBe('Chunk refused');
        http.expectNone(chunkUrl(1));
        http.expectNone(chunkUrl(2));
      },
    );

    it('retry resumes with the chunks the server is missing and the hash of the whole file', async () => {
      const [job] = service.upload([bigFile()], FOLDER);
      (await next('POST', '/api/v1/uploads')).flush(session());
      await acceptChunk(0);
      (await next('PUT', chunkUrl(1))).flush(problem(400, 'Bad Request', 'Broken chunk'), {
        status: 400,
        statusText: 'Bad Request',
      });
      await until(job, 'failed');

      service.retry(job);
      const resume = await next('GET', `/api/v1/uploads/${UPLOAD_ID}`);
      expect(resume.request.context.get(SILENT_ERRORS)).toBe(true);
      // The server got chunks 0 and 2 (e.g. from an earlier tab): only 1 is sent again.
      resume.flush(session([0, 2]));
      await acceptChunk(1);
      const done = await complete();
      http.expectNone(chunkUrl(0));
      http.expectNone(chunkUrl(2));
      // The hash covers the chunks that were not sent again, too.
      expect(done.request.body).toEqual({ sha256: payload.digest });
      done.flush(stored('holiday.mp4'));
      await until(job, 'done');
      expect(job.loaded()).toBe(BIG_SIZE);
    });

    it('retry starts a new upload when the old one expired (404)', async () => {
      const [job] = service.upload([bigFile()], FOLDER);
      (await next('POST', '/api/v1/uploads')).flush(session());
      (await next('PUT', chunkUrl(0))).flush(problem(400, 'Bad Request'), {
        status: 400,
        statusText: 'Bad Request',
      });
      await until(job, 'failed');

      service.retry(job);
      (await next('GET', `/api/v1/uploads/${UPLOAD_ID}`)).flush(problem(404, 'Not Found'), {
        status: 404,
        statusText: 'Not Found',
      });
      const second = '0192f0c4-0000-7000-8000-0000000000c2';
      (await next('POST', '/api/v1/uploads')).flush(session([], second));
      await acceptChunk(0, second);
      expect(job.uploadId).toBe(second);
      await acceptChunk(1, second);
      await acceptChunk(2, second);
      const done = await complete(second);
      expect(done.request.body).toEqual({ sha256: payload.digest });
      done.flush(stored('holiday.mp4'));
      await until(job, 'done');
    });

    it('a failing resume lookup (not 404) fails the retry again', async () => {
      const [job] = service.upload([bigFile()], FOLDER);
      (await next('POST', '/api/v1/uploads')).flush(session());
      (await next('PUT', chunkUrl(0))).flush(problem(400, 'Bad Request'), {
        status: 400,
        statusText: 'Bad Request',
      });
      await until(job, 'failed');
      service.retry(job);
      (await next('GET', `/api/v1/uploads/${UPLOAD_ID}`)).flush(
        problem(500, 'Server Error', 'Down'),
        { status: 500, statusText: 'Server Error' },
      );
      await until(job, 'failed');
      expect(job.error()).toBe('Down');
      expect(job.uploadId).toBe(UPLOAD_ID);
      http.expectNone('/api/v1/uploads');
    });

    it('cancel during the resume lookup stops it and DELETEs the upload', async () => {
      const [job] = service.upload([bigFile()], FOLDER);
      (await next('POST', '/api/v1/uploads')).flush(session());
      (await next('PUT', chunkUrl(0))).flush(problem(400, 'Bad Request'), {
        status: 400,
        statusText: 'Bad Request',
      });
      await until(job, 'failed');
      service.retry(job);
      const lookup = await next('GET', `/api/v1/uploads/${UPLOAD_ID}`);

      service.cancel(job);
      expect(lookup.cancelled).toBe(true);
      (await next('DELETE', `/api/v1/uploads/${UPLOAD_ID}`)).flush(null, {
        status: 204,
        statusText: 'No Content',
      });
      await new Promise((resolve) => setTimeout(resolve, 20));
      http.expectNone(chunkUrl(0));
      http.expectNone('/api/v1/uploads');
      expect(job.state()).toBe('cancelled');
      expect(job.error()).toBeNull();
    });

    it('a file that can no longer be read fails with a generic message and keeps the upload', async () => {
      const file = bigFile();
      // E.g. the file was deleted or changed on disk after it was picked.
      vi.spyOn(file, 'slice').mockImplementation(
        () =>
          ({
            arrayBuffer: () =>
              Promise.reject(new DOMException('The file could not be read.', 'NotReadableError')),
          }) as unknown as Blob,
      );
      const [job] = service.upload([file], FOLDER);
      (await next('POST', '/api/v1/uploads')).flush(session());
      await until(job, 'failed');
      expect(job.error()).toBe('The upload failed.');
      expect(job.uploadId).toBe(UPLOAD_ID);
      http.expectNone(chunkUrl(0));
      http.expectNone((r) => r.method === 'DELETE');
    });

    it('a failed complete (checksum mismatch) fails the upload', async () => {
      const [job] = service.upload([bigFile()], FOLDER);
      (await next('POST', '/api/v1/uploads')).flush(session());
      await acceptChunk(0);
      await acceptChunk(1);
      await acceptChunk(2);
      (await complete()).flush(problem(422, 'Unprocessable', 'Checksum mismatch'), {
        status: 422,
        statusText: 'Unprocessable',
      });
      await until(job, 'failed');
      expect(job.error()).toBe('Checksum mismatch');
    });

    it('cancel aborts the running chunk and DELETEs the upload on the server', async () => {
      const [job] = service.upload([bigFile()], FOLDER);
      (await next('POST', '/api/v1/uploads')).flush(session());
      await acceptChunk(0);
      const running = await next('PUT', chunkUrl(1));

      service.cancel(job);
      expect(running.cancelled).toBe(true);
      expect(job.state()).toBe('cancelled');
      const del = http.expectOne(
        (r) => r.method === 'DELETE' && r.url === `/api/v1/uploads/${UPLOAD_ID}`,
      );
      expect(del.request.context.get(SILENT_ERRORS)).toBe(true);
      del.flush(null, { status: 204, statusText: 'No Content' });
      expect(job.uploadId).toBeNull();
      // No further chunk nor the complete is sent.
      await new Promise((resolve) => setTimeout(resolve, 20));
      http.expectNone(chunkUrl(2));
      http.expectNone(`/api/v1/uploads/${UPLOAD_ID}/complete`);
      expect(job.state()).toBe('cancelled');
    });

    it('cancel between two chunks sends no further chunk and DELETEs the upload', async () => {
      const [job] = service.upload([bigFile()], FOLDER);
      (await next('POST', '/api/v1/uploads')).flush(session());
      (await next('PUT', chunkUrl(0))).flush(null, { status: 204, statusText: 'No Content' });
      // Chunk 0 is stored; the service is now reading chunk 1 from the file.
      service.cancel(job);
      expect(job.state()).toBe('cancelled');
      http
        .expectOne((r) => r.method === 'DELETE' && r.url === `/api/v1/uploads/${UPLOAD_ID}`)
        .flush(null, { status: 204, statusText: 'No Content' });
      await new Promise((resolve) => setTimeout(resolve, 20));
      http.expectNone(chunkUrl(1));
      http.expectNone(`/api/v1/uploads/${UPLOAD_ID}/complete`);
      expect(job.state()).toBe('cancelled');
      expect(job.error()).toBeNull();
    });

    it('cancel of a failed chunked upload discards it; a failing DELETE is ignored', async () => {
      const [job] = service.upload([bigFile()], FOLDER);
      (await next('POST', '/api/v1/uploads')).flush(session());
      (await next('PUT', chunkUrl(0))).flush(problem(400, 'Bad Request'), {
        status: 400,
        statusText: 'Bad Request',
      });
      await until(job, 'failed');
      service.cancel(job);
      expect(job.state()).toBe('cancelled');
      expect(job.error()).toBeNull();
      http
        .expectOne((r) => r.method === 'DELETE' && r.url === `/api/v1/uploads/${UPLOAD_ID}`)
        .flush(null, { status: 500, statusText: 'Server Error' });
      expect(job.state()).toBe('cancelled');
    });

    it('cancel while the upload is being created lets it finish, then DELETEs it', async () => {
      const [job] = service.upload([bigFile()], FOLDER);
      const create = await next('POST', '/api/v1/uploads');
      service.cancel(job);
      // The server may already have created it: the request is not aborted.
      expect(create.cancelled).toBe(false);
      expect(job.state()).toBe('cancelled');
      http.expectNone((r) => r.method === 'DELETE');

      create.flush(session());
      const del = await next('DELETE', `/api/v1/uploads/${UPLOAD_ID}`);
      expect(del.request.context.get(SILENT_ERRORS)).toBe(true);
      del.flush(null, { status: 204, statusText: 'No Content' });
      expect(job.uploadId).toBeNull();
      await new Promise((resolve) => setTimeout(resolve, 20));
      http.expectNone(chunkUrl(0));
      expect(job.state()).toBe('cancelled');
    });

    it('a cancel while the upload is being created, which then fails, leaves nothing behind', async () => {
      const [job] = service.upload([bigFile()], FOLDER);
      const create = await next('POST', '/api/v1/uploads');
      service.cancel(job);
      create.flush(problem(507, 'Insufficient Storage'), {
        status: 507,
        statusText: 'Insufficient Storage',
      });
      await new Promise((resolve) => setTimeout(resolve, 20));
      http.expectNone((r) => r.method === 'DELETE');
      expect(job.state()).toBe('cancelled');
      expect(job.error()).toBeNull();
    });

    it('cancel while the upload is being completed lets it finish: the file counts as uploaded', async () => {
      const uploaded: FileResponse[] = [];
      service.uploaded.subscribe((f) => uploaded.push(f));
      const [job] = service.upload([bigFile()], FOLDER);
      (await next('POST', '/api/v1/uploads')).flush(session());
      await acceptChunk(0);
      await acceptChunk(1);
      await acceptChunk(2);
      const done = await complete();
      service.cancel(job);
      expect(done.cancelled).toBe(false);
      // No DELETE: it could no longer abort a stored file.
      http.expectNone((r) => r.method === 'DELETE');

      const result = stored('holiday.mp4');
      done.flush(result);
      await until(job, 'done');
      expect(job.result()).toEqual(result);
      expect(job.uploadId).toBeNull();
      expect(uploaded).toEqual([result]);
      http.expectNone((r) => r.method === 'DELETE');
    });

    it('cancel while the upload is being completed, which then fails, DELETEs the upload', async () => {
      const [job] = service.upload([bigFile()], FOLDER);
      (await next('POST', '/api/v1/uploads')).flush(session());
      await acceptChunk(0);
      await acceptChunk(1);
      await acceptChunk(2);
      const done = await complete();
      service.cancel(job);
      done.flush(problem(422, 'Unprocessable', 'Checksum mismatch'), {
        status: 422,
        statusText: 'Unprocessable',
      });
      (await next('DELETE', `/api/v1/uploads/${UPLOAD_ID}`)).flush(null, {
        status: 204,
        statusText: 'No Content',
      });
      expect(job.state()).toBe('cancelled');
      expect(job.error()).toBeNull();
    });
  });

  describe('chunked files with real retry pauses', () => {
    beforeEach(() => setup([60_000]));

    it('cancel during the pause before a retry stops the retry and DELETEs the upload', async () => {
      const [job] = service.upload([bigFile()], FOLDER);
      (await next('POST', '/api/v1/uploads')).flush(session());
      (await next('PUT', chunkUrl(0))).flush(null, { status: 503, statusText: 'Unavailable' });
      // Now waiting 60 s before the retry.
      await new Promise((resolve) => setTimeout(resolve, 20));
      http.expectNone(chunkUrl(0));
      expect(job.state()).toBe('uploading');

      service.cancel(job);
      expect(job.state()).toBe('cancelled');
      http
        .expectOne((r) => r.method === 'DELETE' && r.url === `/api/v1/uploads/${UPLOAD_ID}`)
        .flush(null, { status: 204, statusText: 'No Content' });
      await new Promise((resolve) => setTimeout(resolve, 20));
      http.expectNone(chunkUrl(0));
    });

    it('waits the configured pause before retrying a chunk', async () => {
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
      try {
        const [job] = service.upload([bigFile()], FOLDER);
        const nextFake = async (method: string, url: string) => {
          for (let i = 0; i < 200; i++) {
            const found = http.match((r) => r.method === method && r.url === url);
            if (found.length) return found[0];
            await vi.advanceTimersByTimeAsync(0);
            await new Promise<void>((resolve) => queueMicrotask(resolve));
            await job.file.slice(0, 1).arrayBuffer();
          }
          throw new Error(`no ${method} ${url}`);
        };
        (await nextFake('POST', '/api/v1/uploads')).flush(session());
        (await nextFake('PUT', chunkUrl(0))).flush(null, {
          status: 503,
          statusText: 'Unavailable',
        });
        await vi.advanceTimersByTimeAsync(59_000);
        http.expectNone(chunkUrl(0));
        await vi.advanceTimersByTimeAsync(1_000);
        const retried = await nextFake('PUT', chunkUrl(0));
        expect(retried).toBeDefined();
        service.cancel(job);
        http
          .expectOne((r) => r.method === 'DELETE')
          .flush(null, { status: 204, statusText: 'No Content' });
      } finally {
        vi.useRealTimers();
      }
    });
  });
});
