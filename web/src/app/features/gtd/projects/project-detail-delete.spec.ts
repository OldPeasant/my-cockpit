import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { BehaviorSubject, of, Subject } from 'rxjs';
import {
  Api,
  ChangeEvent,
  deleteProject,
  listAreas,
  listContexts,
  listProjects,
  listTasks,
  ProjectResponse,
  restoreTrashItem,
  TaskResponse,
} from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { DeleteProjectDialog } from './delete-project-dialog';
import { ProjectDetailPage } from './project-detail-page';

/** Project detail: deleting the project (menu, confirmation, undo) and the list labels of its tasks. */

function project(fields: Partial<ProjectResponse> = {}): ProjectResponse {
  return {
    id: 'p1',
    title: 'Tax return',
    status: 'ACTIVE',
    stalled: false,
    nextTaskId: 't1',
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-01T08:00:00Z',
    version: 3,
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

describe('ProjectDetailPage: delete and list labels', () => {
  let calls: { fn: unknown; params: Record<string, unknown> }[];
  let projects: ProjectResponse[];
  let tasks: TaskResponse[];
  let deleteFails: boolean;
  let deleteGate: Promise<void> | undefined;
  let restoreFails: boolean;
  let dialogOpens: number;
  let confirmed: boolean | undefined;
  let dialogData: unknown;
  let snackAction: Subject<void>;
  let snackMessages: string[];
  let fixture: ComponentFixture<ProjectDetailPage>;
  let element: HTMLElement;

  const callsOf = (fn: unknown) => calls.filter((call) => call.fn === fn);

  async function refresh(): Promise<void> {
    for (let i = 0; i < 3; i++) {
      await settle();
      TestBed.tick();
    }
  }

  async function render(): Promise<void> {
    fixture = TestBed.createComponent(ProjectDetailPage);
    element = fixture.nativeElement;
    document.body.appendChild(element);
    await settle();
    fixture.detectChanges();
    await refresh();
  }

  async function chooseDelete(): Promise<void> {
    const trigger = element.querySelector<HTMLButtonElement>(
      'button[aria-label="More project actions"]',
    );
    expect(trigger).toBeTruthy();
    trigger!.click();
    await refresh();
    const item = [...document.querySelectorAll<HTMLButtonElement>('[mat-menu-item]')].find(
      (b) => b.textContent?.trim() === 'Delete project',
    );
    expect(item).toBeDefined();
    item!.click();
    await refresh();
  }

  function rowLabels(): Record<string, string | null> {
    const rows = [...element.querySelectorAll('li.task')];
    return Object.fromEntries(
      rows.map((row) => [
        row.querySelector('app-task-row .title')?.textContent?.trim() ?? '',
        row.querySelector('.list-label')?.textContent?.trim() ?? null,
      ]),
    );
  }

  beforeEach(() => {
    calls = [];
    projects = [project()];
    tasks = [task('t1', { title: 'Collect receipts' }), task('t2', { title: 'Fill form' })];
    deleteFails = false;
    deleteGate = undefined;
    restoreFails = false;
    dialogOpens = 0;
    confirmed = true;
    dialogData = undefined;
    snackAction = new Subject<void>();
    snackMessages = [];
    const api = {
      invoke: async (fn: unknown, p: Record<string, unknown> = {}) => {
        calls.push({ fn, params: p });
        if (fn === listProjects) return { items: projects };
        if (fn === listAreas) return { items: [] };
        if (fn === listContexts) return { items: [] };
        if (fn === listTasks) return { items: tasks.filter((t) => t.projectId === p['projectId']) };
        if (fn === deleteProject) {
          if (deleteGate) await deleteGate;
          if (deleteFails) throw new Error('412');
          projects = projects.filter((x) => x.id !== p['id']);
          return undefined;
        }
        if (fn === restoreTrashItem) {
          if (restoreFails) throw new Error('404');
          projects = [project({ version: 4 })];
          return undefined;
        }
        throw new Error('unexpected call');
      },
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'projects', children: [] },
          { path: 'projects/:id', children: [] },
          { path: 'tasks/:id', children: [] },
        ]),
        { provide: Api, useValue: api },
        {
          provide: ActivatedRoute,
          useValue: { paramMap: new BehaviorSubject(convertToParamMap({ id: 'p1' })) },
        },
        {
          provide: ChangeEventsService,
          useValue: { resync$: new Subject<void>(), events$: () => new Subject<ChangeEvent>() },
        },
        {
          provide: MatDialog,
          useValue: {
            open: (_component: unknown, config: { data: unknown }) => {
              dialogData = config.data;
              dialogOpens++;
              return { afterClosed: () => of(confirmed) };
            },
          },
        },
        {
          provide: MatSnackBar,
          useValue: {
            open: (message: string) => {
              snackMessages.push(message);
              return { onAction: () => snackAction };
            },
          },
        },
      ],
    });
  });

  afterEach(() => {
    element?.remove();
    document.querySelectorAll('.cdk-overlay-container').forEach((e) => e.remove());
  });

  describe('list labels', () => {
    it('labels tasks outside Next with their list and leaves Next tasks unlabelled', async () => {
      tasks = [
        task('t1', { title: 'Collect receipts' }),
        task('t2', { title: 'Answer from accountant', list: 'WAITING' }),
        task('t3', { title: 'Find old forms', list: 'SOMEDAY' }),
        task('t4', { title: 'File it', list: 'SCHEDULED', scheduledDate: '2027-03-01' }),
        task('t5', { title: 'Check deductions', list: 'INBOX' }),
      ];
      await render();

      expect(rowLabels()).toEqual({
        'Collect receipts': null,
        'Answer from accountant': 'Waiting',
        'Find old forms': 'Someday',
        'File it': 'Scheduled',
        'Check deductions': 'Inbox',
      });
    });

    it('keeps the next-step marker on a non-Next task that is the next step', async () => {
      projects = [project({ nextTaskId: 't2' })];
      tasks = [task('t1'), task('t2', { title: 'Wait for form', list: 'WAITING' })];
      await render();

      const row = [...element.querySelectorAll('li.task')][1];
      expect(row.querySelector('.list-label')?.textContent?.trim()).toBe('Waiting');
      expect(row.querySelector('.next-label')?.textContent?.trim()).toBe('Next step');
    });
  });

  describe('delete', () => {
    it('asks with the number of open tasks, deletes with If-Match and goes to the projects', async () => {
      await render();
      const router = TestBed.inject(Router);
      const navigate = vi.spyOn(router, 'navigate');

      await chooseDelete();

      expect(dialogData).toEqual({ title: 'Tax return', openTasks: 2 });
      expect(callsOf(deleteProject)).toHaveLength(1);
      expect(callsOf(deleteProject)[0].params['id']).toBe('p1');
      expect(callsOf(deleteProject)[0].params['If-Match']).toContain('3');
      expect(navigate).toHaveBeenCalledWith(['/projects']);
      expect(snackMessages).toEqual(['Moved “Tax return” to the Trash']);
    });

    it('does nothing when the confirmation is cancelled or dismissed', async () => {
      await render();
      confirmed = false;
      await chooseDelete();
      confirmed = undefined;
      await chooseDelete();

      expect(callsOf(deleteProject)).toHaveLength(0);
      expect(snackMessages).toEqual([]);
      expect(element.querySelector('h1')?.textContent).toContain('Tax return');
    });

    it('stays on the project when the server refuses the delete', async () => {
      deleteFails = true;
      await render();
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');

      await chooseDelete();

      expect(callsOf(deleteProject)).toHaveLength(1);
      expect(navigate).not.toHaveBeenCalled();
      expect(snackMessages).toEqual([]);
      expect(element.querySelector('h1')?.textContent).toContain('Tax return');
    });

    it('Undo restores the project from the Trash', async () => {
      await render();
      await chooseDelete();

      snackAction.next();
      await refresh();

      expect(callsOf(restoreTrashItem)[0].params).toEqual({ type: 'PROJECT', id: 'p1' });
      expect(callsOf(listProjects).length).toBeGreaterThan(1);
    });

    it('does not ask or delete twice while the delete runs', async () => {
      let release!: () => void;
      deleteGate = new Promise<void>((resolve) => (release = resolve));
      await render();

      await chooseDelete();
      fixture.detectChanges();
      expect(callsOf(deleteProject)).toHaveLength(1);
      // The store removes the project optimistically, so its menu is gone while the request runs.
      expect(element.querySelector('button[aria-label="More project actions"]')).toBeNull();
      expect(snackMessages).toEqual([]);

      // A second request while the first is pending (e.g. a keyboard shortcut or a stale click).
      await (fixture.componentInstance as unknown as { remove(): Promise<void> }).remove();
      expect(dialogOpens).toBe(1);
      expect(callsOf(deleteProject)).toHaveLength(1);

      release();
      await refresh();
      fixture.detectChanges();
      expect(callsOf(deleteProject)).toHaveLength(1);
      expect(snackMessages).toEqual(['Moved “Tax return” to the Trash']);
    });

    it('enables the menu again after a refused delete so it can be retried', async () => {
      deleteFails = true;
      await render();
      await chooseDelete();
      fixture.detectChanges();

      const trigger = element.querySelector<HTMLButtonElement>(
        'button[aria-label="More project actions"]',
      )!;
      expect(trigger.disabled).toBe(false);

      deleteFails = false;
      await chooseDelete();
      expect(callsOf(deleteProject)).toHaveLength(2);
      expect(snackMessages).toEqual(['Moved “Tax return” to the Trash']);
    });

    it('passes zero open tasks to the confirmation of a project without tasks', async () => {
      tasks = [];
      projects = [project({ nextTaskId: undefined })];
      await render();
      await chooseDelete();

      expect(dialogData).toEqual({ title: 'Tax return', openTasks: 0 });
    });

    it('a failing Undo is swallowed (the HTTP layer reports it) and does not refresh', async () => {
      restoreFails = true;
      await render();
      await chooseDelete();
      const listsBefore = callsOf(listProjects).length;

      snackAction.next();
      await refresh();

      expect(callsOf(restoreTrashItem)).toHaveLength(1);
      expect(callsOf(listProjects)).toHaveLength(listsBefore);
    });
  });
});

describe('DeleteProjectDialog', () => {
  function render(openTasks: number): HTMLElement {
    TestBed.configureTestingModule({
      providers: [{ provide: MAT_DIALOG_DATA, useValue: { title: 'Tax return', openTasks } }],
    });
    const fixture = TestBed.createComponent(DeleteProjectDialog);
    fixture.detectChanges();
    return fixture.nativeElement;
  }

  const paragraphs = (element: HTMLElement) =>
    [...element.querySelectorAll('p')].map((p) => p.textContent?.trim());

  it('names the project and says it goes to the Trash; no task text without open tasks', () => {
    const element = render(0);

    expect(element.querySelector('h2')?.textContent?.trim()).toBe('Delete “Tax return”?');
    expect(paragraphs(element)).toEqual([
      'The project moves to the Trash, where you can restore it.',
    ]);
    expect([...element.querySelectorAll('button')].map((b) => b.textContent?.trim())).toEqual([
      'Cancel',
      'Delete project',
    ]);
  });

  it('says that one open task keeps the project', () => {
    expect(paragraphs(render(1))[1]).toBe('Its open task stays open and keeps the project.');
  });

  it('says that several open tasks keep the project', () => {
    expect(paragraphs(render(4))[1]).toBe('Its 4 open tasks stay open and keep the project.');
  });

  it('closes with false on Cancel and true on Delete project', () => {
    const close = vi.fn();
    TestBed.configureTestingModule({
      providers: [{ provide: MatDialogRef, useValue: { close } }],
    });
    const element = render(2);
    const [cancel, confirm] = [...element.querySelectorAll('button')];

    cancel.click();
    confirm.click();

    expect(close.mock.calls.map((call) => call[0])).toEqual([false, true]);
  });
});
