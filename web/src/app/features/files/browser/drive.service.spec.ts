import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { FileResponse, FolderSummary } from '../../../api/generated';
import { ApiError, NotificationService, provideHttp, SILENT_ERRORS } from '../../../core/http';
import { DRIVE_PAGE_SIZE, DriveEntry, DriveService, toEntry } from './drive.service';

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));
const STAMP = '2026-10-20T10:00:00Z';

const A = '0192f0c4-0000-7000-8000-0000000000a1';
const B = '0192f0c4-0000-7000-8000-0000000000b1';
const C = '0192f0c4-0000-7000-8000-0000000000c1';
const F = '0192f0c4-0000-7000-8000-0000000000f1';

function folder(id: string, name: string, parentId?: string): FolderSummary {
  return { id, name, parentId, version: 2, createdAt: STAMP, updatedAt: STAMP };
}

function file(id: string, name: string): FileResponse {
  return {
    id,
    name,
    size: 10,
    mime: 'text/plain',
    sha256: 'cd'.repeat(32),
    version: 7,
    createdAt: STAMP,
    updatedAt: STAMP,
  };
}

const folderEntry = (f: FolderSummary): DriveEntry => ({
  kind: 'folder',
  id: f.id,
  name: f.name,
  version: f.version,
  folder: f,
});
const fileEntry = (f: FileResponse): DriveEntry => ({
  kind: 'file',
  id: f.id,
  name: f.name,
  version: f.version,
  file: f,
});

describe('toEntry', () => {
  it('maps folders and files and drops entries without their payload', () => {
    const f = folder(A, 'Alpha');
    expect(toEntry({ type: 'FOLDER', folder: f })).toEqual(folderEntry(f));
    const x = file(F, 'x.txt');
    expect(toEntry({ type: 'FILE', file: x })).toEqual(fileEntry(x));
    expect(toEntry({ type: 'FOLDER' })).toBeNull();
    expect(toEntry({ type: 'FILE', folder: f })).toBeNull();
  });
});

describe('DriveService', () => {
  let service: DriveService;
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
    service = TestBed.inject(DriveService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  /** The one pending listing request, after the service had time to send it. */
  async function listing() {
    await tick();
    return http.expectOne((req) => req.url === '/api/v1/drive');
  }

  it('lists the root without a folderId, with the page size and silent errors', async () => {
    const page = service.list(null);
    const req = await listing();
    expect(req.request.method).toBe('GET');
    expect(req.request.params.has('folderId')).toBe(false);
    expect(req.request.params.has('cursor')).toBe(false);
    expect(req.request.params.get('limit')).toBe(String(DRIVE_PAGE_SIZE));
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    const f = folder(A, 'Alpha');
    req.flush({ items: [{ type: 'FOLDER', folder: f }, { type: 'FILE' }] });

    expect(await page).toEqual({ entries: [folderEntry(f)], nextCursor: null });
  });

  it('lists a folder from a cursor and returns the next cursor', async () => {
    const page = service.list(A, 'cur-1');
    const req = await listing();
    expect(req.request.params.get('folderId')).toBe(A);
    expect(req.request.params.get('cursor')).toBe('cur-1');
    req.flush({ items: [], nextCursor: 'cur-2' });

    expect(await page).toEqual({ entries: [], nextCursor: 'cur-2' });
  });

  it('follows the cursor for subfolders while pages hold only folders', async () => {
    const result = service.subfolders(A);
    (await listing()).flush({
      items: [{ type: 'FOLDER', folder: folder(B, 'Beta', A) }],
      nextCursor: 'p2',
    });
    const second = await listing();
    expect(second.request.params.get('cursor')).toBe('p2');
    second.flush({ items: [{ type: 'FOLDER', folder: folder(C, 'Gamma', A) }] });

    expect((await result).map((f) => f.name)).toEqual(['Beta', 'Gamma']);
  });

  it('stops paging subfolders at the first page that contains a file', async () => {
    const result = service.subfolders(null);
    (await listing()).flush({
      items: [
        { type: 'FOLDER', folder: folder(B, 'Beta') },
        { type: 'FILE', file: file(F, 'a.txt') },
      ],
      nextCursor: 'more-files',
    });

    expect((await result).map((f) => f.id)).toEqual([B]);
    // No request for the next page: afterEach verifies none is pending.
  });

  it('rejects subfolders with the ApiError of a failed page, without a toast', async () => {
    const result = service.subfolders(A);
    (await listing()).flush(
      { status: 404, title: 'Not Found', detail: 'Folder not found' },
      { status: 404, statusText: 'Not Found' },
    );

    const error = await result.catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).userMessage).toBe('Folder not found');
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('gives only the root crumb for the drive root without a request', async () => {
    expect(await service.crumbs(null)).toEqual([{ id: null, name: 'My drive' }]);
  });

  it('walks up the parents for the crumbs, root first', async () => {
    const crumbs = service.crumbs(C);
    await tick();
    http.expectOne(`/api/v1/folders/${C}`).flush({ ...folder(C, 'Gamma', B), path: '/x' });
    await tick();
    http.expectOne(`/api/v1/folders/${B}`).flush({ ...folder(B, 'Beta', A), path: '/x' });
    await tick();
    http.expectOne(`/api/v1/folders/${A}`).flush({ ...folder(A, 'Alpha'), path: '/x' });

    expect(await crumbs).toEqual([
      { id: null, name: 'My drive' },
      { id: A, name: 'Alpha' },
      { id: B, name: 'Beta' },
      { id: C, name: 'Gamma' },
    ]);
  });

  it('stops walking up at a parent cycle instead of looping', async () => {
    const crumbs = service.crumbs(A);
    await tick();
    http.expectOne(`/api/v1/folders/${A}`).flush({ ...folder(A, 'Alpha', B), path: '/x' });
    await tick();
    http.expectOne(`/api/v1/folders/${B}`).flush({ ...folder(B, 'Beta', A), path: '/x' });

    expect(await crumbs).toEqual([
      { id: null, name: 'My drive' },
      { id: B, name: 'Beta' },
      { id: A, name: 'Alpha' },
    ]);
  });

  it('creates a folder in a parent, or in the root with parentId null', async () => {
    const inParent = service.createFolder('Taxes', A);
    await tick();
    const post = http.expectOne({ method: 'POST', url: '/api/v1/folders' });
    expect(post.request.body).toEqual({ name: 'Taxes', parentId: A });
    post.flush({ ...folder(B, 'Taxes', A), path: '/x' });
    expect((await inParent).id).toBe(B);

    const inRoot = service.createFolder('Receipts', null);
    await tick();
    const rootPost = http.expectOne({ method: 'POST', url: '/api/v1/folders' });
    expect(rootPost.request.body).toEqual({ name: 'Receipts', parentId: null });
    rootPost.flush({ ...folder(C, 'Receipts'), path: '/x' });
    await inRoot;
  });

  it('renames a folder and a file with the version as quoted If-Match', async () => {
    const f = folderEntry(folder(A, 'Alpha'));
    const renamedFolder = service.rename(f, 'Alpha 2');
    await tick();
    const patchFolder = http.expectOne({ method: 'PATCH', url: `/api/v1/folders/${A}` });
    expect(patchFolder.request.headers.get('If-Match')).toBe('"2"');
    expect(patchFolder.request.body).toEqual({ name: 'Alpha 2' });
    patchFolder.flush({ ...folder(A, 'Alpha 2'), path: '/x' });
    await renamedFolder;

    const renamedFile = service.rename(fileEntry(file(F, 'a.txt')), 'b.txt');
    await tick();
    const patchFile = http.expectOne({ method: 'PATCH', url: `/api/v1/files/${F}` });
    expect(patchFile.request.headers.get('If-Match')).toBe('"7"');
    expect(patchFile.request.body).toEqual({ name: 'b.txt' });
    patchFile.flush(file(F, 'b.txt'));
    await renamedFile;
  });

  it('moves a folder by parentId and a file by folderId', async () => {
    const movedFolder = service.move(folderEntry(folder(B, 'Beta')), A);
    await tick();
    const patchFolder = http.expectOne({ method: 'PATCH', url: `/api/v1/folders/${B}` });
    expect(patchFolder.request.headers.get('If-Match')).toBe('"2"');
    expect(patchFolder.request.body).toEqual({ parentId: A });
    patchFolder.flush({ ...folder(B, 'Beta', A), path: '/x' });
    await movedFolder;

    const movedFile = service.move(fileEntry(file(F, 'a.txt')), null);
    await tick();
    const patchFile = http.expectOne({ method: 'PATCH', url: `/api/v1/files/${F}` });
    expect(patchFile.request.headers.get('If-Match')).toBe('"7"');
    expect(patchFile.request.body).toEqual({ folderId: null });
    patchFile.flush(file(F, 'a.txt'));
    await movedFile;
  });

  it('deletes a folder and a file with If-Match and rejects a conflict silently', async () => {
    const deletedFolder = service.delete(folderEntry(folder(A, 'Alpha')));
    await tick();
    const delFolder = http.expectOne({ method: 'DELETE', url: `/api/v1/folders/${A}` });
    expect(delFolder.request.headers.get('If-Match')).toBe('"2"');
    delFolder.flush(null, { status: 204, statusText: 'No Content' });
    await deletedFolder;

    const deletedFile = service.delete(fileEntry(file(F, 'a.txt')));
    await tick();
    const delFile = http.expectOne({ method: 'DELETE', url: `/api/v1/files/${F}` });
    expect(delFile.request.headers.get('If-Match')).toBe('"7"');
    expect(delFile.request.context.get(SILENT_ERRORS)).toBe(true);
    delFile.flush(
      { status: 412, title: 'Precondition Failed' },
      { status: 412, statusText: 'Precondition Failed' },
    );
    const error = await deletedFile.catch((e: unknown) => e);
    expect((error as ApiError).status).toBe(412);
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('loads the usage', async () => {
    const usage = service.usage();
    await tick();
    const req = http.expectOne({ method: 'GET', url: '/api/v1/drive/usage' });
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush({ fileCount: 1, usedBytes: 10 });
    expect((await usage).fileCount).toBe(1);
  });

  it('builds download URLs for a file and a folder zip, encoding the id', () => {
    expect(service.downloadUrl({ kind: 'file', id: F })).toBe(`/api/v1/files/${F}/content`);
    expect(service.downloadUrl({ kind: 'folder', id: A })).toBe(`/api/v1/folders/${A}/zip`);
    expect(service.downloadUrl({ kind: 'file', id: 'a/b c' })).toBe(
      '/api/v1/files/a%2Fb%20c/content',
    );
  });
});
