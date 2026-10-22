import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
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

describe('BookmarksPage', () => {
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
    expect(row(ESSAY).querySelector('.icon img')?.getAttribute('src')).toBe(ESSAY.favicon);
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
    expect(notifications.info).toHaveBeenCalledWith('Moved “On slow software” to the Trash');
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

    it('does not reload the first page quietly while more pages are loaded', async () => {
      await open('/bookmarks', [ESSAY], 'c1');
      changes.next({ type: 'BOOKMARK', op: 'CREATED', id: NEW.id, at: AT });
      await settle();
      expectNoList();
      flushTags();
      await settle();
      expect(root.querySelectorAll('.bookmark')).toHaveLength(1);
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

    it('does not load a changed bookmark that is not shown', async () => {
      await open();
      changes.next({ type: 'BOOKMARK', op: 'UPDATED', id: NEW.id, at: AT });
      await settle();
      http.expectNone(`${LIST}/${NEW.id}`);
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
  });
});
