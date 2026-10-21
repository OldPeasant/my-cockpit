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
import { MatDialog } from '@angular/material/dialog';
import { Router, RouterLink } from '@angular/router';
import {
  ConvertToEventRequest,
  EventResponse,
  MoveTaskRequest,
  NoteResponse,
  TaskResponse,
} from '../../../api/generated';
import { isTypingTarget } from '../../../shell/hotkeys';
import { wallClockOf } from '../../calendar/edit/event-times';
import { addDays, ContextStore, localDate, ProjectStore, TaskStore } from '../data';
import { askForCalendar } from '../task-detail/to-calendar-dialog';
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
  imports: [RouterLink, TaskRow, EnergyPicker, CreativityPicker, EstimatePicker, ContextChips],
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
  private readonly dialog = inject(MatDialog);

  protected readonly groups = INBOX_ACTION_GROUPS;
  protected readonly newProject = NEW_PROJECT;

  /** Whether the processing mode is shown (otherwise the list). */
  protected readonly processing = signal(false);
  /** Items whose action is running: they are no longer offered. */
  private readonly busy = signal<ReadonlySet<string>>(new Set());
  /** Items processed since the processing mode started, for the progress bar. */
  protected readonly processed = signal(0);
  /** The note the last "To reference" created, offered to open. */
  protected readonly lastNote = signal<NoteResponse | null>(null);
  /** The event the last "To calendar" created, offered to open in the calendar on its day. */
  protected readonly lastEvent = signal<MovedEvent | null>(null);
  /** The item whose "To calendar" dialog is open: the dialog owns the keys meanwhile. */
  private readonly asking = signal<string | null>(null);

  /** The items still to process, in Inbox order. */
  protected readonly queue = computed(() =>
    this.tasks.items().filter((task) => !this.busy().has(task.id)),
  );
  /** The item being processed. */
  protected readonly current = computed<TaskResponse | undefined>(() => this.queue()[0]);
  /** The id of {@link current}: unlike it, it does not change when the item is refetched. */
  private readonly currentId = computed(() => this.current()?.id);
  /** Percentage of the items processed since the processing mode started. */
  protected readonly progress = computed(() => {
    const done = this.processed();
    const total = done + this.queue().length;
    return total === 0 ? 100 : Math.round((done / total) * 100);
  });

  /**
   * The attributes the user set inline for the current item; reset when the next item comes up.
   * Only these are saved, so a concurrent change to the other fields is kept.
   */
  private readonly changes = linkedSignal<string | undefined, Partial<Clarification>>({
    source: this.currentId,
    computation: () => ({}),
  });
  /** The attributes shown for the current item: its own, with the inline changes over them. */
  protected readonly clarification = computed<Clarification>(() => ({
    ...clarificationOf(this.current()),
    ...this.changes(),
  }));
  /**
   * Projects created by "New project" for an item whose move then failed, by item id: a retry
   * offers that project instead of creating a second one.
   */
  private readonly createdProjects = new Map<string, string>();
  /** Counts actions: only the latest may set or clear {@link lastNote} and {@link lastEvent}. */
  private runSeq = 0;
  /** The action waiting for its value (a date, a person, a project), if any. */
  protected readonly prompt = linkedSignal<string | undefined, PromptAction | null>({
    source: this.currentId,
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
    this.lastNote.set(null);
    this.lastEvent.set(null);
    this.processing.set(true);
  }

  protected stopProcessing(): void {
    this.processing.set(false);
  }

  protected updateClarification(change: Partial<Clarification>): void {
    this.changes.update((current) => ({ ...current, ...change }));
  }

  /** Fetches the inbox again after a failed load. */
  protected retry(): void {
    void this.tasks.refresh();
  }

  protected open(task: TaskResponse): void {
    void this.router.navigate(['/tasks', task.id]);
  }

  protected complete(task: TaskResponse): void {
    this.tasks.complete(task).catch(ignore);
  }

  /** Runs `action` on the current item, or asks for its value first. */
  protected choose(action: InboxAction): void {
    const task = this.current();
    if (!task) return;
    if (action === 'calendar') {
      void this.toCalendar(task);
      return;
    }
    if (!isPromptAction(action)) {
      void this.run(action);
      return;
    }
    this.promptDate.set(action === 'schedule' || action === 'tickler' ? this.tomorrow() : '');
    this.promptText.set('');
    // The project named in the capture (`#project`) is offered first while it is open.
    const captured = this.openProjects().find((project) => project.id === task.projectId);
    this.promptProject.set(
      this.createdProjects.get(task.id) ??
        captured?.id ??
        this.openProjects()[0]?.id ??
        NEW_PROJECT,
    );
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

  /** Asks for the calendar and times, then converts `task` (if it is still the current item). */
  private async toCalendar(task: TaskResponse): Promise<void> {
    if (this.asking()) return;
    this.asking.set(task.id);
    let request: ConvertToEventRequest | undefined;
    try {
      request = await askForCalendar(this.dialog, { title: task.title });
    } finally {
      this.asking.set(null);
    }
    // Processing stopped, or the item left the Inbox (elsewhere) while the dialog was open.
    if (!request || !this.processing() || this.current()?.id !== task.id) return;
    void this.run('calendar', request);
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
    if (this.prompt() || this.asking()) return;
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
  private async run(action: InboxAction, toEvent?: ConvertToEventRequest): Promise<void> {
    const task = this.current();
    if (!task) return;
    const changes = this.changes();
    const value = {
      date: this.promptDate(),
      text: this.promptText().trim(),
      project: this.promptProject(),
    };
    this.prompt.set(null);
    const seq = ++this.runSeq;
    this.busy.update((ids) => new Set(ids).add(task.id));
    try {
      let note: NoteResponse | null = null;
      let event: MovedEvent | null = null;
      if (action === 'trash') {
        await this.tasks.remove(task);
      } else if (action === 'reference') {
        // A note has no GTD attributes: the inline changes are not saved.
        note = await this.tasks.convertToNote(task);
      } else if (action === 'calendar' && toEvent) {
        // An event has no GTD attributes either: the inline changes are not saved.
        event = movedEvent(await this.tasks.convertToEvent(task, toEvent));
      } else {
        const patch = clarificationPatch(task, { ...clarificationOf(task), ...changes });
        const base = Object.keys(patch).length ? await this.tasks.update(task, patch) : task;
        await this.apply(action, base, value);
      }
      // The "Moved to ..." status describes only the latest action, whatever answers last.
      if (seq === this.runSeq) {
        this.lastNote.set(note);
        this.lastEvent.set(event);
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
        let projectId = value.project;
        if (projectId === NEW_PROJECT) {
          projectId = (await this.projects.create({ title: value.text })).id;
          this.createdProjects.set(task.id, projectId);
        }
        await move({ list: 'NEXT', projectId });
        this.createdProjects.delete(task.id);
        // A project without a next step (e.g. a new one) gets the item as its next step. The item
        // has moved even when that fails: the project then stays stalled and the HTTP layer says why.
        const project = this.projects.find(projectId);
        if (project?.stalled) await this.projects.setNextStep(project, task.id).catch(ignore);
        return;
      }
      default:
        return;
    }
  }
}

/** An event created from an inbox item, as the status line shows it. */
interface MovedEvent {
  readonly title: string;
  /** The day (`yyyy-MM-dd`) it starts on, on its own clocks: the calendar opens there. */
  readonly date: string;
}

function movedEvent(event: EventResponse): MovedEvent {
  const date =
    event.allDay || !event.start
      ? (event.startDate ?? localDate())
      : wallClockOf(event.start, event.timeZone ?? localZone()).date;
  return { title: event.title, date };
}

function localZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
}

function ignore(): void {
  // The store rolled the change back; the HTTP layer shows the error.
}
