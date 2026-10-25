import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ApiError, NotificationService, provideHttp, SILENT_ERRORS } from '../../core/http';
import { JournalService } from './journal.service';
import { journalEntry } from './testing';

const ENTRIES = '/api/v1/journal/entries';

describe('JournalService', () => {
  let service: JournalService;
  let http: HttpTestingController;
  let notifications: { error: ReturnType<typeof vi.fn>; info: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    notifications = { error: vi.fn(), info: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: notifications },
      ],
    });
    service = TestBed.inject(JournalService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('gets the entry of a date without toasting errors', async () => {
    const entry = service.get('2026-10-25');
    const req = http.expectOne((r) => r.method === 'GET' && r.url === `${ENTRIES}/2026-10-25`);
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush(journalEntry('2026-10-25'));
    await expect(entry).resolves.toEqual(journalEntry('2026-10-25'));
  });

  it('returns null for a date without an entry (404)', async () => {
    const entry = service.get('2026-10-24');
    http
      .expectOne(`${ENTRIES}/2026-10-24`)
      .flush(
        { title: 'Not Found', status: 404, detail: 'No entry.' },
        { status: 404, statusText: 'Not Found' },
      );
    await expect(entry).resolves.toBeNull();
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('rethrows other errors of a get', async () => {
    const entry = service.get('2026-10-24');
    http
      .expectOne(`${ENTRIES}/2026-10-24`)
      .flush(
        { title: 'Bad Gateway', status: 502, detail: 'Try again later.' },
        { status: 502, statusText: 'Bad Gateway' },
      );
    await expect(entry).rejects.toBeInstanceOf(ApiError);
    await expect(entry).rejects.toMatchObject({ status: 502 });
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('creates the entry of a date with a PUT without If-Match', async () => {
    const tags = ['lake'];
    const saved = service.save('2026-10-25', null, { bodyMd: 'Swim', mood: 4, tags });
    const req = http.expectOne((r) => r.method === 'PUT' && r.url === `${ENTRIES}/2026-10-25`);
    expect(req.request.headers.has('If-Match')).toBe(false);
    expect(req.request.body).toEqual({ bodyMd: 'Swim', mood: 4, tags: ['lake'] });
    expect(req.request.body.tags).not.toBe(tags);
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush(journalEntry('2026-10-25', { bodyMd: 'Swim', mood: 4, tags: ['lake'] }), {
      status: 201,
      statusText: 'Created',
    });
    await expect(saved).resolves.toMatchObject({ bodyMd: 'Swim', mood: 4 });
  });

  it('replaces an existing entry with its version as If-Match, and sends a null mood', async () => {
    const saved = service.save('2026-10-25', journalEntry('2026-10-25', { version: 3 }), {
      bodyMd: 'Swim twice',
      mood: null,
      tags: [],
    });
    const req = http.expectOne((r) => r.method === 'PUT' && r.url === `${ENTRIES}/2026-10-25`);
    expect(req.request.headers.get('If-Match')).toBe('"3"');
    expect(req.request.body).toEqual({ bodyMd: 'Swim twice', mood: null, tags: [] });
    req.flush(journalEntry('2026-10-25', { bodyMd: 'Swim twice', version: 4 }));
    await expect(saved).resolves.toMatchObject({ version: 4 });
  });

  it('uploads an image as multipart form data', async () => {
    const file = new File(['x'], 'lake.png', { type: 'image/png' });
    const uploaded = service.uploadImage(file);
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/journal/images');
    expect(req.request.body).toBeInstanceOf(FormData);
    expect((req.request.body as FormData).get('file')).toBeInstanceOf(File);
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    const image = {
      fileId: '0192f0c4-0000-7000-8000-0000000000f1',
      markdown: '![lake.png](cockpit-file:0192f0c4-0000-7000-8000-0000000000f1)',
      reference: 'cockpit-file:0192f0c4-0000-7000-8000-0000000000f1',
      mime: 'image/png',
      name: 'lake.png',
      size: 1,
    };
    req.flush(image, { status: 201, statusText: 'Created' });
    await expect(uploaded).resolves.toEqual(image);
  });

  it('loads the heatmap of the server default range or of a given range', async () => {
    const defaults = service.heatmap();
    const first = http.expectOne((r) => r.url === '/api/v1/journal/heatmap');
    expect(first.request.params.keys()).toEqual([]);
    expect(first.request.context.get(SILENT_ERRORS)).toBe(true);
    first.flush({ from: '2025-10-26', to: '2026-10-25', items: [] });
    await expect(defaults).resolves.toMatchObject({ to: '2026-10-25' });

    const ranged = service.heatmap({ from: '2026-01-01', to: '2026-01-31' });
    const second = http.expectOne((r) => r.url === '/api/v1/journal/heatmap');
    expect(second.request.params.get('from')).toBe('2026-01-01');
    expect(second.request.params.get('to')).toBe('2026-01-31');
    second.flush({ from: '2026-01-01', to: '2026-01-31', items: [{ date: '2026-01-05' }] });
    await expect(ranged).resolves.toMatchObject({ items: [{ date: '2026-01-05' }] });
  });

  it('loads "on this day" for today or a given date', async () => {
    const today = service.onThisDay();
    const first = http.expectOne((r) => r.url === '/api/v1/journal/on-this-day');
    expect(first.request.params.has('date')).toBe(false);
    expect(first.request.context.get(SILENT_ERRORS)).toBe(true);
    first.flush({ date: '2026-10-25', items: [journalEntry('2025-10-25')] });
    await expect(today).resolves.toMatchObject({ items: [{ date: '2025-10-25' }] });

    const other = service.onThisDay('2026-03-01');
    const second = http.expectOne((r) => r.url === '/api/v1/journal/on-this-day');
    expect(second.request.params.get('date')).toBe('2026-03-01');
    second.flush({ date: '2026-03-01', items: [] });
    await expect(other).resolves.toEqual({ date: '2026-03-01', items: [] });
  });
});
