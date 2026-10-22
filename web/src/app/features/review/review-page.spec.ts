import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import {
  Api,
  completeWeeklyReview,
  getWeeklyReviewStatus,
  moveWeeklyReview,
  startWeeklyReview,
  WeeklyReviewResponse,
  WeeklyReviewStatusResponse,
} from '../../api/generated';
import { ReviewPage } from './review-page';
import { REVIEW_STEPS, ReviewStep, stepIndex } from './review-steps';
import { CalendarStep } from './steps/calendar-step';
import { GetClearStep } from './steps/get-clear-step';
import { NextActionsStep } from './steps/next-actions-step';
import { ProjectsStep } from './steps/projects-step';
import { ReflectionStep } from './steps/reflection-step';
import { SomedayStep } from './steps/someday-step';
import { WaitingStep } from './steps/waiting-step';

function review(step: ReviewStep, version = 1): WeeklyReviewResponse {
  return {
    id: 'r1',
    step,
    stepNumber: stepIndex(step) + 1,
    startedAt: '2026-10-18T08:00:00Z',
    createdAt: '2026-10-18T08:00:00Z',
    updatedAt: '2026-10-18T08:00:00Z',
    version,
  };
}

// The steps load their own data: their own specs cover them, here they are stand-ins.
@Component({ selector: 'app-review-get-clear-step', template: 'get-clear' })
class GetClearStub {}
@Component({ selector: 'app-review-calendar-step', template: 'calendar' })
class CalendarStub {}
@Component({ selector: 'app-review-waiting-step', template: 'waiting' })
class WaitingStub {}
@Component({ selector: 'app-review-projects-step', template: 'projects' })
class ProjectsStub {}
@Component({ selector: 'app-review-next-actions-step', template: 'next-actions' })
class NextActionsStub {}
@Component({ selector: 'app-review-someday-step', template: 'someday' })
class SomedayStub {}
@Component({ selector: 'app-review-reflection-step', template: 'reflection' })
class ReflectionStub {}

const settle = () => new Promise((resolve) => setTimeout(resolve));

describe('ReviewPage', () => {
  let status: WeeklyReviewStatusResponse;
  let moves: ReviewStep[];
  let completed: number;
  let element: HTMLElement;

  const text = () => element.textContent?.replace(/\s+/g, ' ') ?? '';
  const button = (label: string) =>
    [...element.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.replace(/\s+/g, ' ').trim() === label,
    );
  const current = () => element.querySelector('[aria-current="step"] .name')?.textContent?.trim();
  const heading = () => element.querySelector('h1')?.textContent?.trim();

  async function refresh(): Promise<void> {
    await settle();
    TestBed.tick();
    await settle();
    TestBed.tick();
  }

  async function render(): Promise<void> {
    const fixture = TestBed.createComponent(ReviewPage);
    element = fixture.nativeElement;
    await refresh();
  }

  async function click(target: HTMLElement | undefined): Promise<void> {
    expect(target).toBeDefined();
    target!.click();
    await refresh();
  }

  beforeEach(() => {
    localStorage.clear();
    status = { daysSinceLastReview: 8 };
    moves = [];
    completed = 0;
    let version = 1;
    const api = {
      invoke: async (fn: unknown, params: Record<string, unknown> = {}) => {
        if (fn === getWeeklyReviewStatus) return status;
        if (fn === startWeeklyReview) return review('GET_CLEAR', version);
        if (fn === moveWeeklyReview) {
          const step = (params['body'] as { step: ReviewStep }).step;
          moves.push(step);
          return review(step, ++version);
        }
        if (fn === completeWeeklyReview) {
          completed++;
          return { ...review('REFLECTION', ++version), completedAt: '2026-10-18T09:00:00Z' };
        }
        throw new Error('unexpected call');
      },
    };
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: Api, useValue: api }],
    });
    TestBed.overrideComponent(ReviewPage, {
      remove: {
        imports: [
          GetClearStep,
          CalendarStep,
          WaitingStep,
          ProjectsStep,
          NextActionsStep,
          SomedayStep,
          ReflectionStep,
        ],
      },
      add: {
        imports: [
          GetClearStub,
          CalendarStub,
          WaitingStub,
          ProjectsStub,
          NextActionsStub,
          SomedayStub,
          ReflectionStub,
        ],
      },
    });
  });

  it('offers to start a review and says when the last one was', async () => {
    await render();

    expect(heading()).toBe('Weekly review');
    expect(text()).toContain('Last weekly review: 8 days ago.');
    expect(element.querySelectorAll('.outline li')).toHaveLength(8);
  });

  it('says so before the first review', async () => {
    status = {};
    await render();

    expect(text()).toContain("You haven't done a weekly review yet.");
  });

  it('starts at step 1 of 8 with the inbox', async () => {
    await render();
    await click(button('Start review'));

    expect(text()).toContain('Step 1 of 8');
    expect(heading()).toBe('Get clear');
    expect(current()).toBe('Get clear');
    expect(element.querySelector('app-review-get-clear-step')).not.toBeNull();
    expect(button('Back')?.disabled).toBe(true);
  });

  it('walks forward and back through the steps, saving each move', async () => {
    await render();
    await click(button('Start review'));

    await click(button('Next step'));
    expect(heading()).toBe('Past calendar');
    expect(element.querySelector('app-review-calendar-step')?.getAttribute('range')).toBe('past');

    await click(button('Next step'));
    expect(heading()).toBe('Upcoming calendar');

    await click(button('Back'));
    expect(heading()).toBe('Past calendar');
    expect(moves).toEqual(['PAST_CALENDAR', 'UPCOMING_CALENDAR', 'PAST_CALENDAR']);
    // Steps before the current one are marked done in the rail.
    expect(element.querySelectorAll('.step.done')).toHaveLength(1);
  });

  it('jumps to any step from the rail', async () => {
    await render();
    await click(button('Start review'));

    const someday = [...element.querySelectorAll<HTMLButtonElement>('.step')][6];
    await click(someday);

    expect(moves).toEqual(['SOMEDAY']);
    expect(heading()).toBe('Someday/Maybe');
    expect(element.querySelector('app-review-someday-step')).not.toBeNull();
  });

  it('resumes an open review at its saved step', async () => {
    status = { current: review('NEXT_ACTIONS') };
    await render();

    expect(text()).toContain('Step 6 of 8');
    expect(current()).toBe('Next actions');
    expect(element.querySelector('app-review-next-actions-step')).not.toBeNull();
    expect(moves).toEqual([]);
  });

  it('shows every step with its own content', async () => {
    const selectors = [
      'app-review-get-clear-step',
      'app-review-calendar-step',
      'app-review-calendar-step',
      'app-review-waiting-step',
      'app-review-projects-step',
      'app-review-next-actions-step',
      'app-review-someday-step',
      'app-review-reflection-step',
    ];
    status = { current: review('GET_CLEAR') };
    await render();
    for (const [i, info] of REVIEW_STEPS.entries()) {
      expect(heading()).toBe(info.title);
      expect(element.querySelector(selectors[i])).not.toBeNull();
      if (i < REVIEW_STEPS.length - 1) await click(button('Next step'));
    }
  });

  it('finishes the review on the last step', async () => {
    status = { current: review('REFLECTION') };
    await render();

    expect(button('Next step')).toBeUndefined();
    await click(button('Finish review'));

    expect(completed).toBe(1);
    expect(heading()).toBe('Review complete');
  });
});
