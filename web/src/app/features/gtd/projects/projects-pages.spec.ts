import { Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { BehaviorSubject, Subject } from 'rxjs';
import {
  Api,
  AreaResponse,
  ChangeEvent,
  completeTask,
  createProject,
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

interface Call {
  fn: unknown;
  params: Record<string, unknown>;
}

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

const settle = () => new Promise((resolve) => setTimeout(resolve));

describe('Projects views', () => {
  let calls: Call[];
  let projects: ProjectResponse[];
  let areas: AreaResponse[];
  let tasks: TaskResponse[];
  let element: HTMLElement;
  let params: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
  /** What `/complete` answers for `needsNextStep`. */
  let needsNextStep: boolean;

  const callsOf = (fn: unknown) => calls.filter((call) => call.fn === fn);
  const text = (selector: string) =>
    [...element.querySelectorAll(selector)].map((e) => e.textContent?.replace(/\s+/g, ' ').trim());
  const button = (label: string) =>
    [...element.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.getAttribute('aria-label') === label || b.textContent?.trim() === label,
    );

  async function refresh(): Promise<void> {
    for (let i = 0; i < 3; i++) {
      await settle();
      TestBed.tick();
    }
  }

  async function render(component: Type<unknown>): Promise<void> {
    const fixture = TestBed.createComponent(component);
    element = fixture.nativeElement;
    document.body.appendChild(element);
    await settle();
    fixture.detectChanges();
    await fixture.whenStable();
    await refresh();
  }

  async function click(target: HTMLElement | undefined): Promise<void> {
    expect(target).toBeDefined();
    target!.click();
    await refresh();
  }

  async function type(selector: string, value: string): Promise<void> {
    const input = element.querySelector<HTMLInputElement | HTMLSelectElement>(selector)!;
    expect(input).toBeTruthy();
    input.value = value;
    input.dispatchEvent(new Event(input.tagName === 'SELECT' ? 'change' : 'input'));
    await refresh();
  }

  async function submit(selector: string): Promise<void> {
    element.querySelector<HTMLFormElement>(selector)!.dispatchEvent(new Event('submit'));
    await refresh();
  }

  beforeEach(() => {
    calls = [];
    needsNextStep = false;
    areas = [
      { id: 'work', title: 'Work', position: 1, version: 1 },
      { id: 'home', title: 'Home', position: 0, version: 1 },
    ];
    projects = [];
    tasks = [];
    params = new BehaviorSubject(convertToParamMap({ id: 'p1' }));
    const api = {
      invoke: async (fn: unknown, p: Record<string, unknown> = {}) => {
        calls.push({ fn, params: p });
        const body = p['body'] as Record<string, unknown>;
        if (fn === listProjects) return { items: projects };
        if (fn === listAreas) return { items: areas };
        if (fn === listContexts) return { items: [] };
        if (fn === listTasks) return { items: tasks };
        if (fn === createProject) return project('new', body);
        if (fn === updateProject) {
          const current = projects.find((x) => x.id === p['id'])!;
          return { ...current, ...body, version: current.version + 1 };
        }
        if (fn === setProjectNextStep) {
          const current = projects.find((x) => x.id === p['id'])!;
          const next = {
            ...current,
            nextTaskId: body['taskId'] as string,
            stalled: false,
            version: 2,
          };
          projects = projects.map((x) => (x.id === next.id ? next : x));
          return next;
        }
        if (fn === createTask) {
          const created = task('t9', { ...body, position: 9 });
          tasks = [...tasks, created];
          return created;
        }
        if (fn === completeTask) {
          const current = tasks.find((t) => t.id === p['id'])!;
          tasks = tasks.filter((t) => t.id !== current.id);
          if (needsNextStep) projects = projects.map((x) => ({ ...x, stalled: true }));
          return { ...current, completedAt: '2026-10-20T10:00:00Z', version: 2, needsNextStep };
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
        provideRouter([{ path: 'projects/:id', children: [] }]),
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
    it('groups projects by area in area order and highlights stalled ones', async () => {
      projects = [
        project('p1', { title: 'Tax return', areaId: 'home', stalled: true }),
        project('p2', { title: 'Launch site', areaId: 'work' }),
        project('p3', { title: 'Learn piano' }),
        project('p4', { title: 'Old plan', areaId: 'work', status: 'DROPPED' }),
      ];
      await render(ProjectsPage);

      expect(text('.area-head')).toEqual(['Home', 'Work', 'No area']);
      expect(text('.project .name')).toEqual(['Tax return', 'Launch site', 'Learn piano']);
      const stalled = element.querySelector('.project.stalled');
      expect(stalled?.querySelector('.name')?.textContent).toBe('Tax return');
      expect(stalled?.querySelector('.stalled-badge')?.textContent?.trim()).toBe('Stalled');
      expect(element.querySelectorAll('.stalled-badge')).toHaveLength(1);
      expect(text('[role=status]')).toEqual(['1 project needs a next step.']);
    });

    it('shows completed and dropped projects on request', async () => {
      projects = [
        project('p1', { title: 'Live' }),
        project('p2', { title: 'Done', status: 'COMPLETED' }),
      ];
      await render(ProjectsPage);
      expect(text('.project .name')).toEqual(['Live']);

      const toggle = element.querySelector<HTMLInputElement>('.toggle input')!;
      toggle.click();
      await refresh();

      expect(text('.project .name')).toEqual(['Live', 'Done']);
    });

    it('creates a project with status, area and dates', async () => {
      await render(ProjectsPage);
      expect(text('.empty')[0]).toContain('No projects yet');

      await click(button('New project'));
      await submit('app-project-form form');
      expect(callsOf(createProject)).toHaveLength(0);
      expect(text('.field-error')).toEqual(['Give the project a title.']);

      await type('app-project-form input[type=text]', '  Garden shed  ');
      await type('app-project-form select[id$=status]', 'SOMEDAY');
      await type('app-project-form select[id$=area]', 'home');
      await type('app-project-form input[id$=due]', '2026-12-01');
      await submit('app-project-form form');

      expect(callsOf(createProject)[0].params['body']).toEqual({
        title: 'Garden shed',
        status: 'SOMEDAY',
        areaId: 'home',
        dueDate: '2026-12-01',
        reviewDate: undefined,
      });
      expect(TestBed.inject(Router).url).toBe('/projects/new');
    });

    it('edits a project, sending only the changed fields', async () => {
      projects = [project('p1', { title: 'Tax return', areaId: 'home', dueDate: '2026-11-01' })];
      await render(ProjectsPage);

      await click(button('Edit “Tax return”'));
      await type('app-project-form select[id$=status]', 'ON_HOLD');
      await type('app-project-form select[id$=area]', '');
      await type('app-project-form input[id$=due]', '');
      await submit('app-project-form form');

      expect(callsOf(updateProject)[0].params).toMatchObject({
        id: 'p1',
        'If-Match': '"1"',
        body: { status: 'ON_HOLD', areaId: null, dueDate: null },
      });
      expect(element.querySelector('app-project-form')).toBeNull();
      expect(text('.project .badge')).toEqual(['On hold']);
    });
  });

  describe('detail', () => {
    beforeEach(() => {
      projects = [
        project('p1', { title: 'Tax return', nextTaskId: 't1', descriptionMd: '**File** by May' }),
      ];
      tasks = [task('t1', { title: 'Collect receipts' }), task('t2', { title: 'Fill form' })];
    });

    it('lists the open tasks in order with the next step marked', async () => {
      await render(ProjectDetailPage);

      expect(callsOf(listTasks)[0].params).toMatchObject({ projectId: 'p1' });
      expect(element.querySelector('h1')?.textContent).toBe('Tax return');
      expect(text('.task app-task-row .title')).toEqual(['Collect receipts', 'Fill form']);
      expect(text('.task.next .next-label')).toEqual(['Next step']);
      expect(element.querySelector('.task.next .title')?.textContent?.trim()).toBe(
        'Collect receipts',
      );
      expect(element.querySelector('.markdown')?.innerHTML).toBe(
        '<p><strong>File</strong> by May</p>',
      );
    });

    it('makes another task the next step', async () => {
      await render(ProjectDetailPage);

      await click(button('Make “Fill form” the next step'));

      expect(callsOf(setProjectNextStep)[0].params).toMatchObject({
        id: 'p1',
        body: { taskId: 't2' },
      });
      expect(element.querySelector('.task.next .title')?.textContent?.trim()).toBe('Fill form');
    });

    it('completing the next step opens "Choose next step"; picking a task sets it', async () => {
      needsNextStep = true;
      await render(ProjectDetailPage);

      await click(element.querySelector<HTMLElement>('.task.next input[type=checkbox]')!);

      const prompt = element.querySelector('[role=dialog]');
      expect(prompt?.querySelector('h2')?.textContent).toBe('Choose next step');
      expect(prompt?.textContent).toContain('“Collect receipts” is done.');
      await click(prompt!.querySelector<HTMLElement>('.choice')!);

      expect(callsOf(setProjectNextStep)[0].params['body']).toEqual({ taskId: 't2' });
      expect(element.querySelector('[role=dialog]')).toBeNull();
    });

    it('the prompt adds a new task as next step', async () => {
      needsNextStep = true;
      await render(ProjectDetailPage);
      await click(element.querySelector<HTMLElement>('.task.next input[type=checkbox]')!);

      await type('#prompt-new', 'Book advisor');
      await submit('[role=dialog] form');

      expect(callsOf(createTask)[0].params['body']).toEqual({
        title: 'Book advisor',
        list: 'NEXT',
        projectId: 'p1',
      });
      expect(callsOf(setProjectNextStep)[0].params['body']).toEqual({ taskId: 't9' });
      expect(element.querySelector('[role=dialog]')).toBeNull();
    });

    it('skipping leaves the project stalled', async () => {
      needsNextStep = true;
      await render(ProjectDetailPage);
      await click(element.querySelector<HTMLElement>('.task.next input[type=checkbox]')!);

      await click(button('Skip for now'));

      expect(element.querySelector('[role=dialog]')).toBeNull();
      expect(callsOf(setProjectNextStep)).toHaveLength(0);
      expect(element.querySelector('.stalled-badge')?.textContent?.trim()).toBe('Stalled');
      expect(element.querySelector('.stalled-note')?.textContent).toContain(
        'This project has no next step.',
      );
      await click(button('Choose next step'));
      expect(element.querySelector('[role=dialog]')).not.toBeNull();
    });

    it('completing another task does not prompt', async () => {
      await render(ProjectDetailPage);

      await click(element.querySelectorAll<HTMLElement>('.task input[type=checkbox]')[1]);

      expect(callsOf(completeTask)[0].params).toMatchObject({ id: 't2' });
      expect(element.querySelector('[role=dialog]')).toBeNull();
      expect(text('.task app-task-row .title')).toEqual(['Collect receipts']);
    });

    it('arrow keys on the handle reorder the tasks', async () => {
      await render(ProjectDetailPage);

      const handle = element.querySelectorAll<HTMLElement>('.handle')[1];
      handle.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp' }));
      await refresh();

      expect(callsOf(orderProjectTasks)[0].params).toEqual({
        id: 'p1',
        body: { taskIds: ['t2', 't1'] },
      });
      expect(text('.task app-task-row .title')).toEqual(['Fill form', 'Collect receipts']);
    });

    it('adds a task; a stalled project gets it as next step', async () => {
      projects = [project('p1', { title: 'Tax return', stalled: true })];
      tasks = [];
      await render(ProjectDetailPage);
      expect(text('.tasks .empty')).toEqual(['No open tasks. Add the first step below.']);

      await type('#add-task', 'Call accountant');
      await submit('form.add');

      expect(callsOf(createTask)[0].params['body']).toEqual({
        title: 'Call accountant',
        list: 'NEXT',
        projectId: 'p1',
      });
      expect(callsOf(setProjectNextStep)[0].params['body']).toEqual({ taskId: 't9' });
      expect(text('.task app-task-row .title')).toEqual(['Call accountant']);
    });

    it('edits the description with a live Markdown preview', async () => {
      await render(ProjectDetailPage);

      await click(button('Edit description'));
      await type('#description-md', '# Plan\n- one');
      expect(element.querySelector('.preview')?.innerHTML).toBe(
        '<h3>Plan</h3><ul><li>one</li></ul>',
      );
      await click(button('Save description'));

      expect(callsOf(updateProject)[0].params['body']).toEqual({ descriptionMd: '# Plan\n- one' });
      expect(element.querySelector('#description-md')).toBeNull();
    });

    it('says when the project does not exist', async () => {
      params.next(convertToParamMap({ id: 'missing' }));
      await render(ProjectDetailPage);

      expect(element.querySelector('[role=alert]')?.textContent).toContain(
        'This project does not exist',
      );
    });
  });
});
