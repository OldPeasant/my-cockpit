import { Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { Subject } from 'rxjs';
import {
  Api,
  ChangeEvent,
  completeTask,
  createTask,
  listContexts,
  listProjects,
  listTasks,
  moveTask,
  TaskResponse,
  uncompleteTask,
} from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { addDays, localDate } from '../data';
import { LogbookPage } from './logbook-page';
import { ScheduledPage } from './scheduled-page';
import { SomedayPage } from './someday-page';
import { WaitingPage } from './waiting-page';

interface Call {
  fn: unknown;
  params: Record<string, unknown>;
}

function task(id: string, fields: Partial<TaskResponse> = {}): TaskResponse {
  return {
    id,
    title: `Task ${id}`,
    list: 'WAITING',
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

describe('GTD list pages', () => {
  let calls: Call[];
  let items: TaskResponse[];
  let element: HTMLElement;
  /** Makes the next call of `fn` fail. */
  let failing: unknown;
  let listFails: boolean;
  /** Calls of `held.fn` wait until the test calls `held.release()`. */
  let held: { fn: unknown; until: Promise<void>; release: () => void } | undefined;

  function hold(fn: unknown): void {
    let release!: () => void;
    const until = new Promise<void>((resolve) => (release = resolve));
    held = { fn, until, release };
  }

  const callsOf = (fn: unknown) => calls.filter((call) => call.fn === fn);
  const titles = () =>
    [...element.querySelectorAll('app-task-row .title')].map((e) => e.textContent?.trim());
  const headings = () =>
    [...element.querySelectorAll('.day-head')].map((e) =>
      e.textContent?.replace(/\s+/g, ' ').trim(),
    );
  const button = (label: string) =>
    [...element.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.getAttribute('aria-label') === label || b.textContent?.trim() === label,
    );

  async function render(component: Type<unknown>, tasks: TaskResponse[]): Promise<void> {
    items = tasks;
    const fixture = TestBed.createComponent(component);
    element = fixture.nativeElement;
    document.body.appendChild(element);
    await settle();
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function refresh(): Promise<void> {
    await settle();
    TestBed.tick();
    await settle();
    TestBed.tick();
  }

  async function click(target: HTMLElement | undefined): Promise<void> {
    expect(target).toBeDefined();
    target!.click();
    await refresh();
  }

  beforeEach(() => {
    calls = [];
    failing = undefined;
    listFails = false;
    held = undefined;
    const api = {
      invoke: async (fn: unknown, params: Record<string, unknown> = {}) => {
        calls.push({ fn, params });
        if (held && fn === held.fn) await held.until;
        if (fn === failing) {
          failing = undefined;
          throw new Error('refused');
        }
        if (fn === listTasks) {
          if (listFails) throw new Error('offline');
          return { items };
        }
        if (fn === listContexts || fn === listProjects) return { items: [] };
        if (fn === createTask) {
          return task('new', { ...(params['body'] as object), list: 'NEXT' });
        }
        const current = items.find((t) => t.id === params['id'])!;
        if (fn === moveTask) return { ...current, ...(params['body'] as object), version: 2 };
        if (fn === completeTask) return { ...current, completedAt: new Date().toISOString() };
        if (fn === uncompleteTask) return { ...current, completedAt: undefined, version: 2 };
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

  describe('Waiting For', () => {
    const waiting = () => [
      task('t1', { title: 'Invoice', waitingSince: addDays(today, -2) }),
      task('t2', { title: 'Lease', waitingSince: addDays(today, -10), waitingForText: 'Alex' }),
      task('t3', { title: 'Parcel' }),
      task('t4', { title: 'Quote', waitingSince: today }),
    ];

    it('loads the WAITING list sorted by waitingSince, oldest first', async () => {
      await render(WaitingPage, waiting());

      expect(callsOf(listTasks)[0].params).toMatchObject({ list: 'WAITING' });
      expect(titles()).toEqual(['Lease', 'Invoice', 'Quote', 'Parcel']);
    });

    it('shows how long each task waits and accents waits over a week', async () => {
      await render(WaitingPage, waiting());

      const ages = [...element.querySelectorAll('.age')];
      expect(ages.map((a) => a.textContent?.trim())).toEqual(['10 days', '2 days', 'Today']);
      expect(ages.map((a) => a.classList.contains('long'))).toEqual([true, false, false]);
      expect(element.querySelector('.who')?.textContent?.trim()).toBe('for Alex');
    });

    it('follow up creates a next action and keeps the waiting task', async () => {
      await render(WaitingPage, waiting());

      await click(button('Follow up on “Lease”'));

      expect(callsOf(createTask)[0].params['body']).toEqual({
        title: 'Follow up with Alex: Lease',
        list: 'NEXT',
        projectId: undefined,
      });
      expect(titles()).toContain('Lease');
      expect(titles()).not.toContain('Follow up with Alex: Lease');
      expect(element.querySelector('[role=status]')?.textContent?.trim()).toBe(
        'Added “Follow up with Alex: Lease” to Next.',
      );
    });

    it('announces nothing when the follow up fails', async () => {
      await render(WaitingPage, waiting());
      failing = createTask;

      await click(button('Follow up on “Lease”'));

      expect(element.querySelector('[role=status]')?.textContent?.trim()).toBe('');
      expect(button('Follow up on “Lease”')?.disabled).toBe(false);
    });

    it('shows an empty state', async () => {
      await render(WaitingPage, []);
      expect(element.textContent).toContain('Nothing to wait for.');
    });

    it('accents a wait only once it is longer than a week', async () => {
      await render(WaitingPage, [
        task('t1', { title: 'Eight', waitingSince: addDays(today, -8) }),
        task('t2', { title: 'Seven', waitingSince: addDays(today, -7) }),
        task('t3', { title: 'One', waitingSince: addDays(today, -1) }),
        task('t4', { title: 'Future', waitingSince: addDays(today, 3) }),
      ]);

      const ages = [...element.querySelectorAll('.age')];
      expect(ages.map((a) => a.textContent?.trim())).toEqual([
        '8 days',
        '7 days',
        '1 day',
        'Today',
      ]);
      expect(ages.map((a) => a.classList.contains('long'))).toEqual([true, false, false, false]);
    });

    it('follow up keeps the project of the waiting task', async () => {
      await render(WaitingPage, [
        task('t1', { title: 'Contract', waitingSince: today, projectId: 'p-fictional' }),
      ]);

      await click(button('Follow up on “Contract”'));

      expect(callsOf(createTask)[0].params['body']).toEqual({
        title: 'Follow up: Contract',
        list: 'NEXT',
        projectId: 'p-fictional',
      });
    });

    it('creates only one follow up while one is running', async () => {
      await render(WaitingPage, waiting());
      hold(createTask);

      await click(button('Follow up on “Lease”'));
      expect(button('Follow up on “Lease”')?.disabled).toBe(true);
      // A second click on the (re-enabled by force) button is ignored while the first runs.
      button('Follow up on “Lease”')!.disabled = false;
      await click(button('Follow up on “Lease”'));
      expect(button('Follow up on “Invoice”')?.disabled).toBe(false);

      held!.release();
      await refresh();

      expect(callsOf(createTask)).toHaveLength(1);
      expect(button('Follow up on “Lease”')?.disabled).toBe(false);
      expect(element.querySelector('[role=status]')?.textContent?.trim()).toBe(
        'Added “Follow up with Alex: Lease” to Next.',
      );
    });

    it('shows a loading state until the list arrives', async () => {
      hold(listTasks);
      items = waiting();
      const fixture = TestBed.createComponent(WaitingPage);
      element = fixture.nativeElement;
      document.body.appendChild(element);
      await settle();
      fixture.detectChanges();

      expect(element.textContent).toContain('Loading…');
      expect(titles()).toEqual([]);

      held!.release();
      await refresh();
      expect(element.textContent).not.toContain('Loading…');
      expect(titles()).toHaveLength(4);
    });

    it('shows a load error with a retry', async () => {
      listFails = true;
      await render(WaitingPage, waiting());
      expect(element.querySelector('[role=alert]')?.textContent).toContain(
        'The list could not be loaded.',
      );

      listFails = false;
      await click(button('Try again'));
      expect(titles()).toHaveLength(4);
    });

    it('completes a task from its checkbox', async () => {
      await render(WaitingPage, waiting());

      await click(element.querySelector<HTMLElement>('[aria-label="Complete “Lease”"]')!);

      expect(callsOf(completeTask)[0].params).toMatchObject({ id: 't2', 'If-Match': '"1"' });
      expect(titles()).not.toContain('Lease');
    });

    it('opens a task from its title', async () => {
      await render(WaitingPage, waiting());
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

      await click(element.querySelector<HTMLElement>('app-task-row .title')!);

      expect(navigate).toHaveBeenCalledWith(['/tasks', 't2']);
    });
  });

  describe('Someday', () => {
    const someday = () => [
      task('t2', { title: 'Learn Italian', list: 'SOMEDAY' }),
      task('t1', { title: 'Sail to Elba', list: 'SOMEDAY' }),
    ];

    it('loads the SOMEDAY list in list order', async () => {
      await render(SomedayPage, someday());

      expect(callsOf(listTasks)[0].params).toMatchObject({ list: 'SOMEDAY' });
      expect(titles()).toEqual(['Sail to Elba', 'Learn Italian']);
    });

    it('activate moves the task to Next and out of the list', async () => {
      await render(SomedayPage, someday());

      await click(button('Activate “Learn Italian”'));

      expect(callsOf(moveTask)[0].params).toMatchObject({
        id: 't2',
        'If-Match': '"1"',
        body: { list: 'NEXT' },
      });
      expect(titles()).toEqual(['Sail to Elba']);
    });

    it('brings the task back when activating fails', async () => {
      await render(SomedayPage, someday());
      failing = moveTask;

      await click(button('Activate “Learn Italian”'));

      expect(titles()).toEqual(['Sail to Elba', 'Learn Italian']);
      expect(button('Activate “Learn Italian”')?.disabled).toBe(false);
    });

    it('shows an empty state', async () => {
      await render(SomedayPage, []);
      expect(element.textContent).toContain('No someday ideas.');
    });

    it('completes a someday task from its checkbox', async () => {
      await render(SomedayPage, someday());

      await click(element.querySelector<HTMLElement>('[aria-label="Complete “Sail to Elba”"]')!);

      expect(callsOf(completeTask)[0].params).toMatchObject({ id: 't1' });
      expect(titles()).toEqual(['Learn Italian']);
    });

    it('moves the task only once on a double activate', async () => {
      await render(SomedayPage, someday());
      hold(moveTask);
      const activate = button('Activate “Sail to Elba”')!;

      activate.click();
      activate.click();
      held!.release();
      await refresh();

      expect(callsOf(moveTask)).toHaveLength(1);
      expect(titles()).toEqual(['Learn Italian']);
    });
  });

  describe('Scheduled', () => {
    it('loads the SCHEDULED list grouped by date, earliest first', async () => {
      const later = addDays(today, 5);
      await render(ScheduledPage, [
        task('t3', { title: 'Renew passport', list: 'SCHEDULED', scheduledDate: later }),
        task('t1', { title: 'Book dentist', list: 'SCHEDULED', scheduledDate: addDays(today, 1) }),
        task('t2', { title: 'Water plants', list: 'SCHEDULED', scheduledDate: today }),
        task('t4', { title: 'Call bank', list: 'SCHEDULED', scheduledDate: later }),
      ]);

      expect(callsOf(listTasks)[0].params).toMatchObject({ list: 'SCHEDULED' });
      const [y, m, d] = later.split('-').map(Number);
      const laterLabel = new Date(y, m - 1, d).toLocaleDateString('en-US', {
        weekday: 'long',
        month: 'short',
        day: 'numeric',
        year: 'numeric',
      });
      expect(headings()).toEqual(['Today', 'Tomorrow', laterLabel]);
      const groups = [...element.querySelectorAll('.day')].map((day) =>
        [...day.querySelectorAll('app-task-row .title')].map((e) => e.textContent?.trim()),
      );
      expect(groups).toEqual([['Water plants'], ['Book dentist'], ['Renew passport', 'Call bank']]);
    });

    it('shows an empty state', async () => {
      await render(ScheduledPage, []);
      expect(element.textContent).toContain('Nothing scheduled.');
    });

    it('heads past dates with Yesterday and undated tasks with No date', async () => {
      await render(ScheduledPage, [
        task('t1', { title: 'Undated', list: 'SCHEDULED' }),
        task('t2', { title: 'Missed', list: 'SCHEDULED', scheduledDate: addDays(today, -1) }),
      ]);

      expect(headings()).toEqual(['Yesterday', 'No date']);
      expect(titles()).toEqual(['Missed', 'Undated']);
    });

    it('completes a scheduled task from its checkbox', async () => {
      await render(ScheduledPage, [
        task('t1', { title: 'Book dentist', list: 'SCHEDULED', scheduledDate: today }),
      ]);

      await click(element.querySelector<HTMLElement>('[aria-label="Complete “Book dentist”"]')!);

      expect(callsOf(completeTask)[0].params).toMatchObject({ id: 't1' });
      expect(element.textContent).toContain('Nothing scheduled.');
    });
  });

  describe('Logbook', () => {
    const at = (daysAgo: number, hour: number) => {
      const date = new Date();
      date.setDate(date.getDate() - daysAgo);
      date.setHours(hour, 0, 0, 0);
      return date.toISOString();
    };
    const done = () => [
      task('t1', { title: 'Paid rent', list: 'NEXT', completedAt: at(1, 9) }),
      task('t2', { title: 'Filed taxes', list: 'NEXT', completedAt: at(0, 8) }),
      task('t3', { title: 'Fixed bike', list: 'NEXT', completedAt: at(0, 7) }),
      task('t4', { title: 'Old thing', list: 'SOMEDAY', completedAt: at(9, 12) }),
    ];

    it('loads completed tasks of all lists grouped by day, newest first', async () => {
      await render(LogbookPage, done());

      const params = callsOf(listTasks)[0].params;
      expect(params).toMatchObject({ completed: true });
      expect(params['list']).toBeUndefined();
      expect(headings().slice(0, 2)).toEqual(['Today', 'Yesterday']);
      expect(headings()).toHaveLength(3);
      expect(titles()).toEqual(['Filed taxes', 'Fixed bike', 'Paid rent', 'Old thing']);
    });

    it('uncompletes a task from its checkbox and drops it from the logbook', async () => {
      await render(LogbookPage, done());

      await click(element.querySelector<HTMLElement>('[aria-label="Reopen “Paid rent”"]')!);

      expect(callsOf(uncompleteTask)[0].params).toMatchObject({ id: 't1', 'If-Match': '"1"' });
      expect(titles()).not.toContain('Paid rent');
    });

    it('keeps the task when uncompleting fails', async () => {
      await render(LogbookPage, done());
      failing = uncompleteTask;

      await click(element.querySelector<HTMLElement>('[aria-label="Reopen “Paid rent”"]')!);

      expect(titles()).toContain('Paid rent');
    });

    it('shows an empty state', async () => {
      await render(LogbookPage, []);
      expect(element.textContent).toContain('Nothing done yet.');
    });

    it('heads older days with their full date', async () => {
      await render(LogbookPage, done());

      const old = new Date(done()[3].completedAt!).toLocaleDateString('en-US', {
        weekday: 'long',
        month: 'short',
        day: 'numeric',
        year: 'numeric',
      });
      expect(headings()[2]).toBe(old);
    });

    it('shows done tasks with a checked checkbox', async () => {
      await render(LogbookPage, done());

      const boxes = [...element.querySelectorAll<HTMLInputElement>('input[type=checkbox]')];
      expect(boxes).toHaveLength(4);
      expect(boxes.every((box) => box.checked)).toBe(true);
    });
  });

  describe.each([
    ['Someday', SomedayPage, { list: 'SOMEDAY' }],
    ['Scheduled', ScheduledPage, { list: 'SCHEDULED', scheduledDate: today }],
    ['Logbook', LogbookPage, { list: 'NEXT', completedAt: '2026-10-01T09:00:00Z' }],
  ] as const)('%s shared behavior', (_name, page, fields: Partial<TaskResponse>) => {
    it('shows a loading state until the list arrives', async () => {
      hold(listTasks);
      items = [task('t1', { title: 'Fictional item', ...fields })];
      const fixture = TestBed.createComponent<unknown>(page);
      element = fixture.nativeElement;
      document.body.appendChild(element);
      await settle();
      fixture.detectChanges();

      expect(element.textContent).toContain('Loading…');

      held!.release();
      await refresh();
      expect(element.textContent).not.toContain('Loading…');
      expect(titles()).toEqual(['Fictional item']);
    });

    it('opens a task from its title', async () => {
      await render(page, [task('t1', { title: 'Fictional item', ...fields })]);
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

      await click(element.querySelector<HTMLElement>('app-task-row .title')!);

      expect(navigate).toHaveBeenCalledWith(['/tasks', 't1']);
    });

    it('shows an error with a retry that loads the list', async () => {
      listFails = true;
      await render(page, [task('t1', { title: 'Fictional item', ...fields })]);
      expect(element.querySelector('[role=alert]')?.textContent).toContain(
        'The list could not be loaded.',
      );
      expect(titles()).toEqual([]);

      listFails = false;
      await click(button('Try again'));

      expect(element.querySelector('[role=alert]')).toBeNull();
      expect(titles()).toEqual(['Fictional item']);
      expect(callsOf(listTasks)).toHaveLength(2);
    });
  });
});
