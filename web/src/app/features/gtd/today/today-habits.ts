import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  DOCUMENT,
  inject,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { HabitResponse } from '../../../api/generated';
import { ApiError } from '../../../core/http';
import { nextCount, scheduleLabel, todayProgress } from '../../habits/habit-display';
import { HabitsService } from '../../habits/habits.service';

/**
 * The habits due today (SPEC.md §5.3, §8.5) as a section of the Today view, each with the same
 * one-tap check as the Habits view. Without any active habit the section stays hidden.
 */
@Component({
  selector: 'app-today-habits',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, RouterLink],
  templateUrl: './today-habits.html',
  styleUrl: './today-habits.scss',
})
export class TodayHabits {
  private readonly service = inject(HabitsService);

  /** The active habits, by name; null while they load. */
  private readonly habits = signal<HabitResponse[] | null>(null);
  protected readonly failed = signal(false);
  /** Ids of the habits whose check is still running. */
  protected readonly busy = signal<ReadonlySet<string>>(new Set());
  /** The last failed check, by habit id. */
  protected readonly rowError = signal<{ id: string; message: string } | null>(null);

  protected readonly loaded = computed(() => this.habits() !== null);
  /** Hidden for someone who does not track habits. */
  protected readonly hidden = computed(() => this.habits()?.length === 0);
  protected readonly due = computed(() => (this.habits() ?? []).filter((h) => h.stats.dueToday));
  protected readonly doneDue = computed(() => this.due().filter((h) => h.stats.doneToday).length);

  protected readonly scheduleLabel = scheduleLabel;
  protected readonly todayProgress = todayProgress;

  /** Counts the loads and checks: a reload that overlapped a check may be stale and is dropped. */
  private seq = 0;

  constructor() {
    void this.load();
    // A view left open past midnight would check yesterday (`stats.today`): load again on return.
    const document = inject(DOCUMENT);
    const visible = () => {
      if (document.visibilityState === 'visible' && !this.busy().size) void this.load(true);
    };
    document.addEventListener('visibilitychange', visible);
    inject(DestroyRef).onDestroy(() => document.removeEventListener('visibilitychange', visible));
  }

  /** Loads the habits; `quietly` keeps the current ones on screen and ignores a failure. */
  protected async load(quietly = false): Promise<void> {
    const seq = ++this.seq;
    if (!quietly) {
      this.failed.set(false);
      this.habits.set(null);
    }
    try {
      const habits = await this.service.list(false);
      if (seq === this.seq) this.habits.set(habits);
    } catch {
      if (seq === this.seq && !quietly) this.failed.set(true);
    }
  }

  /** One tap: one more check today until the target is reached, then back to none. */
  protected async check(habit: HabitResponse): Promise<void> {
    if (this.busy().has(habit.id)) return;
    this.busy.update((ids) => new Set(ids).add(habit.id));
    this.rowError.set(null);
    this.seq++;
    try {
      const checked = await this.service.setCheck(habit, habit.stats.today, nextCount(habit));
      this.seq++;
      this.habits.update(
        (habits) => habits?.map((h) => (h.id === checked.id ? checked : h)) ?? null,
      );
    } catch (error) {
      this.seq++;
      if (error instanceof ApiError && error.status === 404) {
        this.habits.update((habits) => habits?.filter((h) => h.id !== habit.id) ?? null);
      } else {
        this.rowError.set({ id: habit.id, message: message(error) });
        if (error instanceof ApiError && (error.status === 409 || error.status === 412)) {
          void this.load(true);
        }
      }
    } finally {
      this.busy.update((ids) => {
        const rest = new Set(ids);
        rest.delete(habit.id);
        return rest;
      });
    }
  }

  protected isBusy(habit: HabitResponse): boolean {
    return this.busy().has(habit.id);
  }

  protected errorOf(habit: HabitResponse): string | null {
    const error = this.rowError();
    return error?.id === habit.id ? error.message : null;
  }

  /** The label of the check button, for screen readers (the same texts as the Habits view). */
  protected checkLabel(habit: HabitResponse): string {
    const name = habit.name;
    if (habit.targetCount === 1) {
      return habit.stats.doneToday
        ? $localize`:@@habits.uncheck:Uncheck “${name}:name:” for today`
        : $localize`:@@habits.check:Check “${name}:name:” for today`;
    }
    const count = habit.stats.todayCount;
    const target = habit.targetCount;
    return $localize`:@@habits.checkCount:“${name}:name:”: ${count}:count: of ${target}:target: today`;
  }
}

function message(error: unknown): string {
  return error instanceof ApiError
    ? error.userMessage
    : $localize`:@@habits.error:Something went wrong.`;
}
