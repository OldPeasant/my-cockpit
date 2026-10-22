import {
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  input,
  OnInit,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { HabitRequest } from '../../api/generated';
import { draftProblem, HabitDraft, requestOf, Schedule, Weekday, WEEKDAYS } from './habit-display';

/**
 * Creates or edits a habit: name, schedule (every day, N times a week or chosen weekdays), times a
 * day and an optional reminder. Emits the request on save; the page sends it and shows its errors.
 */
@Component({
  selector: 'app-habit-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule],
  templateUrl: './habit-form.html',
  styleUrl: './habit-form.scss',
})
export class HabitForm implements OnInit {
  /** The starting values. */
  readonly initial = input.required<HabitDraft>();
  /** Whether the form edits an existing habit (changes the save label). */
  readonly editing = input(false);
  readonly saving = input(false);
  /** Why the last save failed. */
  readonly error = input<string | null>(null);

  readonly save = output<HabitRequest>();
  readonly cancelled = output<void>();

  protected readonly weekdays = WEEKDAYS;
  protected readonly draft = signal<HabitDraft>({
    name: '',
    schedule: 'DAILY',
    timesPerWeek: 3,
    weekdays: [],
    targetCount: 1,
    reminderTime: '',
  });
  /** Shown once a save was tried, so an untouched form does not start out red. */
  protected readonly tried = signal(false);
  protected readonly problem = computed(() => draftProblem(this.draft()));

  private readonly nameInput = viewChild<ElementRef<HTMLInputElement>>('name');

  ngOnInit(): void {
    this.draft.set({ ...this.initial(), weekdays: [...this.initial().weekdays] });
    setTimeout(() => this.nameInput()?.nativeElement.focus());
  }

  protected setName(event: Event): void {
    this.patch({ name: (event.target as HTMLInputElement).value });
  }

  protected setSchedule(schedule: Schedule): void {
    this.patch({ schedule });
  }

  protected setNumber(field: 'timesPerWeek' | 'targetCount', event: Event): void {
    this.patch({ [field]: (event.target as HTMLInputElement).valueAsNumber });
  }

  protected setReminder(event: Event): void {
    this.patch({ reminderTime: (event.target as HTMLInputElement).value });
  }

  protected toggleDay(day: Weekday): void {
    const days = this.draft().weekdays;
    this.patch({ weekdays: days.includes(day) ? days.filter((d) => d !== day) : [...days, day] });
  }

  protected submit(): void {
    if (this.saving()) return;
    this.tried.set(true);
    if (this.problem()) return;
    this.save.emit(requestOf(this.draft()));
  }

  private patch(fields: Partial<HabitDraft>): void {
    this.draft.update((draft) => ({ ...draft, ...fields }));
  }
}
