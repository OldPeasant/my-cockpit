import { Component, signal, viewChild } from '@angular/core';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ItemRefDto, LinkEndResponse, LinkResponse } from '../../api/generated';
import { NotificationService, provideHttp } from '../../core/http';
import { LinkedCreate } from './linked-create';
import { LINK_SEARCH_DEBOUNCE_MS, LinkedItems } from './linked-items';
import { LinksService } from './links.service';

const TASK: ItemRefDto = { type: 'TASK', id: '0192f0c4-0000-7000-8000-0000000000a1' };
const NOTE_ID = '0192f0c4-0000-7000-8000-0000000000b1';
const EVENT_ID = '0192f0c4-0000-7000-8000-0000000000b2';
const GONE_ID = '0192f0c4-0000-7000-8000-0000000000b3';
const OTHER_TASK_ID = '0192f0c4-0000-7000-8000-0000000000b4';

const tick = (ms = 0): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms));

function end(type: LinkEndResponse['type'], id: string, title?: string): LinkEndResponse {
  if (!title) return { type, id, available: false };
  return {
    type,
    id,
    available: true,
    title,
    subtitle: `${title} subtitle`,
    deepLink: `/${type.toLowerCase()}s/${id}`,
  };
}

function link(
  id: string,
  other: LinkEndResponse,
  direction: LinkResponse['direction'] = 'OUTGOING',
  origin: LinkResponse['origin'] = 'MANUAL',
): LinkResponse {
  const self: LinkEndResponse = { ...TASK, available: true, title: 'Plan holiday' };
  return {
    id,
    direction,
    origin,
    createdAt: '2026-10-19T08:00:00Z',
    source: direction === 'OUTGOING' ? self : other,
    target: direction === 'OUTGOING' ? other : self,
  };
}

@Component({
  imports: [LinkedItems, LinkedCreate],
  template: `
    <app-linked-items [item]="item()">
      <button type="button" appLinkedCreate (click)="created = created + 1">New note</button>
    </app-linked-items>
  `,
})
class Host {
  readonly item = signal<ItemRefDto>(TASK);
  readonly panel = viewChild.required(LinkedItems);
  created = 0;
}

describe('LinkedItems', () => {
  let fixture: ComponentFixture<Host>;
  let root: HTMLElement;
  let http: HttpTestingController;

  async function setup(links: LinkResponse[]): Promise<void> {
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: { error: vi.fn(), info: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Host);
    root = fixture.nativeElement as HTMLElement;
    await settle();
    expectList(TASK).flush({ items: links });
    await settle();
  }

  function expectList(item: ItemRefDto) {
    return http.expectOne(
      (r) =>
        r.method === 'GET' &&
        r.url === '/api/v1/links' &&
        r.params.get('type') === item.type &&
        r.params.get('id') === item.id,
    );
  }

  async function settle(): Promise<void> {
    await tick();
    await fixture.whenStable();
  }

  afterEach(() => http.verify());

  function titles(): string[] {
    return [...root.querySelectorAll('.links .row .title')].map((el) => el.textContent!.trim());
  }

  function button(text: string): HTMLButtonElement {
    return [...root.querySelectorAll('button')].find(
      (b) => b.textContent?.trim() === text,
    ) as HTMLButtonElement;
  }

  async function type(text: string): Promise<void> {
    const input = root.querySelector('input.query') as HTMLInputElement;
    input.value = text;
    input.dispatchEvent(new Event('input'));
    // Let the query reach the search stream before the debounce wait starts.
    await settle();
    await tick(LINK_SEARCH_DEBOUNCE_MS + 10);
    await settle();
  }

  it('lists the other end of links in both directions with icon, subtitle and deep link', async () => {
    await setup([
      link('l1', end('NOTE', NOTE_ID, 'Holiday ideas')),
      link('l2', end('EVENT', EVENT_ID, 'Flight to Lisbon'), 'INCOMING'),
    ]);

    expect(titles()).toEqual(['Holiday ideas', 'Flight to Lisbon']);
    const first = root.querySelector('.links .row') as HTMLElement;
    expect(first.querySelector('a.title')?.getAttribute('href')).toBe(`/notes/${NOTE_ID}`);
    expect(first.querySelector('.subtitle')?.textContent).toContain('Note');
    expect(first.querySelector('.subtitle')?.textContent).toContain('Holiday ideas subtitle');
    expect(first.querySelector('app-item-type-icon')?.getAttribute('data-type')).toBe('NOTE');
    expect(root.querySelector('.count')?.textContent?.trim()).toBe('2');
  });

  it('greys out unavailable items without a deep link, but keeps them removable', async () => {
    await setup([link('l1', end('FILE', GONE_ID))]);

    const row = root.querySelector('.links .row') as HTMLElement;
    expect(row.classList).toContain('unavailable');
    expect(row.querySelector('a')).toBeNull();
    expect(row.querySelector('.title')?.textContent?.trim()).toBe('Unavailable item');
    expect(row.querySelector('button.remove')).not.toBeNull();
  });

  it('shows an inline (SYNC) link without a remove button', async () => {
    await setup([link('l1', end('NOTE', NOTE_ID, 'Holiday ideas'), 'INCOMING', 'SYNC')]);

    expect(root.querySelector('button.remove')).toBeNull();
    expect(root.querySelector('.inline')?.textContent?.trim()).toBe('In text');
  });

  it('shows an empty state', async () => {
    await setup([]);

    expect(root.querySelector('.empty')?.textContent).toContain('No linked items yet');
  });

  it('removes a link', async () => {
    await setup([link('l1', end('NOTE', NOTE_ID, 'Holiday ideas'))]);

    (
      root.querySelector('button[aria-label="Remove link to Holiday ideas"]') as HTMLElement
    ).click();
    await settle();
    http
      .expectOne({ method: 'DELETE', url: '/api/v1/links/l1' })
      .flush(null, { status: 204, statusText: 'No Content' });
    await settle();

    expect(titles()).toEqual([]);
  });

  it('shows the error when removing fails and keeps the link', async () => {
    await setup([link('l1', end('NOTE', NOTE_ID, 'Holiday ideas'))]);

    (root.querySelector('button.remove') as HTMLElement).click();
    await settle();
    http
      .expectOne({ method: 'DELETE', url: '/api/v1/links/l1' })
      .flush(
        { status: 409, title: 'Conflict', detail: 'Remove it in its source.' },
        { status: 409, statusText: 'Conflict' },
      );
    await settle();

    expect(titles()).toEqual(['Holiday ideas']);
    expect(root.querySelector('[role="alert"]')?.textContent).toContain('Remove it in its source.');
  });

  it('searches as you type and links the chosen item', async () => {
    await setup([link('l1', end('NOTE', NOTE_ID, 'Holiday ideas'))]);

    button('Add link').click();
    await settle();
    expect(document.activeElement).toBe(root.querySelector('input.query'));
    await type('lis');

    const search = http.expectOne((r) => r.url === '/api/v1/search');
    expect(search.request.params.get('q')).toBe('lis');
    search.flush({
      groups: [
        {
          type: 'EVENT',
          items: [{ type: 'EVENT', id: EVENT_ID, title: 'Flight to Lisbon', updatedAt: 'x' }],
        },
        {
          type: 'NOTE',
          // Already linked: not offered again.
          items: [{ type: 'NOTE', id: NOTE_ID, title: 'Holiday ideas', updatedAt: 'x' }],
        },
        {
          type: 'TASK',
          // The item itself: not offered.
          items: [{ ...TASK, title: 'Plan holiday', updatedAt: 'x' }],
        },
      ],
    });
    await settle();

    const options = [...root.querySelectorAll('[role="option"]')];
    expect(options.map((o) => o.querySelector('.title')?.textContent?.trim())).toEqual([
      'Flight to Lisbon',
    ]);
    expect(root.querySelector('.group-name')?.textContent?.trim()).toBe('Event');

    (options[0] as HTMLElement).click();
    await settle();
    const create = http.expectOne({ method: 'POST', url: '/api/v1/links' });
    expect(create.request.body).toEqual({ source: TASK, target: { type: 'EVENT', id: EVENT_ID } });
    create.flush(link('l2', end('EVENT', EVENT_ID, 'Flight to Lisbon')), {
      status: 201,
      statusText: 'Created',
    });
    await settle();

    expect(titles()).toEqual(['Holiday ideas', 'Flight to Lisbon']);
    expect(root.querySelector('input.query')).toBeNull();
  });

  it('links the active result with the keyboard', async () => {
    await setup([]);
    button('Add link').click();
    await settle();
    await type('task');
    http
      .expectOne((r) => r.url === '/api/v1/search')
      .flush({
        groups: [
          {
            type: 'TASK',
            items: [
              { type: 'TASK', id: OTHER_TASK_ID, title: 'Book hotel', updatedAt: 'x' },
              { type: 'TASK', id: NOTE_ID, title: 'Pack bags', updatedAt: 'x' },
            ],
          },
        ],
      });
    await settle();

    const input = root.querySelector('input.query') as HTMLInputElement;
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' }));
    await settle();
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    await settle();

    const create = http.expectOne({ method: 'POST', url: '/api/v1/links' });
    expect(create.request.body.target).toEqual({ type: 'TASK', id: NOTE_ID });
    create.flush(link('l9', end('TASK', NOTE_ID, 'Pack bags')), {
      status: 201,
      statusText: 'Created',
    });
    await settle();
    expect(titles()).toEqual(['Pack bags']);
  });

  it('closes the search with Escape', async () => {
    await setup([]);
    button('Add link').click();
    await settle();

    const input = root.querySelector('input.query') as HTMLInputElement;
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await settle();

    expect(root.querySelector('input.query')).toBeNull();
    expect(document.activeElement).toBe(button('Add link'));
  });

  it('shows the create-linked slot and reloads on request', async () => {
    await setup([]);

    expect(root.querySelector('.create')?.textContent).toContain('Create linked');
    button('New note').click();
    expect(fixture.componentInstance.created).toBe(1);

    const reloaded = fixture.componentInstance.panel().reload();
    expectList(TASK).flush({ items: [link('l1', end('NOTE', NOTE_ID, 'Holiday ideas'))] });
    await reloaded;
    await settle();
    expect(titles()).toEqual(['Holiday ideas']);
  });

  it('loads the links again when the item changes', async () => {
    await setup([]);

    const other: ItemRefDto = { type: 'TASK', id: OTHER_TASK_ID };
    fixture.componentInstance.item.set(other);
    await settle();
    expectList(other).flush({ items: [link('l1', end('NOTE', NOTE_ID, 'Holiday ideas'))] });
    await settle();

    expect(titles()).toEqual(['Holiday ideas']);
  });

  it('shows a load error with a retry', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: { error: vi.fn(), info: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Host);
    root = fixture.nativeElement as HTMLElement;
    await settle();
    expectList(TASK).flush(
      { status: 500, title: 'Server error' },
      { status: 500, statusText: 'Server Error' },
    );
    await settle();

    expect(root.querySelector('[role="alert"]')?.textContent).toContain('Server error');
    button('Try again').click();
    await settle();
    expectList(TASK).flush({ items: [] });
    await settle();
    expect(root.querySelector('.empty')).not.toBeNull();
  });

  /** Creates the panel without answering its first list request. */
  async function start(): Promise<void> {
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: { error: vi.fn(), info: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Host);
    root = fixture.nativeElement as HTMLElement;
    await settle();
  }

  function searchRequest(query: string) {
    return http.expectOne((r) => r.url === '/api/v1/search' && r.params.get('q') === query);
  }

  function optionTitles(): string[] {
    return [...root.querySelectorAll('[role="option"] .title')].map((el) => el.textContent!.trim());
  }

  function status(): string {
    return root.querySelector('.search-status')?.textContent?.trim() ?? '';
  }

  function hits(...items: [LinkEndResponse['type'], string, string][]) {
    const groups = new Map<string, { type: string; items: object[] }>();
    for (const [type, id, title] of items) {
      const group = groups.get(type) ?? { type, items: [] };
      group.items.push({ type, id, title, subtitle: `${title} subtitle`, updatedAt: 'x' });
      groups.set(type, group);
    }
    return { groups: [...groups.values()] };
  }

  async function openAndSearch(query: string, response: object): Promise<void> {
    button('Add link').click();
    await settle();
    await type(query);
    searchRequest(query).flush(response);
    await settle();
  }

  describe('loading', () => {
    it('shows a loading hint and no count until the links arrive', async () => {
      await start();

      expect(root.querySelector('.hint')?.textContent).toContain('Loading links');
      expect(root.querySelector('.links')).toBeNull();
      expect(root.querySelector('.count')).toBeNull();

      expectList(TASK).flush({ items: [] });
      await settle();
      expect(root.querySelector('.hint')).toBeNull();
      expect(root.querySelector('.count')).toBeNull();
    });

    it('ignores a list response for the previous item that answers after the new one', async () => {
      await start();
      const first = expectList(TASK);
      const other: ItemRefDto = { type: 'TASK', id: OTHER_TASK_ID };
      fixture.componentInstance.item.set(other);
      await settle();

      expectList(other).flush({ items: [link('l1', end('NOTE', NOTE_ID, 'Holiday ideas'))] });
      await settle();
      first.flush({ items: [link('l2', end('EVENT', EVENT_ID, 'Flight to Lisbon'))] });
      await settle();

      expect(titles()).toEqual(['Holiday ideas']);
    });

    it('ignores a failure of the previous item load that answers after the new one', async () => {
      await start();
      const first = expectList(TASK);
      const other: ItemRefDto = { type: 'TASK', id: OTHER_TASK_ID };
      fixture.componentInstance.item.set(other);
      await settle();

      expectList(other).flush({ items: [] });
      await settle();
      first.flush({ status: 500, title: 'Server error' }, { status: 500, statusText: 'Error' });
      await settle();

      expect(root.querySelector('.load-error')).toBeNull();
      expect(root.querySelector('.empty')).not.toBeNull();
    });

    it('keeps the newer links when an older load of the same item answers last', async () => {
      await start();
      const older = expectList(TASK);
      const reloaded = fixture.componentInstance.panel().reload();
      await settle();
      const newer = expectList(TASK);

      newer.flush({
        items: [
          link('l1', end('NOTE', NOTE_ID, 'Holiday ideas')),
          link('l2', end('EVENT', EVENT_ID, 'Flight to Lisbon')),
        ],
      });
      await reloaded;
      await settle();
      older.flush({ items: [link('l1', end('NOTE', NOTE_ID, 'Holiday ideas'))] });
      await settle();

      expect(titles()).toEqual(['Holiday ideas', 'Flight to Lisbon']);
    });

    it('keeps the newer links when an older load of the same item fails last', async () => {
      await start();
      const older = expectList(TASK);
      const reloaded = fixture.componentInstance.panel().reload();
      await settle();

      expectList(TASK).flush({ items: [link('l1', end('NOTE', NOTE_ID, 'Holiday ideas'))] });
      await reloaded;
      await settle();
      older.flush({ status: 500, title: 'Server error' }, { status: 500, statusText: 'Error' });
      await settle();

      expect(root.querySelector('.load-error')).toBeNull();
      expect(titles()).toEqual(['Holiday ideas']);
    });
  });

  describe('changing the item', () => {
    const other: ItemRefDto = { type: 'TASK', id: OTHER_TASK_ID };

    it('clears the action error of the previous item', async () => {
      await setup([link('l1', end('NOTE', NOTE_ID, 'Holiday ideas'))]);
      (root.querySelector('button.remove') as HTMLElement).click();
      await settle();
      http
        .expectOne({ method: 'DELETE', url: '/api/v1/links/l1' })
        .flush({ status: 500, title: 'Server error' }, { status: 500, statusText: 'Error' });
      await settle();
      expect(root.querySelector('[role="alert"]')).not.toBeNull();

      fixture.componentInstance.item.set(other);
      await settle();
      expectList(other).flush({ items: [] });
      await settle();

      expect(root.querySelector('[role="alert"]')).toBeNull();
    });

    it('closes the open search without moving the focus', async () => {
      await setup([]);
      await openAndSearch('lis', hits(['EVENT', EVENT_ID, 'Flight to Lisbon']));
      const outside = document.createElement('button');
      document.body.appendChild(outside);
      outside.focus();

      fixture.componentInstance.item.set(other);
      await settle();
      expectList(other).flush({ items: [] });
      await settle();

      expect(root.querySelector('input.query')).toBeNull();
      expect(document.activeElement).toBe(outside);
      outside.remove();

      button('Add link').click();
      await settle();
      expect((root.querySelector('input.query') as HTMLInputElement).value).toBe('');
      // The cleared query reaches the search after the debounce, like after Cancel.
      await tick(LINK_SEARCH_DEBOUNCE_MS + 10);
      await settle();
      expect(root.querySelector('[role="option"]')).toBeNull();
    });

    it('ignores a removal error of the previous item', async () => {
      await setup([link('l1', end('NOTE', NOTE_ID, 'Holiday ideas'))]);
      (root.querySelector('button.remove') as HTMLElement).click();
      await settle();
      const remove = http.expectOne({ method: 'DELETE', url: '/api/v1/links/l1' });

      fixture.componentInstance.item.set(other);
      await settle();
      expectList(other).flush({ items: [link('l2', end('EVENT', EVENT_ID, 'Flight to Lisbon'))] });
      await settle();
      remove.flush({ status: 500, title: 'Server error' }, { status: 500, statusText: 'Error' });
      await settle();

      expect(root.querySelector('[role="alert"]')).toBeNull();
      expect(titles()).toEqual(['Flight to Lisbon']);
    });

    it('ignores a linking error of the previous item', async () => {
      await setup([]);
      await openAndSearch('lis', hits(['EVENT', EVENT_ID, 'Flight to Lisbon']));
      (root.querySelector('[role="option"]') as HTMLElement).click();
      await settle();
      const create = http.expectOne({ method: 'POST', url: '/api/v1/links' });

      fixture.componentInstance.item.set(other);
      await settle();
      expectList(other).flush({ items: [] });
      await settle();
      create.flush(
        { status: 422, title: 'Unprocessable', detail: 'The target item does not exist.' },
        { status: 422, statusText: 'Unprocessable Entity' },
      );
      await settle();

      expect(root.querySelector('[role="alert"]')).toBeNull();
      expect(root.querySelector('input.query')).toBeNull();
    });
  });

  describe('removing', () => {
    it('disables the remove button while the removal runs', async () => {
      await setup([link('l1', end('NOTE', NOTE_ID, 'Holiday ideas'))]);

      const remove = root.querySelector('button.remove') as HTMLButtonElement;
      remove.click();
      await settle();
      expect(remove.disabled).toBe(true);

      http
        .expectOne({ method: 'DELETE', url: '/api/v1/links/l1' })
        .flush({ status: 500, title: 'Server error' }, { status: 500, statusText: 'Error' });
      await settle();
      expect((root.querySelector('button.remove') as HTMLButtonElement).disabled).toBe(false);
    });

    it('drops a link that was already removed elsewhere (404) without an error', async () => {
      await setup([
        link('l1', end('NOTE', NOTE_ID, 'Holiday ideas')),
        link('l2', end('EVENT', EVENT_ID, 'Flight to Lisbon')),
      ]);

      (root.querySelector('button.remove') as HTMLElement).click();
      await settle();
      http
        .expectOne({ method: 'DELETE', url: '/api/v1/links/l1' })
        .flush({ status: 404, title: 'Not Found' }, { status: 404, statusText: 'Not Found' });
      await settle();

      expect(titles()).toEqual(['Flight to Lisbon']);
      expect(root.querySelector('[role="alert"]')).toBeNull();
    });

    it('moves the focus to the next row after removing a link', async () => {
      await setup([
        link('l1', end('NOTE', NOTE_ID, 'Holiday ideas')),
        link('l2', end('EVENT', EVENT_ID, 'Flight to Lisbon')),
        link('l3', end('TASK', OTHER_TASK_ID, 'Book hotel')),
      ]);

      const middle = root.querySelector(
        'button[aria-label="Remove link to Flight to Lisbon"]',
      ) as HTMLButtonElement;
      middle.focus();
      middle.click();
      await settle();
      http
        .expectOne({ method: 'DELETE', url: '/api/v1/links/l2' })
        .flush(null, { status: 204, statusText: 'No Content' });
      await settle();

      expect(titles()).toEqual(['Holiday ideas', 'Book hotel']);
      expect(document.activeElement?.getAttribute('aria-label')).toBe('Remove link to Book hotel');
    });

    it('moves the focus to the previous row after removing the last row', async () => {
      await setup([
        link('l1', end('NOTE', NOTE_ID, 'Holiday ideas')),
        link('l2', end('EVENT', EVENT_ID, 'Flight to Lisbon')),
      ]);

      const last = root.querySelector(
        'button[aria-label="Remove link to Flight to Lisbon"]',
      ) as HTMLButtonElement;
      last.focus();
      last.click();
      await settle();
      http
        .expectOne({ method: 'DELETE', url: '/api/v1/links/l2' })
        .flush(null, { status: 204, statusText: 'No Content' });
      await settle();

      expect(document.activeElement?.getAttribute('aria-label')).toBe(
        'Remove link to Holiday ideas',
      );
    });

    it('moves the focus to the heading after removing the only link', async () => {
      await setup([link('l1', end('NOTE', NOTE_ID, 'Holiday ideas'))]);

      const remove = root.querySelector('button.remove') as HTMLButtonElement;
      remove.focus();
      remove.click();
      await settle();
      http
        .expectOne({ method: 'DELETE', url: '/api/v1/links/l1' })
        .flush(null, { status: 204, statusText: 'No Content' });
      await settle();

      expect(document.activeElement).toBe(root.querySelector('h2.heading'));
      expect(root.querySelector('.empty')).not.toBeNull();
    });

    it('leaves the focus alone when the user moved it elsewhere during the removal', async () => {
      await setup([
        link('l1', end('NOTE', NOTE_ID, 'Holiday ideas')),
        link('l2', end('EVENT', EVENT_ID, 'Flight to Lisbon')),
      ]);

      (root.querySelector('button.remove') as HTMLButtonElement).click();
      await settle();
      button('Add link').focus();
      http
        .expectOne({ method: 'DELETE', url: '/api/v1/links/l1' })
        .flush(null, { status: 204, statusText: 'No Content' });
      await settle();

      expect(titles()).toEqual(['Flight to Lisbon']);
      expect(document.activeElement).toBe(button('Add link'));
    });
  });

  describe('searching', () => {
    it('shows "Searching…" while pending, then the hits with their subtitles', async () => {
      await setup([]);
      button('Add link').click();
      await settle();
      await type('lis');

      expect(status()).toBe('Searching…');
      expect(root.querySelector('input.query')?.getAttribute('aria-busy')).toBe('true');
      searchRequest('lis').flush(hits(['EVENT', EVENT_ID, 'Flight to Lisbon']));
      await settle();

      expect(status()).toBe('');
      expect(optionTitles()).toEqual(['Flight to Lisbon']);
      expect(root.querySelector('[role="option"] .subtitle')?.textContent?.trim()).toBe(
        'Flight to Lisbon subtitle',
      );
      expect(root.querySelector('input.query')?.getAttribute('aria-expanded')).toBe('true');
    });

    it('does not search for a blank query', async () => {
      await setup([]);
      button('Add link').click();
      await settle();
      await type('   ');

      http.expectNone((r) => r.url === '/api/v1/search');
      expect(status()).toBe('');
    });

    it('says nothing matches when every hit is filtered out', async () => {
      await setup([link('l1', end('NOTE', NOTE_ID, 'Holiday ideas'))]);

      await openAndSearch('holiday', hits(['NOTE', NOTE_ID, 'Holiday ideas']));

      expect(optionTitles()).toEqual([]);
      expect(status()).toContain('Nothing to link matches');
      expect(root.querySelector('input.query')?.getAttribute('aria-expanded')).toBe('false');
    });

    it('shows the search error and recovers with the next query', async () => {
      await setup([]);
      button('Add link').click();
      await settle();
      await type('lis');
      searchRequest('lis').flush(
        { status: 503, title: 'Search unavailable' },
        { status: 503, statusText: 'Unavailable' },
      );
      await settle();

      expect(root.querySelector('.search-status .error')?.textContent).toContain(
        'Search unavailable',
      );
      expect(optionTitles()).toEqual([]);

      await type('lisb');
      searchRequest('lisb').flush(hits(['EVENT', EVENT_ID, 'Flight to Lisbon']));
      await settle();
      expect(root.querySelector('.search-status .error')).toBeNull();
      expect(optionTitles()).toEqual(['Flight to Lisbon']);
    });

    it('shows a generic message when the search fails unexpectedly', async () => {
      await setup([]);
      vi.spyOn(TestBed.inject(LinksService), 'search').mockRejectedValue(new Error('boom'));
      button('Add link').click();
      await settle();
      await type('lis');
      await settle();

      expect(root.querySelector('.search-status .error')?.textContent).toContain(
        'Something went wrong.',
      );
    });

    it('ignores an older search that answers after a newer query', async () => {
      await setup([]);
      button('Add link').click();
      await settle();
      await type('li');
      const older = searchRequest('li');
      await type('lis');
      const newer = searchRequest('lis');

      newer.flush(hits(['EVENT', EVENT_ID, 'Flight to Lisbon']));
      await settle();
      older.flush(hits(['TASK', OTHER_TASK_ID, 'Little errands']));
      await settle();

      expect(optionTitles()).toEqual(['Flight to Lisbon']);
    });

    it('ignores a failing older search that answers after a newer query', async () => {
      await setup([]);
      button('Add link').click();
      await settle();
      await type('li');
      const older = searchRequest('li');
      await type('lis');
      const newer = searchRequest('lis');

      newer.flush(hits(['EVENT', EVENT_ID, 'Flight to Lisbon']));
      await settle();
      older.flush({ status: 500, title: 'Server error' }, { status: 500, statusText: 'Error' });
      await settle();

      expect(optionTitles()).toEqual(['Flight to Lisbon']);
      expect(root.querySelector('.search-status .error')).toBeNull();
    });

    it('shows no results when the query is cleared while a search is pending', async () => {
      await setup([]);
      button('Add link').click();
      await settle();
      await type('lis');
      const pending = searchRequest('lis');
      await type('');

      pending.flush(hits(['EVENT', EVENT_ID, 'Flight to Lisbon']));
      await settle();

      expect(optionTitles()).toEqual([]);
      expect(status()).toBe('');
    });

    it('moves through the results with the arrow keys, wrapping around', async () => {
      await setup([]);
      await openAndSearch(
        'b',
        hits(
          ['TASK', OTHER_TASK_ID, 'Book hotel'],
          ['NOTE', NOTE_ID, 'Booking notes'],
          ['EVENT', EVENT_ID, 'Boarding'],
        ),
      );
      const input = root.querySelector('input.query') as HTMLInputElement;
      const active = () => root.querySelector('[role="option"].active .title')?.textContent?.trim();

      expect(active()).toBe('Book hotel');
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp' }));
      await settle();
      expect(active()).toBe('Boarding');
      expect(input.getAttribute('aria-activedescendant')).toBe(
        root.querySelector('[role="option"].active')?.id,
      );
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' }));
      await settle();
      expect(active()).toBe('Book hotel');
    });

    it('ignores Enter and arrows when there are no results', async () => {
      await setup([]);
      await openAndSearch('zzz', { groups: [] });
      const input = root.querySelector('input.query') as HTMLInputElement;

      for (const key of ['ArrowDown', 'ArrowUp', 'Enter']) {
        input.dispatchEvent(new KeyboardEvent('keydown', { key }));
        await settle();
      }

      http.expectNone({ method: 'POST', url: '/api/v1/links' });
      expect(input.getAttribute('aria-activedescendant')).toBeNull();
    });

    it('closes the search with Cancel and returns the focus to "Add link"', async () => {
      await setup([]);
      button('Add link').click();
      await settle();
      await type('lis');
      searchRequest('lis').flush(hits(['EVENT', EVENT_ID, 'Flight to Lisbon']));
      await settle();

      button('Cancel').click();
      await settle();

      expect(root.querySelector('input.query')).toBeNull();
      expect(document.activeElement).toBe(button('Add link'));
      await tick(LINK_SEARCH_DEBOUNCE_MS + 10);

      // Opening again starts with an empty query and searches the same word again.
      button('Add link').click();
      await settle();
      expect((root.querySelector('input.query') as HTMLInputElement).value).toBe('');
      await type('lis');
      searchRequest('lis').flush({ groups: [] });
      await settle();
    });
  });

  describe('adding', () => {
    it('links a result chosen with Enter on the option itself', async () => {
      await setup([]);
      await openAndSearch('lis', hits(['EVENT', EVENT_ID, 'Flight to Lisbon']));

      root
        .querySelector('[role="option"]')!
        .dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
      await settle();

      http
        .expectOne({ method: 'POST', url: '/api/v1/links' })
        .flush(link('l1', end('EVENT', EVENT_ID, 'Flight to Lisbon')), {
          status: 201,
          statusText: 'Created',
        });
      await settle();
      expect(titles()).toEqual(['Flight to Lisbon']);
    });

    it('creates the link only once when a result is chosen twice', async () => {
      await setup([]);
      await openAndSearch('lis', hits(['EVENT', EVENT_ID, 'Flight to Lisbon']));

      const option = root.querySelector('[role="option"]') as HTMLElement;
      option.click();
      await settle();
      expect((root.querySelector('input.query') as HTMLInputElement).disabled).toBe(true);
      option.click();
      await settle();

      const create = http.expectOne({ method: 'POST', url: '/api/v1/links' });
      create.flush(link('l1', end('EVENT', EVENT_ID, 'Flight to Lisbon')), {
        status: 201,
        statusText: 'Created',
      });
      await settle();
      expect(titles()).toEqual(['Flight to Lisbon']);
    });

    it('keeps the search open and shows the error when linking fails', async () => {
      await setup([]);
      await openAndSearch('lis', hits(['EVENT', EVENT_ID, 'Flight to Lisbon']));

      (root.querySelector('[role="option"]') as HTMLElement).click();
      await settle();
      http
        .expectOne({ method: 'POST', url: '/api/v1/links' })
        .flush(
          { status: 422, title: 'Unprocessable', detail: 'The target item does not exist.' },
          { status: 422, statusText: 'Unprocessable Entity' },
        );
      await settle();

      expect(root.querySelector('[role="alert"]')?.textContent).toContain(
        'The target item does not exist.',
      );
      const input = root.querySelector('input.query') as HTMLInputElement;
      expect(input).not.toBeNull();
      expect(input.disabled).toBe(false);
      expect(titles()).toEqual([]);
    });

    it('reloads the list when the link already exists (409)', async () => {
      await setup([]);
      await openAndSearch('lis', hits(['EVENT', EVENT_ID, 'Flight to Lisbon']));

      (root.querySelector('[role="option"]') as HTMLElement).click();
      await settle();
      http
        .expectOne({ method: 'POST', url: '/api/v1/links' })
        .flush({ status: 409, title: 'Conflict' }, { status: 409, statusText: 'Conflict' });
      await settle();

      expect(root.querySelector('input.query')).toBeNull();
      expect(root.querySelector('[role="alert"]')).toBeNull();
      expectList(TASK).flush({ items: [link('l7', end('EVENT', EVENT_ID, 'Flight to Lisbon'))] });
      await settle();
      expect(titles()).toEqual(['Flight to Lisbon']);
    });

    it('does not show a link created for the previous item after the item changed', async () => {
      await setup([]);
      await openAndSearch('lis', hits(['EVENT', EVENT_ID, 'Flight to Lisbon']));

      (root.querySelector('[role="option"]') as HTMLElement).click();
      await settle();
      const create = http.expectOne({ method: 'POST', url: '/api/v1/links' });
      expect(create.request.body.source).toEqual(TASK);

      const other: ItemRefDto = { type: 'TASK', id: OTHER_TASK_ID };
      fixture.componentInstance.item.set(other);
      await settle();
      expectList(other).flush({ items: [] });
      await settle();
      create.flush(link('l1', end('EVENT', EVENT_ID, 'Flight to Lisbon')), {
        status: 201,
        statusText: 'Created',
      });
      await settle();

      expect(titles()).toEqual([]);
    });

    it('clears an earlier action error when adding starts', async () => {
      await setup([link('l1', end('NOTE', NOTE_ID, 'Holiday ideas'))]);
      (root.querySelector('button.remove') as HTMLElement).click();
      await settle();
      http
        .expectOne({ method: 'DELETE', url: '/api/v1/links/l1' })
        .flush({ status: 500, title: 'Server error' }, { status: 500, statusText: 'Error' });
      await settle();
      expect(root.querySelector('[role="alert"]')).not.toBeNull();

      button('Add link').click();
      await settle();

      expect(root.querySelector('[role="alert"]')).toBeNull();
    });
  });
});

describe('LinkedItems without create-linked actions', () => {
  it('hides the "Create linked" row', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: { error: vi.fn(), info: vi.fn() } },
      ],
    });
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(LinkedItems);
    fixture.componentRef.setInput('item', TASK);
    await tick();
    await fixture.whenStable();
    http.expectOne((r) => r.url === '/api/v1/links').flush({ items: [] });
    await tick();
    await fixture.whenStable();

    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('.empty')).not.toBeNull();
    expect(root.querySelector('.create')).toBeNull();
    http.verify();
  });
});
