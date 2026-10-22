import { HttpContext } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import {
  Api,
  completeWeeklyReview,
  getJournalEntry,
  getWeeklyReviewStatus,
  JournalEntryResponse,
  moveWeeklyReview,
  putJournalEntry,
  startWeeklyReview,
  WeeklyReviewResponse,
  WeeklyReviewStatusResponse,
} from '../../api/generated';
import { ApiError, SILENT_ERRORS } from '../../core/http';
import { localDate } from '../gtd/data';
import { draftKey, ReviewSession } from './review-session';
import { ReviewStep, stepIndex } from './review-steps';

interface Call {
  fn: unknown;
  params: Record<string, unknown>;
  context?: HttpContext;
}

function review(step: ReviewStep = 'GET_CLEAR', fields: Partial<WeeklyReviewResponse> = {}) {
  return {
    id: 'r1',
    step,
    stepNumber: stepIndex(step) + 1,
    startedAt: '2026-10-18T08:00:00Z',
    createdAt: '2026-10-18T08:00:00Z',
    updatedAt: '2026-10-18T08:00:00Z',
    version: 3,
    ...fields,
  } satisfies WeeklyReviewResponse;
}

describe('ReviewSession', () => {
  let calls: Call[];
  let status: WeeklyReviewStatusResponse;
  let journal: JournalEntryResponse | null;
  /** The error the next call of `fn` throws. */
  let failing: { fn: unknown; error: unknown } | undefined;
  /** Calls of `held.fn` wait until the test calls `held.release()`. */
  let held: { fn: unknown; until: Promise<void>; release: () => void } | undefined;
  let session: ReviewSession;

  function hold(fn: unknown): void {
    let release!: () => void;
    const until = new Promise<void>((resolve) => (release = resolve));
    held = { fn, until, release };
  }

  const callsOf = (fn: unknown) => calls.filter((call) => call.fn === fn);

  beforeEach(() => {
    localStorage.clear();
    calls = [];
    status = { daysSinceLastReview: 9 };
    journal = null;
    failing = undefined;
    held = undefined;
    const api = {
      invoke: async (fn: unknown, params: Record<string, unknown> = {}, context?: HttpContext) => {
        calls.push({ fn, params, context });
        if (held && held.fn === fn) await held.until;
        if (failing && failing.fn === fn) {
          const { error } = failing;
          failing = undefined;
          throw error;
        }
        const body = params['body'] as Record<string, unknown> | undefined;
        if (fn === getWeeklyReviewStatus) return status;
        if (fn === startWeeklyReview) return review();
        if (fn === moveWeeklyReview) {
          const step = body!['step'] as ReviewStep;
          return review(step, { version: 4 });
        }
        if (fn === completeWeeklyReview) {
          return review('REFLECTION', { completedAt: '2026-10-18T09:00:00Z', version: 5 });
        }
        if (fn === getJournalEntry) {
          if (!journal) throw new ApiError(404, 'Not Found');
          return journal;
        }
        if (fn === putJournalEntry) return { ...journal, ...body };
        throw new Error('unexpected call');
      },
    };
    TestBed.configureTestingModule({ providers: [ReviewSession, { provide: Api, useValue: api }] });
    session = TestBed.inject(ReviewSession);
  });

  it('shows the start screen with the days since the last review when none is open', async () => {
    await session.load();

    expect(session.phase()).toBe('idle');
    expect(session.daysSinceLast()).toBe(9);
    expect(session.review()).toBeNull();
  });

  it('resumes the open review at its saved step', async () => {
    status = { current: review('PROJECTS'), daysSinceLastReview: 9 };

    await session.load();

    expect(session.phase()).toBe('open');
    expect(session.step()).toBe('PROJECTS');
    expect(session.index()).toBe(4);
  });

  it('shows an error when the status cannot be loaded', async () => {
    failing = { fn: getWeeklyReviewStatus, error: new ApiError(0, 'offline') };

    await session.load();

    expect(session.phase()).toBe('error');
  });

  it('starts a review at the first step', async () => {
    await session.load();
    await session.start();

    expect(session.phase()).toBe('open');
    expect(session.step()).toBe('GET_CLEAR');
  });

  it('saves a move with the ETag of the review', async () => {
    status = { current: review() };
    await session.load();

    await session.goTo('PAST_CALENDAR');

    expect(callsOf(moveWeeklyReview)[0].params).toEqual({
      id: 'r1',
      'If-Match': '"3"',
      body: { step: 'PAST_CALENDAR' },
    });
    expect(session.step()).toBe('PAST_CALENDAR');
    expect(session.review()?.version).toBe(4);
    expect(session.saving()).toBe(false);
  });

  it('does not save a move to the current step', async () => {
    status = { current: review('WAITING_FOR') };
    await session.load();

    await session.goTo('WAITING_FOR');

    expect(callsOf(moveWeeklyReview)).toHaveLength(0);
  });

  it('reloads the review when a move conflicts with another tab', async () => {
    status = { current: review() };
    await session.load();
    status = { current: review('SOMEDAY', { version: 7 }) };
    failing = { fn: moveWeeklyReview, error: new ApiError(412, 'Precondition Failed') };

    await session.goTo('PAST_CALENDAR');

    expect(callsOf(getWeeklyReviewStatus)).toHaveLength(2);
    expect(session.step()).toBe('SOMEDAY');
  });

  it('keeps the step when a move fails otherwise', async () => {
    status = { current: review() };
    await session.load();
    failing = { fn: moveWeeklyReview, error: new ApiError(500, 'Server Error') };

    await session.goTo('PAST_CALENDAR');

    expect(callsOf(getWeeklyReviewStatus)).toHaveLength(1);
    expect(session.step()).toBe('GET_CLEAR');
    expect(session.saving()).toBe(false);
  });

  it('completes the review without touching the journal when there is no reflection', async () => {
    status = { current: review('REFLECTION') };
    await session.load();

    await session.complete();

    expect(callsOf(putJournalEntry)).toHaveLength(0);
    expect(callsOf(completeWeeklyReview)[0].params).toEqual({ id: 'r1', 'If-Match': '"3"' });
    expect(session.phase()).toBe('completed');
    expect(session.daysSinceLast()).toBe(0);
  });

  it('saves the reflection as a new journal entry of today', async () => {
    status = { current: review('REFLECTION') };
    await session.load();
    session.setReflection('  A calm week.  ');

    await session.complete();

    const put = callsOf(putJournalEntry)[0].params;
    expect(put['date']).toBe(localDate());
    expect(put['If-Match']).toBeUndefined();
    expect(put['body']).toEqual({
      bodyMd: '## Weekly review\n\nA calm week.\n',
      mood: null,
      tags: [],
    });
    expect(calls.indexOf(callsOf(putJournalEntry)[0])).toBeLessThan(
      calls.indexOf(callsOf(completeWeeklyReview)[0]),
    );
  });

  it("appends the reflection to today's entry, keeping its mood and tags", async () => {
    journal = {
      id: 'j1',
      date: localDate(),
      bodyMd: 'Morning run.\n\n',
      mood: 4,
      tags: ['sport'],
      displayTitle: 'Journal',
      createdAt: '2026-10-18T07:00:00Z',
      updatedAt: '2026-10-18T07:00:00Z',
      version: 2,
    };
    status = { current: review('REFLECTION') };
    await session.load();
    session.setReflection('Ship less, finish more.');

    await session.complete();

    const put = callsOf(putJournalEntry)[0].params;
    expect(put['If-Match']).toBe('"2"');
    expect(put['body']).toEqual({
      bodyMd: 'Morning run.\n\n## Weekly review\n\nShip less, finish more.\n',
      mood: 4,
      tags: ['sport'],
    });
  });

  it('does not complete the review when the reflection cannot be saved', async () => {
    status = { current: review('REFLECTION') };
    await session.load();
    session.setReflection('Notes');
    failing = { fn: putJournalEntry, error: new ApiError(500, 'Server Error') };

    await session.complete();

    expect(callsOf(completeWeeklyReview)).toHaveLength(0);
    expect(session.phase()).toBe('open');
    expect(session.reflection()).toBe('Notes');
  });

  it("does not overwrite or complete when today's entry cannot be read", async () => {
    status = { current: review('REFLECTION') };
    await session.load();
    session.setReflection('A fictional busy week.');
    failing = { fn: getJournalEntry, error: new ApiError(500, 'Server Error') };

    await session.complete();

    expect(callsOf(putJournalEntry)).toHaveLength(0);
    expect(callsOf(completeWeeklyReview)).toHaveLength(0);
    expect(session.phase()).toBe('open');
    expect(session.reflection()).toBe('A fictional busy week.');
    expect(session.saving()).toBe(false);
  });

  it('does not append the reflection twice when completing is retried', async () => {
    status = { current: review('REFLECTION') };
    await session.load();
    session.setReflection('A fictional quiet week.');
    failing = { fn: completeWeeklyReview, error: new ApiError(500, 'Server Error') };

    await session.complete();
    expect(callsOf(putJournalEntry)).toHaveLength(1);
    expect(session.phase()).toBe('open');

    await session.complete();

    expect(callsOf(putJournalEntry)).toHaveLength(1);
    expect(callsOf(completeWeeklyReview)).toHaveLength(2);
    expect(session.phase()).toBe('completed');
  });

  it('does nothing when completing without an open review', async () => {
    await session.load();

    await session.complete();

    expect(callsOf(completeWeeklyReview)).toHaveLength(0);
    expect(session.phase()).toBe('idle');
  });

  it('keeps the reflection draft for a resumed review and drops it once saved', async () => {
    status = { current: review('REFLECTION') };
    await session.load();
    session.setReflection('Half a thought');
    expect(localStorage.getItem(draftKey('r1'))).toBe('Half a thought');

    const resumed = TestBed.runInInjectionContext(() => new ReviewSession());
    await resumed.load();
    expect(resumed.reflection()).toBe('Half a thought');

    await resumed.complete();
    expect(localStorage.getItem(draftKey('r1'))).toBeNull();
  });

  it('saves only one move when a second one is asked while the first is saved', async () => {
    status = { current: review() };
    await session.load();
    hold(moveWeeklyReview);

    const first = session.goTo('PAST_CALENDAR');
    expect(session.saving()).toBe(true);
    await session.goTo('SOMEDAY');
    held!.release();
    await first;

    expect(callsOf(moveWeeklyReview).map((call) => call.params['body'])).toEqual([
      { step: 'PAST_CALENDAR' },
    ]);
    expect(session.step()).toBe('PAST_CALENDAR');
  });

  it('completes only once when finishing is clicked twice', async () => {
    status = { current: review('REFLECTION') };
    await session.load();
    hold(completeWeeklyReview);

    const first = session.complete();
    await session.complete();
    held!.release();
    await first;

    expect(callsOf(completeWeeklyReview)).toHaveLength(1);
    expect(session.phase()).toBe('completed');
  });

  it('reloads the review when a move finds it gone', async () => {
    status = { current: review() };
    await session.load();
    status = { daysSinceLastReview: 0 };
    failing = { fn: moveWeeklyReview, error: new ApiError(404, 'Not Found') };

    await session.goTo('PAST_CALENDAR');

    expect(session.phase()).toBe('idle');
    expect(session.review()).toBeNull();
  });

  it('shows the start screen when another tab already completed the review', async () => {
    status = { current: review('REFLECTION') };
    await session.load();
    status = { daysSinceLastReview: 0 };
    failing = { fn: completeWeeklyReview, error: new ApiError(409, 'Conflict') };

    await session.complete();

    expect(callsOf(getWeeklyReviewStatus)).toHaveLength(2);
    expect(session.phase()).toBe('idle');
    expect(session.daysSinceLast()).toBe(0);
    expect(session.saving()).toBe(false);
  });

  it('stays on the start screen when starting fails', async () => {
    await session.load();
    failing = { fn: startWeeklyReview, error: new ApiError(500, 'Server Error') };

    await session.start();

    expect(session.phase()).toBe('idle');
    expect(session.saving()).toBe(false);
  });

  it("looks up today's journal entry without an error message for a missing one", async () => {
    status = { current: review('REFLECTION') };
    await session.load();
    session.setReflection('A fictional short week.');

    await session.complete();

    const lookup = callsOf(getJournalEntry)[0];
    expect(lookup.params).toEqual({ date: localDate() });
    expect(lookup.context?.get(SILENT_ERRORS)).toBe(true);
  });

  it('keeps a reflection draft per review', async () => {
    localStorage.setItem(draftKey('r-older'), 'A fictional draft of another review');
    status = { current: review('REFLECTION') };

    await session.load();

    expect(session.reflection()).toBe('');
    session.setReflection('This review only');
    expect(localStorage.getItem(draftKey('r1'))).toBe('This review only');
    expect(localStorage.getItem(draftKey('r-older'))).toBe('A fictional draft of another review');
  });

  it('drops the stored draft when the reflection is cleared', async () => {
    status = { current: review('REFLECTION') };
    await session.load();
    session.setReflection('Soon gone');

    session.setReflection('');

    expect(localStorage.getItem(draftKey('r1'))).toBeNull();
  });

  it('does not store a draft before a review is open', async () => {
    await session.load();

    session.setReflection('No review yet');

    expect(session.reflection()).toBe('No review yet');
    expect(localStorage.length).toBe(0);
  });

  describe('without usable browser storage', () => {
    afterEach(() => vi.restoreAllMocks());

    it('resumes the review without a draft when storage cannot be read', async () => {
      vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
        throw new Error('denied');
      });
      status = { current: review('REFLECTION') };

      await session.load();

      expect(session.phase()).toBe('open');
      expect(session.reflection()).toBe('');
    });

    it('keeps the reflection in the page and saves it when storage cannot be written', async () => {
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
        throw new Error('quota');
      });
      status = { current: review('REFLECTION') };
      await session.load();

      session.setReflection('A fictional thought');
      expect(session.reflection()).toBe('A fictional thought');

      await session.complete();

      expect(callsOf(putJournalEntry)[0].params['body']).toMatchObject({
        bodyMd: '## Weekly review\n\nA fictional thought\n',
      });
      expect(session.phase()).toBe('completed');
    });
  });
});
