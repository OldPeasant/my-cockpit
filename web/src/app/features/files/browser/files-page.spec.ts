import { HttpRequest } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { ActivatedRoute, convertToParamMap, ParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { FileResponse, FolderSummary } from '../../../api/generated';
import { NotificationService, provideHttp } from '../../../core/http';
import FilesPage, { formatBytes, VIEW_MODE_KEY } from './files-page';

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));
const STAMP = '2026-10-20T10:00:00Z';

const DOCS = '0192f0c4-0000-7000-8000-00000000000d';
const PHOTOS = '0192f0c4-0000-7000-8000-00000000000e';
const TRIP = '0192f0c4-0000-7000-8000-00000000000f';
const ATTACHMENTS = '0192f0c4-0000-7000-8000-00000000000a';
const REPORT = '0192f0c4-0000-7000-8000-0000000000f1';

interface Problem {
  status: number;
  title: string;
  detail?: string;
}

/** An in-memory drive answering the requests of the page, with switches for failures. */
class FakeDrive {
  folders = new Map<string, FolderSummary>();
  files = new Map<string, FileResponse>();
  /** Answers the next request whose `METHOD url` contains the key with this problem. */
  failures = new Map<string, Problem>();
  requests: HttpRequest<unknown>[] = [];
  /** Entries per listing page (cursor: the offset); unset: everything on one page. */
  pageSize?: number;
  private seq = 100;

  folder(id: string, name: string, parentId?: string, special?: FolderSummary['special']): void {
    this.folders.set(id, {
      id,
      name,
      parentId,
      special,
      version: 0,
      createdAt: STAMP,
      updatedAt: STAMP,
    });
  }

  file(id: string, name: string, size: number, folderId?: string): void {
    this.files.set(id, {
      id,
      name,
      size,
      folderId,
      mime: 'application/pdf',
      sha256: 'ab'.repeat(32),
      version: 3,
      createdAt: STAMP,
      updatedAt: STAMP,
    });
  }

  answer(
    http: HttpTestingController,
    only: (req: HttpRequest<unknown>) => boolean = () => true,
  ): number {
    const pending = http.match(only);
    for (const req of pending) {
      this.requests.push(req.request);
      const key = `${req.request.method} ${req.request.urlWithParams}`;
      const failure = [...this.failures].find(([needle]) => key.includes(needle));
      if (failure) {
        this.failures.delete(failure[0]);
        const problem = failure[1];
        req.flush(problem, { status: problem.status, statusText: problem.title });
        continue;
      }
      const result = this.handle(req.request);
      if (result === null) req.flush(null, { status: 204, statusText: 'No Content' });
      else req.flush(result);
    }
    return pending.length;
  }

  private handle(req: HttpRequest<unknown>): object | null {
    const url = req.url;
    const body = req.body as Record<string, unknown> | null;
    if (url === '/api/v1/drive/usage') {
      return {
        fileCount: this.files.size,
        folderCount: this.folders.size,
        maxFileSize: 5 * 1024 ** 3,
        totalSpaceBytes: 100 * 1024 ** 3,
        usableSpaceBytes: 75 * 1024 ** 3,
        usedBytes: [...this.files.values()].reduce((sum, f) => sum + f.size, 0),
        trashBytes: 2048,
        trashFileCount: 1,
      };
    }
    if (url === '/api/v1/drive') {
      const parent = req.params.get('folderId') ?? undefined;
      const folders = [...this.folders.values()]
        .filter((f) => f.parentId === parent)
        .sort((a, b) => a.name.localeCompare(b.name))
        .map((folder) => ({ type: 'FOLDER', folder }));
      const files = [...this.files.values()]
        .filter((f) => f.folderId === parent)
        .sort((a, b) => a.name.localeCompare(b.name))
        .map((file) => ({ type: 'FILE', file }));
      const items = [...folders, ...files];
      if (!this.pageSize) return { items };
      const start = Number(req.params.get('cursor') ?? 0);
      const end = start + this.pageSize;
      return {
        items: items.slice(start, end),
        nextCursor: end < items.length ? String(end) : undefined,
      };
    }
    if (url === '/api/v1/folders' && req.method === 'POST') {
      const id = `0192f0c4-0000-7000-8000-000000000${++this.seq}`;
      this.folder(id, body?.['name'] as string, (body?.['parentId'] as string | null) ?? undefined);
      return { ...this.folders.get(id), path: '/x' };
    }
    const folderMatch = /^\/api\/v1\/folders\/([^/]+)$/.exec(url);
    if (folderMatch) {
      const folder = this.folders.get(folderMatch[1]);
      if (!folder) throw new Error(`unknown folder ${folderMatch[1]}`);
      if (req.method === 'DELETE') {
        this.folders.delete(folder.id);
        return null;
      }
      if (req.method === 'PATCH') {
        const next = { ...folder, version: folder.version + 1 };
        if (body && 'name' in body) next.name = body['name'] as string;
        if (body && 'parentId' in body)
          next.parentId = (body['parentId'] as string | null) ?? undefined;
        this.folders.set(folder.id, next);
        return { ...next, path: '/x' };
      }
      return { ...folder, path: '/x' };
    }
    const fileMatch = /^\/api\/v1\/files\/([^/]+)$/.exec(url);
    if (fileMatch) {
      const file = this.files.get(fileMatch[1]);
      if (!file) throw new Error(`unknown file ${fileMatch[1]}`);
      if (req.method === 'DELETE') {
        this.files.delete(file.id);
        return null;
      }
      const next = { ...file, version: file.version + 1 };
      if (body && 'name' in body) next.name = body['name'] as string;
      if (body && 'folderId' in body)
        next.folderId = (body['folderId'] as string | null) ?? undefined;
      this.files.set(file.id, next);
      return next;
    }
    throw new Error(`unexpected request ${req.method} ${req.urlWithParams}`);
  }
}

describe('formatBytes', () => {
  it('uses binary units with at most one decimal below 100', () => {
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(1023)).toBe('1023 B');
    expect(formatBytes(1536)).toBe('1.5 KB');
    expect(formatBytes(150 * 1024 * 1024)).toBe('150 MB');
    expect(formatBytes(5 * 1024 ** 4)).toBe('5 TB');
    expect(formatBytes(75 * 1024 ** 3)).toBe('75 GB');
  });
});

describe('FilesPage', () => {
  let fixture: ComponentFixture<FilesPage>;
  let root: HTMLElement;
  let http: HttpTestingController;
  let drive: FakeDrive;
  let params: BehaviorSubject<ParamMap>;
  let notifications: { error: ReturnType<typeof vi.fn>; info: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    localStorage.removeItem(VIEW_MODE_KEY);
    drive = new FakeDrive();
    drive.folder(ATTACHMENTS, 'Attachments', undefined, 'ATTACHMENTS');
    drive.folder(DOCS, 'Documents');
    drive.folder(PHOTOS, 'Photos');
    drive.folder(TRIP, 'Trip 2026', DOCS);
    drive.file(REPORT, 'Report.pdf', 1536, DOCS);
  });

  afterEach(() => {
    TestBed.inject(MatDialog).closeAll();
    http.verify();
  });

  /** Answers requests until none are left. */
  async function settle(): Promise<void> {
    for (let round = 0; round < 20; round++) {
      await tick();
      await fixture.whenStable();
      if (drive.answer(http) === 0) {
        await tick();
        if (http.match(() => true).length === 0) {
          fixture.detectChanges();
          return;
        }
      }
    }
  }

  async function open(folderId: string | null = null): Promise<void> {
    notifications = { error: vi.fn(), info: vi.fn() };
    params = new BehaviorSubject(convertToParamMap(folderId ? { folderId } : {}));
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap: params } },
        { provide: NotificationService, useValue: notifications },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(FilesPage);
    root = fixture.nativeElement as HTMLElement;
    await settle();
  }

  function names(): string[] {
    return [...root.querySelectorAll('.entries .name')].map((el) => el.textContent?.trim() ?? '');
  }

  function crumbs(): string[] {
    return [...root.querySelectorAll('.crumbs li')].map((el) =>
      (el.querySelector('a, h1')?.textContent ?? '').trim(),
    );
  }

  function byLabel(label: string): HTMLElement {
    return root.querySelector(`[aria-label="${label}"]`) as HTMLElement;
  }

  function button(text: string, scope: ParentNode = root): HTMLButtonElement {
    return [...scope.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === text,
    ) as HTMLButtonElement;
  }

  async function menu(name: string, action: string): Promise<void> {
    byLabel(`Actions for ${name}`).click();
    await settle();
    const item = [...document.querySelectorAll<HTMLElement>('.mat-mdc-menu-item')].find(
      (el) => el.textContent?.trim() === action,
    );
    if (!item) throw new Error(`no menu item ${action}`);
    item.click();
    await settle();
  }

  async function dialog(selector: string): Promise<HTMLElement> {
    let found: HTMLElement | null = null;
    await vi.waitFor(() => {
      found = document.querySelector<HTMLElement>(selector);
      if (!found) throw new Error(`no ${selector}`);
    });
    await settle();
    return found as unknown as HTMLElement;
  }

  async function typeName(dialogEl: HTMLElement, value: string): Promise<void> {
    const input = dialogEl.querySelector('input') as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    (dialogEl.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await settle();
  }

  async function closed(selector: string): Promise<void> {
    await vi.waitFor(() => {
      drive.answer(http);
      if (document.querySelector(selector)) throw new Error(`${selector} still open`);
    });
    await settle();
  }

  it('lists the root folders first, then files, with crumbs, tree and usage', async () => {
    drive.file('0192f0c4-0000-7000-8000-0000000000f2', 'Notes.txt', 10);
    await open();

    expect(names()).toEqual(['Attachments', 'Documents', 'Photos', 'Notes.txt']);
    expect(crumbs()).toEqual(['My drive']);
    expect(root.querySelector('h1')?.textContent?.trim()).toBe('My drive');
    expect(
      [...root.querySelectorAll('app-folder-tree .name')].map((a) => a.textContent?.trim()),
    ).toEqual(['My drive', 'Attachments', 'Documents', 'Photos']);
    const usage = root.querySelector('.usage')?.textContent ?? '';
    expect(usage).toContain('1.5 KB in 2 files');
    expect(usage).toContain('75 GB free of 100 GB');
    expect(root.querySelector('[role="meter"]')?.getAttribute('aria-valuenow')).toBe('25');
    // No zip download of the whole drive.
    expect(root.querySelector('.toolbar a[download]')).toBeNull();
  });

  it('shows a folder with breadcrumbs, its tree branch expanded and download links', async () => {
    await open(TRIP);

    expect(crumbs()).toEqual(['My drive', 'Documents', 'Trip 2026']);
    expect(names()).toEqual([]);
    expect(root.querySelector('.empty')?.textContent).toContain('This folder is empty');
    expect(root.querySelector('.toolbar a[download]')?.getAttribute('href')).toBe(
      `/api/v1/folders/${TRIP}/zip`,
    );
    const current = root.querySelector('app-folder-tree .row.current .name');
    expect(current?.textContent?.trim()).toBe('Trip 2026');
    expect(byLabel('Collapse Documents').getAttribute('aria-expanded')).toBe('true');

    params.next(convertToParamMap({ folderId: DOCS }));
    await settle();
    expect(crumbs()).toEqual(['My drive', 'Documents']);
    expect(names()).toEqual(['Trip 2026', 'Report.pdf']);
    const file = root.querySelector('.entry[data-kind="file"]') as HTMLElement;
    expect(file.querySelector('a.open')?.getAttribute('href')).toBe(
      `/api/v1/files/${REPORT}/content`,
    );
    expect(file.querySelector('.size')?.textContent?.trim()).toBe('1.5 KB');
  });

  it('switches between list and grid and remembers the choice', async () => {
    await open();
    expect(root.querySelector('.entries')?.classList).not.toContain('grid');

    button('Grid').click();
    await settle();
    expect(root.querySelector('.entries')?.classList).toContain('grid');
    expect(button('Grid').getAttribute('aria-pressed')).toBe('true');
    expect(localStorage.getItem(VIEW_MODE_KEY)).toBe('grid');
  });

  it('creates a folder in the shown folder', async () => {
    await open(DOCS);

    button('New folder').click();
    const el = await dialog('app-files-name-dialog');
    await typeName(el, '  Taxes  ');
    await closed('app-files-name-dialog');

    const post = drive.requests.find((r) => r.method === 'POST');
    expect(post?.body).toEqual({ name: 'Taxes', parentId: DOCS });
    expect(names()).toEqual(['Taxes', 'Trip 2026', 'Report.pdf']);
  });

  it('keeps the name dialog open with the server error, e.g. a taken name', async () => {
    await open();
    drive.failures.set('POST /api/v1/folders', {
      status: 409,
      title: 'Conflict',
      detail: 'A folder named Photos already exists',
    });

    button('New folder').click();
    const el = await dialog('app-files-name-dialog');
    await typeName(el, 'Photos');

    expect(el.querySelector('.error')?.textContent).toContain('already exists');
    expect(document.querySelector('app-files-name-dialog')).not.toBeNull();
  });

  it('rejects names the server would refuse before sending them', async () => {
    await open();

    button('New folder').click();
    const el = await dialog('app-files-name-dialog');
    await typeName(el, 'a/b');
    expect(el.textContent).toContain('cannot contain');
    await typeName(el, '   ');
    expect(el.textContent).toContain('Enter a name.');
    expect(drive.requests.some((r) => r.method === 'POST')).toBe(false);
  });

  it('renames a file with its version as If-Match', async () => {
    await open(DOCS);

    await menu('Report.pdf', 'Rename');
    const el = await dialog('app-files-name-dialog');
    expect((el.querySelector('input') as HTMLInputElement).value).toBe('Report.pdf');
    await typeName(el, 'Report 2026.pdf');
    await closed('app-files-name-dialog');

    const patch = drive.requests.find((r) => r.method === 'PATCH');
    expect(patch?.url).toBe(`/api/v1/files/${REPORT}`);
    expect(patch?.headers.get('If-Match')).toBe('"3"');
    expect(patch?.body).toEqual({ name: 'Report 2026.pdf' });
    expect(names()).toEqual(['Trip 2026', 'Report 2026.pdf']);
  });

  it('moves a folder through the picker, which cannot open the folder itself', async () => {
    await open();

    await menu('Documents', 'Move');
    const el = await dialog('app-files-move-dialog');
    const own = button('Documents', el);
    expect(own.disabled).toBe(true);
    expect(button('Move here', el).disabled).toBe(true);

    button('Photos', el).click();
    await settle();
    expect(el.querySelector('[aria-current="location"]')?.textContent?.trim()).toBe('Photos');
    button('Move here', el).click();
    await closed('app-files-move-dialog');

    const patch = drive.requests.find((r) => r.method === 'PATCH');
    expect(patch?.url).toBe(`/api/v1/folders/${DOCS}`);
    expect(patch?.headers.get('If-Match')).toBe('"0"');
    expect(patch?.body).toEqual({ parentId: PHOTOS });
    expect(names()).toEqual(['Attachments', 'Photos']);
    expect(notifications.info).toHaveBeenCalledWith('Moved “Documents”');
  });

  it('moves a file to the drive root', async () => {
    await open(DOCS);

    await menu('Report.pdf', 'Move');
    const el = await dialog('app-files-move-dialog');
    button('My drive', el).click();
    await settle();
    button('Move here', el).click();
    await closed('app-files-move-dialog');

    const patch = drive.requests.find((r) => r.method === 'PATCH');
    expect(patch?.body).toEqual({ folderId: null });
    expect(names()).toEqual(['Trip 2026']);
  });

  it('shows a failed move in the picker', async () => {
    await open();
    drive.failures.set('PATCH', { status: 409, title: 'Conflict', detail: 'Name taken' });

    await menu('Documents', 'Move');
    const el = await dialog('app-files-move-dialog');
    button('Photos', el).click();
    await settle();
    button('Move here', el).click();
    await settle();

    expect(el.querySelector('[role="alert"]')?.textContent).toContain('Name taken');
  });

  it('deletes to the Trash and refreshes the list, tree and usage', async () => {
    await open();
    const before = drive.requests.filter((r) => r.url === '/api/v1/drive/usage').length;

    await menu('Photos', 'Delete');

    expect(drive.requests.find((r) => r.method === 'DELETE')?.url).toBe(
      `/api/v1/folders/${PHOTOS}`,
    );
    expect(names()).toEqual(['Attachments', 'Documents']);
    expect(
      [...root.querySelectorAll('app-folder-tree .name')].map((a) => a.textContent?.trim()),
    ).not.toContain('Photos');
    expect(drive.requests.filter((r) => r.url === '/api/v1/drive/usage').length).toBe(before + 1);
    expect(notifications.info).toHaveBeenCalledWith('Moved “Photos” to the Trash');
  });

  it('shows a failed delete next to the list', async () => {
    await open();
    drive.failures.set('DELETE', {
      status: 412,
      title: 'Precondition Failed',
      detail: 'Changed elsewhere',
    });

    await menu('Photos', 'Delete');

    expect(root.querySelector('.error[role="alert"]')?.textContent).toContain(
      '“Photos” could not be changed: Changed elsewhere',
    );
    expect(names()).toContain('Photos');
  });

  it('offers only the download for special folders', async () => {
    await open();

    byLabel('Actions for Attachments').click();
    await settle();
    const items = [...document.querySelectorAll('.mat-mdc-menu-item')].map((el) =>
      el.textContent?.trim(),
    );
    expect(items).toEqual(['Download as zip']);
  });

  it('shows a load error with a retry', async () => {
    drive.failures.set(`GET /api/v1/folders/${DOCS}`, {
      status: 404,
      title: 'Not Found',
      detail: 'Folder not found',
    });
    await open(DOCS);

    expect(root.querySelector('.load-error')?.textContent).toContain('Folder not found');
    // The page keeps its heading, which names the section, while it has no crumbs.
    expect(root.querySelector('h1#files-title')?.textContent?.trim()).toBe('Files');
    expect(root.querySelector('section[aria-labelledby="files-title"]')).not.toBeNull();
    button('Try again').click();
    await settle();
    expect(root.querySelector('.load-error')).toBeNull();
    expect(names()).toEqual(['Trip 2026', 'Report.pdf']);
    expect(root.querySelectorAll('#files-title')).toHaveLength(1);
    expect(root.querySelector('h1#files-title')?.textContent?.trim()).toBe('Documents');
  });

  it('loads more entries page by page and hides "Show more" after the last page', async () => {
    drive.file('0192f0c4-0000-7000-8000-0000000000f2', 'Notes.txt', 10);
    drive.pageSize = 2;
    await open();
    expect(names()).toEqual(['Attachments', 'Documents']);

    button('Show more').click();
    await settle();
    expect(names()).toEqual(['Attachments', 'Documents', 'Photos', 'Notes.txt']);
    expect(button('Show more')).toBeUndefined();
    // The page and the folder tree (whose first page held only folders) both asked for page 2.
    const more = drive.requests.filter((r) => r.params.get('cursor') === '2');
    expect(more.length).toBeGreaterThan(0);
    expect(more.every((r) => !r.params.has('folderId'))).toBe(true);
  });

  it('keeps the loaded entries and shows the error when "Show more" fails, then retries', async () => {
    drive.file('0192f0c4-0000-7000-8000-0000000000f2', 'Notes.txt', 10);
    drive.pageSize = 2;
    await open();
    drive.failures.set('cursor=2', { status: 503, title: 'Unavailable', detail: 'Try later' });

    button('Show more').click();
    await settle();
    expect(names()).toEqual(['Attachments', 'Documents']);
    expect(root.querySelector('.more-entries .error')?.textContent?.trim()).toBe('Try later');
    expect(button('Show more').disabled).toBe(false);

    button('Show more').click();
    await settle();
    expect(root.querySelector('.more-entries')).toBeNull();
    expect(names()).toEqual(['Attachments', 'Documents', 'Photos', 'Notes.txt']);
  });

  it('reloads without an error when the deleted entry was already gone (404)', async () => {
    await open();
    drive.folders.delete(PHOTOS);
    drive.failures.set('DELETE', { status: 404, title: 'Not Found', detail: 'Folder not found' });

    await menu('Photos', 'Delete');

    expect(root.querySelector('.error[role="alert"]')).toBeNull();
    expect(names()).toEqual(['Attachments', 'Documents']);
    expect(notifications.info).not.toHaveBeenCalled();
  });

  it('disables the actions of an entry while its delete runs', async () => {
    await open();
    byLabel('Actions for Photos').click();
    await settle();
    const item = [...document.querySelectorAll<HTMLElement>('.mat-mdc-menu-item')].find(
      (el) => el.textContent?.trim() === 'Delete',
    ) as HTMLElement;
    item.click();
    await tick();
    fixture.detectChanges();

    const entry = byLabel('Actions for Photos').closest('.entry') as HTMLElement;
    expect(entry.classList).toContain('busy');
    expect((byLabel('Actions for Photos') as HTMLButtonElement).disabled).toBe(true);
    await settle();
    expect(drive.requests.filter((r) => r.method === 'DELETE')).toHaveLength(1);
    expect(names()).toEqual(['Attachments', 'Documents']);
  });

  it('shows the newest folder when an older folder load answers last', async () => {
    await open();
    params.next(convertToParamMap({ folderId: DOCS }));
    await tick();
    params.next(convertToParamMap({ folderId: PHOTOS }));
    await tick();

    // The newer load (Photos) answers first, the older one (Documents) afterwards.
    drive.answer(http, (req) => req.urlWithParams.includes(PHOTOS));
    await settle();

    expect(crumbs()).toEqual(['My drive', 'Photos']);
    expect(root.querySelector('.empty')).not.toBeNull();
    expect(names()).toEqual([]);
  });

  it('ignores a failure of an older folder load after a newer one succeeded', async () => {
    await open();
    drive.failures.set(`folderId=${DOCS}`, { status: 500, title: 'Server error' });
    params.next(convertToParamMap({ folderId: DOCS }));
    await tick();
    params.next(convertToParamMap({ folderId: PHOTOS }));
    await tick();

    drive.answer(http, (req) => req.urlWithParams.includes(PHOTOS));
    await settle();

    expect(root.querySelector('.load-error')).toBeNull();
    expect(crumbs()).toEqual(['My drive', 'Photos']);
  });

  it('does not change anything when the move dialog is cancelled', async () => {
    await open();

    await menu('Documents', 'Move');
    const el = await dialog('app-files-move-dialog');
    const before = drive.requests.length;
    button('Cancel', el).click();
    await closed('app-files-move-dialog');

    expect(drive.requests.some((r) => r.method === 'PATCH')).toBe(false);
    expect(notifications.info).not.toHaveBeenCalled();
    // Nothing is reloaded: no listing, tree or usage request after the cancel.
    expect(drive.requests).toHaveLength(before);
  });

  it('lists every subfolder in the move picker when they span several pages', async () => {
    drive.pageSize = 2;
    await open(DOCS);

    await menu('Report.pdf', 'Move');
    const el = await dialog('app-files-move-dialog');
    button('My drive', el).click();
    await settle();

    expect([...el.querySelectorAll('.folder span')].map((n) => n.textContent?.trim())).toEqual([
      'Attachments',
      'Documents',
      'Photos',
    ]);
    const rootPages = drive.requests.filter(
      (r) => r.url === '/api/v1/drive' && !r.params.has('folderId'),
    );
    expect(rootPages.map((r) => r.params.get('cursor'))).toContain('2');
  });

  it('drops entries a "Show more" page repeats after the listing shifted', async () => {
    drive.file('0192f0c4-0000-7000-8000-0000000000f2', 'Notes.txt', 10);
    drive.pageSize = 2;
    await open();
    expect(names()).toEqual(['Attachments', 'Documents']);

    // A folder created elsewhere sorts first: the next page now starts with Documents again.
    drive.folder('0192f0c4-0000-7000-8000-000000000099', 'Archive');
    button('Show more').click();
    await settle();
    expect(names()).toEqual(['Attachments', 'Documents', 'Photos']);

    button('Show more').click();
    await settle();
    expect(names()).toEqual(['Attachments', 'Documents', 'Photos', 'Notes.txt']);
    expect(button('Show more')).toBeUndefined();
  });

  it('keeps the move dialog open until a running move has answered', async () => {
    await open();

    await menu('Documents', 'Move');
    const el = await dialog('app-files-move-dialog');
    button('Photos', el).click();
    await settle();
    button('Move here', el).click();
    await tick();
    TestBed.tick();

    // The PATCH is still running: neither Cancel nor Escape can close the dialog.
    expect(button('Cancel', el).disabled).toBe(true);
    el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', keyCode: 27, bubbles: true }));
    await tick();
    expect(document.querySelector('app-files-move-dialog')).not.toBeNull();

    await closed('app-files-move-dialog');
    expect(drive.requests.filter((r) => r.method === 'PATCH')).toHaveLength(1);
    expect(notifications.info).toHaveBeenCalledWith('Moved “Documents”');
    expect(names()).toEqual(['Attachments', 'Photos']);
  });

  it('shows the newest usage when an older usage load answers last', async () => {
    await open();
    const isUsage = (req: HttpRequest<unknown>): boolean => req.url === '/api/v1/drive/usage';

    /** Deletes an entry and answers everything it reloads except the usage. */
    async function deleteKeepingUsage(name: string): Promise<void> {
      byLabel(`Actions for ${name}`).click();
      await tick();
      fixture.detectChanges();
      const items = [...document.querySelectorAll<HTMLElement>('.mat-mdc-menu-item')].filter(
        (el) => el.textContent?.trim() === 'Delete',
      );
      items.at(-1)?.click();
      for (let round = 0; round < 10; round++) {
        await tick();
        drive.answer(http, (req) => !isUsage(req));
      }
      fixture.detectChanges();
    }

    await deleteKeepingUsage('Photos');
    await deleteKeepingUsage('Documents');
    const pending = http.match(isUsage);
    expect(pending).toHaveLength(2);
    const [older, newer] = pending;
    const usage = (fileCount: number, usedBytes: number) => ({
      fileCount,
      folderCount: 1,
      maxFileSize: 1024 ** 3,
      totalSpaceBytes: 100 * 1024 ** 3,
      usableSpaceBytes: 75 * 1024 ** 3,
      usedBytes,
      trashBytes: 0,
      trashFileCount: 0,
    });

    newer.flush(usage(1, 1536));
    await tick();
    older.flush(usage(7, 9000));
    await settle();

    expect(root.querySelector('.usage')?.textContent).toContain('1.5 KB in 1 file');
  });

  it('shows in the move dialog when its folders cannot be loaded', async () => {
    await open();
    drive.failures.set('GET /api/v1/drive?', {
      status: 503,
      title: 'Unavailable',
      detail: 'Drive offline',
    });

    await menu('Documents', 'Move');
    const el = await dialog('app-files-move-dialog');

    expect(el.querySelector('[role="alert"]')?.textContent).toContain('Drive offline');
    expect(el.querySelector('.folders')).toBeNull();
  });

  it('falls back to the list view when the storage is unavailable', async () => {
    const get = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('denied');
    });
    const set = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('denied');
    });
    try {
      await open();
      expect(root.querySelector('.entries')?.classList).not.toContain('grid');

      button('Grid').click();
      await settle();
      expect(root.querySelector('.entries')?.classList).toContain('grid');
    } finally {
      get.mockRestore();
      set.mockRestore();
    }
  });

  it('opens in the grid view when it was chosen before', async () => {
    localStorage.setItem(VIEW_MODE_KEY, 'grid');
    await open();
    expect(root.querySelector('.entries')?.classList).toContain('grid');
    expect(button('List').getAttribute('aria-pressed')).toBe('false');

    button('List').click();
    await settle();
    expect(root.querySelector('.entries')?.classList).not.toContain('grid');
    expect(localStorage.getItem(VIEW_MODE_KEY)).toBe('list');
  });

  it('drops a "Show more" page that answers after navigating to another folder', async () => {
    drive.file('0192f0c4-0000-7000-8000-0000000000f2', 'Notes.txt', 10);
    drive.pageSize = 2;
    await open();

    button('Show more').click();
    await tick();
    params.next(convertToParamMap({ folderId: DOCS }));
    await tick();
    // The new folder answers first, the old root page 2 afterwards.
    drive.answer(http, (req) => req.urlWithParams.includes(DOCS));
    await settle();

    expect(crumbs()).toEqual(['My drive', 'Documents']);
    expect(names()).toEqual(['Trip 2026', 'Report.pdf']);
    expect(root.querySelector('.more-entries')).toBeNull();
  });

  it('shows the newest picker folder when an older picker load answers last', async () => {
    await open(DOCS);

    await menu('Report.pdf', 'Move');
    const el = await dialog('app-files-move-dialog');
    expect(el.querySelector('[aria-current="location"]')?.textContent?.trim()).toBe('Documents');

    // Open Trip 2026, and go back to the root before Trip answers.
    button('Trip 2026', el).click();
    await tick();
    button('My drive', el).click();
    await tick();
    drive.answer(http, (req) => !req.urlWithParams.includes(TRIP));
    await settle();

    expect(el.querySelector('[aria-current="location"]')?.textContent?.trim()).toBe('My drive');
    expect([...el.querySelectorAll('.folder span')].map((n) => n.textContent?.trim())).toEqual([
      'Attachments',
      'Documents',
      'Photos',
    ]);
  });

  it('hides the usage when it cannot be loaded', async () => {
    drive.failures.set('/api/v1/drive/usage', { status: 503, title: 'Unavailable' });
    await open();

    expect(root.querySelector('.usage')).toBeNull();
    expect(names()).toHaveLength(3);
  });

  it('does not change anything when the rename dialog is cancelled', async () => {
    await open(DOCS);

    await menu('Report.pdf', 'Rename');
    const el = await dialog('app-files-name-dialog');
    const before = drive.requests.length;
    button('Cancel', el).click();
    await closed('app-files-name-dialog');

    expect(drive.requests.some((r) => r.method === 'PATCH')).toBe(false);
    expect(notifications.info).not.toHaveBeenCalled();
    // Nothing is reloaded: no listing, tree or usage request after the cancel.
    expect(drive.requests).toHaveLength(before);
    expect(names()).toEqual(['Trip 2026', 'Report.pdf']);
  });

  it('ignores a "Show more" failure that answers after navigating to another folder', async () => {
    drive.file('0192f0c4-0000-7000-8000-0000000000f2', 'Notes.txt', 10);
    drive.pageSize = 2;
    await open();
    drive.failures.set('cursor=2', { status: 503, title: 'Unavailable', detail: 'Try later' });

    button('Show more').click();
    await tick();
    params.next(convertToParamMap({ folderId: DOCS }));
    await tick();
    // The new folder answers first, the old root page 2 fails afterwards.
    drive.answer(http, (req) => req.urlWithParams.includes(DOCS));
    await settle();

    expect(drive.requests.some((r) => r.params.get('cursor') === '2')).toBe(true);
    expect(crumbs()).toEqual(['My drive', 'Documents']);
    expect(names()).toEqual(['Trip 2026', 'Report.pdf']);
    expect(root.querySelector('.more-entries')).toBeNull();
    expect(root.textContent).not.toContain('Try later');
  });

  it('ignores a picker load failure that answers after a newer picker load', async () => {
    await open(DOCS);

    await menu('Report.pdf', 'Move');
    const el = await dialog('app-files-move-dialog');
    drive.failures.set(`folderId=${TRIP}`, {
      status: 503,
      title: 'Unavailable',
      detail: 'Trip offline',
    });

    // Open Trip 2026, and go back to the root before Trip answers (with a failure).
    button('Trip 2026', el).click();
    await tick();
    button('My drive', el).click();
    await tick();
    drive.answer(http, (req) => !req.urlWithParams.includes(TRIP));
    await settle();

    expect(drive.requests.some((r) => r.urlWithParams.includes(`folderId=${TRIP}`))).toBe(true);
    expect(el.querySelector('[role="alert"]')).toBeNull();
    expect(el.querySelector('[aria-current="location"]')?.textContent?.trim()).toBe('My drive');
    expect([...el.querySelectorAll('.folder span')].map((n) => n.textContent?.trim())).toEqual([
      'Attachments',
      'Documents',
      'Photos',
    ]);
  });
});
