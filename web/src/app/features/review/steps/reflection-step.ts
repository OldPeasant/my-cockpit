import { PercentPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Api, HabitResponse, listHabits } from '../../../api/generated';
import { ReviewSession } from '../review-session';

/**
 * Step 8: the habits of the last 30 days and a free-text reflection, which the session saves in
 * today's journal entry when the review is completed.
 */
@Component({
  selector: 'app-review-reflection-step',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PercentPipe],
  styleUrl: './steps.scss',
  template: `
    @if (habits(); as habits) {
      @if (habits.length) {
        <ul class="habits" aria-label="Habits" i18n-aria-label="@@review.reflection.habits">
          @for (habit of habits; track habit.id) {
            <li>
              <span class="title">{{ habit.name }}</span>
              <span class="meta">
                @let rate = habit.stats.completionRateLast30Days;
                @if (rate !== undefined && rate !== null) {
                  <ng-container i18n="@@review.reflection.rate"
                    >{{ rate | percent }} in 30 days</ng-container
                  >
                }
                <ng-container i18n="@@review.reflection.streak"
                  >streak {{ habit.stats.currentStreak }}</ng-container
                >
              </span>
            </li>
          }
        </ul>
      }
    } @else if (failed()) {
      <p class="empty" role="alert" i18n="@@review.reflection.habitsError">
        The habits could not be loaded.
      </p>
    }
    <label class="reflection">
      <span i18n="@@review.reflection.label">Reflection (optional)</span>
      <textarea
        rows="8"
        placeholder="What went well this week? What will you change?"
        i18n-placeholder="@@review.reflection.placeholder"
        [value]="session.reflection()"
        (input)="session.setReflection($any($event.target).value)"
      ></textarea>
    </label>
    <p class="aside" i18n="@@review.reflection.journal">
      Saved in today's journal entry when you finish the review.
    </p>
  `,
})
export class ReflectionStep {
  private readonly api = inject(Api);
  protected readonly session = inject(ReviewSession);

  protected readonly habits = signal<readonly HabitResponse[] | null>(null);
  protected readonly failed = signal(false);

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    try {
      this.habits.set((await this.api.invoke(listHabits)).items);
    } catch {
      this.failed.set(true);
    }
  }
}
