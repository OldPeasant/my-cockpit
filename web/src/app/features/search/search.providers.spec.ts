import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Injector } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subscription } from 'rxjs';
import { SearchResponse } from '../../api/generated';
import { NotificationService, provideHttp } from '../../core/http';
import { PALETTE_PROVIDERS, PaletteItem } from '../../shell/shell-tokens';
import { CommandsPaletteProvider } from './commands-palette.provider';
import { SearchPaletteProvider } from './search-palette.provider';
import { LazySearchPaletteProvider, provideSearch } from './search.providers';

const RESPONSE: SearchResponse = {
  groups: [
    {
      type: 'TASK',
      items: [
        {
          type: 'TASK',
          id: '0192f0c4-0000-7000-8000-0000000000a1',
          title: 'Renew passport',
          deepLink: '/tasks/0192f0c4-0000-7000-8000-0000000000a1',
          updatedAt: '2026-10-19T10:00:00Z',
        },
      ],
    },
  ],
};

function isSearch(q: string) {
  return (r: { method: string; url: string; params: { get(name: string): string | null } }) =>
    r.method === 'GET' && r.url === '/api/v1/search' && r.params.get('q') === q;
}

describe('LazySearchPaletteProvider', () => {
  let http: HttpTestingController;
  let provider: LazySearchPaletteProvider;
  let subscriptions: Subscription[];
  let create: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    subscriptions = [];
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        LazySearchPaletteProvider,
        { provide: NotificationService, useValue: { error: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    provider = TestBed.inject(LazySearchPaletteProvider);
    create = vi.spyOn(Injector, 'create');
  });

  afterEach(() => {
    subscriptions.forEach((s) => s.unsubscribe());
    http.verify();
    vi.restoreAllMocks();
  });

  /** How often the lazily imported search provider was instantiated. */
  function loads(): number {
    return create.mock.calls.filter(([options]: unknown[]) => {
      const providers = (options as { providers?: unknown[] }).providers ?? [];
      return providers.includes(SearchPaletteProvider);
    }).length;
  }

  function collect(query: string): { rows: readonly PaletteItem[] | undefined; error: unknown } {
    const result: { rows: readonly PaletteItem[] | undefined; error: unknown } = {
      rows: undefined,
      error: undefined,
    };
    subscriptions.push(
      provider.items(query).subscribe({
        next: (rows) => (result.rows = rows),
        error: (error: unknown) => (result.error = error),
      }),
    );
    return result;
  }

  function searchRequest(q: string) {
    return vi.waitFor(() => http.expectOne(isSearch(q)), { timeout: 2000 });
  }

  it('loads the search provider on first use and delegates the query to it', async () => {
    expect(loads()).toBe(0);
    const result = collect('passport');
    (await searchRequest('passport')).flush(RESPONSE);
    await vi.waitFor(() => expect(result.rows?.map((r) => r.label)).toEqual(['Renew passport']));
    expect(loads()).toBe(1);
  });

  it('loads the search provider only once for later and concurrent queries', async () => {
    const first = collect('pass');
    const second = collect('passport');
    (await searchRequest('pass')).flush(RESPONSE);
    (await searchRequest('passport')).flush(RESPONSE);
    await vi.waitFor(() => expect(first.rows).toHaveLength(1));
    await vi.waitFor(() => expect(second.rows).toHaveLength(1));

    const third = collect('renew');
    (await searchRequest('renew')).flush({ groups: [] });
    await vi.waitFor(() => expect(third.rows).toEqual([]));
    expect(loads()).toBe(1);
  });

  it('fails the query when loading fails and loads again with the next query', async () => {
    create.mockImplementationOnce(() => {
      throw new Error('Failed to fetch dynamically imported module');
    });
    const failed = collect('passport');
    await vi.waitFor(() => expect(failed.error).toBeInstanceOf(Error));
    expect(failed.rows).toBeUndefined();
    http.expectNone('/api/v1/search');

    const retried = collect('passport');
    (await searchRequest('passport')).flush(RESPONSE);
    await vi.waitFor(() => expect(retried.rows?.map((r) => r.label)).toEqual(['Renew passport']));
    expect(loads()).toBe(2);
  });

  it('keeps the loaded provider after a failed search', async () => {
    const failed = collect('passport');
    (await searchRequest('passport')).flush(
      { title: 'Boom', status: 500 },
      { status: 500, statusText: 'Server Error' },
    );
    await vi.waitFor(() => expect(failed.error).toBeDefined());

    const next = collect('passport');
    (await searchRequest('passport')).flush(RESPONSE);
    await vi.waitFor(() => expect(next.rows).toHaveLength(1));
    expect(loads()).toBe(1);
  });

  it('sends no request when the query is dropped while the provider is loading', async () => {
    collect('passport');
    subscriptions[0].unsubscribe();
    // Give the import, the debounce and a possible request time to happen.
    await new Promise((resolve) => setTimeout(resolve, 400));
    http.expectNone('/api/v1/search');
  });

  it('answers too short queries with no rows and without a request', async () => {
    const result = collect('p');
    await vi.waitFor(() => expect(result.rows).toEqual([]));
    http.expectNone('/api/v1/search');
  });
});

describe('provideSearch', () => {
  it('adds the commands, then the lazy search, to the palette providers', () => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttp(), provideHttpClientTesting(), provideSearch()],
    });
    expect(TestBed.inject(PALETTE_PROVIDERS)).toEqual([
      expect.any(CommandsPaletteProvider),
      expect.any(LazySearchPaletteProvider),
    ]);
  });
});
