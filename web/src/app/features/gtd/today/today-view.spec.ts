import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { NEVER, Subject } from 'rxjs';
import {
  Api,
  completeTask,
  listContexts,
  listProjects,
  listTasks,
  TaskResponse,
} from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { TASK_PAGE_SIZE } from '../data';
import { TODAY_SECTIONS, todaySectionProvider } from './today-sections';
import TodayView from './today-view';

const YESTERDAY = '2026-10-19';
const TODAY = '2026-10-20';
const TOMORROW = '2026-10-21';

function task(id: string, title: string, fields: Partial<TaskResponse> = {}): TaskResponse {
  return {
    id,
    title,
    list: 'NEXT',
    position: 1,
    contextIds: [],
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-01T08:00:00Z',
    version: 1,
    ...fields,
  };
}

const RENEW_PASSPORT = task('t-passport', 'Renew the passport', { dueDate: YESTERDAY });
const PAY_INVOICE = task('t-invoice', 'Pay the fictitious invoice', { dueDate: TODAY });
const WATER_PLANTS = task('t-plants', 'Water the plants', {
  list: 'SCHEDULED',
  scheduledDate: TODAY,
});

@Component({ selector: 'app-fake-events', template: '<h2>Calendar today</h2>' })
class FakeEvents {}

@Component({ selector: 'app-fake-habits', template: '<h2>Habits due</h2>' })
class FakeHabits {}

/** A request the test answers when it wants to. */
interface Call {
  params: Record<string, unknown>;
  resolve: (value: unknown) => void;
  reject: (error: unknown) => void;
}

type Invoke = (fn: unknown, params?: Record<string, unknown>) => Promise<unknown>;

/** Lets pending promise callbacks run. */
const settle = () => new Promise((resolve) => setTimeout(resolve));

describe('TodayView', () => {
  let taskCalls: Call[];
  let api: { invoke: ReturnType<typeof vi.fn<Invoke>> };
  let fixture: ComponentFixture<TodayView>;
  let root: HTMLElement;

  function configure(providers: unknown[] = []): void {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: '**', children: [] }]),
        { provide: Api, useValue: api },
        {
          provide: ChangeEventsService,
          useValue: { resync$: new Subject<void>(), events$: () => NEVER },
        },
        ...(providers as []),
      ],
    });
  }

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 9, 20, 9, 30));
    taskCalls = [];
    api = {
      invoke: vi.fn<Invoke>((fn, params = {}) => {
        if (fn === listTasks) {
          return new Promise((resolve, reject) => taskCalls.push({ params, resolve, reject }));
        }
        if (fn === listProjects || fn === listContexts) return Promise.resolve({ items: [] });
        if (fn === completeTask) {
          return Promise.resolve({ ...(params as object), completedAt: '2026-10-20T09:31:00Z' });
        }
        return Promise.reject(new Error(`unexpected call ${String(fn)}`));
      }),
    };
  });

  afterEach(() => vi.useRealTimers());

  async function render(): Promise<void> {
    fixture = TestBed.createComponent(TodayView);
    root = fixture.nativeElement;
    await refresh();
  }

  async function refresh(): Promise<void> {
    for (let i = 0; i < 3; i++) {
      await settle();
      fixture.detectChanges();
    }
  }

  const call = (key: 'due' | 'list', value: string) =>
    taskCalls.filter((c) => c.params[key] === value).at(-1)!;

  /** Answers the three task requests (in reverse order, so the view can't rely on order). */
  async function answer(
    overdue: TaskResponse[],
    due: TaskResponse[],
    scheduled: TaskResponse[],
  ): Promise<void> {
    call('list', 'SCHEDULED').resolve({ items: scheduled });
    call('due', 'today').resolve({ items: due });
    call('due', 'overdue').resolve({ items: overdue });
    await refresh();
  }

  const sectionIds = () =>
    [...root.querySelectorAll('section.group')].map((el) => el.getAttribute('aria-labelledby'));
  const titlesIn = (id: string) =>
    [...root.querySelectorAll(`section[aria-labelledby="today-${id}"] .title`)].map((el) =>
      el.textContent!.trim(),
    );
  const text = () => root.textContent!.replace(/\s+/g, ' ');

  it('loads overdue, due-today and scheduled tasks', async () => {
    configure();
    await render();

    expect(taskCalls.map((c) => c.params)).toEqual([
      { due: 'overdue', cursor: undefined, limit: TASK_PAGE_SIZE },
      { due: 'today', cursor: undefined, limit: TASK_PAGE_SIZE },
      { list: 'SCHEDULED', cursor: undefined, limit: TASK_PAGE_SIZE },
    ]);
    expect(root.querySelector('h1')?.textContent?.trim()).toBe('Today');
    expect(root.querySelector('.date')?.textContent?.trim()).toBe('Tuesday, October 20, 2026');
  });

  it('shows a loading note until every section answered', async () => {
    configure();
    await render();
    expect(text()).toContain('Loading today…');

    call('due', 'overdue').resolve({ items: [RENEW_PASSPORT] });
    call('due', 'today').resolve({ items: [] });
    await refresh();
    expect(text()).toContain('Loading today…');

    call('list', 'SCHEDULED').resolve({ items: [] });
    await refresh();
    expect(text()).not.toContain('Loading today…');
  });

  it('shows the three sections in order with their counts', async () => {
    configure();
    await render();
    await answer([RENEW_PASSPORT], [PAY_INVOICE], [WATER_PLANTS]);

    expect(sectionIds()).toEqual(['today-overdue', 'today-due', 'today-scheduled']);
    expect(titlesIn('overdue')).toEqual(['Renew the passport']);
    expect(titlesIn('due')).toEqual(['Pay the fictitious invoice']);
    expect(titlesIn('scheduled')).toEqual(['Water the plants']);
    expect(root.querySelector('#today-overdue')?.textContent?.replace(/\s+/g, ' ').trim()).toBe(
      'Overdue 1',
    );
    expect(root.querySelector('#today-due')?.textContent).toContain('Due today');
    expect(root.querySelector('#today-scheduled')?.textContent).toContain('Scheduled for today');
    expect(root.querySelector('section.group.overdue')?.getAttribute('aria-labelledby')).toBe(
      'today-overdue',
    );
  });

  it('hides sections without tasks', async () => {
    configure();
    await render();
    await answer([], [PAY_INVOICE], []);

    expect(sectionIds()).toEqual(['today-due']);
    expect(text()).not.toContain('Nothing overdue');
  });

  it('shows a task that is in two sections only once, in the first', async () => {
    const overdueAndScheduled = task('t-dentist', 'Book the dentist', {
      list: 'SCHEDULED',
      dueDate: YESTERDAY,
      scheduledDate: TODAY,
    });
    const dueAndScheduled = task('t-call', 'Call Alice Example', {
      list: 'SCHEDULED',
      dueDate: TODAY,
      scheduledDate: YESTERDAY,
      position: 2,
    });
    configure();
    await render();
    await answer(
      [overdueAndScheduled],
      [dueAndScheduled],
      [overdueAndScheduled, dueAndScheduled, WATER_PLANTS],
    );

    expect(titlesIn('overdue')).toEqual(['Book the dentist']);
    expect(titlesIn('due')).toEqual(['Call Alice Example']);
    expect(titlesIn('scheduled')).toEqual(['Water the plants']);
    expect(root.querySelectorAll('app-task-row')).toHaveLength(3);
  });

  it('shows scheduled tasks dated today or earlier, not later ones or undated ones', async () => {
    configure();
    await render();
    await answer(
      [],
      [],
      [
        task('t-past', 'Sort the old mail', { list: 'SCHEDULED', scheduledDate: YESTERDAY }),
        WATER_PLANTS,
        task('t-future', 'Prepare the trip', { list: 'SCHEDULED', scheduledDate: TOMORROW }),
        task('t-undated', 'Undated scheduled task', { list: 'SCHEDULED' }),
      ],
    );

    expect(titlesIn('scheduled')).toEqual(['Sort the old mail', 'Water the plants']);
  });

  it('says there is nothing today and links to the next actions', async () => {
    configure();
    await render();
    await answer(
      [],
      [],
      [task('t-future', 'Prepare the trip', { list: 'SCHEDULED', scheduledDate: TOMORROW })],
    );

    expect(sectionIds()).toEqual([]);
    expect(text()).toContain('Nothing overdue, due or scheduled today.');
    const link = root.querySelector<HTMLAnchorElement>('.clear-day a')!;
    expect(link.textContent?.trim()).toBe('Pick a next action');
    expect(link.getAttribute('href')).toBe('/next');
  });

  it('shows an error when a section fails and retries only the failed one', async () => {
    configure();
    await render();
    call('due', 'overdue').resolve({ items: [RENEW_PASSPORT] });
    call('due', 'today').reject(new Error('503'));
    call('list', 'SCHEDULED').resolve({ items: [] });
    await refresh();

    expect(root.querySelector('[role=alert]')?.textContent).toContain(
      "Today's tasks could not be loaded.",
    );
    const before = taskCalls.length;
    root.querySelector<HTMLButtonElement>('[role=alert] button')!.click();
    await refresh();

    expect(taskCalls.slice(before).map((c) => c.params['due'])).toEqual(['today']);
    taskCalls.at(-1)!.resolve({ items: [PAY_INVOICE] });
    await refresh();

    expect(root.querySelector('[role=alert]')).toBeNull();
    expect(sectionIds()).toEqual(['today-overdue', 'today-due']);
  });

  it('completes a task in its section', async () => {
    configure();
    await render();
    await answer([RENEW_PASSPORT], [PAY_INVOICE], []);

    root
      .querySelector<HTMLInputElement>('section[aria-labelledby="today-due"] input[type=checkbox]')!
      .click();
    await refresh();

    expect(api.invoke).toHaveBeenCalledWith(
      completeTask,
      expect.objectContaining({ id: PAY_INVOICE.id }),
    );
    expect(sectionIds()).toEqual(['today-overdue']);
  });

  it('brings a task back into its section when completing it fails', async () => {
    const original = api.invoke.getMockImplementation()!;
    api.invoke.mockImplementation((fn: unknown, params?: Record<string, unknown>) =>
      fn === completeTask ? Promise.reject(new Error('409')) : original(fn, params),
    );
    configure();
    await render();
    await answer([RENEW_PASSPORT], [], []);

    root.querySelector<HTMLInputElement>('section.group input[type=checkbox]')!.click();
    await refresh();

    expect(titlesIn('overdue')).toEqual(['Renew the passport']);
  });

  it('opens a task on its title', async () => {
    configure();
    await render();
    await answer([RENEW_PASSPORT], [], []);

    root.querySelector<HTMLButtonElement>('section.group .title')!.click();
    await refresh();

    expect(TestBed.inject(Router).url).toBe(`/tasks/${RENEW_PASSPORT.id}`);
  });

  describe('extension slots', () => {
    it('renders sections of other features after the task sections, ordered by order', async () => {
      configure([
        todaySectionProvider({ id: 'habits', order: 20, component: FakeHabits }),
        todaySectionProvider({ id: 'calendar', order: 10, component: FakeEvents }),
      ]);
      await render();
      await answer([RENEW_PASSPORT], [], []);

      const extensions = [...root.querySelectorAll('.extension')];
      expect(extensions.map((el) => el.getAttribute('data-section'))).toEqual([
        'calendar',
        'habits',
      ]);
      expect(extensions.map((el) => el.textContent?.trim())).toEqual([
        'Calendar today',
        'Habits due',
      ]);
      // After the task sections.
      const overdue = root.querySelector('section.group')!;
      expect(
        overdue.compareDocumentPosition(extensions[0]) & Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBeTruthy();
    });

    it('renders the extension sections also while loading and on a day without tasks', async () => {
      configure([todaySectionProvider({ id: 'calendar', order: 10, component: FakeEvents })]);
      await render();
      expect(root.querySelector('app-fake-events')).not.toBeNull();

      await answer([], [], []);
      expect(text()).toContain('Nothing overdue, due or scheduled today.');
      expect(root.querySelector('app-fake-events')).not.toBeNull();
    });

    it('renders no extension sections when no feature adds one', async () => {
      configure();
      await render();
      await answer([], [], []);

      expect(TestBed.inject(TODAY_SECTIONS, null)).toBeNull();
      expect(root.querySelectorAll('.extension')).toHaveLength(0);
    });
  });
});
