import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Route, UrlSegment, UrlSegmentGroup } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { ChangeEventsService } from '../../core/events';
import { NotificationService, provideHttp } from '../../core/http';
import { FakeEvents } from './list/mail-testing';
import mailRoutes, { mailListMatcher } from './mail.routes';

const FOLDER = '0192f0c4-0000-7000-8000-00000000f0a1';

function match(...paths: string[]) {
  const segments = paths.map((path) => new UrlSegment(path, {}));
  return mailListMatcher(segments, new UrlSegmentGroup(segments, {}), {} as Route);
}

describe('mailListMatcher', () => {
  it('matches the unified Inbox without consuming anything', () => {
    expect(match()).toEqual({ consumed: [] });
  });

  it('matches folders/:folderId with the folder id as a parameter', () => {
    const result = match('folders', FOLDER);
    expect(result?.consumed.map((s) => s.path)).toEqual(['folders', FOLDER]);
    expect(result?.posParams?.['folderId'].path).toBe(FOLDER);
  });

  it.each([[['folders']], [[FOLDER]], [['other', FOLDER]], [['folders', FOLDER, 'extra']]])(
    'does not match %j',
    (paths) => {
      expect(match(...paths)).toBeNull();
    },
  );
});

describe('mail routes', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'mail', children: mailRoutes }]),
        { provide: ChangeEventsService, useValue: new FakeEvents() },
        { provide: NotificationService, useValue: { error: vi.fn(), info: vi.fn() } },
      ],
    });
  });

  it('opens the list at /mail and keeps the page when navigating to a folder', async () => {
    const harness = await RouterTestingHarness.create();
    const page = await harness.navigateByUrl('/mail');
    const http = TestBed.inject(HttpTestingController);
    expect(harness.routeNativeElement?.tagName.toLowerCase()).toBe('app-mail-list-page');
    expect(document.title).toBe('Mail');
    const unified = http.match((req) => req.url === '/api/v1/mail/messages');
    expect(unified.length).toBeGreaterThan(0);
    expect(unified.every((req) => !req.request.params.has('folderId'))).toBe(true);

    const same = await harness.navigateByUrl(`/mail/folders/${FOLDER}`);
    expect(same).toBe(page);
    const folderList = http
      .match((req) => req.url === '/api/v1/mail/messages')
      .filter((req) => req.request.params.get('folderId') === FOLDER);
    expect(folderList).toHaveLength(1);
  });
});
