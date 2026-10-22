import { WeeklyReviewResponse } from '../../api/generated';

/** A step of the weekly review (SPEC.md §8.6), as the server names it. */
export type ReviewStep = WeeklyReviewResponse['step'];

export interface ReviewStepInfo {
  readonly step: ReviewStep;
  readonly title: string;
  /** What the user does in the step, in one line. */
  readonly hint: string;
}

/** The 8 steps in review order. */
export const REVIEW_STEPS: readonly ReviewStepInfo[] = [
  {
    step: 'GET_CLEAR',
    title: $localize`:@@review.step.getClear:Get clear`,
    hint: $localize`:@@review.step.getClear.hint:Process the inbox to zero and pick up loose captures, like flagged mails.`,
  },
  {
    step: 'PAST_CALENDAR',
    title: $localize`:@@review.step.pastCalendar:Past calendar`,
    hint: $localize`:@@review.step.pastCalendar.hint:Look back at the last 7 days. Anything left to do? Capture it.`,
  },
  {
    step: 'UPCOMING_CALENDAR',
    title: $localize`:@@review.step.upcomingCalendar:Upcoming calendar`,
    hint: $localize`:@@review.step.upcomingCalendar.hint:Look ahead at the next 14 days. Anything to prepare? Capture it.`,
  },
  {
    step: 'WAITING_FOR',
    title: $localize`:@@review.step.waitingFor:Waiting for`,
    hint: $localize`:@@review.step.waitingFor.hint:For each item: still waiting, follow up, or done.`,
  },
  {
    step: 'PROJECTS',
    title: $localize`:@@review.step.projects:Projects`,
    hint: $localize`:@@review.step.projects.hint:Does each active project have a next step? Stalled ones come first.`,
  },
  {
    step: 'NEXT_ACTIONS',
    title: $localize`:@@review.step.nextActions:Next actions`,
    hint: $localize`:@@review.step.nextActions.hint:Next actions untouched for more than 30 days: keep, move to someday, or delete.`,
  },
  {
    step: 'SOMEDAY',
    title: $localize`:@@review.step.someday:Someday/Maybe`,
    hint: $localize`:@@review.step.someday.hint:Activate what is ready, keep the rest, delete what no longer matters.`,
  },
  {
    step: 'REFLECTION',
    title: $localize`:@@review.step.reflection:Reflection`,
    hint: $localize`:@@review.step.reflection.hint:Look at your habits and write down what you noticed. It goes into today's journal.`,
  },
];

/** Index of `step` in {@link REVIEW_STEPS}. */
export function stepIndex(step: ReviewStep): number {
  return REVIEW_STEPS.findIndex((info) => info.step === step);
}

/** The step `offset` steps after `step`, or `null` beyond the first or last one. */
export function stepAfter(step: ReviewStep, offset: number): ReviewStep | null {
  return REVIEW_STEPS[stepIndex(step) + offset]?.step ?? null;
}
