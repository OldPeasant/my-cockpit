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
});
