import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import {
  Api,
  AreaResponse,
  ChangeEvent,
  createProject,
  ProjectResponse,
  updateProject,
} from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { MAX_PROJECT_TITLE, ProjectForm } from './project-form';

interface Call {
  fn: unknown;
  params: Record<string, unknown>;
  resolve: (value: unknown) => void;
  reject: (error: unknown) => void;
}

function project(fields: Partial<ProjectResponse> = {}): ProjectResponse {
  return {
    id: 'p1',
    title: 'Garden shed',
    status: 'ACTIVE',
    stalled: false,
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-01T08:00:00Z',
    version: 3,
    ...fields,
  };
}

const areas: AreaResponse[] = [
  { id: 'home', title: 'Home', position: 0, version: 1 },
  { id: 'work', title: 'Work', position: 1, version: 1 },
];

const settle = () => new Promise((resolve) => setTimeout(resolve));

describe('ProjectForm', () => {
  let calls: Call[];
  let fixture: ComponentFixture<ProjectForm>;
  let element: HTMLElement;
  let saved: ProjectResponse[];
  let cancelled: number;

  const callsOf = (fn: unknown) => calls.filter((call) => call.fn === fn);
  const field = <T extends HTMLElement>(suffix: string) =>
    element.querySelector<T>(`[id$=-${suffix}]`)!;
  const submitButton = () => element.querySelector<HTMLButtonElement>('button[type=submit]')!;

  async function refresh(): Promise<void> {
    await settle();
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function type(suffix: string, value: string): Promise<void> {
    const input = field<HTMLInputElement | HTMLSelectElement>(suffix);
    input.value = value;
    input.dispatchEvent(new Event(input.tagName === 'SELECT' ? 'change' : 'input'));
    await refresh();
  }

  async function submit(): Promise<void> {
    element.querySelector('form')!.dispatchEvent(new Event('submit'));
    await refresh();
  }

  async function render(inputs: Record<string, unknown> = {}): Promise<void> {
    fixture = TestBed.createComponent(ProjectForm);
    element = fixture.nativeElement;
    fixture.componentRef.setInput('areas', areas);
    for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
    fixture.componentInstance.saved.subscribe((p) => saved.push(p));
    fixture.componentInstance.cancelled.subscribe(() => cancelled++);
    await refresh();
  }

  beforeEach(() => {
    calls = [];
    saved = [];
    cancelled = 0;
    const api = {
      invoke: (fn: unknown, params: Record<string, unknown> = {}) =>
        new Promise((resolve, reject) => calls.push({ fn, params, resolve, reject })),
    };
    TestBed.configureTestingModule({
      providers: [
        { provide: Api, useValue: api },
        {
          provide: ChangeEventsService,
          useValue: { resync$: new Subject<void>(), events$: () => new Subject<ChangeEvent>() },
        },
      ],
    });
  });

  it('starts a new project as active, in the given area, limited to the server title length', async () => {
    await render({ areaId: 'work' });

    expect(field<HTMLInputElement>('title').value).toBe('');
    expect(field<HTMLInputElement>('title').getAttribute('maxlength')).toBe(
      String(MAX_PROJECT_TITLE),
    );
    expect(field<HTMLSelectElement>('status').value).toBe('ACTIVE');
    expect(field<HTMLSelectElement>('area').value).toBe('work');
    expect(submitButton().textContent?.trim()).toBe('Create project');
  });

  it('refuses a whitespace-only title and clears the error once a title is typed', async () => {
    await render();

    await type('title', '   ');
    await submit();

    expect(callsOf(createProject)).toHaveLength(0);
    expect(element.querySelector('.field-error')?.textContent?.trim()).toBe(
      'Give the project a title.',
    );
    const title = field<HTMLInputElement>('title');
    expect(title.getAttribute('aria-invalid')).toBe('true');
    expect(title.getAttribute('aria-describedby')).toBe(element.querySelector('.field-error')!.id);

    await type('title', 'Shed');
    expect(element.querySelector('.field-error')).toBeNull();
    expect(title.getAttribute('aria-describedby')).toBeNull();
  });

  it('creates a project without optional fields as undefined, not empty strings', async () => {
    await render();
    await type('title', 'Shed');
    await type('review', '2026-11-15');
    await submit();

    expect(callsOf(createProject)[0].params['body']).toEqual({
      title: 'Shed',
      status: 'ACTIVE',
      areaId: undefined,
      dueDate: undefined,
      reviewDate: '2026-11-15',
    });
    callsOf(createProject)[0].resolve(project({ title: 'Shed' }));
    await refresh();
    expect(saved.map((p) => p.title)).toEqual(['Shed']);
  });

  it('sends one request while saving and re-enables the button after a failure', async () => {
    await render();
    await type('title', 'Shed');

    await submit();
    expect(submitButton().disabled).toBe(true);
    await submit();
    expect(callsOf(createProject)).toHaveLength(1);

    callsOf(createProject)[0].reject(new Error('500'));
    await refresh();

    expect(saved).toEqual([]);
    expect(submitButton().disabled).toBe(false);
    expect(field<HTMLInputElement>('title').value).toBe('Shed');
  });

  it('prefills an edited project and sends only the changed, trimmed title', async () => {
    await render({
      project: project({ areaId: 'home', dueDate: '2026-12-01', reviewDate: '2026-11-01' }),
    });
    expect(field<HTMLInputElement>('title').value).toBe('Garden shed');
    expect(field<HTMLSelectElement>('area').value).toBe('home');
    expect(field<HTMLInputElement>('due').value).toBe('2026-12-01');
    expect(field<HTMLInputElement>('review').value).toBe('2026-11-01');
    expect(submitButton().textContent?.trim()).toBe('Save project');

    await type('title', '  Garden shed v2 ');
    await submit();

    expect(callsOf(updateProject)[0].params).toEqual({
      id: 'p1',
      'If-Match': '"3"',
      body: { title: 'Garden shed v2' },
    });
  });

  it('clears the review date with null and emits the saved project', async () => {
    await render({ project: project({ reviewDate: '2026-11-01' }) });

    await type('review', '');
    await submit();

    expect(callsOf(updateProject)[0].params['body']).toEqual({ reviewDate: null });
    const answer = project({ version: 4 });
    callsOf(updateProject)[0].resolve(answer);
    await refresh();
    expect(saved).toEqual([answer]);
  });

  it('sets a review date and area on a project that had none', async () => {
    await render({ project: project() });

    await type('area', 'work');
    await type('review', '2026-11-20');
    await type('status', 'COMPLETED');
    await submit();

    expect(callsOf(updateProject)[0].params['body']).toEqual({
      status: 'COMPLETED',
      areaId: 'work',
      reviewDate: '2026-11-20',
    });
  });

  it('cancels with the button and with Escape in the title', async () => {
    await render();

    element.querySelector<HTMLButtonElement>('button.quiet')!.click();
    field<HTMLInputElement>('title').dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await refresh();

    expect(cancelled).toBe(2);
    expect(calls).toEqual([]);
  });

  it('gives each form its own control ids', async () => {
    await render();
    const first = field<HTMLInputElement>('title').id;
    const label = element.querySelector('label')!.getAttribute('for');
    expect(label).toBe(first);

    await render();
    expect(field<HTMLInputElement>('title').id).not.toBe(first);
  });
});
