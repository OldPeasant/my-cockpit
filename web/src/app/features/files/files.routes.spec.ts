import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Route, UrlSegment, UrlSegmentGroup } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { NotificationService, provideHttp } from '../../core/http';
import filesRoutes, { driveMatcher } from './files.routes';

const DOCS = '0192f0c4-0000-7000-8000-0000000000d1';

function match(...paths: string[]) {
  const segments = paths.map((path) => new UrlSegment(path, {}));
  return driveMatcher(segments, new UrlSegmentGroup(segments, {}), {} as Route);
}

describe('driveMatcher', () => {
  it('matches the drive root without consuming anything', () => {
    expect(match()).toEqual({ consumed: [] });
  });

  it('matches one segment as the folderId', () => {
    const result = match(DOCS);
    expect(result?.consumed.map((s) => s.path)).toEqual([DOCS]);
    expect(result?.posParams?.['folderId'].path).toBe(DOCS);
  });

  it('does not match deeper paths', () => {
    expect(match(DOCS, 'extra')).toBeNull();
  });
});

describe('files routes', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'files', children: filesRoutes }]),
        { provide: NotificationService, useValue: { error: vi.fn(), info: vi.fn() } },
      ],
    });
  });

  it('opens the browser page at /files and keeps it when navigating to a folder', async () => {
    const harness = await RouterTestingHarness.create();
    const page = await harness.navigateByUrl('/files');
    const http = TestBed.inject(HttpTestingController);
    expect(harness.routeNativeElement?.tagName.toLowerCase()).toBe('app-files-page');
    const rootListing = http.match((req) => req.url === '/api/v1/drive');
    expect(rootListing.length).toBeGreaterThan(0);
    expect(rootListing.every((req) => !req.request.params.has('folderId'))).toBe(true);

    const same = await harness.navigateByUrl(`/files/${DOCS}`);
    expect(same).toBe(page);
    const folderListing = http
      .match((req) => req.url === '/api/v1/drive')
      .filter((req) => req.request.params.get('folderId') === DOCS);
    expect(folderListing).toHaveLength(1);
  });
});
