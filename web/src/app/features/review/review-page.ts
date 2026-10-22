import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ReviewSession } from './review-session';
import { REVIEW_STEPS, ReviewStep, stepAfter } from './review-steps';
import { CalendarStep } from './steps/calendar-step';
import { GetClearStep } from './steps/get-clear-step';
import { NextActionsStep } from './steps/next-actions-step';
import { ProjectsStep } from './steps/projects-step';
import { ReflectionStep } from './steps/reflection-step';
import { SomedayStep } from './steps/someday-step';
import { WaitingStep } from './steps/waiting-step';

/**
 * The weekly review wizard (SPEC.md §8.6): 8 guided steps, any of which can be revisited from the
 * step list. The current step is saved on the server, so the review resumes where it was left.
 */
@Component({
  selector: 'app-review-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    GetClearStep,
    CalendarStep,
    WaitingStep,
    ProjectsStep,
    NextActionsStep,
    SomedayStep,
    ReflectionStep,
  ],
  providers: [ReviewSession],
  templateUrl: './review-page.html',
  styleUrl: './review-page.scss',
})
export class ReviewPage {
  protected readonly session = inject(ReviewSession);
  protected readonly steps = REVIEW_STEPS;

  protected readonly current = computed(() => REVIEW_STEPS[this.session.index()]);
  protected readonly previous = computed(() => stepAfter(this.session.step(), -1));
  protected readonly next = computed(() => stepAfter(this.session.step(), 1));

  constructor() {
    void this.session.load();
  }

  protected goTo(step: ReviewStep | null): void {
    if (step) void this.session.goTo(step);
  }
}
