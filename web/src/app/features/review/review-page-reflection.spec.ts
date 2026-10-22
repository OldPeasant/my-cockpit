import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import {
  Api,
  completeWeeklyReview,
  getJournalEntry,
  getWeeklyReviewStatus,
  listHabits,
  moveWeeklyReview,
  putJournalEntry,
  WeeklyReviewResponse,
} from '../../api/generated';
import { ApiError } from '../../core/http';
import { localDate } from '../gtd/data';
import { ReviewPage } from './review-page';
import { draftKey } from './review-session';
import { ReviewStep, stepIndex } from './review-steps';
import { CalendarStep } from './steps/calendar-step';
import { GetClearStep } from './steps/get-clear-step';
import { NextActionsStep } from './steps/next-actions-step';
import { ProjectsStep } from './steps/projects-step';
import { SomedayStep } from './steps/someday-step';
import { WaitingStep } from './steps/waiting-step';

function review(step: ReviewStep, version = 1): WeeklyReviewResponse {
  return {
    id: 'r-fictional',
    step,
    stepNumber: stepIndex(step) + 1,
    startedAt: '2026-10-18T08:00:00Z',
    createdAt: '2026-10-18T08:00:00Z',
    updatedAt: '2026-10-18T08:00:00Z',
    version,
  };
}

// Only the reflection step is real here: it must share the page's session.
@Component({ selector: 'app-review-get-clear-step', template: '' })
class GetClearStub {}
@Component({ selector: 'app-review-calendar-step', template: '' })
class CalendarStub {}
@Component({ selector: 'app-review-waiting-step', template: '' })
class WaitingStub {}
@Component({ selector: 'app-review-projects-step', template: '' })
class ProjectsStub {}
@Component({ selector: 'app-review-next-actions-step', template: '' })
class NextActionsStub {}
@Component({ selector: 'app-review-someday-step', template: '' })
class SomedayStub {}

const settle = () => new Promise((resolve) => setTimeout(resolve));

describe('ReviewPage with the reflection step', () => {
  let current: WeeklyReviewResponse;
  let puts: Record<string, unknown>[];
  let completed: number;
  let element: HTMLElement;

  const heading = () => element.querySelector('h1')?.textContent?.trim();
  const textarea = () => element.querySelector('textarea');
  const button = (label: string) =>
    [...element.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.replace(/\s+/g, ' ').trim() === label,
    );

  async function refresh(): Promise<void> {
    await settle();
    TestBed.tick();
    await settle();
    TestBed.tick();
  }

  async function render(): Promise<void> {
    element = TestBed.createComponent(ReviewPage).nativeElement;
    await refresh();
  }

  async function click(target: HTMLElement | undefined): Promise<void> {
    expect(target).toBeDefined();
    target!.click();
    await refresh();
  }

  beforeEach(() => {
    localStorage.clear();
    current = review('REFLECTION');
    puts = [];
    completed = 0;
    const api = {
      invoke: async (fn: unknown, params: Record<string, unknown> = {}) => {
        if (fn === getWeeklyReviewStatus) return { current, daysSinceLastReview: 7 };
        if (fn === moveWeeklyReview) {
          current = review((params['body'] as { step: ReviewStep }).step, current.version + 1);
          return current;
        }
        if (fn === listHabits) return { items: [] };
        if (fn === getJournalEntry) throw new ApiError(404, 'Not Found');
        if (fn === putJournalEntry) {
          puts.push(params);
          return {};
        }
        if (fn === completeWeeklyReview) {
          completed++;
          return { ...current, completedAt: '2026-10-18T09:00:00Z' };
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
        ],
      },
    });
  });

  it('saves what was typed in the reflection as a journal entry when finishing', async () => {
    await render();
    expect(heading()).toBe('Reflection');

    textarea()!.value = 'A fictional week with fewer meetings.';
    textarea()!.dispatchEvent(new Event('input'));
    await click(button('Finish review'));

    expect(puts).toHaveLength(1);
    expect(puts[0]['date']).toBe(localDate());
    expect(puts[0]['body']).toEqual({
      bodyMd: '## Weekly review\n\nA fictional week with fewer meetings.\n',
      mood: null,
      tags: [],
    });
    expect(completed).toBe(1);
    expect(heading()).toBe('Review complete');
    expect(localStorage.getItem(draftKey('r-fictional'))).toBeNull();
  });

  it('shows the reflection draft again when the review is resumed', async () => {
    localStorage.setItem(draftKey('r-fictional'), 'Half a fictional thought');

    await render();

    expect(textarea()?.value).toBe('Half a fictional thought');
  });

  it('keeps the reflection when leaving the step and coming back', async () => {
    await render();
    textarea()!.value = 'Kept across steps';
    textarea()!.dispatchEvent(new Event('input'));

    await click(button('Back'));
    expect(heading()).toBe('Someday/Maybe');
    expect(textarea()).toBeNull();

    await click(button('Next step'));
    expect(textarea()?.value).toBe('Kept across steps');
  });
});
