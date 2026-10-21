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
  ElementRef,
  inject,
  Injector,
  signal,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { map } from 'rxjs';
import { ProjectResponse, TaskResponse } from '../../../api/generated';
import { ContextStore, localDate, ProjectStore } from '../data';
import { TaskRow } from '../ui';
import { AreaStore } from './area-store';
import { renderMarkdown } from './markdown';
import { MAX_PROJECT_TITLE, ProjectForm } from './project-form';
import { statusLabel } from './project-groups';
import { ProjectTaskStore } from './project-task-store';

/**
 * `/projects/:id` (SPEC.md §5.3): a project's open tasks in their order (drag or arrow keys on the
 * handle reorder them), its next step marked, its Markdown description with a preview, and adding
 * tasks. Completing the next step of an active project asks for the new one ("Choose next step");
 * skipping leaves the project stalled.
 */
@Component({
  selector: 'app-project-detail-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CdkDrag, CdkDragHandle, CdkDropList, DatePipe, ProjectForm, RouterLink, TaskRow],
  providers: [ProjectTaskStore],
  templateUrl: './project-detail-page.html',
  styleUrls: [
    '../task-detail/controls.scss',
    './project-detail-page.scss',
    './project-description.scss',
  ],
})
export class ProjectDetailPage {
  protected readonly projects = inject(ProjectStore);
  protected readonly tasks = inject(ProjectTaskStore);
  protected readonly contexts = inject(ContextStore);
  protected readonly areas = inject(AreaStore);
  private readonly router = inject(Router);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  protected readonly today = localDate();
  protected readonly maxTitle = MAX_PROJECT_TITLE;
  protected readonly statusLabel = statusLabel;

  protected readonly id = toSignal(
    inject(ActivatedRoute).paramMap.pipe(map((params) => params.get('id') ?? '')),
    { initialValue: '' },
  );
  protected readonly project = computed(() => this.projects.find(this.id()));
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

  /** The "Choose next step" prompt: open, and the title of the step just completed (if any). */
  protected readonly prompt = signal<{ readonly done?: string } | null>(null);
  protected readonly newTask = signal('');
  protected readonly nextTask = signal('');
  /** Ids of the tasks whose action is running. */
  protected readonly busy = signal<ReadonlySet<string>>(new Set());
  /** Announced to screen readers after a change (reorder, next step). */
  protected readonly announcement = signal('');

  private loadedFor?: string;

  constructor() {
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

  /** The row checkbox: completes the task; when it was the next step, asks for the new one. */
  protected async complete(task: TaskResponse): Promise<void> {
    await this.run(task.id, async () => {
      const done = await this.tasks.complete(task);
      if (done.needsNextStep) {
        this.nextTask.set('');
        this.openPrompt({ done: task.title });
        await this.projects.refresh();
      }
    });
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
  protected openPrompt(prompt: { readonly done?: string }): void {
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
