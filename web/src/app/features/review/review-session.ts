import { HttpContext } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import {
  Api,
  completeWeeklyReview,
  getJournalEntry,
  getWeeklyReviewStatus,
  JournalEntryRequest,
  JournalEntryResponse,
  moveWeeklyReview,
  putJournalEntry,
  startWeeklyReview,
  WeeklyReviewResponse,
} from '../../api/generated';
import { ApiError, SILENT_ERRORS } from '../../core/http';
import { etag, localDate } from '../gtd/data';
import { ReviewStep, stepIndex } from './review-steps';

/** Where the wizard is: loading, before a review, in one, after finishing it, or failed to load. */
export type ReviewPhase = 'loading' | 'idle' | 'open' | 'completed' | 'error';

/**
 * The weekly review the wizard shows (SPEC.md §8.6). The step is saved on the server on every
 * move, so leaving the page and coming back resumes where the user was. Provided by the page.
 */
@Injectable()
export class ReviewSession {
  private readonly api = inject(Api);

  private readonly phaseState = signal<ReviewPhase>('loading');
  private readonly reviewState = signal<WeeklyReviewResponse | null>(null);
  private readonly savingState = signal(false);

  readonly phase = this.phaseState.asReadonly();
  /** The open (or just completed) review. */
  readonly review = this.reviewState.asReadonly();
  /** Whether a move, start or completion is running. */
  readonly saving = this.savingState.asReadonly();
  /** Days since the last completed review; `null` before the first one. */
  readonly daysSinceLast = signal<number | null>(null);
  private readonly reflectionState = signal('');
  /**
   * The reflection text of the last step, saved as a journal entry when the review is completed.
   * Until then it is kept in this browser, so a resumed review still has it.
   */
  readonly reflection = this.reflectionState.asReadonly();

  readonly step = computed<ReviewStep>(() => this.reviewState()?.step ?? 'GET_CLEAR');
  readonly index = computed(() => stepIndex(this.step()));

  /** Shows the open review (resuming it), or the start screen. */
  async load(): Promise<void> {
    this.phaseState.set('loading');
    try {
      const status = await this.api.invoke(getWeeklyReviewStatus);
      this.daysSinceLast.set(status.daysSinceLastReview ?? null);
      this.show(status.current ?? null);
      this.phaseState.set(status.current ? 'open' : 'idle');
    } catch {
      this.phaseState.set('error');
    }
  }

  /** Starts a review (or resumes the open one, if another tab started it). */
  async start(): Promise<void> {
    await this.saveWith(async () => {
      this.show(await this.api.invoke(startWeeklyReview));
      this.phaseState.set('open');
    });
  }

  /** Saves `step` as the current step: any step, also an earlier one. */
  async goTo(step: ReviewStep): Promise<void> {
    const review = this.reviewState();
    if (!review || review.step === step || this.savingState()) return;
    await this.saveWith(async () => {
      this.reviewState.set(
        await this.api.invoke(moveWeeklyReview, {
          id: review.id,
          'If-Match': etag(review.version),
          body: { step },
        }),
      );
    });
  }

  /** Saves the reflection (if any) in today's journal entry, then completes the review. */
  async complete(): Promise<void> {
    const review = this.reviewState();
    if (!review || this.savingState()) return;
    await this.saveWith(async () => {
      const text = this.reflection().trim();
      if (text) {
        await this.saveReflection(text);
        // Saved: a retry after a failed completion must not append it a second time.
        this.setReflection('');
      }
      this.reviewState.set(
        await this.api.invoke(completeWeeklyReview, {
          id: review.id,
          'If-Match': etag(review.version),
        }),
      );
      this.daysSinceLast.set(0);
      this.phaseState.set('completed');
    });
  }

  setReflection(text: string): void {
    this.reflectionState.set(text);
    const review = this.reviewState();
    if (!review) return;
    try {
      if (text) localStorage.setItem(draftKey(review.id), text);
      else localStorage.removeItem(draftKey(review.id));
    } catch {
      // Storage unavailable (private mode, quota): the draft lives only in this page.
    }
  }

  /** Shows `review` with the reflection draft kept for it. */
  private show(review: WeeklyReviewResponse | null): void {
    this.reviewState.set(review);
    let draft: string | null = null;
    try {
      draft = review ? localStorage.getItem(draftKey(review.id)) : null;
    } catch {
      // Storage unavailable: no draft.
    }
    this.reflectionState.set(draft ?? '');
  }

  /**
   * Runs a review change. A conflict (another tab moved or completed the review) reloads the
   * review instead; other errors are shown by the HTTP layer and leave the state as it was.
   */
  private async saveWith(change: () => Promise<void>): Promise<void> {
    this.savingState.set(true);
    try {
      await change();
    } catch (error) {
      if (error instanceof ApiError && [404, 409, 412].includes(error.status)) await this.load();
    } finally {
      this.savingState.set(false);
    }
  }

  /** Appends the reflection to today's journal entry, creating the entry if there is none. */
  private async saveReflection(text: string): Promise<void> {
    const date = localDate();
    let existing: JournalEntryResponse | null = null;
    try {
      existing = await this.api.invoke(
        getJournalEntry,
        { date },
        new HttpContext().set(SILENT_ERRORS, true),
      );
    } catch (error) {
      if (!(error instanceof ApiError && error.status === 404)) throw error;
    }
    const section = `## ${$localize`:@@review.journalHeading:Weekly review`}\n\n${text}`;
    const before = existing?.bodyMd.trimEnd() ?? '';
    const body: JournalEntryRequest = {
      bodyMd: before ? `${before}\n\n${section}\n` : `${section}\n`,
      mood: existing?.mood ?? null,
      tags: existing?.tags ?? [],
    };
    await this.api.invoke(putJournalEntry, {
      date,
      'If-Match': existing ? etag(existing.version) : undefined,
      body,
    });
  }
}

/** The `localStorage` key of the reflection draft of a review. */
export function draftKey(reviewId: string): string {
  return `cockpit.review.reflection.${reviewId}`;
}
