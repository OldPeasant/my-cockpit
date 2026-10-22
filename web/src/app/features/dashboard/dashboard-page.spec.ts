import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { filter, Subject } from 'rxjs';
import { ChangeEvent } from '../../api/generated';
import { ChangeEventsService } from '../../core/events';
import { NotificationService, provideHttp, SILENT_ERRORS } from '../../core/http';
import { bookmark } from '../bookmarks/testing';
import { fakeSettings } from '../settings/testing';
import { LAYOUT_SETTING } from './dashboard-layout';
import routes from './dashboard.routes';

const SETTINGS = '/api/v1/settings';
const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

const task = (id: string, fields: Record<string, unknown> = {}) => ({
  id,
  title: `Task ${id}`,
  list: 'NEXT',
  contextIds: [],
  position: 0,
  createdAt: '2026-10-01T08:00:00Z',
  updatedAt: '2026-10-01T08:00:00Z',
  version: 1,
  ...fields,
});

const note = (id: string, title: string) => ({
  id,
  title,
  displayTitle: title,
  bodyMd: '',
  tags: [],
  pinned: true,
  archived: false,
  createdAt: '2026-10-01T08:00:00Z',
  updatedAt: '2026-10-01T08:00:00Z',
  version: 1,
});

describe('DashboardPage', () => {
  let harness: RouterTestingHarness;
  let root: HTMLElement;
  let http: HttpTestingController;
  let changes: Subject<ChangeEvent>;
  let resyncs: Subject<void>;

  async function open(stored: unknown[] = []): Promise<void> {
    changes = new Subject();
    resyncs = new Subject();
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([{ path: '', children: routes }]),
        { provide: NotificationService, useValue: { error: vi.fn(), info: vi.fn() } },
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
    await harness.navigateByUrl('/');
    root = harness.routeNativeElement as HTMLElement;
    http
      .expectOne({ method: 'GET', url: SETTINGS })
      .flush(fakeSettings({ [LAYOUT_SETTING]: stored }), { headers: { ETag: '"1"' } });
    await settle();
  }

  async function settle(): Promise<void> {
    await tick();
    harness.detectChanges();
    await tick();
    harness.detectChanges();
  }

  /** The pending requests; they are taken from the queue, so flush them (or `flushWidgets` them). */
  function pending(): TestRequest[] {
    return http.match(() => true);
  }

  function flushWidgets(answers: Record<string, object> = {}, requests = pending()): void {
    for (const req of requests) {
      const key = req.request.urlWithParams;
      const match = Object.keys(answers).find((prefix) => key.startsWith(prefix));
      req.flush(match ? answers[match] : defaultAnswer(req.request.url));
    }
  }

  function defaultAnswer(url: string): object {
    if (url === '/api/v1/weekly-reviews/status') return {};
    return { items: [] };
  }

  function widgets(): string[] {
    return [...root.querySelectorAll<HTMLElement>('.widget')].map((w) => w.dataset['widget']!);
  }

  function widget(id: string): HTMLElement {
    return root.querySelector<HTMLElement>(`[data-widget="${id}"]`)!;
  }

  afterEach(() => http.verify());

  it('shows every widget by default, each loading its own data without toasting errors', async () => {
    await open();

    expect(widgets()).toEqual([
      'today',
      'inbox',
      'upcoming',
      'waitingOverdue',
      'stalledProjects',
      'weeklyReview',
      'readLater',
      'pinnedNotes',
    ]);
    const requests = pending();
    expect(requests.every((req) => req.request.context.get(SILENT_ERRORS))).toBe(true);
    const urls = requests.map((req) => req.request.urlWithParams);
    expect(urls).toContain('/api/v1/tasks?list=INBOX&limit=50');
    expect(urls).toContain('/api/v1/tasks?list=WAITING&due=overdue&limit=50');
    expect(urls).toContain('/api/v1/projects?status=ACTIVE&stalled=true&limit=50');
    expect(urls).toContain('/api/v1/bookmarks?readLater=true&read=false&limit=50');
    expect(urls).toContain('/api/v1/notes?pinned=true&limit=50');
    expect(urls).toContain('/api/v1/weekly-reviews/status');
    flushWidgets({}, requests);
    await settle();

    expect(widget('stalledProjects').textContent).toContain(
      'Every active project has a next step.',
    );
  });

  it('shows the stored order and leaves hidden widgets out, without loading them', async () => {
    await open(['pinnedNotes', 'inbox', 'today:hidden', 'upcoming:hidden']);

    expect(widgets().slice(0, 2)).toEqual(['pinnedNotes', 'inbox']);
    expect(widgets()).not.toContain('today');
    expect(widgets()).not.toContain('upcoming');
    const requests = pending();
    const urls = requests.map((req) => req.request.url);
    expect(urls).not.toContain('/api/v1/calendar/events');
    expect(urls).not.toContain('/api/v1/habits');
    flushWidgets({}, requests);
  });

  it('fills the widgets with counts and lists', async () => {
    await open();
    flushWidgets({
      '/api/v1/tasks?list=INBOX': { items: [task('i1'), task('i2'), task('i3')], nextCursor: 'x' },
      '/api/v1/mail/messages': {
        items: [
          { seen: false, subject: 'A', snippet: '', threadKey: 'a', receivedAt: '', sentAt: '' },
          { seen: true, subject: 'B', snippet: '', threadKey: 'b', receivedAt: '', sentAt: '' },
        ],
        nextCursor: null,
      },
      '/api/v1/tasks?list=WAITING': {
        items: [task('w1', { title: 'Offer from the plumber', dueDate: '2026-10-01' })],
      },
      '/api/v1/notes': { items: [note('n1', 'Packing list')] },
      '/api/v1/bookmarks': { items: [bookmark('b1', { displayTitle: 'On slow software' })] },
      '/api/v1/weekly-reviews/status': { daysSinceLastReview: 9 },
    });
    await settle();

    const inbox = widget('inbox').querySelectorAll('.figure');
    expect(inbox[0].textContent?.trim()).toBe('3+');
    expect(inbox[1].textContent?.trim()).toBe('1');
    expect(widget('waitingOverdue').textContent).toContain('Offer from the plumber');
    expect(widget('pinnedNotes').querySelector('a[href="/notes/n1"]')?.textContent).toContain(
      'Packing list',
    );
    expect(widget('readLater').textContent).toContain('On slow software');
    const review = widget('weeklyReview');
    expect(review.querySelector('.figure')?.textContent?.trim()).toBe('9');
    expect(review.querySelector('.figure')?.classList).toContain('alert');
    expect(review.textContent).toContain('Start a weekly review');
  });

  it('shows a failed widget with a retry, while the others work', async () => {
    await open();
    for (const req of pending()) {
      if (req.request.url === '/api/v1/notes') {
        req.flush({ title: 'Boom', status: 500 }, { status: 500, statusText: 'Server Error' });
      } else {
        req.flush(defaultAnswer(req.request.url));
      }
    }
    await settle();

    const notes = widget('pinnedNotes');
    expect(notes.querySelector('[role="alert"]')?.textContent).toContain('could not be loaded');
    expect(widget('readLater').textContent).toContain('Your reading queue is empty.');

    notes.querySelector<HTMLButtonElement>('.failed button')!.click();
    http.expectOne((r) => r.url === '/api/v1/notes').flush({ items: [note('n1', 'Recipes')] });
    await settle();
    expect(widget('pinnedNotes').textContent).toContain('Recipes');
  });

  it('reloads a widget after a change event of its type, keeping its data meanwhile', async () => {
    await open();
    flushWidgets({ '/api/v1/notes': { items: [note('n1', 'Recipes')] } });
    await settle();

    changes.next({ op: 'UPDATED', type: 'NOTE', id: 'n1' } as ChangeEvent);
    await new Promise((resolve) => setTimeout(resolve, 350));
    harness.detectChanges();
    expect(widget('pinnedNotes').textContent).toContain('Recipes');
    const reload = http.expectOne((r) => r.url === '/api/v1/notes');
    expect(pending()).toHaveLength(0);
    reload.flush({ items: [note('n2', 'Gift ideas')] });
    await settle();
    expect(widget('pinnedNotes').textContent).toContain('Gift ideas');
  });

  it('hides a widget from the customize panel and saves the layout in the settings', async () => {
    await open();
    flushWidgets();
    await settle();

    root.querySelector<HTMLButtonElement>('.customize-toggle')!.click();
    harness.detectChanges();
    const row = root.querySelector<HTMLElement>('.slot[data-id="upcoming"]')!;
    row.querySelector<HTMLInputElement>('input[type="checkbox"]')!.click();
    await settle();

    expect(widgets()).not.toContain('upcoming');
    const req = http.expectOne({ method: 'PATCH', url: SETTINGS });
    expect(req.request.headers.get('If-Match')).toBe('"1"');
    const stored = req.request.body[LAYOUT_SETTING] as string[];
    expect(stored).toContain('upcoming:hidden');
    expect(stored).toHaveLength(8);
    req.flush(fakeSettings({ [LAYOUT_SETTING]: stored }), { headers: { ETag: '"2"' } });
    await settle();
    expect(widgets()).not.toContain('upcoming');
  });

  it('moves a widget down and up from the customize panel', async () => {
    await open();
    flushWidgets();
    await settle();

    root.querySelector<HTMLButtonElement>('.customize-toggle')!.click();
    harness.detectChanges();
    const first = root.querySelector<HTMLElement>('.slot[data-id="today"]')!;
    expect(first.querySelector<HTMLButtonElement>('.up')!.disabled).toBe(true);
    first.querySelector<HTMLButtonElement>('.down')!.click();
    await settle();

    expect(widgets().slice(0, 2)).toEqual(['inbox', 'today']);
    const req = http.expectOne({ method: 'PATCH', url: SETTINGS });
    expect((req.request.body[LAYOUT_SETTING] as string[]).slice(0, 2)).toEqual(['inbox', 'today']);
    req.flush(fakeSettings({ [LAYOUT_SETTING]: req.request.body[LAYOUT_SETTING] }));
    await settle();
    // A widget shown again after the move loads its data.
    expect(pending()).toHaveLength(0);
  });

  it('offers to choose widgets when all are hidden', async () => {
    await open([
      'today:hidden',
      'inbox:hidden',
      'upcoming:hidden',
      'waitingOverdue:hidden',
      'stalledProjects:hidden',
      'weeklyReview:hidden',
      'readLater:hidden',
      'pinnedNotes:hidden',
    ]);

    expect(widgets()).toEqual([]);
    expect(pending()).toHaveLength(0);
    expect(root.querySelector('.empty')?.textContent).toContain('All widgets are hidden.');
    root.querySelector<HTMLButtonElement>('.empty button')!.click();
    harness.detectChanges();
    expect(root.querySelector('.customize')).not.toBeNull();
  });
});
