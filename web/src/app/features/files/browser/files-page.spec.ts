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

  answer(http: HttpTestingController): number {
    const pending = http.match(() => true);
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
      return { items: [...folders, ...files] };
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
    button('Try again').click();
    await settle();
    expect(root.querySelector('.load-error')).toBeNull();
    expect(names()).toEqual(['Trip 2026', 'Report.pdf']);
  });

  it('hides the usage when it cannot be loaded', async () => {
    drive.failures.set('/api/v1/drive/usage', { status: 503, title: 'Unavailable' });
    await open();

    expect(root.querySelector('.usage')).toBeNull();
    expect(names()).toHaveLength(3);
  });
});
