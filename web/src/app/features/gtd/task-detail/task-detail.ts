import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  Injector,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatDialog } from '@angular/material/dialog';
import { Params, Router } from '@angular/router';
import { filter } from 'rxjs';
import { ItemRefDto, TaskResponse } from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { ApiError } from '../../../core/http';
import { LinkedItems } from '../../../shared/links';
import { ContextStore, localDate, ProjectStore } from '../data';
import {
  ContextChips,
  Creativity,
  CreativityPicker,
  Energy,
  EnergyPicker,
  EstimatePicker,
  renderMarkdown,
  Segmented,
  TaskUndo,
} from '../ui';
import {
  RECURRENCE_MODES,
  RECURRENCE_PRESETS,
  RecurrenceMode,
  RecurrencePreset,
} from './recurrence';
import {
  DraftField,
  draftOf,
  hasChanges,
  MAX_TITLE_LENGTH,
  moveFor,
  patchFor,
  TaskDraft,
  TaskListName,
  validateDraft,
} from './task-draft';
import { TaskChecklist } from './task-checklist';
import { TaskReminders } from './task-reminders';
import { TaskDetailService } from './task-detail.service';
import { askForCalendar, eventStartDay } from './to-calendar-dialog';

/** The lists a task can be on, in GTD order. */
export const LIST_OPTIONS: readonly { readonly value: TaskListName; readonly label: string }[] = [
  { value: 'INBOX', label: $localize`:@@nav.inbox:Inbox` },
  { value: 'NEXT', label: $localize`:@@taskDetail.list.next:Next actions` },
  { value: 'WAITING', label: $localize`:@@taskDetail.list.waiting:Waiting for` },
  { value: 'SCHEDULED', label: $localize`:@@taskDetail.list.scheduled:Scheduled` },
  { value: 'SOMEDAY', label: $localize`:@@taskDetail.list.someday:Someday/Maybe` },
];

/** Shown when a save met a newer version of the task (412). */
export const CONFLICT_MESSAGE = $localize`:@@taskDetail.conflict:This task was changed elsewhere. It has been reloaded with the latest version; make your changes again.`;

type LoadState = 'loading' | 'ready' | 'notFound' | 'deleted' | 'failed';
type LoadKind = 'initial' | 'background' | 'conflict';

/**
 * Edits every field of a task (SPEC.md §5.2): title, Markdown notes, list, project, contexts,
 * energy, creativity, estimate, dates, waiting-for, recurrence, plus its checklist (saved as it
 * changes) and its linked items. The form saves with `If-Match`; a 412 reloads the task and says
 * it was changed elsewhere.
 *
 * `<app-task-detail [taskId]="id" [backLabel]="'Today'" (closed)="back()" />`
 */
@Component({
  selector: 'app-task-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ContextChips,
    CreativityPicker,
    EnergyPicker,
    EstimatePicker,
    LinkedItems,
    Segmented,
    TaskChecklist,
    TaskReminders,
  ],
  providers: [TaskDetailService],
  templateUrl: './task-detail.html',
  styleUrls: [
    './controls.scss',
    './task-detail.scss',
    '../ui/markdown.scss',
    './task-detail-notes.scss',
  ],
  host: { '(keydown)': 'onKeydown($event)' },
})
export class TaskDetail {
  private readonly service = inject(TaskDetailService);
  protected readonly contexts = inject(ContextStore);
  private readonly projects = inject(ProjectStore);
  private readonly router = inject(Router);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  private readonly dialog = inject(MatDialog);
  private readonly undo = inject(TaskUndo);

  readonly taskId = input.required<string>();
  /** Where closing leads ("Today", the project's title), shown on the close button; none: "Close". */
  readonly backLabel = input<string | null>(null);
  /** The user closed the editor (after confirming to drop unsaved changes). */
  readonly closed = output<void>();

  protected readonly closeLabel = computed(() => {
    const destination = this.backLabel();
    return destination
      ? $localize`:@@back.aria:Back to ${destination}:destination:`
      : $localize`:@@taskDetail.close:Close`;
  });

  protected readonly lists = LIST_OPTIONS;
  protected readonly presets = RECURRENCE_PRESETS;
  protected readonly modes = RECURRENCE_MODES;
  protected readonly maxTitle = MAX_TITLE_LENGTH;
  protected readonly conflictMessage = CONFLICT_MESSAGE;
  protected readonly repeatModeLabel = $localize`:@@taskDetail.repeatMode:Next occurrence`;

  protected readonly state = signal<LoadState>('loading');
  protected readonly task = signal<TaskResponse | null>(null);
  protected readonly draft = signal<TaskDraft | null>(null);
  protected readonly saving = signal(false);
  /** The task whose "To reference" or "To calendar" is running, if any, and what it becomes. */
  private readonly conversion = signal<{ id: string; to: ConversionTarget } | null>(null);
  /**
   * What the task shown is turning into (`null`: nothing runs for it; another task the editor
   * switched to is free).
   */
  protected readonly converting = computed<ConversionTarget | null>(() => {
    const conversion = this.conversion();
    return conversion?.id === this.taskId() ? conversion.to : null;
  });
  /** The task whose Complete, Reopen or Delete is running, if any, and which one. */
  private readonly action = signal<{ id: string; kind: TaskAction } | null>(null);
  /** Which of Complete, Reopen or Delete runs for the task shown. */
  protected readonly acting = computed<TaskAction | null>(() => {
    const action = this.action();
    return action?.id === this.taskId() ? action.kind : null;
  });
  /** The title asked for by the project picker's "New project…" (`null`: not asking). */
  protected readonly newProjectTitle = signal<string | null>(null);
  /** The project of "New project…" is being created. */
  protected readonly creatingProject = signal(false);
  protected readonly newProject = NEW_PROJECT;
  /**
   * The project "New project…" created for a task whose save then failed: a retry assigns it
   * instead of creating a second one (as Inbox processing does).
   */
  private createdProject: { taskId: string; projectId: string } | null = null;
  /** Saving, converting or another action runs: the actions and the save button wait. */
  protected readonly busy = computed(
    () =>
      this.saving() ||
      this.creatingProject() ||
      this.converting() !== null ||
      this.acting() !== null,
  );
  /** The notes are edited as Markdown source (else shown rendered, when there are any). */
  protected readonly editingNotes = signal(false);
  protected readonly notesHtml = computed(() => renderMarkdown(this.draft()?.notesMd ?? ''));
  /** The task met a newer version on the server and was reloaded. */
  protected readonly conflict = signal(false);
  /** The last save error other than a conflict or field errors. */
  protected readonly saveError = signal<string | null>(null);
  /** Field errors the server returned for the last save. */
  protected readonly serverErrors = signal<Partial<Record<string, string>>>({});
  /** Shown briefly after a successful save. */
  protected readonly saved = signal(false);

  protected readonly today = computed(() => localDate());
  protected readonly errors = computed(() => {
    const task = this.task();
    const draft = this.draft();
    return task && draft ? validateDraft(task, draft, this.today()) : {};
  });
  protected readonly valid = computed(() => Object.keys(this.errors()).length === 0);
  protected readonly dirty = computed(() => {
    const task = this.task();
    const draft = this.draft();
    return Boolean(task && draft && hasChanges(task, draft));
  });
  protected readonly ref = computed<ItemRefDto>(() => ({ type: 'TASK', id: this.taskId() }));
  /** Active projects, plus the task's own project whatever its status. */
  protected readonly projectOptions = computed(() => {
    const own = this.draft()?.projectId;
    return this.projects
      .items()
      .filter((project) => project.status === 'ACTIVE' || project.id === own);
  });
  /** Counts loads: only the newest may apply its answer. */
  private loadSeq = 0;
  private savedTimer?: ReturnType<typeof setTimeout>;
  /** The task this editor moved to the Trash: its deletion event is ours, not someone else's. */
  private deletedId: string | null = null;

  constructor() {
    void this.contexts.ensureLoaded();
    void this.projects.ensureLoaded();
    effect(() => {
      const id = this.taskId();
      untracked(() => {
        this.editingNotes.set(false);
        this.newProjectTitle.set(null);
        if (id !== this.deletedId) this.deletedId = null;
        void this.load(id, 'initial');
      });
    });
    const events = inject(ChangeEventsService);
    events
      .events$('TASK')
      .pipe(
        filter((event) => event.id === this.taskId()),
        takeUntilDestroyed(),
      )
      .subscribe((event) => {
        // Our own Delete navigates back; its event must not replace the page until then.
        if (event.op === 'DELETED') {
          if (event.id !== this.deletedId) this.state.set('deleted');
        }
        // Someone else changed it: show the new version unless the user is editing or the task
        // is turning into a note or an event.
        else if (!this.dirty() && !this.busy()) {
          void this.load(this.taskId(), 'background');
        }
      });
    inject(DestroyRef).onDestroy(() => clearTimeout(this.savedTimer));
  }

  protected error(field: DraftField | string): string | undefined {
    return (
      (this.errors() as Record<string, string | undefined>)[field] ?? this.serverErrors()[field]
    );
  }

  protected edit(change: Partial<TaskDraft>): void {
    this.draft.update((draft) => (draft ? { ...draft, ...change } : draft));
    this.saved.set(false);
    if (Object.keys(this.serverErrors()).length) this.serverErrors.set({});
  }

  protected text(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  /** A choice in the project picker: a project, "No project", or "New project…" (asks for a title). */
  protected pickProject(event: Event): void {
    const value = this.text(event);
    if (value !== NEW_PROJECT) {
      this.newProjectTitle.set(null);
      this.createdProject = null;
      this.edit({ projectId: value });
      return;
    }
    this.newProjectTitle.set('');
    afterNextRender(
      () => this.host.nativeElement.querySelector<HTMLElement>('#td-new-project')?.focus(),
      { injector: this.injector },
    );
  }

  /** Drops "New project…": the task's project stays as it was. */
  protected cancelNewProject(): void {
    if (this.creatingProject()) return;
    this.newProjectTitle.set(null);
  }

  /**
   * Creates the project titled in "New project…" (Active, no area), assigns the task to it and
   * saves the task. An empty title changes nothing. When the save fails, the project stays chosen
   * and remembered: a retry assigns it again instead of creating another.
   */
  async createProject(): Promise<void> {
    const task = this.task();
    const title = this.newProjectTitle()?.trim() ?? '';
    if (!task || this.busy()) return;
    if (!title) {
      this.newProjectTitle.set(null);
      return;
    }
    let projectId =
      this.createdProject?.taskId === task.id ? this.createdProject.projectId : undefined;
    if (!projectId) {
      this.creatingProject.set(true);
      this.saveError.set(null);
      try {
        projectId = (await this.projects.create({ title })).id;
        this.createdProject = { taskId: task.id, projectId };
      } catch (error) {
        // The title stays: Create again retries.
        if (task.id === this.taskId()) this.saveError.set(messageOf(error));
        return;
      } finally {
        this.creatingProject.set(false);
      }
    }
    // The editor switched to another task meanwhile: the project stays for a retry there.
    if (task.id !== this.taskId()) return;
    this.newProjectTitle.set(null);
    this.edit({ projectId });
    if (await this.save()) this.createdProject = null;
  }

  protected setList(list: TaskListName): void {
    this.edit({ list });
  }

  protected setEnergy(energy: Energy | undefined): void {
    this.edit({ energy });
  }

  protected setCreativity(creativity: Creativity | undefined): void {
    this.edit({ creativity });
  }

  protected setEstimate(minutes: number | undefined): void {
    this.edit({ estimate: minutes === undefined ? '' : String(minutes) });
  }

  protected estimateValue(draft: TaskDraft): number | undefined {
    return /^\d+$/.test(draft.estimate.trim()) ? Number(draft.estimate) : undefined;
  }

  protected setRepeat(repeat: RecurrencePreset): void {
    const draft = this.draft();
    if (!draft) return;
    // Switching to "custom" starts from the rule chosen so far.
    const customRule =
      repeat === 'custom' && draft.repeat !== 'custom'
        ? (RECURRENCE_PRESETS.find((option) => option.value === draft.repeat)?.rule ?? '')
        : draft.customRule;
    this.edit({ repeat, customRule });
  }

  /**
   * A recurring task always has a mode: pressing the chosen mode again (which clears the
   * segmented group) puts the current mode back instead of leaving none shown.
   */
  protected setRepeatMode(
    mode: RecurrenceMode | undefined,
    picker: Segmented<RecurrenceMode>,
  ): void {
    const current = this.draft()?.repeatMode;
    if (!mode) picker.value.set(current);
    else if (mode !== current) this.edit({ repeatMode: mode });
  }

  /** Switches the notes to their Markdown source and focuses it. */
  protected editNotes(): void {
    this.editingNotes.set(true);
    afterNextRender(
      () => this.host.nativeElement.querySelector<HTMLElement>('#td-notes')?.focus(),
      {
        injector: this.injector,
      },
    );
  }

  /**
   * A click (or Enter) on the rendered notes edits them, except on a link (which opens) or while
   * text is being selected.
   */
  protected notesClicked(event: Event): void {
    if ((event.target as HTMLElement).closest('a')) return;
    if (window.getSelection()?.toString()) return;
    this.editNotes();
  }

  /**
   * Marks the task done, or reopens it when it is done. Unsaved changes are saved first. Completing
   * offers "Undo" in a snackbar.
   */
  async toggleDone(): Promise<void> {
    if (this.busy() || !this.task()) return;
    if (this.dirty()) {
      const id = this.taskId();
      // Stop when the save failed (a conflict reloads the task) or the editor switched tasks.
      if (!(await this.save()) || id !== this.taskId() || this.dirty()) return;
    }
    const task = this.task();
    if (!task) return;
    const kind: TaskAction = task.completedAt ? 'reopen' : 'complete';
    const draft = this.draft();
    await this.act(task, kind, async () => {
      const result =
        kind === 'complete'
          ? await this.service.complete(task)
          : await this.service.uncomplete(task);
      // Undo is offered also when the editor switched to another task meanwhile.
      if (kind === 'complete') {
        void this.undo.completed(task, () => this.reopenFromUndo(result));
      }
      if (task.id !== this.taskId()) return;
      this.task.set(result);
      // Edits made while the action ran stay: they are compared with the new version now.
      if (this.draft() === draft) this.draft.set(draftOf(result));
    });
  }

  /**
   * Moves the task to the Trash (unsaved changes are confirmed and dropped), offers "Undo" in a
   * snackbar and goes back to where the task was opened from.
   */
  async remove(): Promise<void> {
    const task = this.task();
    if (!task || this.busy()) return;
    if (this.dirty() && !confirm(DELETE_DIRTY_CONFIRM)) return;
    // Set before the request: the deletion event may arrive before its answer, or after it but
    // before the page has navigated away.
    this.deletedId = task.id;
    await this.act(task, 'delete', async () => {
      try {
        await this.service.remove(task);
      } catch (error) {
        if (this.deletedId === task.id) this.deletedId = null;
        throw error;
      }
      void this.undo.deleted(task);
      if (task.id === this.taskId()) this.closed.emit();
    });
  }

  /** The snackbar's "Undo" after completing: reopens the newest version known of the task. */
  private async reopenFromUndo(done: TaskResponse): Promise<void> {
    const shown = this.task();
    const latest = shown?.id === done.id && shown.version > done.version ? shown : done;
    // Unsaved edits (made before or while the undo runs) stay.
    const draft = this.dirty() ? null : this.draft();
    const reopened = await this.service.uncomplete(latest, true);
    // Show it, unless the editor moved on or a save or another action runs.
    if (reopened.id !== this.taskId() || this.busy()) return;
    this.task.set(reopened);
    if (this.draft() === draft) this.draft.set(draftOf(reopened));
  }

  /** Runs Complete, Reopen or Delete of `task`; errors show like save errors. */
  private async act(task: TaskResponse, kind: TaskAction, run: () => Promise<void>): Promise<void> {
    this.action.set({ id: task.id, kind });
    this.saveError.set(null);
    this.conflict.set(false);
    // A background reload started before would answer with an older version.
    this.loadSeq++;
    try {
      await run();
    } catch (error) {
      if (task.id === this.taskId()) await this.failed(error);
    } finally {
      if (this.action()?.id === task.id) this.action.set(null);
    }
  }

  /** Puts the form back to the saved task. */
  protected discard(): void {
    const task = this.task();
    if (task) this.draft.set(draftOf(task));
    this.editingNotes.set(false);
    this.serverErrors.set({});
    this.saveError.set(null);
  }

  protected close(): void {
    if (this.dirty() && !confirm(DISCARD_CONFIRM)) return;
    this.closed.emit();
  }

  /**
   * Moves the task to reference: the server turns it into a note (title, notes, links) and trashes
   * the task; then the note opens. Unsaved changes are not part of the note, so they are confirmed.
   */
  async toReference(): Promise<void> {
    const task = this.task();
    if (!task || this.busy()) return;
    if (!confirm(this.dirty() ? REFERENCE_DIRTY_CONFIRM : REFERENCE_CONFIRM)) return;
    await this.convert(task, 'note', async () => {
      const note = await this.service.convertToNote(task);
      return { commands: ['/notes', note.id] };
    });
  }

  /**
   * Moves the task to the calendar: a dialog asks for the calendar and the times, the server turns
   * the task into that event (title, notes, links) and trashes the task; then the calendar opens on
   * the event's day. The dialog says that unsaved changes are not part of the event.
   */
  async toCalendar(): Promise<void> {
    const task = this.task();
    if (!task || this.busy()) return;
    const request = await askForCalendar(this.dialog, { title: task.title, unsaved: this.dirty() });
    // Cancelled, or the editor moved on (another task, a save) while the dialog was open.
    if (!request || task.id !== this.taskId() || this.busy()) return;
    await this.convert(task, 'event', async () => {
      const event = await this.service.convertToEvent(this.task() ?? task, request);
      return { commands: ['/calendar'], queryParams: { date: eventStartDay(event) } };
    });
  }

  /** Runs a conversion of `task`, then opens what it became (unless the editor switched tasks). */
  private async convert(
    task: TaskResponse,
    to: ConversionTarget,
    run: () => Promise<{ commands: unknown[]; queryParams?: Params }>,
  ): Promise<void> {
    this.conversion.set({ id: task.id, to });
    this.saveError.set(null);
    this.conflict.set(false);
    // A background reload would replace the form while the task turns into something else.
    this.loadSeq++;
    try {
      const target = await run();
      // The editor switched to another task meanwhile: the conversion stays done, the screen is not ours.
      if (task.id !== this.taskId()) return;
      const { commands, queryParams } = target;
      void (queryParams
        ? this.router.navigate(commands, { queryParams })
        : this.router.navigate(commands));
    } catch (error) {
      if (task.id === this.taskId()) await this.failed(error);
    } finally {
      if (this.conversion()?.id === task.id) this.conversion.set(null);
    }
  }

  protected onKeydown(event: KeyboardEvent): void {
    const save = (event.ctrlKey || event.metaKey) && (event.key === 'Enter' || event.key === 's');
    if (!save) return;
    event.preventDefault();
    void this.save();
  }

  /**
   * Saves the form: a move (list, tickler) if needed, then a PATCH of the changed fields. Resolves to
   * true only when it saved and the task is still shown.
   */
  async save(): Promise<boolean> {
    const task = this.task();
    const draft = this.draft();
    if (!task || !draft || this.busy() || !this.valid() || !this.dirty()) {
      return false;
    }
    this.saving.set(true);
    this.saveError.set(null);
    this.serverErrors.set({});
    this.conflict.set(false);
    // A background reload started before this save would answer with an older version.
    this.loadSeq++;
    let current = task;
    try {
      const move = moveFor(current, draft);
      if (move) current = await this.service.move(current, move);
      const patch = patchFor(current, draft);
      if (Object.keys(patch).length) current = await this.service.update(current, patch);
      // The editor switched to another task meanwhile: the save stays done, the form is not ours.
      if (task.id !== this.taskId()) return false;
      this.task.set(current);
      // Edits made while the save ran stay: they are compared with the saved version now.
      if (this.draft() === draft) {
        this.draft.set(draftOf(current));
        this.editingNotes.set(false);
      }
      this.flashSaved();
      return true;
    } catch (error) {
      // Neither the result nor a conflict reload of the previous task belongs to the new one.
      if (task.id !== this.taskId()) return false;
      // A move that went through stays: the next save starts from it.
      this.task.set(current);
      await this.failed(error);
      return false;
    } finally {
      this.saving.set(false);
    }
  }

  protected retryLoad(): void {
    void this.load(this.taskId(), 'initial');
  }

  private async failed(error: unknown): Promise<void> {
    if (error instanceof ApiError && error.status === 412) {
      this.conflict.set(true);
      await this.load(this.taskId(), 'conflict');
      return;
    }
    if (error instanceof ApiError && error.status === 400 && error.errors.length) {
      const errors = error.errors.map((field) => ({ ...field, field: serverField(field.field) }));
      this.serverErrors.set(
        Object.fromEntries(errors.map((field) => [field.field, field.message])),
      );
      // Errors of fields the form does not show (e.g. the calendar or times of "To calendar").
      const hidden = errors.filter((field) => !Object.hasOwn(SHOWN_FIELDS, field.field));
      if (hidden.length) {
        this.saveError.set(hidden.map((field) => field.message).join(' ') || error.userMessage);
      }
      return;
    }
    this.saveError.set(messageOf(error));
  }

  /**
   * Loads the task. An `initial` load shows the loading state; a `conflict` load replaces the
   * form after a 412; a `background` load (a change event) is dropped when the user started
   * editing or saving meanwhile, or when it answers with an older version than the one shown.
   */
  private async load(id: string, kind: LoadKind): Promise<void> {
    const seq = ++this.loadSeq;
    const initial = kind === 'initial';
    if (initial) this.state.set('loading');
    try {
      const task = await this.service.get(id);
      if (seq !== this.loadSeq) return;
      if (kind === 'background' && this.isStale(task)) return;
      this.task.set(task);
      this.draft.set(draftOf(task));
      this.state.set('ready');
    } catch (error) {
      if (seq !== this.loadSeq) return;
      if (error instanceof ApiError && error.status === 404) this.state.set('notFound');
      else if (initial || this.state() !== 'ready') this.state.set('failed');
      else this.saveError.set(messageOf(error));
    }
  }

  /** A background answer must not replace edits, a running save or a newer version. */
  private isStale(task: TaskResponse): boolean {
    const shown = this.task();
    return this.dirty() || this.busy() || (!!shown && task.version < shown.version);
  }

  private flashSaved(): void {
    this.saved.set(true);
    clearTimeout(this.savedTimer);
    this.savedTimer = setTimeout(() => this.saved.set(false), 2500);
  }
}

/** Value of the project picker's "New project…" (no project id looks like it). */
const NEW_PROJECT = 'new-project';

/** What a task can be converted into. */
type ConversionTarget = 'note' | 'event';

/** The actions of the header besides the conversions. */
type TaskAction = 'complete' | 'reopen' | 'delete';

const REFERENCE_CONFIRM = $localize`:@@taskDetail.referenceConfirm:Move this task to reference? It becomes a note and the task goes to the Trash.`;
const REFERENCE_DIRTY_CONFIRM = $localize`:@@taskDetail.referenceDirtyConfirm:Move this task to reference? Your unsaved changes are discarded; the note gets the saved version.`;
const DELETE_DIRTY_CONFIRM = $localize`:@@taskDetail.deleteDirtyConfirm:Move this task to the Trash? Your unsaved changes are discarded.`;
const DISCARD_CONFIRM = $localize`:@@taskDetail.discardConfirm:Discard your unsaved changes?`;

/** The fields the form shows server errors under; others go to the save bar. */
const SHOWN_FIELDS: Readonly<Record<DraftField, true>> = {
  title: true,
  notesMd: true,
  estimate: true,
  dueTime: true,
  scheduledDate: true,
  ticklerDate: true,
  waitingForText: true,
  customRule: true,
};

/** Server field names that the form shows under another field. */
function serverField(field: string): string {
  switch (field) {
    case 'timeEstimateMin':
      return 'estimate';
    case 'recurrenceRule':
    case 'recurrenceMode':
      return 'customRule';
    default:
      return field;
  }
}

function messageOf(error: unknown): string {
  if (error instanceof ApiError) return error.userMessage;
  return $localize`:@@taskDetail.unknownError:Something went wrong. Try again.`;
}
