import { Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import {
  Api,
  completeTask,
  createTask,
  deleteProject,
  deleteTask,
  getReviewProjects,
  getReviewSomeday,
  getReviewStaleNextActions,
  getReviewWaitingFor,
  moveTask,
  ProjectResponse,
  setProjectNextStep,
  TaskResponse,
  updateProject,
} from '../../../api/generated';
import { NextActionsStep } from './next-actions-step';
import { ProjectsStep } from './projects-step';
import { SomedayStep } from './someday-step';
import { WaitingStep } from './waiting-step';

interface Call {
  fn: unknown;
  params: Record<string, unknown>;
}

function task(id: string, fields: Partial<TaskResponse> = {}): TaskResponse {
  return {
    id,
    title: `Task ${id}`,
    list: 'NEXT',
    position: 1,
    contextIds: [],
    createdAt: '2026-08-01T08:00:00Z',
    updatedAt: '2026-08-01T08:00:00Z',
    version: 1,
    ...fields,
  };
}

function project(id: string, fields: Partial<ProjectResponse> = {}): ProjectResponse {
  return {
    id,
    title: `Project ${id}`,
    status: 'ACTIVE',
    stalled: false,
    createdAt: '2026-08-01T08:00:00Z',
    updatedAt: '2026-08-01T08:00:00Z',
    version: 1,
    ...fields,
  };
}

const settle = () => new Promise((resolve) => setTimeout(resolve));
const LOADERS = [
  getReviewWaitingFor,
  getReviewProjects,
  getReviewStaleNextActions,
  getReviewSomeday,
];

describe('Review step lists', () => {
  let calls: Call[];
  let tasks: TaskResponse[];
  let projects: ProjectResponse[];
  let element: HTMLElement;
  /** Makes the next call of `fn` fail. */
  let failing: unknown;
  let loadFails: boolean;
  /** Every step's load waits for this. */
  let loadGate: Promise<void> | undefined;
  /** Calls of `held.fn` wait until the test calls `held.release()`. */
  let held: { fn: unknown; until: Promise<void>; release: () => void } | undefined;

  function hold(fn: unknown): void {
    let release!: () => void;
    const until = new Promise<void>((resolve) => (release = resolve));
    held = { fn, until, release };
  }

  const callsOf = (fn: unknown) => calls.filter((call) => call.fn === fn);
  const actionCalls = () => calls.filter((call) => !LOADERS.includes(call.fn as never));
  const titles = () =>
    [...element.querySelectorAll('.item .title')].map((e) => e.textContent?.trim());
  const status = () => element.querySelector('[role=status]')?.textContent?.trim();
  const choice = (title: string, label: string) =>
    [
      ...(element
        .querySelector(`[role=group][aria-label="${title}"]`)
        ?.querySelectorAll<HTMLButtonElement>('button') ?? []),
    ].find((b) => b.textContent?.trim() === label);
  const button = (label: string) =>
    [...element.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === label,
    );

  async function refresh(): Promise<void> {
    await settle();
    TestBed.tick();
    await settle();
    TestBed.tick();
  }

  async function render(component: Type<unknown>): Promise<void> {
    const fixture = TestBed.createComponent(component);
    element = fixture.nativeElement;
    document.body.appendChild(element);
    await settle();
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function click(target: HTMLElement | undefined): Promise<void> {
    expect(target).toBeDefined();
    target!.click();
    await refresh();
  }

  beforeEach(() => {
    calls = [];
    tasks = [];
    projects = [];
    failing = undefined;
    loadFails = false;
    loadGate = undefined;
    held = undefined;
    const api = {
      invoke: async (fn: unknown, params: Record<string, unknown> = {}) => {
        calls.push({ fn, params });
        if (held && fn === held.fn) await held.until;
        if (fn === failing) {
          failing = undefined;
          throw new Error('refused');
        }
        if (LOADERS.includes(fn as never)) {
          await loadGate;
          if (loadFails) throw new Error('offline');
        }
        const body = params['body'] as Record<string, unknown> | undefined;
        if (fn === getReviewWaitingFor || fn === getReviewStaleNextActions) {
          return { items: tasks };
        }
        if (fn === getReviewProjects) return { items: projects };
        if (fn === getReviewSomeday) return { tasks, projects };
        if (fn === createTask) return task('t-new', { ...body });
        if (fn === setProjectNextStep) {
          const current = projects.find((p) => p.id === params['id'])!;
          return { ...current, stalled: false, nextTaskId: body!['taskId'] as string, version: 2 };
        }
        if (fn === moveTask || fn === completeTask || fn === updateProject) return {};
        if (fn === deleteTask || fn === deleteProject) return undefined;
        throw new Error('unexpected call');
      },
    };
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: Api, useValue: api }],
    });
  });

  afterEach(() => element?.remove());

  describe('Waiting For (step 4)', () => {
    beforeEach(() => {
      tasks = [
        task('t1', {
          title: 'Fictional quote',
          list: 'WAITING',
          waitingForText: 'Robin Example',
          waitingSince: '2026-10-05',
          version: 3,
        }),
        task('t2', { title: 'Fictional parcel', list: 'WAITING', projectId: 'p-fictional' }),
      ];
    });

    it('loads the waiting-for items and shows for whom and since when', async () => {
      await render(WaitingStep);

      expect(callsOf(getReviewWaitingFor)).toHaveLength(1);
      expect(titles()).toEqual(['Fictional quote', 'Fictional parcel']);
      const meta = [...element.querySelectorAll('.item .meta')].map((e) => e.textContent ?? '');
      expect(meta[0]).toContain('for Robin Example');
      expect(meta[0]).toContain('since Oct 5');
      expect(meta[1].trim()).toBe('');
    });

    it('still waiting removes the item without calling the server', async () => {
      await render(WaitingStep);

      await click(choice('Fictional quote', 'Still waiting'));

      expect(actionCalls()).toEqual([]);
      expect(titles()).toEqual(['Fictional parcel']);
      expect(status()).toBe('Still waiting for “Fictional quote”.');
    });

    it('follow up creates a next action in the same project and removes the item', async () => {
      await render(WaitingStep);

      await click(choice('Fictional parcel', 'Follow up'));

      expect(callsOf(createTask)[0].params['body']).toEqual({
        title: 'Follow up: Fictional parcel',
        list: 'NEXT',
        projectId: 'p-fictional',
      });
      expect(titles()).toEqual(['Fictional quote']);
      expect(status()).toBe('Added “Follow up: Fictional parcel” to Next.');
    });

    it('follow up names whom the item waits for', async () => {
      await render(WaitingStep);

      await click(choice('Fictional quote', 'Follow up'));

      expect(callsOf(createTask)[0].params['body']).toMatchObject({
        title: 'Follow up with Robin Example: Fictional quote',
      });
    });

    it('done completes the task with If-Match and removes it', async () => {
      await render(WaitingStep);

      await click(choice('Fictional quote', 'Done'));

      expect(callsOf(completeTask)[0].params).toEqual({ id: 't1', 'If-Match': '"3"' });
      expect(titles()).toEqual(['Fictional parcel']);
      expect(status()).toBe('Completed “Fictional quote”.');
    });

    it('keeps the item and announces nothing when done fails', async () => {
      await render(WaitingStep);
      failing = completeTask;

      await click(choice('Fictional quote', 'Done'));

      expect(titles()).toEqual(['Fictional quote', 'Fictional parcel']);
      expect(status()).toBe('');
      expect(choice('Fictional quote', 'Done')?.disabled).toBe(false);
    });

    it('keeps the earlier announcement when a later action fails', async () => {
      await render(WaitingStep);
      await click(choice('Fictional parcel', 'Still waiting'));
      failing = completeTask;

      await click(choice('Fictional quote', 'Done'));

      expect(status()).toBe('Still waiting for “Fictional parcel”.');
      expect(titles()).toEqual(['Fictional quote']);
    });

    it('disables only the item whose action runs and runs it once', async () => {
      await render(WaitingStep);
      hold(completeTask);

      await click(choice('Fictional quote', 'Done'));
      expect(choice('Fictional quote', 'Done')?.disabled).toBe(true);
      expect(choice('Fictional quote', 'Still waiting')?.disabled).toBe(true);
      expect(choice('Fictional quote', 'Follow up')?.disabled).toBe(true);
      expect(choice('Fictional parcel', 'Done')?.disabled).toBe(false);

      // A second decision on the same item while the first runs is ignored.
      const followUp = choice('Fictional quote', 'Follow up')!;
      followUp.disabled = false;
      await click(followUp);
      expect(callsOf(createTask)).toHaveLength(0);

      held!.release();
      await refresh();

      expect(callsOf(completeTask)).toHaveLength(1);
      expect(titles()).toEqual(['Fictional parcel']);
      expect(status()).toBe('Completed “Fictional quote”.');
    });

    it('shows the empty state once every item is decided', async () => {
      await render(WaitingStep);

      await click(choice('Fictional quote', 'Still waiting'));
      await click(choice('Fictional parcel', 'Still waiting'));

      expect(element.textContent).toContain('Nothing left to check here.');
    });
  });

  describe('Projects (step 5)', () => {
    beforeEach(() => {
      projects = [
        project('p1', { title: 'Fictional garden', stalled: true, version: 4 }),
        project('p2', { title: 'Fictional website', nextTaskId: 't9' }),
      ];
    });

    const input = () =>
      element.querySelector<HTMLInputElement>(
        'input[aria-label="Next step for “Fictional garden”"]',
      );
    const select = (title: string) =>
      element.querySelector<HTMLSelectElement>(`select[aria-label="Status of “${title}”"]`);
    const add = () =>
      element.querySelector<HTMLButtonElement>('.item.stalled form button[type=submit]');

    async function type(value: string): Promise<void> {
      input()!.value = value;
      input()!.dispatchEvent(new Event('input'));
      await refresh();
    }

    async function choose(title: string, value: string): Promise<void> {
      select(title)!.value = value;
      select(title)!.dispatchEvent(new Event('change'));
      await refresh();
    }

    it('lists the projects, marks the stalled one and offers it a next step field', async () => {
      await render(ProjectsStep);

      expect(callsOf(getReviewProjects)).toHaveLength(1);
      expect(titles()).toEqual(['Fictional garden', 'Fictional website']);
      const items = [...element.querySelectorAll('.item')];
      expect(items.map((i) => i.classList.contains('stalled'))).toEqual([true, false]);
      expect(items[0].textContent).toContain('No next step');
      expect(items[1].textContent).toContain('Has a next step');
      expect(element.querySelectorAll('form.inline')).toHaveLength(1);
      expect(element.querySelector('a.title')?.getAttribute('href')).toBe('/projects/p1');
      expect(select('Fictional garden')?.value).toBe('ACTIVE');
    });

    it('enables Add only with a non-blank next step', async () => {
      await render(ProjectsStep);
      expect(add()?.disabled).toBe(true);

      await type('   ');
      expect(add()?.disabled).toBe(true);

      await type('Buy fictional seeds');
      expect(add()?.disabled).toBe(false);
    });

    it('does nothing when a blank next step is submitted', async () => {
      await render(ProjectsStep);
      // Nothing typed yet (e.g. Enter in the empty field).
      element
        .querySelector('form.inline')!
        .dispatchEvent(new Event('submit', { cancelable: true }));
      await refresh();
      expect(actionCalls()).toEqual([]);

      await type('   ');

      element
        .querySelector('form.inline')!
        .dispatchEvent(new Event('submit', { cancelable: true }));
      await refresh();

      expect(actionCalls()).toEqual([]);
    });

    it('add creates a NEXT task in the project, sets it as next step and keeps the project', async () => {
      await render(ProjectsStep);
      await type('  Buy fictional seeds  ');

      await click(add()!);

      expect(callsOf(createTask)[0].params['body']).toEqual({
        title: 'Buy fictional seeds',
        list: 'NEXT',
        projectId: 'p1',
      });
      expect(callsOf(setProjectNextStep)[0].params).toEqual({
        id: 'p1',
        body: { taskId: 't-new' },
      });
      const order = calls.map((c) => c.fn);
      expect(order.indexOf(createTask)).toBeLessThan(order.indexOf(setProjectNextStep));
      // The project stays, now with its next step.
      expect(titles()).toEqual(['Fictional garden', 'Fictional website']);
      const garden = element.querySelector('.item')!;
      expect(garden.classList.contains('stalled')).toBe(false);
      expect(garden.textContent).toContain('Has a next step');
      expect(element.querySelector('form.inline')).toBeNull();
      expect(status()).toBe('Added “Buy fictional seeds” as the next step of “Fictional garden”.');
    });

    it('keeps the stalled project and the typed step when creating the task fails', async () => {
      await render(ProjectsStep);
      await type('Buy fictional seeds');
      failing = createTask;

      await click(add()!);

      expect(callsOf(setProjectNextStep)).toHaveLength(0);
      expect(element.querySelector('.item')?.classList.contains('stalled')).toBe(true);
      expect(input()?.value).toBe('Buy fictional seeds');
      expect(add()?.disabled).toBe(false);
      expect(status()).toBe('');
    });

    it('keeps the project stalled and announces nothing when setting the next step fails', async () => {
      await render(ProjectsStep);
      await type('Buy fictional seeds');
      failing = setProjectNextStep;

      await click(add()!);

      expect(titles()).toEqual(['Fictional garden', 'Fictional website']);
      expect(element.querySelector('.item')?.classList.contains('stalled')).toBe(true);
      expect(status()).toBe('');
    });

    it('disables the project controls while the next step is being added', async () => {
      await render(ProjectsStep);
      await type('Buy fictional seeds');
      hold(createTask);

      await click(add()!);
      expect(add()?.disabled).toBe(true);
      expect(select('Fictional garden')?.disabled).toBe(true);
      expect(choice('Fictional garden', 'Looks good')?.disabled).toBe(true);
      expect(choice('Fictional website', 'Looks good')?.disabled).toBe(false);

      held!.release();
      await refresh();
      expect(callsOf(createTask)).toHaveLength(1);
      expect(select('Fictional garden')?.disabled).toBe(false);
    });

    it('a status change updates the project with If-Match and removes it', async () => {
      await render(ProjectsStep);

      await choose('Fictional garden', 'ON_HOLD');

      expect(callsOf(updateProject)[0].params).toEqual({
        id: 'p1',
        'If-Match': '"4"',
        body: { status: 'ON_HOLD' },
      });
      expect(titles()).toEqual(['Fictional website']);
      expect(status()).toBe('“Fictional garden” is now On hold.');
    });

    it('choosing the current status changes nothing', async () => {
      await render(ProjectsStep);

      await choose('Fictional website', 'ACTIVE');

      expect(actionCalls()).toEqual([]);
      expect(titles()).toEqual(['Fictional garden', 'Fictional website']);
    });

    it('shows the old status again when the status change fails', async () => {
      await render(ProjectsStep);
      failing = updateProject;

      await choose('Fictional website', 'DROPPED');

      expect(titles()).toEqual(['Fictional garden', 'Fictional website']);
      expect(select('Fictional website')?.value).toBe('ACTIVE');
      expect(select('Fictional website')?.disabled).toBe(false);
      expect(status()).toBe('');
    });

    it('looks good removes the project without calling the server', async () => {
      await render(ProjectsStep);

      await click(choice('Fictional website', 'Looks good'));

      expect(actionCalls()).toEqual([]);
      expect(titles()).toEqual(['Fictional garden']);
      expect(status()).toBe('Kept “Fictional website”.');
    });
  });

  describe('Next actions (step 6)', () => {
    beforeEach(() => {
      tasks = [
        task('t1', { title: 'Fictional tax form', updatedAt: '2026-08-14T10:00:00Z', version: 2 }),
        task('t2', { title: 'Fictional bike repair' }),
      ];
    });

    it('loads next actions unchanged for more than 30 days', async () => {
      await render(NextActionsStep);

      expect(callsOf(getReviewStaleNextActions)[0].params).toEqual({ olderThanDays: 30 });
      expect(titles()).toEqual(['Fictional tax form', 'Fictional bike repair']);
      expect(element.querySelector('.item .meta')?.textContent?.trim()).toBe(
        'last changed Aug 14, 2026',
      );
    });

    it('keep removes the action without calling the server', async () => {
      await render(NextActionsStep);

      await click(choice('Fictional tax form', 'Keep'));

      expect(actionCalls()).toEqual([]);
      expect(titles()).toEqual(['Fictional bike repair']);
      expect(status()).toBe('Kept “Fictional tax form”.');
    });

    it('someday moves the action to SOMEDAY with If-Match and removes it', async () => {
      await render(NextActionsStep);

      await click(choice('Fictional tax form', 'Someday'));

      expect(callsOf(moveTask)[0].params).toEqual({
        id: 't1',
        'If-Match': '"2"',
        body: { list: 'SOMEDAY' },
      });
      expect(titles()).toEqual(['Fictional bike repair']);
      expect(status()).toBe('Moved “Fictional tax form” to Someday.');
    });

    it('delete trashes the action with If-Match and removes it', async () => {
      await render(NextActionsStep);

      await click(choice('Fictional tax form', 'Delete'));

      expect(callsOf(deleteTask)[0].params).toEqual({ id: 't1', 'If-Match': '"2"' });
      expect(titles()).toEqual(['Fictional bike repair']);
      expect(status()).toBe('Moved “Fictional tax form” to the trash.');
    });

    it('keeps the action when moving it fails', async () => {
      await render(NextActionsStep);
      failing = moveTask;

      await click(choice('Fictional tax form', 'Someday'));

      expect(titles()).toEqual(['Fictional tax form', 'Fictional bike repair']);
      expect(status()).toBe('');
      expect(choice('Fictional tax form', 'Someday')?.disabled).toBe(false);
    });

    it('deletes only once on a double click', async () => {
      await render(NextActionsStep);
      hold(deleteTask);
      const remove = choice('Fictional tax form', 'Delete')!;

      remove.click();
      remove.click();
      held!.release();
      await refresh();

      expect(callsOf(deleteTask)).toHaveLength(1);
      expect(titles()).toEqual(['Fictional bike repair']);
    });
  });

  describe('Someday/Maybe (step 7)', () => {
    beforeEach(() => {
      tasks = [task('t1', { title: 'Fictional pottery class', list: 'SOMEDAY', version: 5 })];
      projects = [project('p1', { title: 'Fictional boat trip', status: 'SOMEDAY', version: 6 })];
    });

    it('lists someday tasks, then someday projects, with their kind', async () => {
      await render(SomedayStep);

      expect(callsOf(getReviewSomeday)).toHaveLength(1);
      expect(titles()).toEqual(['Fictional pottery class', 'Fictional boat trip']);
      expect(
        [...element.querySelectorAll('.item .meta')].map((e) => e.textContent?.trim()),
      ).toEqual(['Task', 'Project']);
    });

    it('activating a task moves it to NEXT with If-Match', async () => {
      await render(SomedayStep);

      await click(choice('Fictional pottery class', 'Activate'));

      expect(callsOf(moveTask)[0].params).toEqual({
        id: 't1',
        'If-Match': '"5"',
        body: { list: 'NEXT' },
      });
      expect(callsOf(updateProject)).toHaveLength(0);
      expect(titles()).toEqual(['Fictional boat trip']);
      expect(status()).toBe('Activated “Fictional pottery class”.');
    });

    it('activating a project makes it ACTIVE with If-Match', async () => {
      await render(SomedayStep);

      await click(choice('Fictional boat trip', 'Activate'));

      expect(callsOf(updateProject)[0].params).toEqual({
        id: 'p1',
        'If-Match': '"6"',
        body: { status: 'ACTIVE' },
      });
      expect(callsOf(moveTask)).toHaveLength(0);
      expect(titles()).toEqual(['Fictional pottery class']);
    });

    it('deleting a task trashes the task', async () => {
      await render(SomedayStep);

      await click(choice('Fictional pottery class', 'Delete'));

      expect(callsOf(deleteTask)[0].params).toEqual({ id: 't1', 'If-Match': '"5"' });
      expect(callsOf(deleteProject)).toHaveLength(0);
      expect(titles()).toEqual(['Fictional boat trip']);
      expect(status()).toBe('Moved “Fictional pottery class” to the trash.');
    });

    it('deleting a project trashes the project', async () => {
      await render(SomedayStep);

      await click(choice('Fictional boat trip', 'Delete'));

      expect(callsOf(deleteProject)[0].params).toEqual({ id: 'p1', 'If-Match': '"6"' });
      expect(callsOf(deleteTask)).toHaveLength(0);
      expect(titles()).toEqual(['Fictional pottery class']);
    });

    it('keep removes the item without calling the server', async () => {
      await render(SomedayStep);

      await click(choice('Fictional boat trip', 'Keep'));

      expect(actionCalls()).toEqual([]);
      expect(titles()).toEqual(['Fictional pottery class']);
      expect(status()).toBe('Kept “Fictional boat trip”.');
    });

    it('keeps the project when activating it fails', async () => {
      await render(SomedayStep);
      failing = updateProject;

      await click(choice('Fictional boat trip', 'Activate'));

      expect(titles()).toEqual(['Fictional pottery class', 'Fictional boat trip']);
      expect(status()).toBe('');
    });
  });

  describe.each([
    ['Waiting For', WaitingStep, 'Nothing left to check here.'],
    ['Projects', ProjectsStep, 'No active projects left to review.'],
    ['Next actions', NextActionsStep, 'No stale next actions.'],
    ['Someday/Maybe', SomedayStep, 'Nothing on Someday/Maybe.'],
  ] as const)('%s shared behavior', (_name, step, empty) => {
    beforeEach(() => {
      tasks = [task('t1', { title: 'Fictional item' })];
      projects = [];
      if (step === ProjectsStep) projects = [project('p1', { title: 'Fictional item' })];
    });

    it('shows a loading state until the items arrive', async () => {
      let release!: () => void;
      loadGate = new Promise<void>((resolve) => (release = resolve));
      const fixture = TestBed.createComponent<unknown>(step);
      element = fixture.nativeElement;
      document.body.appendChild(element);
      await settle();
      fixture.detectChanges();

      expect(element.textContent).toContain('Loading…');
      expect(titles()).toEqual([]);

      release();
      await refresh();
      expect(element.textContent).not.toContain('Loading…');
      expect(titles()).toEqual(['Fictional item']);
    });

    it('shows an empty state', async () => {
      tasks = [];
      projects = [];
      await render(step);

      expect(element.textContent).toContain(empty);
    });

    it('shows a load error with a retry that loads the items', async () => {
      loadFails = true;
      await render(step);

      expect(element.querySelector('[role=alert]')?.textContent).toContain(
        'This step could not be loaded.',
      );
      expect(titles()).toEqual([]);

      loadFails = false;
      await click(button('Try again'));

      expect(element.querySelector('[role=alert]')).toBeNull();
      expect(titles()).toEqual(['Fictional item']);
      expect(calls.filter((call) => LOADERS.includes(call.fn as never))).toHaveLength(2);
    });
  });
});
