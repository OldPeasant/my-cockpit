import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatDialog } from '@angular/material/dialog';
import { Params, Router } from '@angular/router';
import { filter } from 'rxjs';
import { EventResponse, ItemRefDto, TaskResponse } from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { ApiError } from '../../../core/http';
import { LinkedItems } from '../../../shared/links';
import { wallClockOf } from '../../calendar/edit/event-times';
import { ContextStore, localDate, ProjectStore } from '../data';
import {
  ContextChips,
  Creativity,
  CreativityPicker,
  Energy,
  EnergyPicker,
  EstimatePicker,
  Segmented,
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
import { TaskDetailService } from './task-detail.service';
import { askForCalendar } from './to-calendar-dialog';

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
 * `<app-task-detail [taskId]="id" (closed)="back()" />`
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
  ],
  providers: [TaskDetailService],
  templateUrl: './task-detail.html',
  styleUrls: ['./controls.scss', './task-detail.scss'],
  host: { '(keydown)': 'onKeydown($event)' },
})
export class TaskDetail {
  private readonly service = inject(TaskDetailService);
  protected readonly contexts = inject(ContextStore);
  private readonly projects = inject(ProjectStore);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);

  readonly taskId = input.required<string>();
  /** The user closed the editor (after confirming to drop unsaved changes). */
  readonly closed = output<void>();

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

  constructor() {
    void this.contexts.ensureLoaded();
    void this.projects.ensureLoaded();
    effect(() => {
      const id = this.taskId();
      untracked(() => void this.load(id, 'initial'));
    });
    const events = inject(ChangeEventsService);
    events
      .events$('TASK')
      .pipe(
        filter((event) => event.id === this.taskId()),
        takeUntilDestroyed(),
      )
      .subscribe((event) => {
        if (event.op === 'DELETED') this.state.set('deleted');
        // Someone else changed it: show the new version unless the user is editing or the task
        // is turning into a note or an event.
        else if (!this.dirty() && !this.saving() && !this.converting()) {
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

  /** Puts the form back to the saved task. */
  protected discard(): void {
    const task = this.task();
    if (task) this.draft.set(draftOf(task));
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
    if (!task || this.saving() || this.converting()) return;
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
    if (!task || this.saving() || this.converting()) return;
    const request = await askForCalendar(this.dialog, { title: task.title, unsaved: this.dirty() });
    // Cancelled, or the editor moved on (another task, a save) while the dialog was open.
    if (!request || task.id !== this.taskId() || this.saving() || this.converting()) return;
    await this.convert(task, 'event', async () => {
      const event = await this.service.convertToEvent(this.task() ?? task, request);
      return { commands: ['/calendar'], queryParams: { date: eventDate(event) } };
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
      void this.router.navigate(target.commands, { queryParams: target.queryParams });
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

  /** Saves the form: a move (list, tickler) if needed, then a PATCH of the changed fields. */
  async save(): Promise<void> {
    const task = this.task();
    const draft = this.draft();
    if (!task || !draft || this.saving() || this.converting() || !this.valid() || !this.dirty()) {
      return;
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
      if (task.id !== this.taskId()) return;
      this.task.set(current);
      // Edits made while the save ran stay: they are compared with the saved version now.
      if (this.draft() === draft) this.draft.set(draftOf(current));
      this.flashSaved();
    } catch (error) {
      // Neither the result nor a conflict reload of the previous task belongs to the new one.
      if (task.id !== this.taskId()) return;
      // A move that went through stays: the next save starts from it.
      this.task.set(current);
      await this.failed(error);
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
      this.serverErrors.set(
        Object.fromEntries(error.errors.map((field) => [serverField(field.field), field.message])),
      );
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
    return this.dirty() || this.saving() || (!!shown && task.version < shown.version);
  }

  private flashSaved(): void {
    this.saved.set(true);
    clearTimeout(this.savedTimer);
    this.savedTimer = setTimeout(() => this.saved.set(false), 2500);
  }
}

/** What a task can be converted into. */
type ConversionTarget = 'note' | 'event';

/** The day (`yyyy-MM-dd`) `event` starts on, on its own clocks. */
function eventDate(event: EventResponse): string {
  if (event.allDay || !event.start) return event.startDate ?? localDate();
  return wallClockOf(event.start, event.timeZone ?? localZone()).date;
}

function localZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
}

const REFERENCE_CONFIRM = $localize`:@@taskDetail.referenceConfirm:Move this task to reference? It becomes a note and the task goes to the Trash.`;
const REFERENCE_DIRTY_CONFIRM = $localize`:@@taskDetail.referenceDirtyConfirm:Move this task to reference? Your unsaved changes are discarded; the note gets the saved version.`;
const DISCARD_CONFIRM = $localize`:@@taskDetail.discardConfirm:Discard your unsaved changes?`;

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
