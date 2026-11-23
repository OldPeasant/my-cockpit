import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { Subject } from 'rxjs';
import {
  Api,
  ChangeEvent,
  completeTask,
  listContexts,
  listProjects,
  listTasks,
  TaskResponse,
} from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { NAV_GROUPS } from '../../../shell/nav-sections';
import gtdRoutes from '../gtd.routes';
import { addDays, localDate } from '../data';
import { TicklerPage } from './tickler-page';
import { isInTickler, TICKLER_HORIZON_YEARS, ticklerWindows } from './tickler-store';

function task(id: string, fields: Partial<TaskResponse> = {}): TaskResponse {
  return {
    id,
    title: `Task ${id}`,
    list: 'INBOX',
    position: Number(id.replace(/\D/g, '')) || 1,
    contextIds: [],
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-01T08:00:00Z',
    version: 1,
    ...fields,
  };
}

const settle = () => new Promise((resolve) => setTimeout(resolve));
const today = localDate();

/** The date the page shows for `date` (`EEE, d MMM`, with the year outside this one). */
function label(date: string): string {
  const [y, m, d] = date.split('-').map(Number);
  const day = new Date(y, m - 1, d);
  const weekday = day.toLocaleDateString('en-US', { weekday: 'short' });
  const month = day.toLocaleDateString('en-US', { month: 'short' });
  const year = date.slice(0, 4) === today.slice(0, 4) ? '' : ` ${y}`;
  return `Back on ${weekday}, ${d} ${month}${year}`;
}

describe('Tickler page', () => {
  let calls: { fn: unknown; params: Record<string, unknown> }[];
  let items: TaskResponse[];
  let listFails: boolean;
  let release: (() => void) | undefined;
  /** When set, `listTasks` answers in pages of this size, chained by `nextCursor`. */
  let pageSize: number | undefined;
  /** When set, the `listTasks` call for the range starting on this date fails. */
  let failingFrom: string | undefined;
  let completeFails: boolean;
  let taskEvents: Subject<ChangeEvent>;
  let resync: Subject<void>;
  let element: HTMLElement;

  const callsOf = (fn: unknown) => calls.filter((call) => call.fn === fn);
  const titles = () =>
    [...element.querySelectorAll('app-task-row .title')].map((e) => e.textContent?.trim());
  const backs = () =>
    [...element.querySelectorAll('.back')].map((e) => e.textContent?.replace(/\s+/g, ' ').trim());

  async function refresh(): Promise<void> {
    await settle();
    TestBed.tick();
    await settle();
    TestBed.tick();
  }

  async function render(tasks: TaskResponse[]): Promise<ComponentFixture<TicklerPage>> {
    items = tasks;
    const fixture = TestBed.createComponent(TicklerPage);
    element = fixture.nativeElement;
    document.body.appendChild(element);
    await settle();
    fixture.detectChanges();
    await fixture.whenStable();
    await refresh();
    return fixture;
  }

  beforeEach(() => {
    calls = [];
    listFails = false;
    release = undefined;
    pageSize = undefined;
    failingFrom = undefined;
    completeFails = false;
    taskEvents = new Subject<ChangeEvent>();
    resync = new Subject<void>();
    const api = {
      invoke: async (fn: unknown, params: Record<string, unknown> = {}) => {
        calls.push({ fn, params });
        if (fn === listTasks) {
          if (release) await new Promise<void>((resolve) => (release = resolve));
          if (listFails) throw new Error('offline');
          // Like the server: a range matches the due or the tickler date, open tasks only.
          const from = params['from'] as string;
          const to = params['to'] as string;
          if (from === failingFrom) throw new Error('server error');
          const within = (date?: string) => !!date && date >= from && date <= to;
          const matching = items.filter(
            (t) => !t.completedAt && (within(t.dueDate) || within(t.ticklerDate)),
          );
          if (pageSize === undefined) return { items: matching };
          const start = Number(params['cursor'] ?? 0);
          const end = start + pageSize;
          return {
            items: matching.slice(start, end),
            nextCursor: end < matching.length ? String(end) : undefined,
          };
        }
        if (fn === listContexts || fn === listProjects) return { items: [] };
        const current = items.find((t) => t.id === params['id'])!;
        if (fn === completeTask) {
          if (completeFails) throw new Error('conflict');
          return { ...current, completedAt: new Date().toISOString() };
        }
        throw new Error('unexpected call');
      },
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: Api, useValue: api },
        {
          provide: ChangeEventsService,
          useValue: {
            resync$: resync,
            events$: (type: string) => (type === 'TASK' ? taskEvents : new Subject<ChangeEvent>()),
          },
        },
      ],
    });
  });

  afterEach(() => element?.remove());

  it('is in the navigation right after Scheduled, at /tickler', () => {
    const tasks = NAV_GROUPS.find((group) => group.id === 'tasks')!.sections;
    const index = tasks.findIndex((section) => section.id === 'scheduled');
    expect(tasks[index + 1]).toEqual({ id: 'tickler', path: '/tickler', label: 'Tickler' });
    expect(gtdRoutes.some((route) => route.path === 'tickler')).toBe(true);
  });

  it('reads the next years a year at a time through the task list date range', async () => {
    await render([]);

    const ranges = callsOf(listTasks).map((call) => [call.params['from'], call.params['to']]);
    expect(ranges).toEqual(ticklerWindows(today));
    expect(ranges).toHaveLength(TICKLER_HORIZON_YEARS);
    expect(ranges[0][0]).toBe(addDays(today, 1));
  });

  it('lists the tasks in the tickler by date, then list order, with their return date', async () => {
    const soon = addDays(today, 3);
    const later = addDays(today, 500);
    await render([
      task('t4', { title: 'Renew lease', ticklerDate: later }),
      task('t2', { title: 'Plan trip', ticklerDate: soon }),
      task('t1', { title: 'Check offer', ticklerDate: soon }),
      task('t3', { title: 'Call back', ticklerDate: addDays(today, 1) }),
    ]);

    expect(titles()).toEqual(['Call back', 'Check offer', 'Plan trip', 'Renew lease']);
    expect(backs()).toEqual(['Back tomorrow', label(soon), label(soon), label(later)]);
  });

  it('hides tasks whose tickler date has come and tasks only due in the range', async () => {
    await render([
      task('t1', { title: 'Back today', ticklerDate: today }),
      task('t2', { title: 'Due next week', dueDate: addDays(today, 7) }),
      task('t3', { title: 'Done already', ticklerDate: addDays(today, 7), completedAt: today }),
      task('t4', { title: 'Waiting', ticklerDate: addDays(today, 7) }),
    ]);

    expect(titles()).toEqual(['Waiting']);
  });

  it('lists a task found in two ranges once', async () => {
    await render([
      task('t1', { title: 'Twice', dueDate: addDays(today, 2), ticklerDate: addDays(today, 400) }),
    ]);

    expect(titles()).toEqual(['Twice']);
  });

  it('shows an empty state', async () => {
    await render([]);
    expect(element.textContent).toContain('Nothing in the tickler.');
  });

  it('shows a loading state until the list arrives', async () => {
    release = () => undefined;
    items = [task('t1', { title: 'Later', ticklerDate: addDays(today, 2) })];
    const fixture = TestBed.createComponent(TicklerPage);
    element = fixture.nativeElement;
    await settle();
    fixture.detectChanges();
    expect(element.textContent).toContain('Loading…');

    // Each yearly request waits for its own release.
    for (let i = 0; i < TICKLER_HORIZON_YEARS; i++) {
      release!();
      await settle();
    }
    release = undefined;
    await refresh();
    expect(titles()).toEqual(['Later']);
  });

  it('shows a load error (e.g. offline) with a retry', async () => {
    listFails = true;
    await render([task('t1', { title: 'Later', ticklerDate: addDays(today, 2) })]);
    expect(element.querySelector('[role=alert]')?.textContent).toContain(
      'The list could not be loaded.',
    );

    listFails = false;
    const retry = [...element.querySelectorAll('button')].find(
      (b) => b.textContent?.trim() === 'Try again',
    )!;
    retry.click();
    await refresh();
    expect(titles()).toEqual(['Later']);
  });

  it('opens the task detail from a row', async () => {
    await render([task('t1', { title: 'Later', ticklerDate: addDays(today, 2) })]);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    element.querySelector<HTMLElement>('app-task-row .title')!.click();
    await refresh();

    expect(navigate).toHaveBeenCalledWith(['/tasks', 't1']);
  });

  it('shows the changed date after coming back from the task detail', async () => {
    const tasks = [
      task('t1', { title: 'First', ticklerDate: addDays(today, 2) }),
      task('t2', { title: 'Second', ticklerDate: addDays(today, 5) }),
    ];
    await render(tasks);
    element.remove();

    // The detail moved "First" after "Second" and took "Second" out of the tickler.
    await render([
      { ...tasks[0], ticklerDate: addDays(today, 9), version: 2 },
      { ...tasks[1], ticklerDate: undefined, list: 'NEXT', version: 2 },
    ]);

    expect(titles()).toEqual(['First']);
    expect(backs()).toEqual([label(addDays(today, 9))]);
  });

  it('completes a task from its checkbox', async () => {
    await render([task('t1', { title: 'Later', ticklerDate: addDays(today, 2) })]);

    element.querySelector<HTMLElement>('[aria-label="Complete “Later”"]')!.click();
    await refresh();

    expect(callsOf(completeTask)[0].params).toMatchObject({ id: 't1', 'If-Match': '"1"' });
    expect(titles()).toEqual([]);
  });

  it('brings a task back when completing it fails', async () => {
    completeFails = true;
    await render([task('t1', { title: 'Later', ticklerDate: addDays(today, 2) })]);

    element.querySelector<HTMLElement>('[aria-label="Complete “Later”"]')!.click();
    await refresh();

    expect(callsOf(completeTask)).toHaveLength(1);
    expect(titles()).toEqual(['Later']);
  });

  it('follows nextCursor to read every page of a range', async () => {
    pageSize = 2;
    const first = ticklerWindows(today)[0][0];
    await render([
      task('t1', { title: 'One', ticklerDate: addDays(first, 1) }),
      task('t2', { title: 'Two', ticklerDate: addDays(first, 2) }),
      task('t3', { title: 'Three', ticklerDate: addDays(first, 3) }),
      task('t4', { title: 'Four', ticklerDate: addDays(first, 4) }),
      task('t5', { title: 'Five', ticklerDate: addDays(first, 5) }),
      task('t6', { title: 'Far', ticklerDate: addDays(today, 1200) }),
    ]);

    expect(titles()).toEqual(['One', 'Two', 'Three', 'Four', 'Five', 'Far']);
    const firstRange = callsOf(listTasks).filter((call) => call.params['from'] === first);
    expect(firstRange.map((call) => call.params['cursor'])).toEqual([undefined, '2', '4']);
    expect(firstRange.every((call) => call.params['limit'] === 200)).toBe(true);
  });

  it('lists tasks on the edges of the ranges and none beyond the horizon', async () => {
    const windows = ticklerWindows(today);
    const [, firstEnd] = windows[0];
    const [secondStart] = windows[1];
    const horizonEnd = windows[TICKLER_HORIZON_YEARS - 1][1];
    await render([
      task('t1', { title: 'End of year one', ticklerDate: firstEnd }),
      task('t2', { title: 'Start of year two', ticklerDate: secondStart }),
      task('t3', { title: 'Last day', ticklerDate: horizonEnd }),
      task('t4', { title: 'Too far', ticklerDate: addDays(horizonEnd, 1) }),
    ]);

    expect(titles()).toEqual(['End of year one', 'Start of year two', 'Last day']);
  });

  it('shows the error, not a partial list, when a later range fails', async () => {
    failingFrom = ticklerWindows(today)[4][0];
    await render([task('t1', { title: 'Soon', ticklerDate: addDays(today, 2) })]);

    expect(titles()).toEqual([]);
    expect(element.querySelector('[role=alert]')?.textContent).toContain(
      'The list could not be loaded.',
    );
  });

  it('reloads on a task change event and shows the new state', async () => {
    const tasks = [
      task('t1', { title: 'First', ticklerDate: addDays(today, 2) }),
      task('t2', { title: 'Second', ticklerDate: addDays(today, 5) }),
    ];
    await render(tasks);
    const before = callsOf(listTasks).length;

    // Elsewhere (e.g. the task detail in another tab): t1 moves later, t2 leaves the tickler,
    // t3 goes into it.
    items = [
      { ...tasks[0], ticklerDate: addDays(today, 9), version: 2 },
      { ...tasks[1], ticklerDate: undefined, list: 'NEXT', version: 2 },
      task('t3', { title: 'Third', ticklerDate: addDays(today, 4) }),
    ];
    taskEvents.next({ type: 'TASK', op: 'UPDATED', id: 't1', at: '2026-11-23T10:00:00Z' });
    await refresh();

    expect(callsOf(listTasks).length - before).toBe(TICKLER_HORIZON_YEARS);
    expect(titles()).toEqual(['Third', 'First']);
    expect(backs()).toEqual([label(addDays(today, 4)), label(addDays(today, 9))]);
  });

  it('drops a deleted task on its change event without reloading', async () => {
    await render([
      task('t1', { title: 'First', ticklerDate: addDays(today, 2) }),
      task('t2', { title: 'Second', ticklerDate: addDays(today, 5) }),
    ]);
    const before = callsOf(listTasks).length;

    taskEvents.next({ type: 'TASK', op: 'DELETED', id: 't1', at: '2026-11-23T10:00:00Z' });
    await refresh();

    expect(callsOf(listTasks).length).toBe(before);
    expect(titles()).toEqual(['Second']);
  });

  it('reloads after a resync', async () => {
    await render([]);
    items = [task('t1', { title: 'Missed', ticklerDate: addDays(today, 3) })];

    resync.next();
    await refresh();

    expect(titles()).toEqual(['Missed']);
  });

  describe('with a fixed date', () => {
    beforeEach(() => {
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(new Date(2027, 2, 10, 12, 0));
    });

    afterEach(() => vi.useRealTimers());

    it('shows "tomorrow", the date this year and the date with its year after it', async () => {
      await render([
        task('t1', { title: 'Tomorrow', ticklerDate: '2027-03-11' }),
        task('t2', { title: 'This year', ticklerDate: '2027-03-12' }),
        task('t3', { title: 'Last of the year', ticklerDate: '2027-12-31' }),
        task('t4', { title: 'Next year', ticklerDate: '2028-01-05' }),
        task('t5', { title: 'Today', ticklerDate: '2027-03-10' }),
      ]);

      expect(callsOf(listTasks)[0].params).toMatchObject({ from: '2027-03-11', to: '2028-03-10' });
      expect(titles()).toEqual(['Tomorrow', 'This year', 'Last of the year', 'Next year']);
      expect(backs()).toEqual([
        'Back tomorrow',
        'Back on Fri, 12 Mar',
        'Back on Fri, 31 Dec',
        'Back on Wed, 5 Jan 2028',
      ]);
    });
  });
});

describe('Tickler store helpers', () => {
  it('splits the horizon into consecutive ranges of at most a year', () => {
    const windows = ticklerWindows('2027-02-28');
    expect(windows[0]).toEqual(['2027-03-01', '2028-02-29']);
    expect(windows[1]).toEqual(['2028-03-01', '2029-02-28']);
    for (let i = 1; i < windows.length; i++) {
      expect(windows[i][0]).toBe(addDays(windows[i - 1][1], 1));
    }
    for (const [from, to] of windows) {
      const days = (Date.parse(to) - Date.parse(from)) / 86_400_000;
      expect(days).toBeLessThan(366);
    }
  });

  it('keeps open tasks with a tickler date after today', () => {
    expect(isInTickler(task('a', { ticklerDate: '2027-01-02' }), '2027-01-01')).toBe(true);
    expect(isInTickler(task('a', { ticklerDate: '2027-01-01' }), '2027-01-01')).toBe(false);
    expect(isInTickler(task('a'), '2027-01-01')).toBe(false);
    expect(
      isInTickler(task('a', { ticklerDate: '2027-01-05', completedAt: 'x' }), '2027-01-01'),
    ).toBe(false);
  });
});
