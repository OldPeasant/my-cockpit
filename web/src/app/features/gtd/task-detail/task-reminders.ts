import { formatDate } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  LOCALE_ID,
  signal,
  untracked,
} from '@angular/core';
import { ReminderResponse } from '../../../api/generated';
import { ApiError } from '../../../core/http';
import { TaskDetailService } from './task-detail.service';

/** The quick choices for a new reminder, relative to the task's dates. */
export type ReminderPreset = 'AT_DUE' | 'HOUR_BEFORE_DUE' | 'DAY_BEFORE_DUE' | 'AT_SCHEDULED';

const PRESETS: readonly ReminderPreset[] = [
  'AT_DUE',
  'HOUR_BEFORE_DUE',
  'DAY_BEFORE_DUE',
  'AT_SCHEDULED',
];

/** The task dates the presets are relative to (`yyyy-MM-dd`, `HH:mm`, local time). */
export interface ReminderAnchors {
  readonly dueDate?: string | null;
  readonly dueTime?: string | null;
  readonly scheduledDate?: string | null;
}

/** A date without a time reminds at this time of day (as on Android). */
export const DEFAULT_REMINDER_TIME = '09:00';

/** Shown when the picked time has already passed. */
export const REMINDER_PAST = $localize`:@@taskDetail.reminderPast:Pick a time in the future.`;

/** The local date and time `date` (`yyyy-MM-dd`) at `time` (`HH:mm`), or `null` if invalid. */
export function localDateTime(date: string, time: string): Date | null {
  const d = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  const t = /^(\d{2}):(\d{2})(?::\d{2})?$/.exec(time);
  if (!d || !t) return null;
  const result = new Date(+d[1], +d[2] - 1, +d[3], +t[1], +t[2]);
  return Number.isNaN(result.getTime()) ? null : result;
}

/** When `preset` reminds for a task with `anchors`, or `null` when the task lacks the date it needs. */
export function presetTime(preset: ReminderPreset, anchors: ReminderAnchors): Date | null {
  const due = anchors.dueDate
    ? localDateTime(anchors.dueDate, anchors.dueTime || DEFAULT_REMINDER_TIME)
    : null;
  switch (preset) {
    case 'AT_DUE':
      return due;
    case 'HOUR_BEFORE_DUE':
      return due && anchors.dueTime ? new Date(due.getTime() - 3_600_000) : null;
    case 'DAY_BEFORE_DUE':
      // Calendar day, so a DST change keeps the time of day.
      return due
        ? new Date(
            due.getFullYear(),
            due.getMonth(),
            due.getDate() - 1,
            due.getHours(),
            due.getMinutes(),
          )
        : null;
    case 'AT_SCHEDULED':
      return anchors.scheduledDate
        ? localDateTime(anchors.scheduledDate, DEFAULT_REMINDER_TIME)
        : null;
  }
}

/** The presets offered now: the ones the task has a date for and that are still in the future. */
export function availablePresets(
  anchors: ReminderAnchors,
  now: Date,
): { readonly preset: ReminderPreset; readonly at: Date }[] {
  return PRESETS.flatMap((preset) => {
    const at = presetTime(preset, anchors);
    return at && at.getTime() > now.getTime() ? [{ preset, at }] : [];
  });
}

let nextUid = 0;

/**
 * The reminders of a task (SPEC.md §4.6): lists them, adds one from a preset relative to the due
 * or scheduled date or at an exact date and time, and removes them. A reminder fires as a browser
 * notification (`core/notifications`). Needs a {@link TaskDetailService} from the parent.
 *
 * `<app-task-reminders [taskId]="id" [anchors]="task" />`
 */
@Component({
  selector: 'app-task-reminders',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './task-reminders.html',
  styleUrls: ['./controls.scss', './task-reminders.scss'],
})
export class TaskReminders {
  private readonly service = inject(TaskDetailService);
  private readonly locale = inject(LOCALE_ID);

  readonly taskId = input.required<string>();
  /** The saved dates of the task, for the presets. */
  readonly anchors = input<ReminderAnchors | null>(null);

  /** The clock, replaceable in tests. */
  now: () => Date = () => new Date();

  protected readonly uid = `app-task-reminders-${nextUid++}`;
  protected readonly reminders = signal<ReminderResponse[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  /** Whether the list of the current task is loading, loaded, or failed to load. */
  protected readonly loadState = signal<'loading' | 'loaded' | 'failed'>('loading');
  /** Changes wait for the load, so a late list cannot overwrite them. */
  protected readonly locked = computed(() => this.busy() || this.loadState() === 'loading');
  /** The exact time typed so far (`yyyy-MM-ddTHH:mm`, local). */
  protected readonly exact = signal('');

  protected readonly presets = computed(() => {
    const anchors = this.anchors();
    // Recomputed with the anchors and the list, which is when the offer can change visibly.
    this.reminders();
    return anchors ? availablePresets(anchors, this.now()) : [];
  });

  constructor() {
    effect(() => {
      const id = this.taskId();
      untracked(() => {
        this.reminders.set([]);
        this.error.set(null);
        this.exact.set('');
        void this.load(id);
      });
    });
  }

  protected text(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected presetLabel(preset: ReminderPreset): string {
    switch (preset) {
      case 'AT_DUE':
        return $localize`:@@taskDetail.reminder.atDue:When due`;
      case 'HOUR_BEFORE_DUE':
        return $localize`:@@taskDetail.reminder.hourBefore:1 hour before due`;
      case 'DAY_BEFORE_DUE':
        return $localize`:@@taskDetail.reminder.dayBefore:1 day before due`;
      case 'AT_SCHEDULED':
        return $localize`:@@taskDetail.reminder.atScheduled:On the scheduled day`;
    }
  }

  /** The time of a reminder (or preset) in the user's locale and time zone. */
  protected when(at: string | Date): string {
    return formatDate(at, 'EEE d MMM y, HH:mm', this.locale);
  }

  protected removeLabel(reminder: ReminderResponse): string {
    return $localize`:@@taskDetail.reminder.remove:Remove reminder ${this.when(reminder.remindAt)}:time:`;
  }

  /** Adds a reminder at `at`; a time that has passed is refused with a hint. */
  async add(at: Date): Promise<void> {
    if (this.locked()) return;
    if (at.getTime() <= this.now().getTime()) {
      this.error.set(REMINDER_PAST);
      return;
    }
    const taskId = this.taskId();
    await this.change(async () => {
      const reminder = await this.service.addReminder(taskId, at.toISOString());
      // The reminder belongs to the previous task when the editor switched meanwhile.
      if (taskId !== this.taskId()) return;
      this.reminders.update((all) => sortByTime([...all, reminder]));
      this.exact.set('');
    });
  }

  protected addExact(event: Event): void {
    event.preventDefault();
    const [date, time] = this.exact().split('T');
    const at = date && time ? localDateTime(date, time) : null;
    if (at) void this.add(at);
  }

  async remove(reminder: ReminderResponse): Promise<void> {
    if (this.locked()) return;
    const before = this.reminders();
    this.reminders.update((all) => all.filter((other) => other.id !== reminder.id));
    await this.change(
      async () => {
        try {
          await this.service.deleteReminder(reminder.id);
        } catch (error) {
          // Already gone (fired and cleaned up, or removed elsewhere) counts as removed.
          if (!(error instanceof ApiError && error.status === 404)) throw error;
        }
      },
      () => this.reminders.set(before),
    );
  }

  /** Runs a change; on an error it is undone (`rollback`) and the error shown. */
  private async change(run: () => Promise<unknown>, rollback?: () => unknown): Promise<void> {
    const taskId = this.taskId();
    this.busy.set(true);
    this.error.set(null);
    try {
      await run();
    } catch (error) {
      // A failure of the previous task's change must not touch the new task's list.
      if (taskId !== this.taskId()) return;
      rollback?.();
      this.error.set(messageOf(error));
    } finally {
      this.busy.set(false);
    }
  }

  /** Loads the list again after a failed load. */
  protected retry(): void {
    if (this.locked()) return;
    this.error.set(null);
    void this.load(this.taskId());
  }

  private async load(taskId: string): Promise<void> {
    this.loadState.set('loading');
    try {
      const reminders = await this.service.reminders(taskId);
      if (taskId !== this.taskId()) return;
      this.reminders.set(sortByTime(reminders));
      this.loadState.set('loaded');
    } catch (error) {
      if (taskId !== this.taskId()) return;
      this.error.set(messageOf(error));
      this.loadState.set('failed');
    }
  }
}

function sortByTime(reminders: ReminderResponse[]): ReminderResponse[] {
  return [...reminders].sort((a, b) => Date.parse(a.remindAt) - Date.parse(b.remindAt));
}

function messageOf(error: unknown): string {
  if (error instanceof ApiError) return error.userMessage;
  return $localize`:@@taskDetail.unknownError:Something went wrong. Try again.`;
}
