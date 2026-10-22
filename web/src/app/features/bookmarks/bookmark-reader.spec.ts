import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { NotificationService, provideHttp } from '../../core/http';
import routes from './bookmarks.routes';
import { bookmark } from './testing';

const ID = '0192f0c4-0000-7000-8000-000000000001';
const FILE = '0192f0c4-0000-7000-8000-0000000000f1';
const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

describe('BookmarkReader', () => {
  let harness: RouterTestingHarness;
  let root: HTMLElement;
  let http: HttpTestingController;
  let notifications: { error: ReturnType<typeof vi.fn>; info: ReturnType<typeof vi.fn> };

  async function open(): Promise<void> {
    notifications = { error: vi.fn(), info: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'bookmarks', children: routes }]),
        { provide: NotificationService, useValue: notifications },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(`/bookmarks/${ID}`);
    root = harness.routeNativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    await tick();
    harness.detectChanges();
    await harness.fixture.whenStable();
  }

  function text(el: Element | null): string {
    return el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  }

  afterEach(() => http.verify());

  it('renders a Markdown snapshot, safely, with the title and a link to the original', async () => {
    await open();
    http
      .expectOne(`/api/v1/bookmarks/${ID}`)
      .flush(bookmark(ID, { displayTitle: 'On slow software', snapshotFileId: FILE }));
    await settle();
    expect(text(root.querySelector('.hint'))).toBe('Loading the readable copy…');
    http
      .expectOne(`/api/v1/files/${FILE}/preview`)
      .flush('## Part one\n\nSome **bold** text.\n\n<script>alert(1)</script>', {
        headers: { 'Content-Type': 'text/markdown' },
      });
    await settle();

    expect(text(root.querySelector('h1'))).toBe('On slow software');
    expect(root.querySelector('.original')?.getAttribute('href')).toBe(
      `https://example.org/articles/${ID}`,
    );
    const body = root.querySelector('.text.markdown') as HTMLElement;
    expect(text(body.querySelector('h4'))).toBe('Part one');
    expect(text(body.querySelector('strong'))).toBe('bold');
    expect(body.querySelector('script')).toBeNull();
    expect(root.querySelector('.truncated')).toBeNull();
  });

  it('shows a plain snapshot as text and says when it is cut', async () => {
    await open();
    http.expectOne(`/api/v1/bookmarks/${ID}`).flush(bookmark(ID, { snapshotFileId: FILE }));
    await settle();
    http.expectOne(`/api/v1/files/${FILE}/preview`).flush('<b>not markup</b>', {
      headers: { 'Content-Type': 'text/plain', 'X-Preview-Truncated': 'true' },
    });
    await settle();
    expect(text(root.querySelector('.text.plain'))).toBe('<b>not markup</b>');
    expect(root.querySelector('.truncated')).toBeTruthy();
  });

  it('says when there is no snapshot, and marks the bookmark read', async () => {
    await open();
    http.expectOne(`/api/v1/bookmarks/${ID}`).flush(bookmark(ID));
    await settle();
    expect(text(root.querySelector('.empty'))).toContain('No readable copy');

    (root.querySelector('.mark-read') as HTMLButtonElement).click();
    const req = http.expectOne((r) => r.method === 'PATCH');
    expect(req.request.body).toEqual({ read: true });
    req.flush(bookmark(ID, { readAt: '2026-10-22T09:00:00Z', version: 2 }));
    await settle();
    expect(text(root.querySelector('.mark-read'))).toBe('Mark unread');
    expect(text(root.querySelector('.read-mark'))).toBe('read');
  });

  it('shows an error when the bookmark cannot be loaded', async () => {
    await open();
    http
      .expectOne(`/api/v1/bookmarks/${ID}`)
      .flush(
        { type: 'about:blank', title: 'Not Found', status: 404 },
        { status: 404, statusText: 'Not Found' },
      );
    await settle();
    expect(root.querySelector('.load-error')?.getAttribute('role')).toBe('alert');
  });

  describe('gaps', () => {
    function fail(status: number, title: string, detail?: string) {
      return [
        { type: 'about:blank', title, status, ...(detail ? { detail } : {}) },
        { status, statusText: title },
      ] as const;
    }

    it('tries loading the bookmark again after an error', async () => {
      await open();
      http.expectOne(`/api/v1/bookmarks/${ID}`).flush(...fail(500, 'Server error'));
      await settle();
      (root.querySelector('.load-error button') as HTMLButtonElement).click();
      http
        .expectOne(`/api/v1/bookmarks/${ID}`)
        .flush(bookmark(ID, { displayTitle: 'On slow software' }));
      await settle();
      expect(root.querySelector('.load-error')).toBeNull();
      expect(text(root.querySelector('h1'))).toBe('On slow software');
    });

    it('shows the bookmark with an error when its snapshot cannot be loaded', async () => {
      await open();
      http
        .expectOne(`/api/v1/bookmarks/${ID}`)
        .flush(bookmark(ID, { displayTitle: 'On slow software', snapshotFileId: FILE }));
      await settle();
      http
        .expectOne(`/api/v1/files/${FILE}/preview`)
        .flush(...fail(404, 'Not Found', 'The file is gone.'));
      await settle();
      expect(text(root.querySelector('h1'))).toBe('On slow software');
      const error = root.querySelector('.load-error') as HTMLElement;
      expect(error.getAttribute('role')).toBe('alert');
      expect(text(error)).toBe('The readable copy could not be loaded: The file is gone.');
      expect(root.querySelector('.hint')).toBeNull();
      expect(root.querySelector('.text')).toBeNull();
      expect((root.querySelector('.mark-read') as HTMLButtonElement).disabled).toBe(false);
    });

    it('toasts a conflict when marking read and shows the current version', async () => {
      await open();
      http.expectOne(`/api/v1/bookmarks/${ID}`).flush(bookmark(ID));
      await settle();
      (root.querySelector('.mark-read') as HTMLButtonElement).click();
      harness.detectChanges();
      expect((root.querySelector('.mark-read') as HTMLButtonElement).disabled).toBe(true);
      http
        .expectOne((r) => r.method === 'PATCH')
        .flush(...fail(412, 'Precondition Failed', 'Changed elsewhere.'));
      await settle();
      expect(notifications.error).toHaveBeenCalledWith('Changed elsewhere.');
      http
        .expectOne((r) => r.method === 'GET' && r.url === `/api/v1/bookmarks/${ID}`)
        .flush(bookmark(ID, { readAt: '2026-10-22T09:00:00Z', version: 3 }));
      await settle();
      expect(text(root.querySelector('.mark-read'))).toBe('Mark unread');
      expect((root.querySelector('.mark-read') as HTMLButtonElement).disabled).toBe(false);
    });

    it('keeps the bookmark when reloading after a conflict fails', async () => {
      await open();
      http.expectOne(`/api/v1/bookmarks/${ID}`).flush(bookmark(ID));
      await settle();
      (root.querySelector('.mark-read') as HTMLButtonElement).click();
      http.expectOne((r) => r.method === 'PATCH').flush(...fail(409, 'Conflict'));
      await settle();
      http.expectOne(`/api/v1/bookmarks/${ID}`).flush(...fail(500, 'Server error'));
      await settle();
      expect(notifications.error).toHaveBeenCalledTimes(1);
      expect(text(root.querySelector('.mark-read'))).toBe('Mark read');
      expect((root.querySelector('.mark-read') as HTMLButtonElement).disabled).toBe(false);
    });

    it('ignores a bookmark and a snapshot that answer for the previous id', async () => {
      const OTHER = '0192f0c4-0000-7000-8000-000000000002';
      await open();
      const first = http.expectOne(`/api/v1/bookmarks/${ID}`);
      await harness.navigateByUrl(`/bookmarks/${OTHER}`);
      http
        .expectOne(`/api/v1/bookmarks/${OTHER}`)
        .flush(bookmark(OTHER, { displayTitle: 'The second one', snapshotFileId: FILE }));
      await settle();
      const preview = http.expectOne(`/api/v1/files/${FILE}/preview`);
      first.flush(bookmark(ID, { displayTitle: 'The first one' }));
      await settle();
      expect(text(root.querySelector('h1'))).toBe('The second one');

      // Going back while the snapshot of the second one loads: its answer is dropped too.
      await harness.navigateByUrl(`/bookmarks/${ID}`);
      const again = http.expectOne(`/api/v1/bookmarks/${ID}`);
      preview.flush('Late text', { headers: { 'Content-Type': 'text/plain' } });
      await settle();
      again.flush(bookmark(ID, { displayTitle: 'The first one' }));
      await settle();
      expect(text(root.querySelector('h1'))).toBe('The first one');
      expect(root.querySelector('.text')).toBeNull();
      expect(text(root.querySelector('.empty'))).toContain('No readable copy');
    });

    it('ignores a failed load that answers for the previous id', async () => {
      const OTHER = '0192f0c4-0000-7000-8000-000000000002';
      await open();
      const first = http.expectOne(`/api/v1/bookmarks/${ID}`);
      await harness.navigateByUrl(`/bookmarks/${OTHER}`);
      http.expectOne(`/api/v1/bookmarks/${OTHER}`).flush(bookmark(OTHER));
      await settle();
      first.flush(...fail(404, 'Not Found'));
      await settle();
      expect(root.querySelector('.load-error')).toBeNull();
      expect(root.querySelector('h1')).toBeTruthy();
    });

    it('toasts another failure when marking read without reloading', async () => {
      await open();
      http.expectOne(`/api/v1/bookmarks/${ID}`).flush(bookmark(ID));
      await settle();
      (root.querySelector('.mark-read') as HTMLButtonElement).click();
      http.expectOne((r) => r.method === 'PATCH').flush(...fail(500, 'Server error'));
      await settle();
      expect(notifications.error).toHaveBeenCalledWith('Server error');
      http.expectNone(`/api/v1/bookmarks/${ID}`);
      expect(text(root.querySelector('.mark-read'))).toBe('Mark read');
    });
  });
});
