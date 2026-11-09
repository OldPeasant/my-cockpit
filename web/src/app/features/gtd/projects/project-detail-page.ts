import {
  CdkDrag,
  CdkDragDrop,
  CdkDragHandle,
  CdkDropList,
  moveItemInArray,
} from '@angular/cdk/drag-drop';
import { DatePipe } from '@angular/common';
import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  Injector,
  signal,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { MatDialog } from '@angular/material/dialog';
import { MatMenuModule } from '@angular/material/menu';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { firstValueFrom, map } from 'rxjs';
import { Api, ProjectResponse, restoreTrashItem, TaskResponse } from '../../../api/generated';
import { LinkedItems } from '../../../shared/links';
import { BackButton, BackNavigation } from '../../../shared/navigation';
import { ContextStore, localDate, ProjectStore } from '../data';
import { renderMarkdown, TaskRow, TaskUndo, UNDO_DURATION_MS } from '../ui';
import { AreaStore } from './area-store';
import { DeleteProjectData, DeleteProjectDialog } from './delete-project-dialog';
import { MAX_PROJECT_TITLE, ProjectForm } from './project-form';
import { statusLabel } from './project-groups';
import { ProjectTaskStore } from './project-task-store';

/**
 * `/projects/:id` (SPEC.md §5.3): a project's open tasks in their order (drag or arrow keys on the
 * handle reorder them), its next step marked, its Markdown description with a preview, and adding
 * tasks. Completing the next step of an active project asks for the new one ("Choose next step");
 * skipping leaves the project stalled. Tasks outside Next (Waiting, Someday, Scheduled, Inbox) show
 * their list. The menu moves the project to the Trash after a confirmation.
 */
@Component({
  selector: 'app-project-detail-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    BackButton,
    CdkDrag,
    CdkDragHandle,
    CdkDropList,
    DatePipe,
    LinkedItems,
    MatMenuModule,
    ProjectForm,
    RouterLink,
    TaskRow,
  ],
  providers: [ProjectTaskStore],
  templateUrl: './project-detail-page.html',
  styleUrls: [
    '../task-detail/controls.scss',
    './project-detail-page.scss',
    '../ui/markdown.scss',
    './project-description.scss',
    './project-list-label.scss',
  ],
})
export class ProjectDetailPage {
  protected readonly projects = inject(ProjectStore);
  protected readonly tasks = inject(ProjectTaskStore);
  protected readonly contexts = inject(ContextStore);
  protected readonly areas = inject(AreaStore);
  private readonly router = inject(Router);
  private readonly undo = inject(TaskUndo);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly api = inject(Api);

  /** The back button's destination when the project was not opened from a page of the app. */
  protected readonly projectsLabel = $localize`:@@nav.projects:Projects`;
  protected readonly today = localDate();
  protected readonly maxTitle = MAX_PROJECT_TITLE;
  protected readonly statusLabel = statusLabel;

  protected readonly id = toSignal(
    inject(ActivatedRoute).paramMap.pipe(map((params) => params.get('id') ?? '')),
    { initialValue: '' },
  );
  /**
   * The project being moved to the Trash while the request runs: the store removes it at once, so
   * the page keeps showing this copy instead of "Project not found".
   */
  private readonly deleting = signal<ProjectResponse | null>(null);
  protected readonly project = computed(() => {
    const id = this.id();
    const deleting = this.deleting();
    return this.projects.find(id) ?? (deleting?.id === id ? deleting : undefined);
  });
  protected readonly area = computed(() => {
    const areaId = this.project()?.areaId;
    return areaId ? this.areas.byId().get(areaId) : undefined;
  });
  /** The project's open tasks in their order. */
  protected readonly openTasks = this.tasks.ordered;

  /** Whether the edit form of the project's fields is open. */
  protected readonly editing = signal(false);
  /** The description being edited, `null` while it is shown. */
  protected readonly description = signal<string | null>(null);
  protected readonly descriptionHtml = computed(() =>
    renderMarkdown(this.project()?.descriptionMd ?? ''),
  );
  protected readonly previewHtml = computed(() => renderMarkdown(this.description() ?? ''));
  protected readonly savingDescription = signal(false);

  /** The "Choose next step" prompt: open, and the step just completed (if any). */
  protected readonly prompt = signal<NextStepPrompt | null>(null);
  protected readonly newTask = signal('');
  protected readonly nextTask = signal('');
  /** Ids of the tasks whose action is running. */
  protected readonly busy = signal<ReadonlySet<string>>(new Set());
  /** Announced to screen readers after a change (reorder, next step). */
  protected readonly announcement = signal('');

  private loadedFor?: string;

  constructor() {
    // The back buttons of the pages opened from here (tasks, notes, mail) name the project.
    const backNavigation = inject(BackNavigation);
    effect(() => {
      const project = this.project();
      if (project?.id && project.title) {
        const url = this.router.serializeUrl(this.router.createUrlTree(['/projects', project.id]));
        backNavigation.setLabel(url, project.title);
      }
    });
    void this.projects.ensureLoaded();
    void this.areas.ensureLoaded();
    void this.contexts.ensureLoaded();
    // The route reuses the component across project ids.
    inject(ActivatedRoute)
      .paramMap.pipe(takeUntilDestroyed())
      .subscribe((params) => {
        const id = params.get('id') ?? '';
        if (id === this.loadedFor) return;
        this.loadedFor = id;
        this.prompt.set(null);
        this.editing.set(false);
        this.description.set(null);
        void this.tasks.load({ projectId: id });
      });
  }

  protected isNext(task: TaskResponse): boolean {
    return this.project()?.nextTaskId === task.id;
  }

  protected open(task: TaskResponse): void {
    void this.router.navigate(['/tasks', task.id]);
  }

  /**
   * The row checkbox: completes the task (with an undo snackbar); when it was the next step, asks
   * for the new one. Undoing reopens the task and closes that prompt.
   */
  protected async complete(task: TaskResponse): Promise<void> {
    let done: TaskResponse | undefined;
    await this.run(task.id, async () => {
      const completed = await this.tasks.complete(task);
      done = completed;
      if (completed.needsNextStep) {
        this.nextTask.set('');
        this.openPrompt({ done: task.title, doneId: task.id });
        await this.projects.refresh();
      }
    });
    if (!done) return;
    const reopened = done;
    const undone = await this.undo.completed(task, () => this.tasks.uncomplete(reopened));
    if (undone && this.prompt()?.doneId === task.id) this.prompt.set(null);
  }

  /** Makes `task` the project's next step (from a row or the prompt). */
  protected async makeNext(task: TaskResponse): Promise<void> {
    const project = this.project();
    if (!project) return;
    const ok = await this.run(task.id, () => this.projects.setNextStep(project, task.id));
    if (ok) {
      this.prompt.set(null);
      this.announcement.set(this.nextStepAnnouncement(task.title));
    }
  }

  /** Opens the "Choose next step" prompt and moves the focus into it. */
  protected openPrompt(prompt: NextStepPrompt): void {
    this.prompt.set(prompt);
    afterNextRender(
      () =>
        this.host.nativeElement
          .querySelector<HTMLElement>('.prompt .choice:not(:disabled), #prompt-new')
          ?.focus(),
      { injector: this.injector },
    );
  }

  /**
   * The prompt's "add as next step": creates a task and makes it the next step. When only the
   * second step fails, the input is cleared anyway: the task exists and is offered as a choice, so
   * submitting again does not create it twice.
   */
  protected async addNext(): Promise<void> {
    const title = this.nextTask().trim();
    const project = this.project();
    if (!title || !project) return;
    const ok = await this.run('prompt', async () => {
      const task = await this.tasks.create({ title, list: 'NEXT', projectId: project.id });
      this.nextTask.set('');
      await this.projects.setNextStep(this.projects.find(project.id) ?? project, task.id);
    });
    if (ok) {
      this.prompt.set(null);
      this.announcement.set(this.nextStepAnnouncement(title));
    }
  }

  protected skip(): void {
    this.prompt.set(null);
  }

  /**
   * Adds a task at the end of the project. An active project without a next step gets it as its
   * next step: it is the only open step, or the user had not chosen one. Once the task is created
   * the input is cleared, even when making it the next step fails, so a retry adds no duplicate.
   */
  protected async addTask(): Promise<void> {
    const title = this.newTask().trim();
    const project = this.project();
    if (!title || !project) return;
    await this.run('add', async () => {
      const task = await this.tasks.create({ title, list: 'NEXT', projectId: project.id });
      this.newTask.set('');
      const current = this.projects.find(project.id) ?? project;
      if (current.stalled) await this.projects.setNextStep(current, task.id);
    });
  }

  protected drop(event: CdkDragDrop<readonly TaskResponse[]>): void {
    this.move(event.previousIndex, event.currentIndex);
  }

  /** Arrow keys on a drag handle move its task one place up or down. */
  protected key(event: KeyboardEvent, index: number): void {
    const to = event.key === 'ArrowUp' ? index - 1 : event.key === 'ArrowDown' ? index + 1 : -1;
    if (to < 0 || to >= this.openTasks().length) return;
    event.preventDefault();
    this.move(index, to);
    // Keep the focus on the moved task's handle: rows are tracked by task id, so it stays the same
    // element, but moving the rows in the DOM can take the focus away from it.
    const handle = event.currentTarget as HTMLElement;
    afterNextRender(() => handle.focus(), { injector: this.injector });
  }

  private move(from: number, to: number): void {
    const project = this.project();
    if (!project || from === to) return;
    const ids = this.openTasks().map((task) => task.id);
    moveItemInArray(ids, from, to);
    const title = this.openTasks()[from].title;
    this.tasks.reorder(project.id, ids).then(
      () => this.announcement.set(this.movedAnnouncement(title, to + 1, ids.length)),
      () => undefined,
    );
  }

  /**
   * The list of a task outside Next, shown on its row (SPEC.md §5.1: a project's tasks can be in
   * any list); `undefined` for Next.
   */
  protected listLabel(list: TaskResponse['list']): string | undefined {
    switch (list) {
      case 'WAITING':
        return $localize`:@@gtd.project.list.waiting:Waiting`;
      case 'SOMEDAY':
        return $localize`:@@gtd.project.list.someday:Someday`;
      case 'SCHEDULED':
        return $localize`:@@gtd.project.list.scheduled:Scheduled`;
      case 'INBOX':
        return $localize`:@@gtd.project.list.inbox:Inbox`;
      default:
        return undefined;
    }
  }

  /**
   * Asks, then moves the project to the Trash (its tasks keep the project, the server's rule) and
   * goes to the projects list with an "Undo" snackbar that restores it.
   */
  protected async remove(): Promise<void> {
    const shown = this.project();
    if (!shown || this.busy().has('delete')) return;
    const data: DeleteProjectData = { title: shown.title, openTasks: this.openTasks().length };
    const ref = this.dialog.open<DeleteProjectDialog, DeleteProjectData, boolean>(
      DeleteProjectDialog,
      { data },
    );
    if (!(await firstValueFrom(ref.afterClosed()))) return;
    // The project may have changed (or gone) while the dialog was open: delete its current version.
    const project = this.projects.find(shown.id);
    if (!project || this.busy().has('delete')) return;
    this.deleting.set(project);
    const deleted = await this.run('delete', () => this.projects.remove(project));
    if (!deleted) {
      this.deleting.set(null);
      afterNextRender(
        () => this.host.nativeElement.querySelector<HTMLElement>('.head button.more')?.focus(),
        { injector: this.injector },
      );
      return;
    }
    void this.router.navigate(['/projects']).finally(() => this.deleting.set(null));
    const undo = this.snackBar.open(
      $localize`:@@gtd.project.deleted:Moved “${project.title}:title:” to the Trash`,
      $localize`:@@gtd.undo.action:Undo`,
      { duration: UNDO_DURATION_MS },
    );
    undo.onAction().subscribe(() => void this.restore(project));
  }

  /** The snackbar's "Undo" after deleting: restores the project from the Trash. */
  private async restore(project: ProjectResponse): Promise<void> {
    try {
      await this.api.invoke(restoreTrashItem, { type: 'PROJECT', id: project.id });
      await this.projects.refresh();
    } catch {
      // The HTTP layer shows the error.
    }
  }

  protected startDescription(): void {
    this.description.set(this.project()?.descriptionMd ?? '');
  }

  protected async saveDescription(): Promise<void> {
    const project = this.project();
    const text = this.description();
    if (!project || text === null || this.savingDescription()) return;
    this.savingDescription.set(true);
    try {
      await this.projects.update(project, { descriptionMd: text.trim() ? text : null });
      this.description.set(null);
    } catch {
      // The store rolled the change back; the HTTP layer shows the error.
    } finally {
      this.savingDescription.set(false);
    }
  }

  protected edited(project: ProjectResponse): void {
    this.editing.set(false);
    this.announcement.set($localize`:@@gtd.project.saved:Saved “${project.title}:title:”.`);
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement | HTMLTextAreaElement).value;
  }

  protected makeNextLabel(title: string): string {
    return $localize`:@@gtd.project.makeNextLabel:Make “${title}:title:” the next step`;
  }

  protected moveLabel(title: string): string {
    return $localize`:@@gtd.project.moveLabel:Move “${title}:title:” (drag, or use the arrow keys)`;
  }

  private nextStepAnnouncement(title: string): string {
    return $localize`:@@gtd.project.nextStepSet:“${title}:title:” is the next step.`;
  }

  private movedAnnouncement(title: string, position: number, count: number): string {
    return $localize`:@@gtd.project.moved:Moved “${title}:title:” to position ${position}:position: of ${count}:count:.`;
  }

  /** Runs `action` unless one runs for `key`; resolves to whether it succeeded. */
  private async run(key: string, action: () => Promise<unknown>): Promise<boolean> {
    if (this.busy().has(key)) return false;
    this.busy.update((keys) => new Set(keys).add(key));
    try {
      await action();
      return true;
    } catch {
      // The store rolled the change back; the HTTP layer shows the error.
      return false;
    } finally {
      this.busy.update((keys) => {
        const rest = new Set(keys);
        rest.delete(key);
        return rest;
      });
    }
  }
}

/** The "Choose next step" prompt: the title (shown) and id of the step just completed, if any. */
interface NextStepPrompt {
  readonly done?: string;
  readonly doneId?: string;
}
