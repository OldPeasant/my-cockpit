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
});
