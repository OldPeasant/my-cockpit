import { CdkDragDrop, CdkDropList } from '@angular/cdk/drag-drop';
import { Type } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { BehaviorSubject, Subject } from 'rxjs';
import {
  Api,
  AreaResponse,
  ChangeEvent,
  completeTask,
  createTask,
  listAreas,
  listContexts,
  listProjects,
  listTasks,
  orderProjectTasks,
  ProjectResponse,
  setProjectNextStep,
  TaskResponse,
  updateProject,
} from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { ProjectDetailPage } from './project-detail-page';
import { ProjectsPage } from './projects-page';

/**
 * Error, loading, not-found, drag-drop and edge cases of the projects views. The happy paths are in
 * projects-pages.spec.ts.
 */

interface Call {
  fn: unknown;
  params: Record<string, unknown>;
}

type Handler = (params: Record<string, unknown>) => Promise<unknown>;

function project(id: string, fields: Partial<ProjectResponse> = {}): ProjectResponse {
  return {
    id,
    title: `Project ${id}`,
    status: 'ACTIVE',
    stalled: false,
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-01T08:00:00Z',
    version: 1,
    ...fields,
  };
}

function task(id: string, fields: Partial<TaskResponse> = {}): TaskResponse {
  return {
    id,
    title: `Task ${id}`,
    list: 'NEXT',
    projectId: 'p1',
    position: Number(id.replace(/\D/g, '')) || 0,
    contextIds: [],
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-01T08:00:00Z',
    version: 1,
    ...fields,
  };
}

/** A promise the test settles when it wants to. */
function deferred<T = unknown>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const settle = () => new Promise((resolve) => setTimeout(resolve));

describe('Projects views: states and edge cases', () => {
  let calls: Call[];
  let projects: ProjectResponse[];
  let areas: AreaResponse[];
  let tasks: TaskResponse[];
  let overrides: Map<unknown, Handler>;
  let fixture: ComponentFixture<unknown>;
  let element: HTMLElement;
  let params: BehaviorSubject<ReturnType<typeof convertToParamMap>>;

  const callsOf = (fn: unknown) => calls.filter((call) => call.fn === fn);
  const text = (selector: string) =>
    [...element.querySelectorAll(selector)].map((e) => e.textContent?.replace(/\s+/g, ' ').trim());
  const button = (label: string) =>
    [...element.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.getAttribute('aria-label') === label || b.textContent?.trim() === label,
    );
  const titles = () => text('.task app-task-row .title');
  const announcement = () => element.querySelector('p[role=status]')?.textContent?.trim();

  async function refresh(): Promise<void> {
    for (let i = 0; i < 3; i++) {
      await settle();
      TestBed.tick();
    }
  }

  async function render(component: Type<unknown>): Promise<void> {
    fixture = TestBed.createComponent(component);
    element = fixture.nativeElement;
    document.body.appendChild(element);
    await settle();
    fixture.detectChanges();
    await refresh();
  }

  async function click(target: HTMLElement | undefined): Promise<void> {
    expect(target).toBeDefined();
    target!.click();
    await refresh();
  }

  async function type(selector: string, value: string): Promise<void> {
    const input = element.querySelector<HTMLInputElement | HTMLTextAreaElement>(selector)!;
    expect(input).toBeTruthy();
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await refresh();
  }

  async function submit(selector: string): Promise<void> {
    element.querySelector<HTMLFormElement>(selector)!.dispatchEvent(new Event('submit'));
    await refresh();
  }

  async function key(target: Element, name: string): Promise<KeyboardEvent> {
    const event = new KeyboardEvent('keydown', { key: name, cancelable: true });
    target.dispatchEvent(event);
    await refresh();
    return event;
  }

  /** What the CDK emits when a task is dropped at another place. */
  async function drop(previousIndex: number, currentIndex: number): Promise<void> {
    const list = fixture.debugElement.query(By.directive(CdkDropList)).injector.get(CdkDropList);
    list.dropped.emit({ previousIndex, currentIndex } as CdkDragDrop<readonly TaskResponse[]>);
    await refresh();
  }

  beforeEach(() => {
    calls = [];
    overrides = new Map();
    areas = [{ id: 'home', title: 'Home', position: 0, version: 1 }];
    projects = [];
    tasks = [];
    params = new BehaviorSubject(convertToParamMap({ id: 'p1' }));
    const api = {
      invoke: async (fn: unknown, p: Record<string, unknown> = {}) => {
        calls.push({ fn, params: p });
        const override = overrides.get(fn);
        if (override) return override(p);
        const body = p['body'] as Record<string, unknown>;
        if (fn === listProjects) return { items: projects };
        if (fn === listAreas) return { items: areas };
        if (fn === listContexts) return { items: [] };
        if (fn === listTasks) return { items: tasks.filter((t) => t.projectId === p['projectId']) };
        if (fn === updateProject) {
          const current = projects.find((x) => x.id === p['id'])!;
          const next = { ...current, ...body, version: current.version + 1 } as ProjectResponse;
          if (body['descriptionMd'] === null) delete next.descriptionMd;
          projects = projects.map((x) => (x.id === next.id ? next : x));
          return next;
        }
        if (fn === setProjectNextStep) {
          const current = projects.find((x) => x.id === p['id'])!;
          return { ...current, nextTaskId: body['taskId'], stalled: false, version: 2 };
        }
        if (fn === createTask) {
          const created = task('t9', { ...body, position: 9 });
          tasks = [...tasks, created];
          return created;
        }
        if (fn === completeTask) {
          const current = tasks.find((t) => t.id === p['id'])!;
          tasks = tasks.filter((t) => t.id !== current.id);
          return { ...current, completedAt: '2026-10-20T10:00:00Z', version: 2 };
        }
        if (fn === orderProjectTasks) {
          const ids = body['taskIds'] as string[];
          tasks = ids.map((id, position) => ({
            ...tasks.find((t) => t.id === id)!,
            position,
            version: 2,
          }));
          return { items: tasks };
        }
        throw new Error('unexpected call');
      },
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'projects/:id', children: [] },
          { path: 'tasks/:id', children: [] },
        ]),
        { provide: Api, useValue: api },
        { provide: ActivatedRoute, useValue: { paramMap: params } },
        {
          provide: ChangeEventsService,
          useValue: { resync$: new Subject<void>(), events$: () => new Subject<ChangeEvent>() },
        },
      ],
    });
  });

  afterEach(() => element?.remove());

  describe('list', () => {
    it('shows loading, then an error with a retry that loads the projects', async () => {
      const first = deferred();
      overrides.set(listProjects, () => first.promise);
      await render(ProjectsPage);
      expect(text('.empty')).toEqual(['Loading…']);

      first.reject(new Error('503'));
      await refresh();
      expect(element.querySelector('[role=alert]')?.textContent).toContain(
        'The projects could not be loaded.',
      );

      overrides.delete(listProjects);
      projects = [project('p1', { title: 'Tax return' })];
      await click(button('Try again'));

      expect(element.querySelector('[role=alert]')).toBeNull();
      expect(text('.project .name')).toEqual(['Tax return']);
    });

    it('counts several stalled projects and leaves hidden ones out of the count', async () => {
      projects = [
        project('p1', { stalled: true }),
        project('p2', { stalled: true, areaId: 'home' }),
        project('p3', { stalled: true, status: 'DROPPED' }),
      ];
      await render(ProjectsPage);

      expect(text('[role=status]')).toEqual(['2 projects need a next step.']);
      expect(text('.toggle')).toEqual(['Show completed and dropped (1)']);
      expect(element.querySelectorAll('.stalled-badge')).toHaveLength(2);
    });

    it('marks overdue due dates and labels non-active statuses only', async () => {
      projects = [
        project('p1', { title: 'Late', dueDate: '2020-01-15' }),
        project('p2', { title: 'Later', dueDate: '2999-01-15', status: 'SOMEDAY' }),
      ];
      await render(ProjectsPage);

      expect(text('.due.overdue')).toEqual(['Due Jan 15']);
      expect(text('.due')).toHaveLength(2);
      expect(text('.project .badge')).toEqual(['Someday']);
    });

    it('says "No open projects" instead of the onboarding text when all are finished', async () => {
      projects = [project('p1', { status: 'COMPLETED' }), project('p2', { status: 'DROPPED' })];
      await render(ProjectsPage);

      expect(text('.empty')).toEqual(['No open projects.']);
      expect(text('.toggle')).toEqual(['Show completed and dropped (2)']);
    });

    it('closes the new-project form on cancel without creating anything', async () => {
      await render(ProjectsPage);
      await click(button('New project'));
      expect(element.querySelector('app-project-form')).not.toBeNull();
      expect(button('New project')).toBeUndefined();

      await click(button('Cancel'));

      expect(element.querySelector('app-project-form')).toBeNull();
      expect(button('New project')).toBeDefined();
    });

    it('cancelling an inline edit closes the form and keeps the project unchanged', async () => {
      projects = [project('p1', { title: 'Tax return' })];
      await render(ProjectsPage);

      await click(button('Edit “Tax return”'));
      await type('app-project-form input[type=text]', 'Something else');
      await click(button('Cancel'));

      expect(element.querySelector('app-project-form')).toBeNull();
      expect(callsOf(updateProject)).toHaveLength(0);
      expect(text('.project .name')).toEqual(['Tax return']);
    });
  });

  describe('detail', () => {
    beforeEach(() => {
      projects = [project('p1', { title: 'Tax return', nextTaskId: 't1', areaId: 'home' })];
      tasks = [
        task('t1', { title: 'Collect receipts' }),
        task('t2', { title: 'Fill form' }),
        task('t3', { title: 'Send' }),
      ];
    });

    it('dragging a task saves the new order and announces it', async () => {
      await render(ProjectDetailPage);

      await drop(0, 2);

      expect(callsOf(orderProjectTasks)[0].params).toEqual({
        id: 'p1',
        body: { taskIds: ['t2', 't3', 't1'] },
      });
      expect(titles()).toEqual(['Fill form', 'Send', 'Collect receipts']);
      expect(announcement()).toBe('Moved “Collect receipts” to position 3 of 3.');
    });

    it('shows the dragged order while it is saved', async () => {
      const answer = deferred();
      overrides.set(orderProjectTasks, () => answer.promise);
      await render(ProjectDetailPage);

      await drop(2, 0);
      expect(titles()).toEqual(['Send', 'Collect receipts', 'Fill form']);

      answer.resolve({
        items: ['t3', 't1', 't2'].map((id, position) => ({
          ...tasks.find((t) => t.id === id)!,
          position,
          version: 2,
        })),
      });
      await refresh();
      expect(titles()).toEqual(['Send', 'Collect receipts', 'Fill form']);
    });

    it('a drop at the same place sends nothing', async () => {
      await render(ProjectDetailPage);

      await drop(1, 1);

      expect(callsOf(orderProjectTasks)).toHaveLength(0);
    });

    it('a refused reorder restores the previous order without an announcement', async () => {
      overrides.set(orderProjectTasks, () => Promise.reject(new Error('412')));
      await render(ProjectDetailPage);

      await drop(0, 1);

      expect(callsOf(orderProjectTasks)).toHaveLength(1);
      expect(titles()).toEqual(['Collect receipts', 'Fill form', 'Send']);
      expect(announcement()).toBe('');
    });

    it('arrow keys do nothing past the ends of the list or for other keys', async () => {
      await render(ProjectDetailPage);
      const handles = element.querySelectorAll<HTMLElement>('.handle');

      const up = await key(handles[0], 'ArrowUp');
      const down = await key(handles[2], 'ArrowDown');
      const other = await key(handles[1], 'Enter');

      expect(callsOf(orderProjectTasks)).toHaveLength(0);
      expect([up.defaultPrevented, down.defaultPrevented, other.defaultPrevented]).toEqual([
        false,
        false,
        false,
      ]);
    });

    it('ArrowDown moves the task one place and keeps the focus on its handle', async () => {
      await render(ProjectDetailPage);

      const event = await key(element.querySelectorAll('.handle')[0], 'ArrowDown');

      expect(event.defaultPrevented).toBe(true);
      expect(callsOf(orderProjectTasks)[0].params['body']).toEqual({
        taskIds: ['t2', 't1', 't3'],
      });
      expect(titles()).toEqual(['Fill form', 'Collect receipts', 'Send']);
      // The moved task's handle (now second) keeps the focus, so pressing again moves it further.
      expect(document.activeElement?.getAttribute('aria-label')).toBe(
        'Move “Collect receipts” (drag, or use the arrow keys)',
      );
      expect(document.activeElement).toBe(element.querySelectorAll('.handle')[1]);
    });

    it('pressing ArrowDown twice on the focused handle moves the same task to the end', async () => {
      await render(ProjectDetailPage);
      const first = element.querySelectorAll<HTMLElement>('.handle')[0];
      first.focus();

      await key(first, 'ArrowDown');
      await key(document.activeElement!, 'ArrowDown');

      expect(titles()).toEqual(['Fill form', 'Send', 'Collect receipts']);
    });

    it('shows an error when the tasks cannot be loaded, and retries', async () => {
      overrides.set(listTasks, () => Promise.reject(new Error('503')));
      await render(ProjectDetailPage);

      expect(element.querySelector('h1')?.textContent).toBe('Tax return');
      expect(element.querySelector('.block [role=alert]')?.textContent).toContain(
        'The tasks could not be loaded.',
      );

      overrides.delete(listTasks);
      await click(button('Try again'));

      expect(element.querySelector('[role=alert]')).toBeNull();
      expect(titles()).toEqual(['Collect receipts', 'Fill form', 'Send']);
    });

    it('shows loading, then an error when the projects cannot be loaded, and retries', async () => {
      const first = deferred();
      overrides.set(listProjects, () => first.promise);
      await render(ProjectDetailPage);
      expect(text('.page.empty')).toEqual(['Loading…']);

      first.reject(new Error('503'));
      await refresh();
      expect(element.querySelector('[role=alert]')?.textContent).toContain(
        'The projects could not be loaded.',
      );
      expect(element.querySelector('[role=alert]')?.textContent).not.toContain('does not exist');

      overrides.delete(listProjects);
      await click(button('Try again'));

      expect(element.querySelector('h1')?.textContent).toBe('Tax return');
    });

    it('shows area, due and review dates, overdue marked', async () => {
      projects = [
        project('p1', {
          title: 'Tax return',
          areaId: 'home',
          dueDate: '2020-03-01',
          reviewDate: '2999-04-02',
        }),
      ];
      await render(ProjectDetailPage);

      expect(text('.facts .fact')).toEqual(['Home', 'Due Mar 1', 'Review Apr 2']);
      expect(text('.fact.overdue')).toEqual(['Due Mar 1']);
      expect(text('.facts .badge')).toEqual(['Active']);
    });

    it('opens a task from its row', async () => {
      await render(ProjectDetailPage);

      await click(element.querySelectorAll<HTMLElement>('app-task-row button.title')[1]);

      expect(TestBed.inject(Router).url).toBe('/tasks/t2');
    });

    it('a failed completion of the next step does not prompt', async () => {
      overrides.set(completeTask, () => Promise.reject(new Error('500')));
      await render(ProjectDetailPage);

      await click(element.querySelector<HTMLElement>('.task.next input[type=checkbox]')!);

      expect(element.querySelector('[role=dialog]')).toBeNull();
      expect(titles()).toEqual(['Collect receipts', 'Fill form', 'Send']);
    });

    it('the prompt stays open when making a task the next step fails', async () => {
      projects = [project('p1', { title: 'Tax return', stalled: true })];
      overrides.set(setProjectNextStep, () => Promise.reject(new Error('412')));
      await render(ProjectDetailPage);
      await click(button('Choose next step'));
      expect(element.querySelector('#prompt-text')?.textContent?.trim()).toBe(
        'Pick the task that moves this project forward now.',
      );

      await click(button('Make “Fill form” the next step'));

      expect(callsOf(setProjectNextStep)).toHaveLength(1);
      expect(element.querySelector('[role=dialog]')).not.toBeNull();
      expect(element.querySelector('.task.next')).toBeNull();
      expect(announcement()).toBe('');
      expect(button('Make “Fill form” the next step')?.disabled).toBe(false);
    });

    it('opening the prompt moves the focus to its first choice', async () => {
      projects = [project('p1', { title: 'Tax return', stalled: true })];
      await render(ProjectDetailPage);

      await click(button('Choose next step'));

      expect(document.activeElement).toBe(button('Make “Collect receipts” the next step'));
    });

    it('opening the prompt without open tasks focuses the new-step input', async () => {
      projects = [project('p1', { title: 'Tax return', stalled: true })];
      tasks = [];
      await render(ProjectDetailPage);

      await click(button('Choose next step'));

      expect(document.activeElement).toBe(element.querySelector('#prompt-new'));
    });

    it('a failed "add as next step" after the task was created does not create it again', async () => {
      projects = [project('p1', { title: 'Tax return', stalled: true })];
      overrides.set(setProjectNextStep, () => Promise.reject(new Error('412')));
      await render(ProjectDetailPage);
      await click(button('Choose next step'));

      await type('#prompt-new', 'Book advisor');
      await submit('[role=dialog] form');

      expect(callsOf(createTask)).toHaveLength(1);
      expect(callsOf(setProjectNextStep)).toHaveLength(1);
      // The task exists: the input is cleared and the prompt offers it as a choice.
      expect(element.querySelector<HTMLInputElement>('#prompt-new')!.value).toBe('');
      expect(button('Add as next step')?.disabled).toBe(true);
      expect(element.querySelector('[role=dialog]')).not.toBeNull();
      expect(button('Make “Book advisor” the next step')).toBeDefined();
      expect(announcement()).toBe('');

      await submit('[role=dialog] form');
      expect(callsOf(createTask)).toHaveLength(1);

      overrides.delete(setProjectNextStep);
      await click(button('Make “Book advisor” the next step'));
      expect(callsOf(createTask)).toHaveLength(1);
      expect(callsOf(setProjectNextStep).at(-1)?.params['body']).toEqual({ taskId: 't9' });
      expect(element.querySelector('[role=dialog]')).toBeNull();
    });

    it('a failed next step after adding a task to a stalled project clears the input', async () => {
      projects = [project('p1', { title: 'Tax return', stalled: true })];
      overrides.set(setProjectNextStep, () => Promise.reject(new Error('412')));
      await render(ProjectDetailPage);

      await type('#add-task', 'Sign');
      await submit('form.add');

      expect(callsOf(createTask)).toHaveLength(1);
      expect(callsOf(setProjectNextStep)).toHaveLength(1);
      expect(element.querySelector<HTMLInputElement>('#add-task')!.value).toBe('');
      expect(button('Add task')?.disabled).toBe(true);
      await submit('form.add');
      expect(callsOf(createTask)).toHaveLength(1);
    });

    it('the prompt without open tasks offers only a new task; Escape skips it', async () => {
      projects = [project('p1', { title: 'Tax return', stalled: true })];
      tasks = [];
      await render(ProjectDetailPage);
      await click(button('Choose next step'));

      expect(element.querySelector('.choices')).toBeNull();
      expect(button('Add as next step')?.disabled).toBe(true);
      await type('#prompt-new', '   ');
      expect(button('Add as next step')?.disabled).toBe(true);
      await submit('[role=dialog] form');
      expect(callsOf(createTask)).toHaveLength(0);

      await key(element.querySelector('#prompt-new')!, 'Escape');
      expect(element.querySelector('[role=dialog]')).toBeNull();
      expect(element.querySelector('.stalled-note')).not.toBeNull();
    });

    it('adding a task to a project with a next step keeps that next step', async () => {
      await render(ProjectDetailPage);

      await type('#add-task', '  Sign  ');
      await submit('form.add');

      expect(callsOf(createTask)[0].params['body']).toEqual({
        title: 'Sign',
        list: 'NEXT',
        projectId: 'p1',
      });
      expect(callsOf(setProjectNextStep)).toHaveLength(0);
      expect(element.querySelector<HTMLInputElement>('#add-task')!.value).toBe('');
      expect(titles()).toEqual(['Collect receipts', 'Fill form', 'Send', 'Sign']);
    });

    it('does not add a whitespace-only task and keeps the text when adding fails', async () => {
      await render(ProjectDetailPage);

      await type('#add-task', '   ');
      expect(button('Add task')?.disabled).toBe(true);
      await submit('form.add');
      expect(callsOf(createTask)).toHaveLength(0);

      overrides.set(createTask, () => Promise.reject(new Error('500')));
      await type('#add-task', 'Sign');
      await submit('form.add');

      expect(callsOf(createTask)).toHaveLength(1);
      expect(element.querySelector<HTMLInputElement>('#add-task')!.value).toBe('Sign');
      expect(button('Add task')?.disabled).toBe(false);
    });

    it('an emptied description is cleared on the server', async () => {
      projects = [project('p1', { title: 'Tax return', descriptionMd: 'Old notes' })];
      await render(ProjectDetailPage);

      await click(button('Edit description'));
      expect(element.querySelector<HTMLTextAreaElement>('#description-md')!.value).toBe(
        'Old notes',
      );
      await type('#description-md', '  \n ');
      await click(button('Save description'));

      expect(callsOf(updateProject)[0].params['body']).toEqual({ descriptionMd: null });
      expect(element.querySelector('#description-md')).toBeNull();
      expect(button('Add description')).toBeDefined();
      expect(text('.block .empty').at(-1)).toContain('Note the outcome');
    });

    it('cancel and Escape discard description edits', async () => {
      projects = [project('p1', { title: 'Tax return', descriptionMd: 'Kept' })];
      await render(ProjectDetailPage);

      await click(button('Edit description'));
      await type('#description-md', 'Changed');
      await click(button('Cancel'));
      expect(element.querySelector('#description-md')).toBeNull();
      expect(element.querySelector('.markdown')?.innerHTML).toBe('<p>Kept</p>');

      await click(button('Edit description'));
      expect(element.querySelector<HTMLTextAreaElement>('#description-md')!.value).toBe('Kept');
      await key(element.querySelector('#description-md')!, 'Escape');
      expect(element.querySelector('#description-md')).toBeNull();
      expect(callsOf(updateProject)).toHaveLength(0);
    });

    it('keeps the description editor open when saving fails', async () => {
      overrides.set(updateProject, () => Promise.reject(new Error('412')));
      await render(ProjectDetailPage);

      await click(button('Add description'));
      await type('#description-md', 'Plan');
      await click(button('Save description'));

      expect(callsOf(updateProject)).toHaveLength(1);
      expect(element.querySelector<HTMLTextAreaElement>('#description-md')!.value).toBe('Plan');
      expect(button('Save description')?.disabled).toBe(false);
    });

    it('renders hostile description Markdown as text', async () => {
      projects = [
        project('p1', {
          title: 'Tax return',
          descriptionMd:
            '<img src=x onerror="alert(1)"> <script>alert(2)</script> [x](javascript:alert(3))',
        }),
      ];
      await render(ProjectDetailPage);

      const description = element.querySelector('.markdown')!;
      expect(description.querySelector('img, script, a')).toBeNull();
      expect(description.textContent).toContain('<img src=x onerror="alert(1)">');
    });

    it('edits the project from the detail and announces the save', async () => {
      await render(ProjectDetailPage);

      await click(button('Edit'));
      expect(button('Edit')).toBeUndefined();
      await type('app-project-form input[type=text]', 'Tax return 2026');
      await submit('app-project-form form');

      expect(callsOf(updateProject)[0].params['body']).toEqual({ title: 'Tax return 2026' });
      expect(element.querySelector('app-project-form')).toBeNull();
      expect(element.querySelector('h1')?.textContent).toBe('Tax return 2026');
      expect(announcement()).toBe('Saved “Tax return 2026”.');
    });

    it('cancelling the edit form on the detail keeps the project unchanged', async () => {
      await render(ProjectDetailPage);

      await click(button('Edit'));
      await type('app-project-form input[type=text]', 'Something else');
      await click(element.querySelector<HTMLElement>('app-project-form button.quiet')!);

      expect(element.querySelector('app-project-form')).toBeNull();
      expect(button('Edit')).toBeDefined();
      expect(callsOf(updateProject)).toHaveLength(0);
      expect(element.querySelector('h1')?.textContent).toBe('Tax return');
      expect(announcement()).toBe('');
    });

    it('shows the project while its tasks are still loading', async () => {
      const answer = deferred();
      overrides.set(listTasks, () => answer.promise);
      await render(ProjectDetailPage);

      expect(element.querySelector('h1')?.textContent).toBe('Tax return');
      expect(text('.block .empty')).toContain('Loading…');
      expect(element.querySelector('.tasks')).toBeNull();

      answer.resolve({ items: tasks });
      await refresh();
      expect(text('.block .empty')).not.toContain('Loading…');
      expect(titles()).toEqual(['Collect receipts', 'Fill form', 'Send']);
    });

    it('submitting "Add task" again while the first is saving creates one task', async () => {
      const answer = deferred();
      overrides.set(createTask, () => answer.promise);
      await render(ProjectDetailPage);

      await type('#add-task', 'Sign');
      await submit('form.add');
      expect(button('Add task')?.disabled).toBe(true);
      // Enter in the input submits the form even while the button is disabled.
      await submit('form.add');

      expect(callsOf(createTask)).toHaveLength(1);
      answer.resolve(task('t9', { title: 'Sign', position: 9 }));
      await refresh();
      expect(callsOf(createTask)).toHaveLength(1);
      expect(element.querySelector<HTMLInputElement>('#add-task')!.value).toBe('');
      expect(titles()).toEqual(['Collect receipts', 'Fill form', 'Send', 'Sign']);
    });

    it('submitting "Add as next step" again while the first is saving creates one task', async () => {
      projects = [project('p1', { title: 'Tax return', stalled: true })];
      const answer = deferred();
      overrides.set(createTask, () => answer.promise);
      await render(ProjectDetailPage);
      await click(button('Choose next step'));

      await type('#prompt-new', 'Book advisor');
      await submit('[role=dialog] form');
      await submit('[role=dialog] form');

      expect(callsOf(createTask)).toHaveLength(1);
      answer.resolve(task('t9', { title: 'Book advisor', position: 9 }));
      await refresh();
      expect(callsOf(createTask)).toHaveLength(1);
      expect(callsOf(setProjectNextStep)).toHaveLength(1);
      expect(callsOf(setProjectNextStep)[0].params['body']).toEqual({ taskId: 't9' });
      expect(element.querySelector('[role=dialog]')).toBeNull();
      expect(announcement()).toBe('“Book advisor” is the next step.');
    });

    it('completing the next step twice in a row sends one completion and opens one prompt', async () => {
      const answer = deferred();
      overrides.set(completeTask, () => answer.promise);
      await render(ProjectDetailPage);

      const check = element.querySelector<HTMLElement>('.task.next input[type=checkbox]')!;
      check.click();
      check.click();
      await refresh();

      expect(callsOf(completeTask)).toHaveLength(1);
      answer.resolve({
        ...tasks[0],
        completedAt: '2026-10-20T10:00:00Z',
        version: 2,
        needsNextStep: true,
      });
      await refresh();

      expect(callsOf(completeTask)).toHaveLength(1);
      expect(element.querySelectorAll('[role=dialog]')).toHaveLength(1);
      expect(element.querySelector('#prompt-text')?.textContent).toContain(
        '“Collect receipts” is done.',
      );
    });

    it('loads the tasks of another project when the route changes, closing the prompt', async () => {
      projects = [
        project('p1', { title: 'Tax return', stalled: true }),
        project('p2', { title: 'Garden' }),
      ];
      tasks = [task('t1', { title: 'Collect receipts' }), task('t5', { projectId: 'p2' })];
      await render(ProjectDetailPage);
      await click(button('Choose next step'));
      expect(element.querySelector('[role=dialog]')).not.toBeNull();

      params.next(convertToParamMap({ id: 'p2' }));
      await refresh();

      expect(callsOf(listTasks).map((c) => c.params['projectId'])).toEqual(['p1', 'p2']);
      expect(element.querySelector('h1')?.textContent).toBe('Garden');
      expect(element.querySelector('[role=dialog]')).toBeNull();
      expect(titles()).toEqual(['Task t5']);

      params.next(convertToParamMap({ id: 'p2' }));
      await refresh();
      expect(callsOf(listTasks)).toHaveLength(2);
    });
  });
});
