import { Component, signal, viewChild } from '@angular/core';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ItemRefDto, LinkEndResponse, LinkResponse } from '../../api/generated';
import { NotificationService, provideHttp } from '../../core/http';
import { LinkedCreate } from './linked-create';
import { LINK_SEARCH_DEBOUNCE_MS, LinkedItems } from './linked-items';

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
});
