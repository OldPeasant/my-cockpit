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
    const api = {
      invoke: async (fn: unknown, params: Record<string, unknown> = {}) => {
        calls.push({ fn, params });
        if (fn === listTasks) {
          if (release) await new Promise<void>((resolve) => (release = resolve));
          if (listFails) throw new Error('offline');
          // Like the server: a range matches the due or the tickler date, open tasks only.
          const from = params['from'] as string;
          const to = params['to'] as string;
          const within = (date?: string) => !!date && date >= from && date <= to;
          return {
            items: items.filter(
              (t) => !t.completedAt && (within(t.dueDate) || within(t.ticklerDate)),
            ),
          };
        }
        if (fn === listContexts || fn === listProjects) return { items: [] };
        const current = items.find((t) => t.id === params['id'])!;
        if (fn === completeTask) return { ...current, completedAt: new Date().toISOString() };
        throw new Error('unexpected call');
      },
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: Api, useValue: api },
        {
          provide: ChangeEventsService,
          useValue: { resync$: new Subject<void>(), events$: () => new Subject<ChangeEvent>() },
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
