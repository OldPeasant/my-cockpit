import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { HabitRequest, HabitResponse } from '../../api/generated';
import { ApiError, NotificationService } from '../../core/http';
import {
  completionPercent,
  draftOf,
  emptyDraft,
  HabitDraft,
  nextCount,
  scheduleLabel,
  streakLabel,
  todayProgress,
} from './habit-display';
import { HabitForm } from './habit-form';
import { HabitsService } from './habits.service';

/** What the form is open for: nothing, a new habit, or the habit with this id. */
type Editing = null | 'new' | string;

/**
 * The habits (SPEC.md §8.5): the habits due today first, each with a one-tap check, its current and
 * longest streak and its completion over the last 30 days; create, edit and archive habits.
 */
@Component({
  selector: 'app-habits-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, NgTemplateOutlet, HabitForm],
  templateUrl: './habits-page.html',
  styleUrl: './habits-page.scss',
})
export default class HabitsPage implements OnInit {
  private readonly service = inject(HabitsService);
  private readonly notifications = inject(NotificationService);

  /** The habits of the current view, by name; null while they load. */
  protected readonly habits = signal<HabitResponse[] | null>(null);
  protected readonly archived = signal(false);
  protected readonly loadError = signal<string | null>(null);
  /** Ids of the habits whose check or change is still running. */
  protected readonly busy = signal<ReadonlySet<string>>(new Set());
  /** The last failed check or change, by habit id. */
  protected readonly rowError = signal<{ id: string; message: string } | null>(null);

  protected readonly editing = signal<Editing>(null);
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly due = computed(() => (this.habits() ?? []).filter((h) => h.stats.dueToday));
  protected readonly notDue = computed(() =>
    (this.habits() ?? []).filter((h) => !h.stats.dueToday),
  );
  protected readonly doneDue = computed(() => this.due().filter((h) => h.stats.doneToday).length);
  /** The form's starting values: the edited habit's, or the defaults for a new one. */
  protected readonly draft = computed<HabitDraft>(() => {
    const id = this.editing();
    const habit = id && id !== 'new' ? this.habits()?.find((h) => h.id === id) : undefined;
    return habit ? draftOf(habit) : emptyDraft();
  });

  protected readonly scheduleLabel = scheduleLabel;
  protected readonly streakLabel = streakLabel;
  protected readonly completionPercent = completionPercent;
  protected readonly todayProgress = todayProgress;

  /** Counts the loads: habits that arrive for an earlier view are ignored. */
  private loadSeq = 0;

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    const seq = ++this.loadSeq;
    this.loadError.set(null);
    this.habits.set(null);
    try {
      const habits = await this.service.list(this.archived());
      if (seq === this.loadSeq) this.habits.set(habits);
    } catch (error) {
      if (seq === this.loadSeq) this.loadError.set(message(error));
    }
  }

  protected setArchived(archived: boolean): void {
    if (archived === this.archived()) return;
    this.archived.set(archived);
    this.rowError.set(null);
    this.closeForm();
    void this.load();
  }

  protected openNew(): void {
    this.saveError.set(null);
    this.editing.set('new');
  }

  protected openEdit(habit: HabitResponse): void {
    this.saveError.set(null);
    this.rowError.set(null);
    this.editing.set(habit.id);
  }

  protected closeForm(): void {
    if (this.saving()) return;
    this.editing.set(null);
    this.saveError.set(null);
  }

  protected async save(request: HabitRequest): Promise<void> {
    const id = this.editing();
    if (!id || this.saving()) return;
    const habit = id === 'new' ? undefined : this.habits()?.find((h) => h.id === id);
    if (id !== 'new' && !habit) return;
    this.saving.set(true);
    this.saveError.set(null);
    try {
      if (habit) {
        this.replace(await this.service.update(habit, request));
      } else {
        const created = await this.service.create(request);
        // A new habit is active: it shows up only in the active view.
        if (!this.archived()) {
          this.habits.update((habits) => (habits ? byName([...habits, created]) : habits));
        }
        this.notifications.info($localize`:@@habits.created:Habit added`);
      }
      this.editing.set(null);
    } catch (error) {
      this.saveError.set(message(error));
      if (habit && isConflict(error)) void this.reloadKeepingForm();
    } finally {
      this.saving.set(false);
    }
  }

  /** One tap: one more check today until the target is reached, then back to none. */
  protected async check(habit: HabitResponse): Promise<void> {
    await this.run(habit, async () => {
      this.replace(await this.service.setCheck(habit, habit.stats.today, nextCount(habit)));
    });
  }

  protected async toggleArchived(habit: HabitResponse): Promise<void> {
    await this.run(habit, async () => {
      const changed = await this.service.update(habit, { archived: !habit.archived });
      // It now belongs to the other view.
      this.habits.update((habits) => habits?.filter((h) => h.id !== changed.id) ?? null);
      if (this.editing() === habit.id) this.editing.set(null);
      const name = habit.name;
      this.notifications.info(
        changed.archived
          ? $localize`:@@habits.archived:Archived “${name}:name:”`
          : $localize`:@@habits.unarchived:Moved “${name}:name:” back to Habits`,
      );
    });
  }

  protected isBusy(habit: HabitResponse): boolean {
    return this.busy().has(habit.id);
  }

  protected errorOf(habit: HabitResponse): string | null {
    const error = this.rowError();
    return error?.id === habit.id ? error.message : null;
  }

  /** The label of the check button, for screen readers. */
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

  private replace(habit: HabitResponse): void {
    this.habits.update((habits) => habits?.map((h) => (h.id === habit.id ? habit : h)) ?? null);
  }

  /** Loads the habits again after a conflict, so saving the open form again works. */
  private async reloadKeepingForm(): Promise<void> {
    try {
      const habits = await this.service.list(this.archived());
      this.habits.set(habits);
    } catch {
      // The form keeps its error; the next save shows the problem again.
    }
  }

  /** Runs a change of `habit`; a habit gone meanwhile (404) is dropped from the list. */
  private async run(habit: HabitResponse, action: () => Promise<void>): Promise<void> {
    if (this.busy().has(habit.id)) return;
    this.busy.update((ids) => new Set(ids).add(habit.id));
    this.rowError.set(null);
    try {
      await action();
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        this.habits.update((habits) => habits?.filter((h) => h.id !== habit.id) ?? null);
      } else {
        this.rowError.set({ id: habit.id, message: message(error) });
        if (isConflict(error)) void this.load();
      }
    } finally {
      this.busy.update((ids) => {
        const rest = new Set(ids);
        rest.delete(habit.id);
        return rest;
      });
    }
  }
}

function byName(habits: HabitResponse[]): HabitResponse[] {
  return habits.sort((a, b) => a.name.localeCompare(b.name));
}

function isConflict(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 409 || error.status === 412);
}

function message(error: unknown): string {
  return error instanceof ApiError
    ? error.userMessage
    : $localize`:@@habits.error:Something went wrong.`;
}
