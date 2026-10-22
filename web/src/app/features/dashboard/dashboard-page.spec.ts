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
import { fakeOccurrence } from '../calendar/views/testing';
import { habit } from '../habits/testing';
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

  async function open(stored: unknown[] = [], settingsFail = false): Promise<void> {
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
    const settings = http.expectOne({ method: 'GET', url: SETTINGS });
    if (settingsFail) {
      settings.flush({ title: 'Boom', status: 500 }, { status: 500, statusText: 'Server Error' });
    } else {
      settings.flush(fakeSettings({ [LAYOUT_SETTING]: stored }), { headers: { ETag: '"1"' } });
    }
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

  it('shows the default layout when the settings fail, without letting it be changed', async () => {
    await open([], true);

    expect(widgets()).toHaveLength(8);
    expect(root.querySelector<HTMLButtonElement>('.customize-toggle')!.disabled).toBe(true);
    flushWidgets();
    await settle();
  });

  it('fills the Today widget with tasks, events and habits', async () => {
    await open(['today']);
    const overdue = task('t1', { title: 'Renew the fictitious permit', dueDate: '2000-01-01' });
    const due = task('t2', { title: 'Water the plants', dueDate: '2999-01-01' });
    flushWidgets({
      '/api/v1/tasks?due=overdue': { items: [overdue] },
      '/api/v1/tasks?due=today': { items: [overdue, due] },
      '/api/v1/calendar/events': {
        items: [
          fakeOccurrence('Standup'),
          fakeOccurrence('Cancelled lunch', { status: 'CANCELLED' }),
          fakeOccurrence('Holiday', { allDay: true, startDate: '2026-10-22' }),
        ],
      },
      '/api/v1/habits': {
        items: [
          habit('h1', { stats: { dueToday: true, doneToday: true } }),
          habit('h2', { stats: { dueToday: true, doneToday: false } }),
        ],
      },
    });
    await settle();

    const today = widget('today');
    const figures = [...today.querySelectorAll('.figure')].map((f) => f.textContent?.trim());
    expect(figures).toEqual(['2', '2', '1/2']);
    const items = [...today.querySelectorAll('li')].map((li) => [
      li.querySelector('.when')!.textContent!.trim(),
      li.querySelector('a')!.textContent!.trim(),
    ]);
    expect(items).toEqual([
      [expect.any(String), 'Standup'],
      ['All day', 'Holiday'],
      ['Overdue', 'Renew the fictitious permit'],
      ['Due', 'Water the plants'],
    ]);
    expect(today.querySelector('li.task.overdue a')?.getAttribute('href')).toBe('/tasks/t1');
    expect(today.textContent).not.toContain('Cancelled lunch');
  });

  it('says when nothing is due today and leaves the habits out when none are due', async () => {
    await open(['today']);
    flushWidgets();
    await settle();

    const today = widget('today');
    expect(today.textContent).toContain('Nothing due and no events today.');
    expect(today.querySelector('a[href="/habits"]')).toBeNull();
  });

  it.each([
    [{}, 'No weekly review done yet.', 'Start a weekly review'],
    [{ daysSinceLastReview: 0 }, 'Today', null],
    [{ daysSinceLastReview: 3 }, '3', null],
    [{ daysSinceLastReview: 7 }, '7', 'Start a weekly review'],
    [{ daysSinceLastReview: 12, current: { id: 'r1' } }, '12', 'Continue the open review'],
  ])('shows the weekly review status %o', async (status, shown, action) => {
    await open(['weeklyReview']);
    flushWidgets({ '/api/v1/weekly-reviews/status': status });
    await settle();

    const review = widget('weeklyReview');
    const readout = review.querySelector('.figure') ?? review.querySelector('.note');
    expect(readout?.textContent?.trim()).toBe(shown);
    const button = review.querySelector('a[mat-flat-button], a[mat-stroked-button]');
    expect(button?.textContent?.trim() ?? null).toBe(action);
  });

  it('shows five entries of a list widget and how many more there are', async () => {
    await open(['pinnedNotes', 'readLater']);
    const notes = ['n1', 'n2', 'n3', 'n4', 'n5', 'n6', 'n7'].map((id) => note(id, `Note ${id}`));
    flushWidgets({
      '/api/v1/notes': { items: notes, nextCursor: 'next' },
      '/api/v1/bookmarks': {
        items: ['b1', 'b2', 'b3', 'b4', 'b5', 'b6'].map((id) => bookmark(id)),
      },
    });
    await settle();

    expect(widget('pinnedNotes').querySelectorAll('li')).toHaveLength(5);
    expect(widget('pinnedNotes').querySelector('.more')?.textContent?.trim()).toBe('2+ more');
    expect(widget('readLater').querySelector('.more')?.textContent?.trim()).toBe('1 more');
  });

  it('lists upcoming events, overdue Waiting For and stalled projects, with how many more', async () => {
    await open(['upcoming', 'waitingOverdue', 'stalledProjects']);
    const events = ['A', 'B', 'C', 'D', 'E', 'F', 'G'].map((title) =>
      fakeOccurrence(`Event ${title}`),
    );
    events[0] = fakeOccurrence('Event A', { allDay: true, startDate: '2026-10-23' });
    const waiting = ['w1', 'w2', 'w3', 'w4', 'w5', 'w6'].map((id) =>
      task(id, { list: 'WAITING', dueDate: '2026-10-01', waitingForText: `Answer ${id}` }),
    );
    const projects = ['p1', 'p2', 'p3', 'p4', 'p5', 'p6', 'p7', 'p8'].map((id) => ({
      id,
      title: `Project ${id}`,
    }));
    flushWidgets({
      '/api/v1/calendar/events': { items: events },
      '/api/v1/tasks?list=WAITING': { items: waiting },
      '/api/v1/projects': { items: projects },
    });
    await settle();

    const upcoming = widget('upcoming');
    expect(upcoming.querySelectorAll('li')).toHaveLength(5);
    expect(upcoming.querySelector('.more')?.textContent?.trim()).toBe('2 more');
    const waitingWidget = widget('waitingOverdue');
    expect(waitingWidget.querySelectorAll('li')).toHaveLength(5);
    expect(waitingWidget.querySelector('.meta')?.textContent).toContain('Answer w1');
    expect(waitingWidget.querySelector('.more')?.textContent?.trim()).toBe('1 more');
    const stalled = widget('stalledProjects');
    expect(stalled.querySelector('a[href="/projects/p1"]')?.textContent).toContain('Project p1');
    expect(stalled.querySelector('.more')?.textContent?.trim()).toBe('3 more');
  });

  it('says so when there are no upcoming events and nothing waited for is overdue', async () => {
    await open(['upcoming', 'waitingOverdue']);
    flushWidgets();
    await settle();

    expect(widget('upcoming').textContent).toContain('No events in the next 7 days.');
    expect(widget('waitingOverdue').textContent).toContain('Nothing you wait for is overdue.');
  });

  it('moves a widget up from the customize panel', async () => {
    await open();
    flushWidgets();
    await settle();

    root.querySelector<HTMLButtonElement>('.customize-toggle')!.click();
    harness.detectChanges();
    const last = root.querySelector<HTMLElement>('.slot[data-id="pinnedNotes"]')!;
    expect(last.querySelector<HTMLButtonElement>('.down')!.disabled).toBe(true);
    expect(last.querySelector('.up')?.getAttribute('aria-label')).toBe('Move Pinned notes up');
    last.querySelector<HTMLButtonElement>('.up')!.click();
    await settle();

    expect(widgets().slice(-2)).toEqual(['pinnedNotes', 'readLater']);
    const req = http.expectOne({ method: 'PATCH', url: SETTINGS });
    expect((req.request.body[LAYOUT_SETTING] as string[]).slice(-2)).toEqual([
      'pinnedNotes',
      'readLater',
    ]);
    req.flush(fakeSettings({ [LAYOUT_SETTING]: req.request.body[LAYOUT_SETTING] }));
    await settle();
  });

  it('rolls back a refused layout change', async () => {
    await open();
    flushWidgets();
    await settle();

    root.querySelector<HTMLButtonElement>('.customize-toggle')!.click();
    harness.detectChanges();
    root
      .querySelector<HTMLElement>('.slot[data-id="inbox"]')!
      .querySelector<HTMLInputElement>('input[type="checkbox"]')!
      .click();
    await settle();
    expect(widgets()).not.toContain('inbox');

    http
      .expectOne({ method: 'PATCH', url: SETTINGS })
      .flush({ title: 'Bad Request', status: 400 }, { status: 400, statusText: 'Bad Request' });
    await settle();

    expect(widgets()).toContain('inbox');
  });

  it('resets the layout to the default from the customize panel', async () => {
    await open(['inbox:hidden', 'pinnedNotes']);
    flushWidgets();
    await settle();

    root.querySelector<HTMLButtonElement>('.customize-toggle')!.click();
    harness.detectChanges();
    root.querySelector<HTMLButtonElement>('.reset')!.click();
    await settle();

    const req = http.expectOne({ method: 'PATCH', url: SETTINGS });
    expect(req.request.body).toEqual({ [LAYOUT_SETTING]: null });
    req.flush(fakeSettings({ [LAYOUT_SETTING]: [] }));
    await settle();
    expect(widgets()[0]).toBe('today');
    // The Inbox widget, shown again, loads its data.
    flushWidgets();
  });
});
