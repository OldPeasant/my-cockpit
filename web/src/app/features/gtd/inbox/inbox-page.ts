import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  linkedSignal,
  signal,
  viewChild,
} from '@angular/core';
import { Router } from '@angular/router';
import { MoveTaskRequest, TaskResponse } from '../../../api/generated';
import { isTypingTarget } from '../../../shell/hotkeys';
import { addDays, ContextStore, localDate, ProjectStore, TaskStore } from '../data';
import { ContextChips, CreativityPicker, EnergyPicker, EstimatePicker, TaskRow } from '../ui';
import {
  actionForKey,
  Clarification,
  clarificationOf,
  clarificationPatch,
  INBOX_ACTION_GROUPS,
  InboxAction,
  isPromptAction,
  PromptAction,
} from './inbox-actions';

/** Value of the project choice that creates a new project. */
const NEW_PROJECT = '';

/**
 * The Inbox (SPEC.md §5.3): the list of captured items and the processing mode, which shows one
 * item at a time with its attributes and the actions that move it out of the Inbox, all on the
 * keyboard (`P` starts processing, the action letters shown on the buttons, `Esc` goes back).
 */
@Component({
  selector: 'app-inbox-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TaskRow, EnergyPicker, CreativityPicker, EstimatePicker, ContextChips],
  providers: [TaskStore],
  templateUrl: './inbox-page.html',
  styleUrl: './inbox-page.scss',
  host: { '(document:keydown)': 'onKeydown($event)' },
})
export class InboxPage {
  protected readonly tasks = inject(TaskStore);
  protected readonly contexts = inject(ContextStore);
  protected readonly projects = inject(ProjectStore);
  private readonly router = inject(Router);

  protected readonly groups = INBOX_ACTION_GROUPS;
  protected readonly newProject = NEW_PROJECT;

  /** Whether the processing mode is shown (otherwise the list). */
  protected readonly processing = signal(false);
  /** Items whose action is running: they are no longer offered. */
  private readonly busy = signal<ReadonlySet<string>>(new Set());
  /** Items processed since the processing mode started, for the progress bar. */
  protected readonly processed = signal(0);

  /** The items still to process, in Inbox order. */
  protected readonly queue = computed(() =>
    this.tasks.items().filter((task) => !this.busy().has(task.id)),
  );
  /** The item being processed. */
  protected readonly current = computed<TaskResponse | undefined>(() => this.queue()[0]);
  /** Percentage of the items processed since the processing mode started. */
  protected readonly progress = computed(() => {
    const done = this.processed();
    const total = done + this.queue().length;
    return total === 0 ? 100 : Math.round((done / total) * 100);
  });

  /** Attributes set inline for the current item; reset when the next item comes up. */
  protected readonly clarification = linkedSignal<string | undefined, Clarification>({
    source: () => this.current()?.id,
    computation: () => clarificationOf(this.current()),
  });
  /** The action waiting for its value (a date, a person, a project), if any. */
  protected readonly prompt = linkedSignal<string | undefined, PromptAction | null>({
    source: () => this.current()?.id,
    computation: () => null,
  });
  protected readonly promptDate = signal('');
  protected readonly promptText = signal('');
  protected readonly promptProject = signal(NEW_PROJECT);

  protected readonly openProjects = computed(() =>
    this.projects.items().filter((project) => project.status === 'ACTIVE'),
  );
  protected readonly tomorrow = computed(() => addDays(localDate(), 1));
  protected readonly today = computed(() => localDate());
  /** Whether the prompt has what its action needs. */
  protected readonly promptValid = computed(() => {
    switch (this.prompt()) {
      case 'schedule':
        return this.promptDate() !== '';
      case 'tickler':
        return this.promptDate() > this.today();
      case 'project':
        return this.promptProject() !== NEW_PROJECT || this.promptText().trim() !== '';
      default:
        return true;
    }
  });

  private readonly promptField = viewChild<ElementRef<HTMLElement>>('promptField');

  constructor() {
    void this.tasks.load({ list: 'INBOX' });
    void this.contexts.ensureLoaded();
    void this.projects.ensureLoaded();
    effect(() => this.promptField()?.nativeElement.focus());
  }

  protected startProcessing(): void {
    this.processed.set(0);
    this.processing.set(true);
  }

  protected stopProcessing(): void {
    this.processing.set(false);
  }

  protected updateClarification(change: Partial<Clarification>): void {
    this.clarification.update((current) => ({ ...current, ...change }));
  }

  protected open(task: TaskResponse): void {
    void this.router.navigate(['/tasks', task.id]);
  }

  protected complete(task: TaskResponse): void {
    this.tasks.complete(task).catch(ignore);
  }

  /** Runs `action` on the current item, or asks for its value first. */
  protected choose(action: InboxAction): void {
    if (!this.current()) return;
    if (!isPromptAction(action)) {
      void this.run(action);
      return;
    }
    this.promptDate.set(action === 'schedule' || action === 'tickler' ? this.tomorrow() : '');
    this.promptText.set('');
    this.promptProject.set(this.openProjects()[0]?.id ?? NEW_PROJECT);
    this.prompt.set(action);
  }

  protected confirmPrompt(event: Event): void {
    event.preventDefault();
    const action = this.prompt();
    if (action && this.promptValid()) void this.run(action);
  }

  protected cancelPrompt(): void {
    this.prompt.set(null);
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (event.defaultPrevented || event.isComposing || event.repeat) return;
    if (event.ctrlKey || event.metaKey || event.altKey) return;
    const target = event.target;
    // Dialogs (capture, command palette) and form fields keep their keys.
    if (target instanceof Element && target.closest('.cdk-overlay-container')) return;
    if (event.key === 'Escape') {
      if (!this.processing()) return;
      event.preventDefault();
      if (this.prompt()) this.cancelPrompt();
      else this.stopProcessing();
      return;
    }
    if (isTypingTarget(target)) return;
    if (!this.processing()) {
      if (event.key.toLowerCase() === 'p' && this.tasks.items().length > 0) {
        event.preventDefault();
        this.startProcessing();
      }
      return;
    }
    if (this.prompt()) return;
    const action = actionForKey(event.key);
    if (action && this.current()) {
      event.preventDefault();
      this.choose(action);
    }
  }

  /**
   * Saves the inline attributes (if changed), then runs the action. The item leaves the queue at
   * once; if the server refuses, it comes back (the error is shown by the HTTP layer).
   */
  private async run(action: InboxAction): Promise<void> {
    const task = this.current();
    if (!task) return;
    const clarification = this.clarification();
    const value = {
      date: this.promptDate(),
      text: this.promptText().trim(),
      project: this.promptProject(),
    };
    this.prompt.set(null);
    this.busy.update((ids) => new Set(ids).add(task.id));
    try {
      if (action === 'trash') {
        await this.tasks.remove(task);
      } else {
        const patch = clarificationPatch(task, clarification);
        const base = Object.keys(patch).length ? await this.tasks.update(task, patch) : task;
        await this.apply(action, base, value);
      }
      this.processed.update((count) => count + 1);
    } catch {
      // Rolled back by the store; the HTTP layer shows the error.
    } finally {
      this.busy.update((ids) => {
        const rest = new Set(ids);
        rest.delete(task.id);
        return rest;
      });
    }
  }

  private async apply(
    action: InboxAction,
    task: TaskResponse,
    value: { date: string; text: string; project: string },
  ): Promise<void> {
    const move = (body: MoveTaskRequest) => this.tasks.move(task, body);
    switch (action) {
      case 'done':
        await this.tasks.complete(task);
        return;
      case 'next':
        await move({ list: 'NEXT' });
        return;
      case 'someday':
        await move({ list: 'SOMEDAY' });
        return;
      case 'waiting':
        await move({ list: 'WAITING', waitingForText: value.text || undefined });
        return;
      case 'schedule':
        await move({ list: 'SCHEDULED', scheduledDate: value.date });
        return;
      case 'tickler':
        await move({ list: 'INBOX', ticklerDate: value.date });
        return;
      case 'project': {
        const projectId =
          value.project === NEW_PROJECT
            ? (await this.projects.create({ title: value.text })).id
            : value.project;
        await move({ list: 'NEXT', projectId });
        return;
      }
      default:
        return;
    }
  }
}

function ignore(): void {
  // The store rolled the change back; the HTTP layer shows the error.
}
