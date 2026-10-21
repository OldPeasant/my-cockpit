import { TaskPatchRequest, TaskResponse } from '../../../api/generated';
import { Creativity, Energy } from '../ui';

/** What processing can do with an inbox item (SPEC.md §5.3). */
export type InboxAction =
  | 'done'
  | 'next'
  | 'project'
  | 'waiting'
  | 'schedule'
  | 'tickler'
  | 'someday'
  | 'reference'
  | 'calendar'
  | 'trash';

/** Actions that ask for one more value (a date, a person, a project) before they run. */
export type PromptAction = Extract<InboxAction, 'waiting' | 'schedule' | 'tickler' | 'project'>;

export interface InboxActionInfo {
  readonly action: InboxAction;
  readonly label: string;
  /** The shortcut shown on the button (the first of {@link keys}). */
  readonly key: string;
  /** `KeyboardEvent.key` values (lower case) that run the action. */
  readonly keys: readonly string[];
  /** Not available yet (converting to events comes with a later task). */
  readonly disabled?: boolean;
}

/** A group of actions, in the order of the GTD decision: act on it, defer it, or send it elsewhere. */
export interface InboxActionGroup {
  readonly label: string;
  readonly actions: readonly InboxActionInfo[];
}

export const INBOX_ACTION_GROUPS: readonly InboxActionGroup[] = [
  {
    label: $localize`:@@inbox.group.act:Act on it`,
    actions: [
      {
        action: 'done',
        label: $localize`:@@inbox.action.done:Do it`,
        key: 'D',
        keys: ['d'],
      },
      { action: 'next', label: $localize`:@@inbox.action.next:Next`, key: 'N', keys: ['n'] },
      {
        action: 'project',
        label: $localize`:@@inbox.action.project:To project`,
        key: 'P',
        keys: ['p'],
      },
    ],
  },
  {
    label: $localize`:@@inbox.group.defer:Defer it`,
    actions: [
      {
        action: 'waiting',
        label: $localize`:@@inbox.action.waiting:Waiting`,
        key: 'W',
        keys: ['w'],
      },
      {
        action: 'schedule',
        label: $localize`:@@inbox.action.schedule:Schedule`,
        key: 'S',
        keys: ['s'],
      },
      {
        action: 'tickler',
        label: $localize`:@@inbox.action.tickler:Tickler`,
        key: 'T',
        keys: ['t'],
      },
      {
        action: 'someday',
        label: $localize`:@@inbox.action.someday:Someday`,
        key: 'M',
        keys: ['m'],
      },
    ],
  },
  {
    label: $localize`:@@inbox.group.elsewhere:Move it out`,
    actions: [
      {
        action: 'reference',
        label: $localize`:@@inbox.action.reference:To reference`,
        key: 'R',
        keys: ['r'],
      },
      {
        action: 'calendar',
        label: $localize`:@@inbox.action.calendar:To calendar`,
        key: 'C',
        keys: ['c'],
        disabled: true,
      },
      {
        action: 'trash',
        label: $localize`:@@inbox.action.trash:Trash`,
        key: 'X',
        keys: ['x', 'delete'],
      },
    ],
  },
];

const ALL_ACTIONS = INBOX_ACTION_GROUPS.flatMap((group) => group.actions);

export function isPromptAction(action: InboxAction): action is PromptAction {
  return ['waiting', 'schedule', 'tickler', 'project'].includes(action);
}

/** The enabled action of a key press (without modifiers), or `null`. */
export function actionForKey(key: string): InboxAction | null {
  const lower = key.toLowerCase();
  const info = ALL_ACTIONS.find((candidate) => candidate.keys.includes(lower));
  return info && !info.disabled ? info.action : null;
}

/** The attributes set inline while processing an item, before its action. */
export interface Clarification {
  readonly energy?: Energy;
  readonly creativity?: Creativity;
  readonly timeEstimateMin?: number;
  readonly contextIds: readonly string[];
}

export function clarificationOf(task: TaskResponse | undefined): Clarification {
  return {
    energy: task?.energy,
    creativity: task?.creativity,
    timeEstimateMin: task?.timeEstimateMin,
    contextIds: task?.contextIds ?? [],
  };
}

/** The PATCH that applies `clarification` to `task`: only changed fields, `null` clears one. */
export function clarificationPatch(
  task: TaskResponse,
  clarification: Clarification,
): TaskPatchRequest {
  const patch: TaskPatchRequest = {};
  if (clarification.energy !== task.energy) patch.energy = clarification.energy ?? null;
  if (clarification.creativity !== task.creativity) {
    patch.creativity = clarification.creativity ?? null;
  }
  if (clarification.timeEstimateMin !== task.timeEstimateMin) {
    patch.timeEstimateMin = clarification.timeEstimateMin ?? null;
  }
  const before = [...task.contextIds].sort();
  const after = [...clarification.contextIds].sort();
  if (before.length !== after.length || before.some((id, i) => id !== after[i])) {
    patch.contextIds = [...clarification.contextIds];
  }
  return patch;
}
