import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { filter, Subject } from 'rxjs';
import { BookmarkResponse, ChangeEvent } from '../../api/generated';
import { ChangeEventsService } from '../../core/events';
import { NotificationService, provideHttp } from '../../core/http';
import routes from './bookmarks.routes';
import { bookmark } from './testing';

const LIST = '/api/v1/bookmarks';
const TAGS = '/api/v1/bookmarks/tags';
const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

const ESSAY = bookmark('0192f0c4-0000-7000-8000-000000000001', {
  title: 'On slow software',
  displayTitle: 'On slow software',
  url: 'https://www.example.org/slow',
  tags: ['essays'],
  favicon: 'https://example.org/favicon.ico',
});
const GUIDE = bookmark('0192f0c4-0000-7000-8000-000000000002', {
  title: undefined,
  displayTitle: 'docs.example.com',
  url: 'https://docs.example.com/guide',
  description: undefined,
  metadataStatus: 'PENDING',
  snapshotFileId: '0192f0c4-0000-7000-8000-0000000000f1',
});

/** A MatSnackBar double: each `open` gives a ref whose action and dismissal the test fires. */
class FakeSnackBar {
  readonly opened: { message: string; action: string; act: () => void; dismiss: () => void }[] = [];

  open(message: string, action: string) {
    const onAction = new Subject<void>();
    const afterDismissed = new Subject<void>();
    const dismiss = (): void => {
      afterDismissed.next();
      afterDismissed.complete();
    };
    this.opened.push({
      message,
      action,
      act: () => {
        onAction.next();
        onAction.complete();
        dismiss();
      },
      dismiss,
    });
    return { onAction: () => onAction, afterDismissed: () => afterDismissed };
  }
}

describe('BookmarksPage', () => {
  let snackBar: FakeSnackBar;
  let harness: RouterTestingHarness;
  let root: HTMLElement;
  let http: HttpTestingController;
  let changes: Subject<ChangeEvent>;
  let resyncs: Subject<void>;
  let notifications: { error: ReturnType<typeof vi.fn>; info: ReturnType<typeof vi.fn> };

  async function open(
    url = '/bookmarks',
    items: BookmarkResponse[] = [ESSAY, GUIDE],
    nextCursor?: string,
  ): Promise<void> {
    notifications = { error: vi.fn(), info: vi.fn() };
    snackBar = new FakeSnackBar();
    changes = new Subject();
    resyncs = new Subject();
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'bookmarks', children: routes }]),
        { provide: NotificationService, useValue: notifications },
        { provide: MatSnackBar, useValue: snackBar },
        {
          provide: ChangeEventsService,
          useValue: {
            resync$: resyncs.asObservable(),
            events$: (type: string) => changes.pipe(filter((e) => e.type === type)),
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    root = harness.routeNativeElement as HTMLElement;
    flushTags();
    flushList(items, nextCursor);
    await settle();
  }

  function flushList(items: BookmarkResponse[], nextCursor?: string): void {
    http
      .expectOne((r) => r.method === 'GET' && r.url === LIST)
      .flush({ items, ...(nextCursor ? { nextCursor } : {}) });
  }

  function flushTags(items = [{ tag: 'essays', count: 1 }]): void {
    http.expectOne((r) => r.method === 'GET' && r.url === TAGS).flush({ items });
  }

  async function settle(): Promise<void> {
    await tick();
    harness.detectChanges();
    await harness.fixture.whenStable();
    await tick();
    harness.detectChanges();
  }

  function row(b: BookmarkResponse): HTMLElement {
    return root.querySelector(`.bookmark[data-id="${b.id}"]`) as HTMLElement;
  }

  function button(b: BookmarkResponse, cls: string): HTMLButtonElement {
    return row(b).querySelector(`.${cls}`) as HTMLButtonElement;
  }

  function text(el: Element | null): string {
    return el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  }

  function type(selector: string, value: string): void {
    const input = root.querySelector(selector) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    harness.detectChanges();
  }

  afterEach(() => http.verify());

  it('shows the read-later queue with title, host, icon, tags and pending metadata', async () => {
    await open();

    expect(text(row(ESSAY).querySelector('.title'))).toBe('On slow software');
    expect(row(ESSAY).querySelector('.title')?.getAttribute('href')).toBe(ESSAY.url);
    expect(row(ESSAY).querySelector('.title')?.getAttribute('rel')).toContain('noopener');
    expect(text(row(ESSAY).querySelector('.host'))).toBe('example.org');
    // No remote favicon (CSP, privacy): the host's initial instead, also when a favicon is known.
    expect(root.querySelector('img')).toBeNull();
    expect(text(row(ESSAY).querySelector('.icon'))).toBe('E');
    expect(text(row(ESSAY).querySelector('.tags'))).toBe('#essays');
    expect(text(row(GUIDE).querySelector('.icon'))).toBe('D');
    expect(text(row(GUIDE).querySelector('.pending'))).toBe('fetching title…');
    expect(row(ESSAY).querySelector('.snapshot')).toBeNull();
    expect(row(GUIDE).querySelector('.snapshot')?.getAttribute('href')).toBe(
      `/bookmarks/${GUIDE.id}`,
    );
    expect(root.querySelector('.show-queue')?.getAttribute('aria-current')).toBe('page');
    expect(root.querySelector('.tag-filter')).toBeNull();
  });

  it('lists all bookmarks of a tag through the query string', async () => {
    await open('/bookmarks?view=all&tag=essays', [ESSAY]);
    expect(root.querySelector('.show-all')?.getAttribute('aria-current')).toBeNull();
    expect(text(root.querySelector('.filtered'))).toContain('Tagged #essays');
    const tag = root.querySelector('.tag-filter .tag') as HTMLElement;
    expect(text(tag)).toBe('#essays 1');
    expect(tag.getAttribute('aria-current')).toBe('page');

    await TestBed.inject(Router).navigateByUrl('/bookmarks?view=all');
    await settle();
    const req = http.expectOne((r) => r.method === 'GET' && r.url === LIST);
    expect(req.request.params.has('tag')).toBe(false);
    expect(req.request.params.has('readLater')).toBe(false);
    req.flush({ items: [ESSAY, GUIDE] });
    await settle();
    expect(root.querySelectorAll('.bookmark')).toHaveLength(2);
    expect(root.querySelector('.show-all')?.getAttribute('aria-current')).toBe('page');
  });

  it('adds a URL to the queue with its tags', async () => {
    await open('/bookmarks', []);
    expect(text(root.querySelector('.empty'))).toContain('Nothing left to read');
    type('.url input', 'example.net/post');
    type('.tags-field input', 'kotlin, #design');
    (root.querySelector('.add') as HTMLFormElement).dispatchEvent(new Event('submit'));
    harness.detectChanges();

    const req = http.expectOne((r) => r.method === 'POST' && r.url === LIST);
    expect(req.request.body).toEqual({
      url: 'https://example.net/post',
      readLater: true,
      tags: ['kotlin', 'design'],
    });
    const created = bookmark('0192f0c4-0000-7000-8000-000000000009', {
      url: 'https://example.net/post',
      metadataStatus: 'PENDING',
    });
    req.flush(created, { status: 201, statusText: 'Created' });
    await settle();
    flushTags();
    await settle();

    expect(row(created)).toBeTruthy();
    expect((root.querySelector('.url input') as HTMLInputElement).value).toBe('');
    expect(notifications.info).toHaveBeenCalledWith('Saved to Read later');
  });

  it('refuses an address that is not a web address without calling the server', async () => {
    await open();
    type('.url input', 'not a link');
    (root.querySelector('.add') as HTMLFormElement).dispatchEvent(new Event('submit'));
    await settle();
    expect(text(root.querySelector('.add-error'))).toContain('Enter a web address');
  });

  it('marks a bookmark read: it leaves the queue', async () => {
    await open();
    button(ESSAY, 'mark-read').click();
    harness.detectChanges();
    expect(button(ESSAY, 'mark-read').disabled).toBe(true);
    const req = http.expectOne((r) => r.method === 'PATCH' && r.url === `${LIST}/${ESSAY.id}`);
    expect(req.request.body).toEqual({ read: true });
    expect(req.request.headers.get('If-Match')).toBe('"1"');
    req.flush({ ...ESSAY, readAt: '2026-10-22T09:00:00Z', version: 2 });
    await settle();
    expect(row(ESSAY)).toBeNull();
    expect(notifications.info).toHaveBeenCalledWith('Marked “On slow software” as read');
  });

  it('marks a read bookmark unread in the all view, where it stays', async () => {
    const read = { ...ESSAY, readAt: '2026-10-21T09:00:00Z' };
    await open('/bookmarks?view=all', [read]);
    expect(row(read).classList).toContain('read');
    expect(text(button(read, 'mark-read'))).toBe('Mark unread');
    button(read, 'mark-read').click();
    const req = http.expectOne((r) => r.method === 'PATCH');
    expect(req.request.body).toEqual({ read: false });
    req.flush({ ...ESSAY, version: 2 });
    await settle();
    expect(row(read).classList).not.toContain('read');
    expect(text(button(read, 'mark-read'))).toBe('Mark read');
  });

  it('takes a bookmark out of the queue and moves one to the Trash', async () => {
    await open();
    button(GUIDE, 'queue').click();
    const patch = http.expectOne((r) => r.method === 'PATCH');
    expect(patch.request.body).toEqual({ readLater: false });
    patch.flush({ ...GUIDE, readLater: false, version: 2 });
    await settle();
    expect(row(GUIDE)).toBeNull();

    button(ESSAY, 'delete').click();
    http
      .expectOne((r) => r.method === 'DELETE' && r.url === `${LIST}/${ESSAY.id}`)
      .flush(null, { status: 204, statusText: 'No Content' });
    await settle();
    flushTags([]);
    await settle();
    expect(row(ESSAY)).toBeNull();
    expect(snackBar.opened.map((o) => [o.message, o.action])).toEqual([
      ['Moved “On slow software” to the Trash', 'Undo'],
    ]);
    snackBar.opened[0].dismiss();
  });

  it('shows a conflict next to the bookmark and loads its current version', async () => {
    await open();
    button(ESSAY, 'mark-read').click();
    http
      .expectOne((r) => r.method === 'PATCH')
      .flush(
        { type: 'about:blank', title: 'Precondition Failed', status: 412 },
        { status: 412, statusText: 'Precondition Failed' },
      );
    await settle();
    expect(row(ESSAY).querySelector('.error')?.getAttribute('role')).toBe('alert');
    http
      .expectOne((r) => r.method === 'GET' && r.url === `${LIST}/${ESSAY.id}`)
      .flush({ ...ESSAY, title: 'Renamed', displayTitle: 'Renamed', version: 3 });
    await settle();
    expect(text(row(ESSAY).querySelector('.title'))).toBe('Renamed');
  });

  it('replaces a bookmark when its fetched metadata arrives', async () => {
    await open();
    changes.next({ type: 'BOOKMARK', op: 'UPDATED', id: GUIDE.id, at: '2026-10-22T09:00:00Z' });
    http
      .expectOne((r) => r.method === 'GET' && r.url === `${LIST}/${GUIDE.id}`)
      .flush({
        ...GUIDE,
        title: 'The guide',
        displayTitle: 'The guide',
        metadataStatus: 'FETCHED',
        version: 2,
      });
    await settle();
    expect(text(row(GUIDE).querySelector('.title'))).toBe('The guide');
    expect(row(GUIDE).querySelector('.pending')).toBeNull();

    changes.next({ type: 'BOOKMARK', op: 'DELETED', id: ESSAY.id, at: '2026-10-22T09:00:00Z' });
    await settle();
    expect(row(ESSAY)).toBeNull();
  });

  it('loads more bookmarks from the cursor', async () => {
    await open('/bookmarks', [ESSAY], 'c1');
    (root.querySelector('.more') as HTMLButtonElement).click();
    const req = http.expectOne((r) => r.method === 'GET' && r.url === LIST);
    expect(req.request.params.get('cursor')).toBe('c1');
    req.flush({ items: [GUIDE] });
    await settle();
    expect(root.querySelectorAll('.bookmark')).toHaveLength(2);
    expect(root.querySelector('.more')).toBeNull();
  });

  it('shows a load error and tries again', async () => {
    notifications = { error: vi.fn(), info: vi.fn() };
    changes = new Subject();
    resyncs = new Subject();
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'bookmarks', children: routes }]),
        { provide: NotificationService, useValue: notifications },
        {
          provide: ChangeEventsService,
          useValue: { resync$: resyncs.asObservable(), events$: () => changes.asObservable() },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/bookmarks');
    root = harness.routeNativeElement as HTMLElement;
    flushTags();
    http
      .expectOne((r) => r.url === LIST)
      .flush(
        { type: 'about:blank', title: 'Server error', status: 500 },
        { status: 500, statusText: 'Server Error' },
      );
    await settle();
    expect(root.querySelector('.load-error')).toBeTruthy();
    (root.querySelector('.load-error button') as HTMLButtonElement).click();
    flushList([ESSAY]);
    await settle();
    expect(row(ESSAY)).toBeTruthy();
  });

  describe('gaps', () => {
    const AT = '2026-10-22T09:00:00Z';
    const NEW = bookmark('0192f0c4-0000-7000-8000-000000000003', {
      displayTitle: 'Saved on the phone',
      url: 'https://example.net/phone',
    });

    function problem(status: number, title: string, detail?: string) {
      return [
        { type: 'about:blank', title, status, ...(detail ? { detail } : {}) },
        { status, statusText: title },
      ] as const;
    }

    function submit(): void {
      (root.querySelector('.add') as HTMLFormElement).dispatchEvent(new Event('submit'));
      harness.detectChanges();
    }

    function ids(): (string | null)[] {
      return [...root.querySelectorAll('.bookmark')].map((el) => el.getAttribute('data-id'));
    }

    function expectNoList(): void {
      http.expectNone((r) => r.method === 'GET' && r.url === LIST);
    }

    it('reloads the first page quietly on a CREATED event and on a resync', async () => {
      await open();
      changes.next({ type: 'BOOKMARK', op: 'CREATED', id: NEW.id, at: AT });
      await settle();
      // The current bookmarks stay on screen while the reload runs.
      expect(root.querySelector('.hint')).toBeNull();
      expect(root.querySelectorAll('.bookmark')).toHaveLength(2);
      flushList([NEW, ESSAY, GUIDE]);
      await settle();
      flushTags();
      await settle();
      expect(row(NEW)).toBeTruthy();
      expect(root.querySelectorAll('.bookmark')).toHaveLength(3);

      resyncs.next();
      await settle();
      expect(root.querySelectorAll('.bookmark')).toHaveLength(3);
      flushList([ESSAY]);
      await settle();
      flushTags();
      await settle();
      expect(root.querySelectorAll('.bookmark')).toHaveLength(1);
      expect(row(ESSAY)).toBeTruthy();
    });

    it('keeps the list when a quiet reload fails', async () => {
      await open();
      resyncs.next();
      await settle();
      http
        .expectOne((r) => r.method === 'GET' && r.url === LIST)
        .flush(...problem(500, 'Server error'));
      await settle();
      flushTags();
      await settle();
      expect(root.querySelectorAll('.bookmark')).toHaveLength(2);
      expect(root.querySelector('.load-error')).toBeNull();
    });

    it('loads only the created bookmark while more pages are loaded, and puts it on top', async () => {
      await open('/bookmarks', [ESSAY], 'c1');
      changes.next({ type: 'BOOKMARK', op: 'CREATED', id: NEW.id, at: AT });
      await settle();
      expectNoList();
      flushTags();
      http
        .expectOne((r) => r.method === 'GET' && r.url === `${LIST}/${NEW.id}`)
        .flush({ ...NEW, createdAt: AT });
      await settle();
      expect(ids()).toEqual([NEW.id, ESSAY.id]);
      expect(root.querySelector('.more')).toBeTruthy();
    });

    it('does not put a created bookmark older than the last shown one on the loaded pages', async () => {
      await open('/bookmarks', [ESSAY], 'c1');
      changes.next({ type: 'BOOKMARK', op: 'CREATED', id: NEW.id, at: AT });
      await settle();
      flushTags();
      http
        .expectOne((r) => r.method === 'GET' && r.url === `${LIST}/${NEW.id}`)
        .flush({ ...NEW, createdAt: '2026-10-01T08:00:00Z' });
      await settle();
      // It comes with "Show more".
      expect(ids()).toEqual([ESSAY.id]);
    });

    it('loads only the created bookmark while a change of another one is running', async () => {
      await open();
      button(ESSAY, 'mark-read').click();
      const patch = http.expectOne((r) => r.method === 'PATCH');
      changes.next({ type: 'BOOKMARK', op: 'CREATED', id: NEW.id, at: AT });
      await settle();
      expectNoList();
      flushTags();
      http
        .expectOne((r) => r.method === 'GET' && r.url === `${LIST}/${NEW.id}`)
        .flush({ ...NEW, createdAt: AT });
      await settle();
      expect(ids()).toEqual([NEW.id, ESSAY.id, GUIDE.id]);
      patch.flush({ ...ESSAY, readAt: AT, version: 2 });
      await settle();
      expect(ids()).toEqual([NEW.id, GUIDE.id]);
    });

    it('does not reload quietly while a change of a bookmark is running', async () => {
      await open();
      button(ESSAY, 'mark-read').click();
      const patch = http.expectOne((r) => r.method === 'PATCH');
      resyncs.next();
      await settle();
      expectNoList();
      flushTags();
      patch.flush({ ...ESSAY, readAt: AT, version: 2 });
      await settle();
      expect(row(ESSAY)).toBeNull();
      expect(row(GUIDE)).toBeTruthy();
    });

    it('adds a bookmark with "Read later" unchecked in the all view', async () => {
      await open('/bookmarks?view=all', [ESSAY]);
      const box = root.querySelector('.later input') as HTMLInputElement;
      expect(box.checked).toBe(false);
      type('.url input', 'https://example.net/phone');
      submit();
      const req = http.expectOne((r) => r.method === 'POST' && r.url === LIST);
      expect(req.request.body).toEqual({
        url: 'https://example.net/phone',
        readLater: false,
        tags: [],
      });
      req.flush({ ...NEW, readLater: false }, { status: 201, statusText: 'Created' });
      await settle();
      flushTags();
      await settle();
      expect(row(NEW)).toBeTruthy();
      expect(root.querySelectorAll('.bookmark')[0].getAttribute('data-id')).toBe(NEW.id);
      expect(notifications.info).toHaveBeenCalledWith('Bookmark saved');
    });

    it('does not show a bookmark added with "Read later" unchecked in the queue', async () => {
      await open();
      const box = root.querySelector('.later input') as HTMLInputElement;
      expect(box.checked).toBe(true);
      box.checked = false;
      box.dispatchEvent(new Event('change'));
      type('.url input', 'example.net/phone');
      submit();
      const req = http.expectOne((r) => r.method === 'POST' && r.url === LIST);
      expect(req.request.body.readLater).toBe(false);
      req.flush({ ...NEW, readLater: false }, { status: 201, statusText: 'Created' });
      await settle();
      flushTags();
      await settle();
      expect(row(NEW)).toBeNull();
      expect(root.querySelectorAll('.bookmark')).toHaveLength(2);
      expect(notifications.info).toHaveBeenCalledWith('Bookmark saved');
      expect((root.querySelector('.url input') as HTMLInputElement).value).toBe('');
    });

    it('shows an added bookmark in a tag view only when it has that tag, ignoring case', async () => {
      await open('/bookmarks?view=all&tag=essays', [ESSAY]);
      type('.url input', 'example.net/a');
      type('.tags-field input', 'kotlin');
      submit();
      const other = bookmark('0192f0c4-0000-7000-8000-000000000004', { tags: ['kotlin'] });
      http
        .expectOne((r) => r.method === 'POST')
        .flush(other, { status: 201, statusText: 'Created' });
      await settle();
      flushTags();
      await settle();
      expect(row(other)).toBeNull();
      expect(root.querySelectorAll('.bookmark')).toHaveLength(1);

      type('.url input', 'example.net/b');
      type('.tags-field input', 'Essays');
      submit();
      const req = http.expectOne((r) => r.method === 'POST');
      expect(req.request.body.tags).toEqual(['Essays']);
      const matching = bookmark('0192f0c4-0000-7000-8000-000000000005', { tags: ['Essays'] });
      req.flush(matching, { status: 201, statusText: 'Created' });
      await settle();
      flushTags();
      await settle();
      expect(row(matching)).toBeTruthy();
    });

    it('shows the server error of an add and keeps the address', async () => {
      await open();
      type('.url input', 'example.net/broken');
      submit();
      expect((root.querySelector('.save') as HTMLButtonElement).disabled).toBe(true);
      http
        .expectOne((r) => r.method === 'POST')
        .flush(...problem(400, 'Bad Request', 'The address is too long.'));
      await settle();
      expect(text(root.querySelector('.add-error'))).toBe('The address is too long.');
      expect((root.querySelector('.url input') as HTMLInputElement).value).toBe(
        'example.net/broken',
      );
      expect((root.querySelector('.save') as HTMLButtonElement).disabled).toBe(false);
      expect(notifications.info).not.toHaveBeenCalled();
    });

    it('ignores a change event whose bookmark is not newer than the shown one', async () => {
      await open();
      changes.next({ type: 'BOOKMARK', op: 'UPDATED', id: ESSAY.id, at: AT });
      http
        .expectOne((r) => r.method === 'GET' && r.url === `${LIST}/${ESSAY.id}`)
        .flush({ ...ESSAY, displayTitle: 'Stale title', version: ESSAY.version });
      await settle();
      expect(text(row(ESSAY).querySelector('.title'))).toBe('On slow software');
    });

    it('adds a changed bookmark that entered the view, in its place by date', async () => {
      const older = { ...GUIDE, createdAt: '2026-10-19T08:00:00Z' };
      await open('/bookmarks', [ESSAY, older]);
      // E.g. put back into Read later on another device.
      changes.next({ type: 'BOOKMARK', op: 'UPDATED', id: NEW.id, at: AT });
      http
        .expectOne((r) => r.method === 'GET' && r.url === `${LIST}/${NEW.id}`)
        .flush({ ...NEW, createdAt: '2026-10-19T12:00:00Z', version: 3 });
      await settle();
      expect(ids()).toEqual([ESSAY.id, NEW.id, older.id]);
    });

    it('adds a changed bookmark at the end when no more pages exist', async () => {
      await open('/bookmarks?view=all&tag=essays', [ESSAY]);
      changes.next({ type: 'BOOKMARK', op: 'UPDATED', id: NEW.id, at: AT });
      http
        .expectOne((r) => r.method === 'GET' && r.url === `${LIST}/${NEW.id}`)
        .flush({ ...NEW, tags: ['Essays'], createdAt: '2026-10-01T08:00:00Z', version: 2 });
      await settle();
      expect(ids()).toEqual([ESSAY.id, NEW.id]);
    });

    it('does not add a changed bookmark that does not belong to the view', async () => {
      await open();
      changes.next({ type: 'BOOKMARK', op: 'UPDATED', id: NEW.id, at: AT });
      http
        .expectOne((r) => r.method === 'GET' && r.url === `${LIST}/${NEW.id}`)
        .flush({ ...NEW, readAt: AT, version: 2 });
      await settle();
      expect(ids()).toEqual([ESSAY.id, GUIDE.id]);

      changes.next({ type: 'BOOKMARK', op: 'UPDATED', id: NEW.id, at: AT });
      http
        .expectOne((r) => r.method === 'GET' && r.url === `${LIST}/${NEW.id}`)
        .flush(...problem(404, 'Not Found'));
      await settle();
      expect(ids()).toEqual([ESSAY.id, GUIDE.id]);
    });

    it('does not load a changed bookmark while the list loads', async () => {
      await open();
      await TestBed.inject(Router).navigateByUrl('/bookmarks?view=all');
      await settle();
      changes.next({ type: 'BOOKMARK', op: 'UPDATED', id: NEW.id, at: AT });
      await settle();
      http.expectNone(`${LIST}/${NEW.id}`);
      flushList([NEW]);
      await settle();
      expect(ids()).toEqual([NEW.id]);
    });

    it('drops a quiet reload that overlaps a change made here', async () => {
      await open();
      resyncs.next();
      await settle();
      const quiet = http.expectOne((r) => r.method === 'GET' && r.url === LIST);
      button(ESSAY, 'mark-read').click();
      http.expectOne((r) => r.method === 'PATCH').flush({ ...ESSAY, readAt: AT, version: 2 });
      await settle();
      expect(ids()).toEqual([GUIDE.id]);
      // Read before the change: it still lists the bookmark as unread.
      quiet.flush({ items: [ESSAY, GUIDE] });
      await settle();
      expect(ids()).toEqual([GUIDE.id]);
    });

    it('drops a quiet reload that overlaps an add, a delete or a queue change', async () => {
      await open();
      resyncs.next();
      await settle();
      const first = http.expectOne((r) => r.method === 'GET' && r.url === LIST);
      type('.url input', 'https://example.net/phone');
      submit();
      http
        .expectOne((r) => r.method === 'POST')
        .flush({ ...NEW, createdAt: AT }, { status: 201, statusText: 'Created' });
      await settle();
      flushTags();
      first.flush({ items: [ESSAY, GUIDE] });
      await settle();
      expect(ids()).toEqual([NEW.id, ESSAY.id, GUIDE.id]);

      resyncs.next();
      await settle();
      const second = http.expectOne((r) => r.method === 'GET' && r.url === LIST);
      button(GUIDE, 'delete').click();
      http.expectOne((r) => r.method === 'DELETE').flush(null, { status: 204, statusText: '' });
      await settle();
      flushTags();
      second.flush({ items: [NEW, ESSAY, GUIDE] });
      await settle();
      expect(ids()).toEqual([NEW.id, ESSAY.id]);

      resyncs.next();
      await settle();
      const third = http.expectOne((r) => r.method === 'GET' && r.url === LIST);
      button(ESSAY, 'queue').click();
      http.expectOne((r) => r.method === 'PATCH').flush({ ...ESSAY, readLater: false, version: 2 });
      await settle();
      third.flush({ items: [NEW, ESSAY] });
      await settle();
      expect(ids()).toEqual([NEW.id]);
    });

    it('drops a refreshed bookmark that was deleted here meanwhile', async () => {
      await open();
      changes.next({ type: 'BOOKMARK', op: 'UPDATED', id: ESSAY.id, at: AT });
      const refresh = http.expectOne((r) => r.method === 'GET' && r.url === `${LIST}/${ESSAY.id}`);
      button(ESSAY, 'delete').click();
      http.expectOne((r) => r.method === 'DELETE').flush(null, { status: 204, statusText: '' });
      await settle();
      flushTags();
      refresh.flush({ ...ESSAY, displayTitle: 'From elsewhere', version: 2 });
      await settle();
      expect(ids()).toEqual([GUIDE.id]);
    });

    it('keeps a refreshed bookmark when another bookmark was changed here meanwhile', async () => {
      await open();
      changes.next({ type: 'BOOKMARK', op: 'UPDATED', id: GUIDE.id, at: AT });
      const refresh = http.expectOne((r) => r.method === 'GET' && r.url === `${LIST}/${GUIDE.id}`);
      button(ESSAY, 'mark-read').click();
      http.expectOne((r) => r.method === 'PATCH').flush({ ...ESSAY, readAt: AT, version: 2 });
      await settle();
      refresh.flush({ ...GUIDE, displayTitle: 'The guide', metadataStatus: 'FETCHED', version: 2 });
      await settle();
      expect(text(row(GUIDE).querySelector('.title'))).toBe('The guide');
    });

    it('ignores a 404 of a refreshed bookmark that was changed here meanwhile', async () => {
      await open();
      changes.next({ type: 'BOOKMARK', op: 'UPDATED', id: ESSAY.id, at: AT });
      const refresh = http.expectOne((r) => r.method === 'GET' && r.url === `${LIST}/${ESSAY.id}`);
      button(ESSAY, 'queue').click();
      http.expectOne((r) => r.method === 'PATCH').flush({ ...ESSAY, version: 2 });
      await settle();
      refresh.flush(...problem(404, 'Not Found'));
      await settle();
      expect(row(ESSAY)).toBeTruthy();
    });

    it('opens the reader with the view and tag, so it can lead back to them', async () => {
      const tagged = { ...GUIDE, tags: ['essays'] };
      await open('/bookmarks?view=all&tag=essays', [tagged]);
      expect(row(tagged).querySelector('.snapshot')?.getAttribute('href')).toBe(
        `/bookmarks/${GUIDE.id}?view=all&tag=essays`,
      );
      await TestBed.inject(Router).navigateByUrl('/bookmarks?view=all');
      await settle();
      flushList([tagged]);
      await settle();
      expect(row(tagged).querySelector('.snapshot')?.getAttribute('href')).toBe(
        `/bookmarks/${GUIDE.id}?view=all`,
      );
    });

    it('shows an added bookmark once when its CREATED event reloaded the list first', async () => {
      await open();
      type('.url input', 'https://example.net/phone');
      submit();
      const post = http.expectOne((r) => r.method === 'POST' && r.url === LIST);
      changes.next({ type: 'BOOKMARK', op: 'CREATED', id: NEW.id, at: AT });
      await settle();
      flushList([NEW, ESSAY, GUIDE]);
      await settle();
      flushTags();
      post.flush(NEW, { status: 201, statusText: 'Created' });
      await settle();
      flushTags();
      await settle();
      expect(root.querySelectorAll(`.bookmark[data-id="${NEW.id}"]`)).toHaveLength(1);
      expect(root.querySelectorAll('.bookmark')).toHaveLength(3);
    });

    it('keeps a bookmark whose refresh fails with another error than 404', async () => {
      await open();
      changes.next({ type: 'BOOKMARK', op: 'UPDATED', id: ESSAY.id, at: AT });
      http
        .expectOne((r) => r.method === 'GET' && r.url === `${LIST}/${ESSAY.id}`)
        .flush(...problem(500, 'Server error'));
      await settle();
      expect(row(ESSAY)).toBeTruthy();
      expect(row(ESSAY).querySelector('.error')).toBeNull();
    });

    it('drops a changed bookmark that has gone meanwhile (404)', async () => {
      await open();
      changes.next({ type: 'BOOKMARK', op: 'UPDATED', id: ESSAY.id, at: AT });
      http
        .expectOne((r) => r.method === 'GET' && r.url === `${LIST}/${ESSAY.id}`)
        .flush(...problem(404, 'Not Found'));
      await settle();
      expect(row(ESSAY)).toBeNull();
      expect(row(GUIDE)).toBeTruthy();
    });

    it('drops a changed bookmark that left the queue (read elsewhere)', async () => {
      await open();
      changes.next({ type: 'BOOKMARK', op: 'UPDATED', id: ESSAY.id, at: AT });
      http
        .expectOne((r) => r.method === 'GET' && r.url === `${LIST}/${ESSAY.id}`)
        .flush({ ...ESSAY, readAt: AT, version: 2 });
      await settle();
      expect(row(ESSAY)).toBeNull();
    });

    it('does not load a bookmark from a change event while it is being changed here', async () => {
      await open();
      button(ESSAY, 'mark-read').click();
      const patch = http.expectOne((r) => r.method === 'PATCH');
      changes.next({ type: 'BOOKMARK', op: 'UPDATED', id: ESSAY.id, at: AT });
      await settle();
      http.expectNone(`${LIST}/${ESSAY.id}`);
      patch.flush({ ...ESSAY, readAt: AT, version: 2 });
      await settle();
      expect(row(ESSAY)).toBeNull();
    });

    it('drops the row without an error when a row action answers 404', async () => {
      await open();
      button(ESSAY, 'mark-read').click();
      http.expectOne((r) => r.method === 'PATCH').flush(...problem(404, 'Not Found'));
      await settle();
      expect(row(ESSAY)).toBeNull();
      expect(root.querySelector('.error')).toBeNull();

      button(GUIDE, 'delete').click();
      http.expectOne((r) => r.method === 'DELETE').flush(...problem(404, 'Not Found'));
      await settle();
      expect(row(GUIDE)).toBeNull();
      expect(notifications.info).not.toHaveBeenCalled();
    });

    it('shows a server error next to the bookmark without reloading it, and clears it on the next try', async () => {
      await open();
      button(ESSAY, 'queue').click();
      http
        .expectOne((r) => r.method === 'PATCH')
        .flush(...problem(500, 'Server error', 'Storage is unavailable.'));
      await settle();
      expect(text(row(ESSAY).querySelector('.error'))).toBe(
        '“On slow software” could not be changed: Storage is unavailable.',
      );
      expect(row(GUIDE).querySelector('.error')).toBeNull();
      expect(button(ESSAY, 'queue').disabled).toBe(false);

      button(ESSAY, 'queue').click();
      harness.detectChanges();
      expect(row(ESSAY).querySelector('.error')).toBeNull();
      http.expectOne((r) => r.method === 'PATCH').flush({ ...ESSAY, readLater: false, version: 2 });
      await settle();
      expect(row(ESSAY)).toBeNull();
    });

    it('drops the bookmark when it is gone while reloading after a conflict', async () => {
      await open();
      button(ESSAY, 'mark-read').click();
      http.expectOne((r) => r.method === 'PATCH').flush(...problem(409, 'Conflict'));
      await settle();
      http
        .expectOne((r) => r.method === 'GET' && r.url === `${LIST}/${ESSAY.id}`)
        .flush(...problem(404, 'Not Found'));
      await settle();
      expect(row(ESSAY)).toBeNull();
    });

    it('puts a bookmark into the queue in the all view, where it stays', async () => {
      const out = { ...GUIDE, readLater: false };
      await open('/bookmarks?view=all', [out]);
      expect(text(button(out, 'queue'))).toBe('Read later');
      button(out, 'queue').click();
      const req = http.expectOne((r) => r.method === 'PATCH');
      expect(req.request.body).toEqual({ readLater: true });
      req.flush({ ...GUIDE, readLater: true, version: 2 });
      await settle();
      expect(text(button(out, 'queue'))).toBe('Remove from Read later');
    });

    it('toasts a failed "Show more" and keeps the button', async () => {
      await open('/bookmarks', [ESSAY], 'c1');
      (root.querySelector('.more') as HTMLButtonElement).click();
      http
        .expectOne((r) => r.method === 'GET' && r.url === LIST)
        .flush(...problem(500, 'Server error', 'Try later.'));
      await settle();
      expect(notifications.error).toHaveBeenCalledWith('Try later.');
      expect((root.querySelector('.more') as HTMLButtonElement).disabled).toBe(false);
      expect(root.querySelectorAll('.bookmark')).toHaveLength(1);
    });

    it('ignores a list that answers for an earlier view', async () => {
      await open();
      const router = TestBed.inject(Router);
      await router.navigateByUrl('/bookmarks?view=all');
      await settle();
      await router.navigateByUrl('/bookmarks');
      await settle();
      const [all, queue] = http.match((r) => r.method === 'GET' && r.url === LIST);
      expect(all.request.params.has('readLater')).toBe(false);
      expect(queue.request.params.get('readLater')).toBe('true');
      queue.flush({ items: [GUIDE] });
      await settle();
      all.flush({ items: [ESSAY, GUIDE] });
      await settle();
      expect(root.querySelectorAll('.bookmark')).toHaveLength(1);
      expect(row(GUIDE)).toBeTruthy();
      expect(root.querySelector('.show-queue')?.getAttribute('aria-current')).toBe('page');
    });

    it('ignores a quiet reload and a "Show more" that answer after the view changed', async () => {
      await open('/bookmarks', [ESSAY], 'c1');
      (root.querySelector('.more') as HTMLButtonElement).click();
      const more = http.expectOne((r) => r.method === 'GET' && r.params.get('cursor') === 'c1');
      await TestBed.inject(Router).navigateByUrl('/bookmarks?view=all');
      await settle();
      const all = http.expectOne(
        (r) => r.method === 'GET' && r.url === LIST && !r.params.has('cursor'),
      );
      all.flush({ items: [GUIDE] });
      await settle();
      more.flush({ items: [ESSAY] });
      await settle();
      expect(root.querySelectorAll('.bookmark')).toHaveLength(1);
      expect(row(GUIDE)).toBeTruthy();

      resyncs.next();
      await settle();
      const quiet = http.expectOne((r) => r.method === 'GET' && r.url === LIST);
      await TestBed.inject(Router).navigateByUrl('/bookmarks');
      await settle();
      const queue = http.expectOne((r) => r.method === 'GET' && r.url === LIST);
      queue.flush({ items: [ESSAY] });
      await settle();
      quiet.flush({ items: [GUIDE, NEW] });
      await settle();
      expect(root.querySelectorAll('.bookmark')).toHaveLength(1);
      expect(row(ESSAY)).toBeTruthy();
    });

    it('ignores a refreshed bookmark when a change of it started meanwhile', async () => {
      await open();
      changes.next({ type: 'BOOKMARK', op: 'UPDATED', id: ESSAY.id, at: AT });
      const refresh = http.expectOne((r) => r.method === 'GET' && r.url === `${LIST}/${ESSAY.id}`);
      button(ESSAY, 'queue').click();
      const patch = http.expectOne((r) => r.method === 'PATCH');
      refresh.flush({ ...ESSAY, displayTitle: 'From elsewhere', version: 2 });
      await settle();
      expect(text(row(ESSAY).querySelector('.title'))).toBe('On slow software');
      patch.flush({ ...ESSAY, readLater: false, version: 3 });
      await settle();
      expect(row(ESSAY)).toBeNull();
    });

    it('does not reload when the query string names the same view', async () => {
      await open('/bookmarks?view=all', [ESSAY]);
      await TestBed.inject(Router).navigateByUrl('/bookmarks?view=all&tag=');
      await settle();
      expectNoList();
      expect(row(ESSAY)).toBeTruthy();
    });

    it('shows the empty texts of the all view and of a tag', async () => {
      await open('/bookmarks?view=all', []);
      expect(text(root.querySelector('.empty'))).toContain('No bookmarks yet');
      await TestBed.inject(Router).navigateByUrl('/bookmarks?view=all&tag=rust');
      await settle();
      flushList([]);
      await settle();
      expect(text(root.querySelector('.empty'))).toBe('No bookmarks with this tag.');
    });

    it('places a bookmark that entered the view by id when it was created at the same time', async () => {
      const at = '2026-10-20T08:00:00Z';
      const high = bookmark('0192f0c4-0000-7000-8000-000000000005', { createdAt: at });
      const low = bookmark('0192f0c4-0000-7000-8000-000000000003', { createdAt: at });
      const middle = bookmark('0192f0c4-0000-7000-8000-000000000004', { createdAt: at });
      // The server orders by createdAt desc, then id desc.
      await open('/bookmarks', [high, low]);
      changes.next({ type: 'BOOKMARK', op: 'UPDATED', id: middle.id, at: AT });
      http
        .expectOne((r) => r.method === 'GET' && r.url === `${LIST}/${middle.id}`)
        .flush({ ...middle, version: 2 });
      await settle();
      expect(ids()).toEqual([high.id, middle.id, low.id]);
    });

    it('puts a bookmark that entered the view between the loaded ones while more pages exist', async () => {
      const older = { ...GUIDE, createdAt: '2026-10-10T08:00:00Z' };
      await open('/bookmarks', [ESSAY, older], 'c1');
      changes.next({ type: 'BOOKMARK', op: 'UPDATED', id: NEW.id, at: AT });
      http
        .expectOne((r) => r.method === 'GET' && r.url === `${LIST}/${NEW.id}`)
        .flush({ ...NEW, createdAt: '2026-10-15T08:00:00Z', version: 2 });
      await settle();
      expect(ids()).toEqual([ESSAY.id, NEW.id, older.id]);
      expect(root.querySelector('.more')).toBeTruthy();
    });

    it('does not add a refreshed bookmark that answers after the view changed', async () => {
      await open();
      changes.next({ type: 'BOOKMARK', op: 'UPDATED', id: NEW.id, at: AT });
      const refresh = http.expectOne((r) => r.method === 'GET' && r.url === `${LIST}/${NEW.id}`);
      await TestBed.inject(Router).navigateByUrl('/bookmarks?view=all');
      await settle();
      flushList([GUIDE]);
      await settle();
      // It belongs to the all view, but was loaded for the queue: the new list is the truth.
      refresh.flush({ ...NEW, version: 2 });
      await settle();
      expect(ids()).toEqual([GUIDE.id]);
    });

    it('does not load a created bookmark while the list loads', async () => {
      await open();
      await TestBed.inject(Router).navigateByUrl('/bookmarks?view=all');
      await settle();
      changes.next({ type: 'BOOKMARK', op: 'CREATED', id: NEW.id, at: AT });
      await settle();
      http.expectNone(`${LIST}/${NEW.id}`);
      flushTags();
      flushList([NEW, ESSAY]);
      await settle();
      expect(ids()).toEqual([NEW.id, ESSAY.id]);
    });

    it('shows a bookmark added here once when its CREATED event loads it while more pages exist', async () => {
      await open('/bookmarks', [ESSAY], 'c1');
      type('.url input', 'https://example.net/phone');
      submit();
      const post = http.expectOne((r) => r.method === 'POST' && r.url === LIST);
      changes.next({ type: 'BOOKMARK', op: 'CREATED', id: NEW.id, at: AT });
      await settle();
      flushTags();
      const refresh = http.expectOne((r) => r.method === 'GET' && r.url === `${LIST}/${NEW.id}`);
      post.flush({ ...NEW, createdAt: AT }, { status: 201, statusText: 'Created' });
      await settle();
      flushTags();
      refresh.flush({ ...NEW, createdAt: AT });
      await settle();
      expect(ids()).toEqual([NEW.id, ESSAY.id]);
    });
  });

  describe('search, edit, undo and to inbox', () => {
    const TASKS = '/api/v1/tasks';
    const LINKS = '/api/v1/links';
    const NOTED = bookmark('0192f0c4-0000-7000-8000-000000000006', {
      title: 'Kotlin coroutines in practice',
      displayTitle: 'Kotlin coroutines in practice',
      url: 'https://blog.example.net/coroutines',
      description: 'Structured concurrency explained with fictitious examples.',
      tags: ['kotlin', 'Async'],
      createdAt: '2026-10-19T08:00:00Z',
    });

    function problem(status: number, title: string, detail?: string) {
      return [
        { type: 'about:blank', title, status, ...(detail ? { detail } : {}) },
        { status, statusText: title },
      ] as const;
    }

    function ids(): (string | null)[] {
      return [...root.querySelectorAll('.bookmark')].map((el) => el.getAttribute('data-id'));
    }

    function search(query: string): void {
      type('.search input', query);
    }

    function editField(b: BookmarkResponse, cls: string): HTMLInputElement {
      return row(b).querySelector(`.edit .${cls}`) as HTMLInputElement;
    }

    function typeInto(input: HTMLInputElement, value: string): void {
      input.value = value;
      input.dispatchEvent(new Event('input'));
      harness.detectChanges();
    }

    async function deleteAndSettle(b: BookmarkResponse): Promise<void> {
      button(b, 'delete').click();
      http
        .expectOne((r) => r.method === 'DELETE' && r.url === `${LIST}/${b.id}`)
        .flush(null, { status: 204, statusText: 'No Content' });
      await settle();
      flushTags();
      await settle();
    }

    describe('search', () => {
      beforeEach(async () => {
        await open('/bookmarks?view=all', [ESSAY, GUIDE, NOTED]);
      });

      it('filters the loaded bookmarks by title, ignoring case', () => {
        search('SLOW');
        expect(ids()).toEqual([ESSAY.id]);
        // Without a title, the shown title (the host) counts.
        search('docs.example');
        expect(ids()).toEqual([GUIDE.id]);
      });

      it('filters by URL, notes and tag', () => {
        search('blog.example.net/coroutines');
        expect(ids()).toEqual([NOTED.id]);
        search('structured concurrency');
        expect(ids()).toEqual([NOTED.id]);
        search('#essays');
        expect(ids()).toEqual([ESSAY.id]);
        search('#async');
        expect(ids()).toEqual([NOTED.id]);
      });

      it('needs every word to match', () => {
        search('example');
        expect(ids()).toEqual([ESSAY.id, GUIDE.id, NOTED.id]);
        search('example #kotlin');
        expect(ids()).toEqual([NOTED.id]);
        search('example #kotlin slow');
        expect(ids()).toEqual([]);
      });

      it('says that nothing matches, then shows all again when the search is cleared', () => {
        search('  quantum  ');
        expect(ids()).toEqual([]);
        const empty = root.querySelector('.empty') as HTMLElement;
        expect(text(empty)).toBe('No loaded bookmark matches “quantum”.');
        expect(empty.getAttribute('role')).toBe('status');

        search('   ');
        expect(ids()).toEqual([ESSAY.id, GUIDE.id, NOTED.id]);
        expect(root.querySelector('.empty')).toBeNull();
      });

      it('clears the search with Escape', () => {
        search('slow');
        expect(ids()).toEqual([ESSAY.id]);
        const input = root.querySelector('.search input') as HTMLInputElement;
        input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
        harness.detectChanges();
        expect(input.value).toBe('');
        expect(ids()).toEqual([ESSAY.id, GUIDE.id, NOTED.id]);
      });
    });

    it('keeps the search when a bookmark it hides arrives from the server', async () => {
      await open('/bookmarks?view=all', [ESSAY]);
      search('slow');
      changes.next({ type: 'BOOKMARK', op: 'UPDATED', id: NOTED.id, at: '2026-10-22T09:00:00Z' });
      http
        .expectOne((r) => r.method === 'GET' && r.url === `${LIST}/${NOTED.id}`)
        .flush({ ...NOTED, version: 2 });
      await settle();
      expect(ids()).toEqual([ESSAY.id]);
      search('');
      expect(ids()).toEqual([ESSAY.id, NOTED.id]);
    });

    describe('edit', () => {
      it('opens the editor with the current title, tags and notes', async () => {
        await open('/bookmarks?view=all', [NOTED, GUIDE]);
        expect(row(NOTED).querySelector('.edit')).toBeNull();
        button(NOTED, 'edit-open').click();
        harness.detectChanges();
        expect(editField(NOTED, 'edit-title').value).toBe('Kotlin coroutines in practice');
        expect(editField(NOTED, 'edit-tags').value).toBe('kotlin, Async');
        expect(editField(NOTED, 'edit-notes').value).toBe(
          'Structured concurrency explained with fictitious examples.',
        );
        expect(button(NOTED, 'edit-open').disabled).toBe(true);
        // Only one editor at a time.
        expect(row(GUIDE).querySelector('.edit')).toBeNull();

        button(GUIDE, 'edit-open').click();
        harness.detectChanges();
        expect(row(NOTED).querySelector('.edit')).toBeNull();
        // No title: an empty field with the host as placeholder.
        expect(editField(GUIDE, 'edit-title').value).toBe('');
        expect(editField(GUIDE, 'edit-title').placeholder).toBe('docs.example.com');
        expect(editField(GUIDE, 'edit-notes').value).toBe('');
        expect(editField(GUIDE, 'edit-tags').value).toBe('');
      });

      it('saves title, tags and notes with the version and shows the result', async () => {
        await open('/bookmarks?view=all', [NOTED]);
        button(NOTED, 'edit-open').click();
        harness.detectChanges();
        typeInto(editField(NOTED, 'edit-title'), '  Coroutines, revisited  ');
        typeInto(editField(NOTED, 'edit-tags'), '#kotlin, concurrency kotlin');
        typeInto(editField(NOTED, 'edit-notes'), ' Read again later. ');
        (row(NOTED).querySelector('.edit') as HTMLFormElement).dispatchEvent(new Event('submit'));
        harness.detectChanges();
        expect(button(NOTED, 'edit-save').disabled).toBe(true);

        const req = http.expectOne((r) => r.method === 'PATCH' && r.url === `${LIST}/${NOTED.id}`);
        expect(req.request.headers.get('If-Match')).toBe('"1"');
        expect(req.request.body).toEqual({
          title: 'Coroutines, revisited',
          description: 'Read again later.',
          tags: ['kotlin', 'concurrency'],
        });
        req.flush({
          ...NOTED,
          title: 'Coroutines, revisited',
          displayTitle: 'Coroutines, revisited',
          description: 'Read again later.',
          tags: ['kotlin', 'concurrency'],
          version: 2,
        });
        await settle();
        flushTags([{ tag: 'concurrency', count: 1 }]);
        await settle();

        expect(row(NOTED).querySelector('.edit')).toBeNull();
        expect(text(row(NOTED).querySelector('.title'))).toBe('Coroutines, revisited');
        expect(text(row(NOTED).querySelector('.description'))).toBe('Read again later.');
        expect([...row(NOTED).querySelectorAll('.tags .tag')].map(text)).toEqual([
          '#kotlin',
          '#concurrency',
        ]);
      });

      it('sends a blank title and blank notes as null, to clear them', async () => {
        await open('/bookmarks?view=all', [NOTED]);
        button(NOTED, 'edit-open').click();
        harness.detectChanges();
        typeInto(editField(NOTED, 'edit-title'), '   ');
        typeInto(editField(NOTED, 'edit-tags'), '');
        typeInto(editField(NOTED, 'edit-notes'), '\n  \n');
        (row(NOTED).querySelector('.edit') as HTMLFormElement).dispatchEvent(new Event('submit'));
        const req = http.expectOne((r) => r.method === 'PATCH');
        expect(req.request.body).toEqual({ title: null, description: null, tags: [] });
        req.flush({
          ...NOTED,
          title: undefined,
          displayTitle: 'blog.example.net',
          description: undefined,
          tags: [],
          version: 2,
        });
        await settle();
        flushTags();
        await settle();
        expect(text(row(NOTED).querySelector('.title'))).toBe('blog.example.net');
        expect(row(NOTED).querySelector('.description')).toBeNull();
        expect(row(NOTED).querySelector('.tags')).toBeNull();
      });

      it('cancels without saving, with the button and with Escape', async () => {
        await open('/bookmarks?view=all', [NOTED]);
        button(NOTED, 'edit-open').click();
        harness.detectChanges();
        typeInto(editField(NOTED, 'edit-title'), 'Discarded');
        (row(NOTED).querySelector('.edit-cancel') as HTMLButtonElement).click();
        harness.detectChanges();
        expect(row(NOTED).querySelector('.edit')).toBeNull();
        expect(text(row(NOTED).querySelector('.title'))).toBe('Kotlin coroutines in practice');

        // Reopened: the discarded value is gone.
        button(NOTED, 'edit-open').click();
        harness.detectChanges();
        expect(editField(NOTED, 'edit-title').value).toBe('Kotlin coroutines in practice');
        (row(NOTED).querySelector('.edit') as HTMLFormElement).dispatchEvent(
          new KeyboardEvent('keydown', { key: 'Escape' }),
        );
        harness.detectChanges();
        expect(row(NOTED).querySelector('.edit')).toBeNull();
        http.expectNone((r) => r.method === 'PATCH');
      });

      it('keeps the editor and its values when saving fails, and saves on the next try', async () => {
        await open('/bookmarks?view=all', [NOTED]);
        button(NOTED, 'edit-open').click();
        harness.detectChanges();
        typeInto(editField(NOTED, 'edit-title'), 'Coroutines, revisited');
        (row(NOTED).querySelector('.edit') as HTMLFormElement).dispatchEvent(new Event('submit'));
        http
          .expectOne((r) => r.method === 'PATCH')
          .flush(...problem(500, 'Server error', 'Storage is unavailable.'));
        await settle();

        expect(text(row(NOTED).querySelector('.error'))).toBe(
          '“Kotlin coroutines in practice” could not be changed: Storage is unavailable.',
        );
        expect(row(NOTED).querySelector('.edit')).toBeTruthy();
        expect(editField(NOTED, 'edit-title').value).toBe('Coroutines, revisited');
        expect(button(NOTED, 'edit-save').disabled).toBe(false);

        (row(NOTED).querySelector('.edit') as HTMLFormElement).dispatchEvent(new Event('submit'));
        harness.detectChanges();
        expect(row(NOTED).querySelector('.error')).toBeNull();
        const retry = http.expectOne((r) => r.method === 'PATCH');
        expect(retry.request.body.title).toBe('Coroutines, revisited');
        retry.flush({ ...NOTED, displayTitle: 'Coroutines, revisited', version: 2 });
        await settle();
        flushTags();
        await settle();
        expect(row(NOTED).querySelector('.edit')).toBeNull();
      });

      it('keeps the editor after a conflict and loads the current version', async () => {
        await open('/bookmarks?view=all', [NOTED]);
        button(NOTED, 'edit-open').click();
        harness.detectChanges();
        (row(NOTED).querySelector('.edit') as HTMLFormElement).dispatchEvent(new Event('submit'));
        http.expectOne((r) => r.method === 'PATCH').flush(...problem(412, 'Precondition Failed'));
        await settle();
        http
          .expectOne((r) => r.method === 'GET' && r.url === `${LIST}/${NOTED.id}`)
          .flush({ ...NOTED, version: 5 });
        await settle();
        expect(row(NOTED).querySelector('.edit')).toBeTruthy();
        expect(text(row(NOTED).querySelector('.error'))).toBe(
          '“Kotlin coroutines in practice” could not be changed: It was changed elsewhere in the meantime. Save again to replace it with your values.',
        );

        (row(NOTED).querySelector('.edit') as HTMLFormElement).dispatchEvent(new Event('submit'));
        const retry = http.expectOne((r) => r.method === 'PATCH');
        expect(retry.request.headers.get('If-Match')).toBe('"5"');
        retry.flush({ ...NOTED, version: 6 });
        await settle();
        flushTags();
        await settle();
      });

      it('saves with the version the editor opened with, so a newer row is a conflict', async () => {
        await open('/bookmarks?view=all', [GUIDE]);
        button(GUIDE, 'edit-open').click();
        harness.detectChanges();
        typeInto(editField(GUIDE, 'edit-tags'), 'docs');
        // The fetched title arrives while the editor is open.
        const fetched = {
          ...GUIDE,
          title: 'The guide',
          displayTitle: 'The guide',
          description: 'How to use the fictitious tool.',
          metadataStatus: 'FETCHED' as const,
          version: 2,
        };
        changes.next({ type: 'BOOKMARK', op: 'UPDATED', id: GUIDE.id, at: '2026-10-22T09:00:00Z' });
        http.expectOne((r) => r.method === 'GET' && r.url === `${LIST}/${GUIDE.id}`).flush(fetched);
        await settle();
        expect(text(row(GUIDE).querySelector('.title'))).toBe('The guide');

        (row(GUIDE).querySelector('.edit') as HTMLFormElement).dispatchEvent(new Event('submit'));
        const save = http.expectOne((r) => r.method === 'PATCH');
        expect(save.request.headers.get('If-Match')).toBe('"1"');
        expect(save.request.body).toEqual({ title: null, description: null, tags: ['docs'] });
        save.flush(...problem(412, 'Precondition Failed'));
        await settle();
        http.expectOne((r) => r.method === 'GET' && r.url === `${LIST}/${GUIDE.id}`).flush(fetched);
        await settle();

        // The fetched values stay on the row; the editor keeps the user's values.
        expect(text(row(GUIDE).querySelector('.title'))).toBe('The guide');
        expect(text(row(GUIDE).querySelector('.error'))).toContain('changed elsewhere');
        expect(editField(GUIDE, 'edit-tags').value).toBe('docs');

        // Saving again overwrites knowingly, with the current version.
        (row(GUIDE).querySelector('.edit') as HTMLFormElement).dispatchEvent(new Event('submit'));
        harness.detectChanges();
        expect(row(GUIDE).querySelector('.error')).toBeNull();
        const retry = http.expectOne((r) => r.method === 'PATCH');
        expect(retry.request.headers.get('If-Match')).toBe('"2"');
        expect(retry.request.body.tags).toEqual(['docs']);
        retry.flush({
          ...fetched,
          title: undefined,
          description: undefined,
          tags: ['docs'],
          version: 3,
        });
        await settle();
        flushTags();
        await settle();
        expect(row(GUIDE).querySelector('.edit')).toBeNull();
      });

      it('keeps the editor current after marking the bookmark read while editing', async () => {
        await open('/bookmarks?view=all', [NOTED]);
        button(NOTED, 'edit-open').click();
        harness.detectChanges();
        button(NOTED, 'mark-read').click();
        http
          .expectOne((r) => r.method === 'PATCH')
          .flush({ ...NOTED, readAt: '2026-10-22T09:00:00Z', version: 2 });
        await settle();

        (row(NOTED).querySelector('.edit') as HTMLFormElement).dispatchEvent(new Event('submit'));
        const save = http.expectOne((r) => r.method === 'PATCH');
        expect(save.request.headers.get('If-Match')).toBe('"2"');
        save.flush({ ...NOTED, readAt: '2026-10-22T09:00:00Z', version: 3 });
        await settle();
        flushTags();
        await settle();
        expect(row(NOTED).querySelector('.edit')).toBeNull();
      });

      it('limits the notes to the 2000 characters the server accepts', async () => {
        await open('/bookmarks?view=all', [NOTED]);
        button(NOTED, 'edit-open').click();
        harness.detectChanges();
        expect(editField(NOTED, 'edit-notes').getAttribute('maxlength')).toBe('2000');
        expect(editField(NOTED, 'edit-title').getAttribute('maxlength')).toBe('500');
      });

      it('keeps the editor of another bookmark opened while a save was running', async () => {
        await open('/bookmarks?view=all', [NOTED, ESSAY]);
        button(NOTED, 'edit-open').click();
        harness.detectChanges();
        (row(NOTED).querySelector('.edit') as HTMLFormElement).dispatchEvent(new Event('submit'));
        const save = http.expectOne((r) => r.method === 'PATCH');
        button(ESSAY, 'edit-open').click();
        harness.detectChanges();
        typeInto(editField(ESSAY, 'edit-title'), 'Unsaved draft');
        save.flush({ ...NOTED, version: 2 });
        await settle();
        flushTags();
        await settle();
        expect(row(NOTED).querySelector('.edit')).toBeNull();
        expect(editField(ESSAY, 'edit-title').value).toBe('Unsaved draft');
      });

      it('drops an edited bookmark that leaves the queue, and closes the editor of a deleted one', async () => {
        await open('/bookmarks', [NOTED, ESSAY]);
        button(NOTED, 'edit-open').click();
        harness.detectChanges();
        (row(NOTED).querySelector('.edit') as HTMLFormElement).dispatchEvent(new Event('submit'));
        // Read on another device meanwhile: the answer is no longer in the queue.
        http
          .expectOne((r) => r.method === 'PATCH')
          .flush({ ...NOTED, readAt: '2026-10-22T09:00:00Z', version: 2 });
        await settle();
        flushTags();
        await settle();
        expect(ids()).toEqual([ESSAY.id]);

        button(ESSAY, 'edit-open').click();
        harness.detectChanges();
        expect(row(ESSAY).querySelector('.edit')).toBeTruthy();
        await deleteAndSettle(ESSAY);
        expect(root.querySelector('.edit')).toBeNull();
        snackBar.opened[0].dismiss();
        await settle();
      });
    });

    describe('undo after delete', () => {
      it('restores the bookmark from the Trash and shows it again', async () => {
        await open();
        await deleteAndSettle(ESSAY);
        expect(ids()).toEqual([GUIDE.id]);
        const undo = snackBar.opened[0];
        expect(undo.action).toBe('Undo');

        undo.act();
        await settle();
        const restore = http.expectOne(`/api/v1/trash/BOOKMARK/${ESSAY.id}/restore`);
        expect(restore.request.method).toBe('POST');
        restore.flush(null, { status: 204, statusText: 'No Content' });
        await settle();
        http
          .expectOne((r) => r.method === 'GET' && r.url === `${LIST}/${ESSAY.id}`)
          // Newer than GUIDE (same createdAt in the fixtures would order it by id, after GUIDE).
          .flush({ ...ESSAY, createdAt: '2026-10-20T09:00:00Z', version: 2 });
        await settle();
        flushTags();
        await settle();

        expect(ids()).toEqual([ESSAY.id, GUIDE.id]);
        expect(notifications.error).not.toHaveBeenCalled();
      });

      it('restores nothing when the snackbar is dismissed', async () => {
        await open();
        await deleteAndSettle(ESSAY);
        snackBar.opened[0].dismiss();
        await settle();
        http.expectNone((r) => r.url.includes('/trash/'));
        expect(ids()).toEqual([GUIDE.id]);
      });

      it('toasts a failed restore and leaves the bookmark out of the list', async () => {
        await open();
        await deleteAndSettle(ESSAY);
        snackBar.opened[0].act();
        await settle();
        http
          .expectOne(`/api/v1/trash/BOOKMARK/${ESSAY.id}/restore`)
          .flush(...problem(404, 'Not Found', 'The item is no longer in the Trash.'));
        await settle();
        expect(notifications.error).toHaveBeenCalledWith(
          '“On slow software” could not be restored: The item is no longer in the Trash.',
        );
        http.expectNone((r) => r.method === 'GET' && r.url === `${LIST}/${ESSAY.id}`);
        expect(ids()).toEqual([GUIDE.id]);
      });

      it('offers no undo when the delete fails', async () => {
        await open();
        button(ESSAY, 'delete').click();
        http
          .expectOne((r) => r.method === 'DELETE')
          .flush(...problem(500, 'Server error', 'Storage is unavailable.'));
        await settle();
        expect(snackBar.opened).toEqual([]);
        expect(row(ESSAY)).toBeTruthy();
      });
    });

    describe('to Inbox task', () => {
      const UUIDV7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

      it('creates an Inbox task linked to the bookmark', async () => {
        await open();
        button(ESSAY, 'to-inbox').click();
        harness.detectChanges();
        expect(button(ESSAY, 'to-inbox').disabled).toBe(true);
        const create = http.expectOne((r) => r.method === 'POST' && r.url === TASKS);
        const id = create.request.body.id as string;
        expect(id).toMatch(UUIDV7);
        expect(create.request.body).toEqual({
          id,
          title: 'On slow software',
          notesMd: ESSAY.url,
          list: 'INBOX',
        });
        create.flush({ id, title: 'On slow software' }, { status: 201, statusText: 'Created' });
        await settle();
        const link = http.expectOne((r) => r.method === 'POST' && r.url === LINKS);
        expect(link.request.body).toEqual({
          source: { type: 'TASK', id },
          target: { type: 'BOOKMARK', id: ESSAY.id },
          label: 'origin',
        });
        link.flush({}, { status: 201, statusText: 'Created' });
        await settle();

        expect(notifications.info).toHaveBeenCalledWith('Added to Inbox: On slow software');
        expect(button(ESSAY, 'to-inbox').disabled).toBe(false);
        // The bookmark stays where it was.
        expect(ids()).toEqual([ESSAY.id, GUIDE.id]);
      });

      it('says when the task was created but the link failed', async () => {
        await open();
        button(GUIDE, 'to-inbox').click();
        const create = http.expectOne((r) => r.method === 'POST' && r.url === TASKS);
        expect(create.request.body.title).toBe('docs.example.com');
        create.flush(
          { id: create.request.body.id, title: 'docs.example.com' },
          { status: 201, statusText: 'Created' },
        );
        await settle();
        http.expectOne((r) => r.url === LINKS).flush(...problem(500, 'Server error'));
        await settle();
        expect(notifications.info).toHaveBeenCalledWith(
          'Added to Inbox: docs.example.com (the link to the bookmark could not be made)',
        );
        expect(row(GUIDE).querySelector('.error')).toBeNull();
      });

      it('retries with the same task id after a failure, and a new one after success', async () => {
        await open();
        button(ESSAY, 'to-inbox').click();
        const first = http.expectOne((r) => r.method === 'POST' && r.url === TASKS);
        const id = first.request.body.id as string;
        first.flush(...problem(503, 'Service Unavailable', 'Try again in a moment.'));
        await settle();
        expect(text(row(ESSAY).querySelector('.error'))).toBe(
          '“On slow software” could not be changed: Try again in a moment.',
        );
        expect(notifications.info).not.toHaveBeenCalled();
        http.expectNone((r) => r.url === LINKS);

        button(ESSAY, 'to-inbox').click();
        harness.detectChanges();
        expect(row(ESSAY).querySelector('.error')).toBeNull();
        const retry = http.expectOne((r) => r.method === 'POST' && r.url === TASKS);
        expect(retry.request.body.id).toBe(id);
        retry.flush({ id, title: 'On slow software' }, { status: 201, statusText: 'Created' });
        await settle();
        http.expectOne((r) => r.url === LINKS).flush({}, { status: 201, statusText: '' });
        await settle();

        // Another task for the same bookmark later: a new id.
        button(ESSAY, 'to-inbox').click();
        const again = http.expectOne((r) => r.method === 'POST' && r.url === TASKS);
        expect(again.request.body.id).toMatch(UUIDV7);
        expect(again.request.body.id).not.toBe(id);
        again.flush(
          { id: again.request.body.id, title: 'On slow software' },
          { status: 201, statusText: 'Created' },
        );
        await settle();
        http.expectOne((r) => r.url === LINKS).flush({}, { status: 201, statusText: '' });
        await settle();
      });

      it('uses a separate task id per bookmark', async () => {
        await open();
        button(ESSAY, 'to-inbox').click();
        button(GUIDE, 'to-inbox').click();
        const [a, b] = http.match((r) => r.method === 'POST' && r.url === TASKS);
        expect(a.request.body.id).not.toBe(b.request.body.id);
        a.flush(...problem(500, 'Server error'));
        b.flush(...problem(500, 'Server error'));
        await settle();
      });

      it('does not create a second task while one is being created', async () => {
        await open();
        button(ESSAY, 'to-inbox').click();
        harness.detectChanges();
        // The button is disabled; a programmatic second click must not start another request.
        button(ESSAY, 'to-inbox').disabled = false;
        button(ESSAY, 'to-inbox').click();
        const reqs = http.match((r) => r.method === 'POST' && r.url === TASKS);
        expect(reqs).toHaveLength(1);
        reqs[0].flush(...problem(500, 'Server error'));
        await settle();
      });
    });
  });
});
