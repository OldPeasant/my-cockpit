import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NoteResponse } from '../../../api/generated';
import { NotificationService, provideHttp, SILENT_ERRORS } from '../../../core/http';
import { NoteEditorService } from './note-editor.service';

const ID = '0192f0c4-0000-7000-8000-000000000001';
const FILE_ID = '0192f0c4-0000-7000-8000-0000000000f1';
const URL = `/api/v1/notes/${ID}`;

function note(fields: Partial<NoteResponse> = {}): NoteResponse {
  return {
    id: ID,
    title: 'Packing list',
    displayTitle: 'Packing list',
    bodyMd: 'Passport',
    tags: [],
    pinned: false,
    archived: false,
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-01T08:00:00Z',
    version: 7,
    ...fields,
  };
}

describe('NoteEditorService', () => {
  let service: NoteEditorService;
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
    service = TestBed.inject(NoteEditorService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads a note without toasting errors', async () => {
    const loaded = service.get(ID);
    const req = http.expectOne((r) => r.method === 'GET' && r.url === URL);
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush(note());
    expect(await loaded).toEqual(note());
  });

  it('saves title and body with the version as a quoted If-Match', async () => {
    const saved = service.save(note({ version: 7 }), { title: null, bodyMd: 'Charger' });
    const req = http.expectOne((r) => r.method === 'PATCH' && r.url === URL);
    expect(req.request.headers.get('If-Match')).toBe('"7"');
    expect(req.request.body).toEqual({ title: null, bodyMd: 'Charger' });
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush(note({ title: undefined, bodyMd: 'Charger', version: 8 }));
    expect((await saved).version).toBe(8);
  });

  it('rejects a save of a note changed elsewhere and does not toast it', async () => {
    const saved = service.save(note(), { title: 'Packing list', bodyMd: 'mine' });
    http
      .expectOne((r) => r.method === 'PATCH')
      .flush(
        { type: 'about:blank', title: 'Precondition Failed', status: 412 },
        { status: 412, statusText: 'Precondition Failed' },
      );
    await expect(saved).rejects.toMatchObject({ status: 412 });
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('uploads an image as multipart form data to the note', async () => {
    const file = new File(['x'], 'tent.png', { type: 'image/png' });
    const uploaded = service.uploadImage(ID, file);
    const req = http.expectOne((r) => r.method === 'POST' && r.url === `${URL}/images`);
    expect(req.request.body).toBeInstanceOf(FormData);
    expect((req.request.body as FormData).get('file')).toBeInstanceOf(File);
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush({
      fileId: FILE_ID,
      markdown: `![tent.png](cockpit-file:${FILE_ID})`,
      reference: `cockpit-file:${FILE_ID}`,
      mime: 'image/png',
      name: 'tent.png',
      size: 1,
    });
    expect((await uploaded).markdown).toBe(`![tent.png](cockpit-file:${FILE_ID})`);
  });

  it('builds the authenticated preview URL of a file', () => {
    expect(service.fileUrl(FILE_ID)).toBe(`/api/v1/files/${FILE_ID}/preview`);
    expect(service.fileUrl('a/b')).toBe('/api/v1/files/a%2Fb/preview');
  });

  it('lists the revisions of a note', async () => {
    const revisions = service.revisions(ID);
    const req = http.expectOne((r) => r.method === 'GET' && r.url === `${URL}/revisions`);
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    const item = {
      number: 3,
      bodyMd: 'old',
      savedAt: '2026-09-01T08:00:00Z',
      replacedAt: '2026-09-02T08:00:00Z',
    };
    req.flush({ items: [item] });
    expect(await revisions).toEqual([item]);
  });

  it('restores a revision with the version as If-Match', async () => {
    const restored = service.restore(note({ version: 4 }), 2);
    const req = http.expectOne(
      (r) => r.method === 'POST' && r.url === `${URL}/revisions/2/restore`,
    );
    expect(req.request.headers.get('If-Match')).toBe('"4"');
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush(note({ bodyMd: 'old', version: 5 }));
    expect((await restored).bodyMd).toBe('old');
  });
});
