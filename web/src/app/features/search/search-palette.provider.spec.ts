import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialogRef } from '@angular/material/dialog';
import { provideRouter, Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { SearchResponse } from '../../api/generated';
import { NotificationService, provideHttp, SILENT_ERRORS } from '../../core/http';
import { CommandPalette } from '../../shell/command-palette';
import { provideNavigationPalette } from '../../shell/navigation-palette.provider';
import { CAPTURE_DIALOG, PaletteItem } from '../../shell/shell-tokens';
import { SEARCH_DEBOUNCE_MS, SEARCH_LIMIT, SearchPaletteProvider } from './search-palette.provider';
import { provideSearch } from './search.providers';

const UPDATED = '2026-10-19T10:00:00Z';

const PASSPORT: SearchResponse = {
  groups: [
    {
      type: 'TASK',
      items: [
        {
          type: 'TASK',
          id: '0192f0c4-0000-7000-8000-000000000001',
          title: 'Renew passport',
          subtitle: 'Inbox',
          deepLink: '/tasks/0192f0c4-0000-7000-8000-000000000001',
          updatedAt: UPDATED,
        },
        {
          type: 'TASK',
          id: '0192f0c4-0000-7000-8000-000000000002',
          title: 'Passport photos',
          deepLink: '/tasks/0192f0c4-0000-7000-8000-000000000002',
          updatedAt: UPDATED,
        },
      ],
    },
    {
      type: 'NOTE',
      items: [
        {
          type: 'NOTE',
          id: '0192f0c4-0000-7000-8000-000000000003',
          title: 'Passport office hours',
          deepLink: '/notes/0192f0c4-0000-7000-8000-000000000003',
          updatedAt: UPDATED,
        },
        // No resolver for its module yet: nothing to open, so not listed.
        {
          type: 'NOTE',
          id: '0192f0c4-0000-7000-8000-000000000004',
          title: 'Unresolved passport note',
          updatedAt: UPDATED,
        },
      ],
    },
  ],
};

function isSearch(q: string) {
  return (r: { method: string; url: string; params: { get(name: string): string | null } }) =>
    r.method === 'GET' && r.url === '/api/v1/search' && r.params.get('q') === q;
}

describe('SearchPaletteProvider', () => {
  let http: HttpTestingController;
  let provider: SearchPaletteProvider;
  let notifications: { error: ReturnType<typeof vi.fn> };
  let subscription: Subscription | undefined;

  beforeEach(() => {
    vi.useFakeTimers();
    notifications = { error: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        SearchPaletteProvider,
        { provide: NotificationService, useValue: notifications },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    provider = TestBed.inject(SearchPaletteProvider);
  });

  afterEach(() => {
    subscription?.unsubscribe();
    subscription = undefined;
    http.verify();
    vi.useRealTimers();
  });

  function collect(query: string): { rows: readonly PaletteItem[] | undefined; error: unknown } {
    const result: { rows: readonly PaletteItem[] | undefined; error: unknown } = {
      rows: undefined,
      error: undefined,
    };
    subscription = provider.items(query).subscribe({
      next: (rows) => (result.rows = rows),
      error: (error: unknown) => (result.error = error),
    });
    return result;
  }

  it('waits for the debounce, then searches all types with a limit, silently', () => {
    collect('  passport ');
    vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS - 1);
    http.expectNone('/api/v1/search');
    vi.advanceTimersByTime(1);
    const req = http.expectOne(isSearch('passport'));
    expect(req.request.params.get('limit')).toBe(String(SEARCH_LIMIT));
    expect(req.request.params.has('types')).toBe(false);
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush(PASSPORT);
  });

  it('groups the results by item type in the server order and skips items without a deep link', () => {
    const result = collect('passport');
    vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS);
    http.expectOne(isSearch('passport')).flush(PASSPORT);
    expect(result.rows?.map((r) => [r.group, r.label, r.hint])).toEqual([
      ['Task', 'Renew passport', 'Inbox'],
      ['Task', 'Passport photos', undefined],
      ['Note', 'Passport office hours', undefined],
    ]);
    expect(new Set(result.rows?.map((r) => r.id)).size).toBe(3);
  });

  it('opens the deep link of a chosen result', () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const result = collect('passport');
    vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS);
    http.expectOne(isSearch('passport')).flush(PASSPORT);
    result.rows![2].run();
    expect(navigate).toHaveBeenCalledWith('/notes/0192f0c4-0000-7000-8000-000000000003');
  });

  it.each(['', ' ', 'p', ' p '])('does not search for the too short query %j', (query) => {
    const result = collect(query);
    expect(result.rows).toEqual([]);
    vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS);
    http.expectNone('/api/v1/search');
  });

  it('sends nothing when the query changes within the debounce (unsubscribed)', () => {
    collect('pass');
    vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS - 1);
    subscription!.unsubscribe();
    vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS);
    http.expectNone('/api/v1/search');
  });

  it('passes a server error on without a toast (the palette keeps its other rows)', () => {
    const result = collect('passport');
    vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS);
    http
      .expectOne(isSearch('passport'))
      .flush(
        { title: 'Boom', status: 500 },
        {
          status: 500,
          statusText: 'Server Error',
          headers: { 'Content-Type': 'application/problem+json' },
        },
      );
    expect(result.error).toBeDefined();
    expect(notifications.error).not.toHaveBeenCalled();
  });
});

describe('Command palette with provideSearch()', () => {
  let fixture: ComponentFixture<CommandPalette>;
  let root: HTMLElement;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;
  let openCapture: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    close = vi.fn();
    openCapture = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        provideNavigationPalette(),
        provideSearch(),
        { provide: CAPTURE_DIALOG, useValue: { open: openCapture } },
        { provide: NotificationService, useValue: { error: vi.fn() } },
        { provide: MatDialogRef, useValue: { close } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(CommandPalette);
    root = fixture.nativeElement as HTMLElement;
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  function input(): HTMLInputElement {
    return root.querySelector('input')!;
  }

  async function type(value: string): Promise<void> {
    input().value = value;
    input().dispatchEvent(new Event('input'));
    await fixture.whenStable();
  }

  async function key(name: string): Promise<void> {
    input().dispatchEvent(new KeyboardEvent('keydown', { key: name, cancelable: true }));
    await fixture.whenStable();
  }

  function labels(): string[] {
    return Array.from(root.querySelectorAll('[role="option"] .label')).map(
      (e) => e.textContent?.trim() ?? '',
    );
  }

  function groupNames(): string[] {
    return Array.from(root.querySelectorAll('.group-name')).map((e) => e.textContent?.trim() ?? '');
  }

  function active(): string | undefined {
    return root.querySelector('[role="option"].active .label')?.textContent?.trim();
  }

  /** The search request for `q`, once the debounce has passed. */
  function searchRequest(q: string): Promise<TestRequest> {
    return vi.waitFor(() => http.expectOne(isSearch(q)), { timeout: 2000 });
  }

  async function shows(expected: string[]): Promise<void> {
    await vi.waitFor(async () => {
      await fixture.whenStable();
      expect(labels()).toEqual(expected);
    });
  }

  it('offers the "New task" command, which opens the quick capture dialog', async () => {
    expect(groupNames()).toEqual(['Go to', 'Commands']);
    await type('new');
    expect(labels()).toEqual(['New task']);
    await key('Enter');
    expect(close).toHaveBeenCalled();
    expect(openCapture).toHaveBeenCalled();
    (await searchRequest('new')).flush({ groups: [] });
  });

  it('shows the results grouped by type as you type, below matching commands', async () => {
    await type('pass');
    (await searchRequest('pass')).flush(PASSPORT);
    await shows(['Renew passport', 'Passport photos', 'Passport office hours']);
    expect(groupNames()).toEqual(['Task', 'Note']);
    expect(root.querySelector('[role="option"] .hint')?.textContent?.trim()).toBe('Inbox');

    await type('inbox');
    (await searchRequest('inbox')).flush({
      groups: [
        {
          type: 'PROJECT',
          items: [
            {
              type: 'PROJECT',
              id: '0192f0c4-0000-7000-8000-000000000005',
              title: 'Inbox zero',
              deepLink: '/projects/0192f0c4-0000-7000-8000-000000000005',
              updatedAt: UPDATED,
            },
          ],
        },
      ],
    } satisfies SearchResponse);
    await shows(['Inbox', 'Inbox zero']);
    expect(groupNames()).toEqual(['Go to', 'Project']);
  });

  it('ignores the response of an older query (its request is cancelled)', async () => {
    await type('pass');
    const stale = await searchRequest('pass');
    await type('photos');
    expect(stale.cancelled).toBe(true);
    (await searchRequest('photos')).flush({ groups: [PASSPORT.groups[0]] });
    await shows(['Renew passport', 'Passport photos']);
  });

  it('never sends the requests of queries replaced within the debounce', async () => {
    await type('pa');
    await type('pas');
    await type('passport');
    (await searchRequest('passport')).flush(PASSPORT);
    await shows(['Renew passport', 'Passport photos', 'Passport office hours']);
    http.expectNone(isSearch('pa'));
    http.expectNone(isSearch('pas'));
  });

  it('moves through the results with the arrow keys and opens the selected one with Enter', async () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    await type('passport');
    (await searchRequest('passport')).flush(PASSPORT);
    await shows(['Renew passport', 'Passport photos', 'Passport office hours']);
    expect(active()).toBe('Renew passport');
    await key('ArrowDown');
    await key('ArrowDown');
    expect(active()).toBe('Passport office hours');
    await key('ArrowDown');
    expect(active()).toBe('Renew passport');
    await key('ArrowUp');
    expect(active()).toBe('Passport office hours');
    await key('ArrowUp');
    expect(active()).toBe('Passport photos');
    await key('Enter');
    expect(close).toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledWith('/tasks/0192f0c4-0000-7000-8000-000000000002');
  });

  it('keeps the commands when the search fails', async () => {
    await type('new');
    (await searchRequest('new')).flush(
      { title: 'Boom', status: 500 },
      { status: 500, statusText: 'Server Error' },
    );
    await shows(['New task']);
  });
});
