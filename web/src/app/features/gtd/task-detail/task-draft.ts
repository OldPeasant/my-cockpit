import { MoveTaskRequest, TaskPatchRequest, TaskResponse } from '../../../api/generated';
import { Creativity, Energy } from '../ui';
import {
  normalizeRule,
  presetOf,
  presetRule,
  RecurrenceMode,
  RecurrencePreset,
  recurrenceProblem,
} from './recurrence';

export type TaskListName = TaskResponse['list'];

/** Server limits (`TaskService`). */
export const MAX_TITLE_LENGTH = 500;
export const MAX_NOTES_LENGTH = 100_000;
export const MAX_ESTIMATE_MINUTES = 10_000;

/**
 * The editor's state of a task: what the form fields show, as strings where the field is a text or
 * date input (empty = not set).
 */
export interface TaskDraft {
  readonly title: string;
  readonly notesMd: string;
  readonly list: TaskListName;
  /** Empty: no project. */
  readonly projectId: string;
  readonly contextIds: readonly string[];
  readonly energy?: Energy;
  readonly creativity?: Creativity;
  /** Minutes as typed; empty: no estimate. */
  readonly estimate: string;
  readonly dueDate: string;
  readonly dueTime: string;
  readonly scheduledDate: string;
  readonly ticklerDate: string;
  readonly waitingForText: string;
  readonly waitingSince: string;
  readonly repeat: RecurrencePreset;
  /** The rule of the `custom` preset as typed. */
  readonly customRule: string;
  readonly repeatMode: RecurrenceMode;
}

/** The fields that can show a validation error. */
export type DraftField =
  | 'title'
  | 'notesMd'
  | 'estimate'
  | 'dueTime'
  | 'scheduledDate'
  | 'ticklerDate'
  | 'waitingForText'
  | 'customRule';

export type DraftErrors = Partial<Record<DraftField, string>>;

/** The draft showing `task` unchanged. */
export function draftOf(task: TaskResponse): TaskDraft {
  return {
    title: task.title,
    notesMd: task.notesMd ?? '',
    list: task.list,
    projectId: task.projectId ?? '',
    contextIds: task.contextIds,
    energy: task.energy,
    creativity: task.creativity,
    estimate: task.timeEstimateMin == null ? '' : String(task.timeEstimateMin),
    dueDate: task.dueDate ?? '',
    dueTime: (task.dueTime ?? '').slice(0, 5),
    scheduledDate: task.scheduledDate ?? '',
    ticklerDate: task.ticklerDate ?? '',
    waitingForText: task.waitingForText ?? '',
    waitingSince: task.waitingSince ?? '',
    repeat: presetOf(task.recurrenceRule),
    customRule: task.recurrenceRule ?? '',
    repeatMode: task.recurrenceMode ?? 'FIXED',
  };
}

/** The rule the draft saves, `null` for none. */
export function draftRule(draft: TaskDraft): string | null {
  if (draft.repeat === 'none') return null;
  if (draft.repeat === 'custom') return normalizeRule(draft.customRule) || null;
  return presetRule(draft.repeat);
}

/** What is wrong with `draft` (empty when it can be saved). `today` is the local date. */
export function validateDraft(task: TaskResponse, draft: TaskDraft, today: string): DraftErrors {
  const errors: DraftErrors = {};
  const title = draft.title.trim();
  if (!title) errors.title = $localize`:@@taskDetail.error.titleRequired:Give the task a title.`;
  else if (title.length > MAX_TITLE_LENGTH) {
    errors.title = $localize`:@@taskDetail.error.titleLength:Keep the title under ${MAX_TITLE_LENGTH}:max: characters.`;
  }
  if (draft.notesMd.length > MAX_NOTES_LENGTH) {
    errors.notesMd = $localize`:@@taskDetail.error.notesLength:Notes can have at most ${MAX_NOTES_LENGTH}:max: characters.`;
  }
  const estimate = draft.estimate.trim();
  if (estimate && (!/^\d+$/.test(estimate) || Number(estimate) > MAX_ESTIMATE_MINUTES)) {
    errors.estimate = $localize`:@@taskDetail.error.estimate:Enter whole minutes from 0 to ${MAX_ESTIMATE_MINUTES}:max:.`;
  }
  if (draft.dueTime && !draft.dueDate) {
    errors.dueTime = $localize`:@@taskDetail.error.dueTime:A due time needs a due date.`;
  }
  if (draft.list === 'SCHEDULED' && !draft.scheduledDate) {
    errors.scheduledDate = $localize`:@@taskDetail.error.scheduled:Scheduled tasks need a date.`;
  }
  if (
    draft.ticklerDate &&
    draft.ticklerDate !== (task.ticklerDate ?? '') &&
    draft.ticklerDate <= today
  ) {
    errors.ticklerDate = $localize`:@@taskDetail.error.tickler:Pick a date after today.`;
  }
  if (draft.list === 'WAITING' && draft.waitingForText.trim().length > MAX_TITLE_LENGTH) {
    errors.waitingForText = $localize`:@@taskDetail.error.waitingLength:Keep this under ${MAX_TITLE_LENGTH}:max: characters.`;
  }
  if (draft.repeat === 'custom') {
    const problem = recurrenceProblem(draft.customRule);
    if (problem) errors.customRule = problem;
  }
  return errors;
}

/**
 * The `/move` the draft needs: when its list or tickler differ from `task`. List, scheduled date
 * and tickler are sent together, because a move sets all three; the waiting-for fields are kept by
 * the server and patched afterwards.
 */
export function moveFor(task: TaskResponse, draft: TaskDraft): MoveTaskRequest | undefined {
  if (draft.list === task.list && draft.ticklerDate === (task.ticklerDate ?? '')) return undefined;
  const body: MoveTaskRequest = { list: draft.list };
  if (draft.list === 'SCHEDULED') body.scheduledDate = draft.scheduledDate;
  if (draft.ticklerDate) body.ticklerDate = draft.ticklerDate;
  return body;
}

/**
 * The PATCH turning `base` (the task, after a move if any) into the draft: only changed fields,
 * `null` for cleared ones. List-bound fields are only sent while the task is on their list.
 */
export function patchFor(base: TaskResponse, draft: TaskDraft): TaskPatchRequest {
  const patch: Record<string, unknown> = {};
  const set = (key: keyof TaskPatchRequest, value: unknown, current: unknown) => {
    if ((value ?? null) !== (current ?? null)) patch[key] = value ?? null;
  };
  const text = (value: string) => value || null;
  const title = draft.title.trim();
  if (title !== base.title) patch['title'] = title;
  set('notesMd', text(draft.notesMd), base.notesMd);
  set('projectId', text(draft.projectId), base.projectId);
  if (!sameIds(draft.contextIds, base.contextIds)) patch['contextIds'] = [...draft.contextIds];
  set('energy', draft.energy, base.energy);
  set('creativity', draft.creativity, base.creativity);
  const estimate = draft.estimate.trim();
  set('timeEstimateMin', estimate ? Number(estimate) : null, base.timeEstimateMin);
  set('dueDate', text(draft.dueDate), base.dueDate);
  if (text(draft.dueTime) !== (base.dueTime?.slice(0, 5) ?? null)) {
    patch['dueTime'] = text(draft.dueTime);
  }
  if (base.list === 'SCHEDULED' && draft.list === 'SCHEDULED') {
    set('scheduledDate', text(draft.scheduledDate), base.scheduledDate);
  }
  if (base.list === 'WAITING' && draft.list === 'WAITING') {
    set('waitingForText', text(draft.waitingForText.trim()), base.waitingForText);
    if (draft.waitingSince) set('waitingSince', draft.waitingSince, base.waitingSince);
  }
  const rule = draftRule(draft);
  set('recurrenceRule', rule, base.recurrenceRule);
  set('recurrenceMode', rule ? draft.repeatMode : null, base.recurrenceMode);
  return patch as TaskPatchRequest;
}

/** Whether saving `draft` would change `task`. */
export function hasChanges(task: TaskResponse, draft: TaskDraft): boolean {
  return moveFor(task, draft) !== undefined || Object.keys(patchFor(task, draft)).length > 0;
}

function sameIds(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((id, index) => id === b[index]);
}
