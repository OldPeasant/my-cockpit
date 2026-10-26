import { ApiError } from '../../core/http';
import {
  checkLabel,
  completionPercent,
  draftOf,
  draftProblem,
  emptyDraft,
  errorMessage,
  nextCount,
  requestOf,
  scheduleLabel,
  todayProgress,
} from './habit-display';
import { habit } from './testing';

describe('checkLabel', () => {
  it('offers to check a once-a-day habit not done today', () => {
    expect(checkLabel(habit('01', { name: 'Floss' }))).toBe('Check “Floss” for today');
  });

  it('offers to uncheck a once-a-day habit done today', () => {
    const floss = habit('01', { name: 'Floss', stats: { todayCount: 1, doneToday: true } });
    expect(checkLabel(floss)).toBe('Uncheck “Floss” for today');
  });

  it('reads the count of today against a target above 1', () => {
    const water = (todayCount: number, doneToday = false) =>
      habit('01', { name: 'Drink water', targetCount: 3, stats: { todayCount, doneToday } });
    expect(checkLabel(water(0))).toBe('“Drink water”: 0 of 3 today');
    expect(checkLabel(water(2))).toBe('“Drink water”: 2 of 3 today');
    // Reaching the target does not switch to the check/uncheck wording.
    expect(checkLabel(water(3, true))).toBe('“Drink water”: 3 of 3 today');
    // The smallest target above 1, and a count above a lowered target.
    expect(checkLabel(habit('02', { name: 'Walk', targetCount: 2 }))).toBe(
      '“Walk”: 0 of 2 today',
    );
    expect(checkLabel(water(5, true))).toBe('“Drink water”: 5 of 3 today');
  });
});

describe('errorMessage', () => {
  it('shows the detail of an API error, else its title', () => {
    expect(errorMessage(new ApiError(409, 'Conflict', 'The habit was changed elsewhere.'))).toBe(
      'The habit was changed elsewhere.',
    );
    expect(errorMessage(new ApiError(0, 'The server cannot be reached.'))).toBe(
      'The server cannot be reached.',
    );
  });

  it('shows a generic message for anything that is not an API error', () => {
    for (const error of [new Error('TypeError: x is undefined'), 'boom', undefined, null, {}]) {
      expect(errorMessage(error)).toBe('Something went wrong.');
    }
  });
});

describe('habit display', () => {
  it('toggles a once-a-day habit between 0 and 1', () => {
    expect(nextCount(habit('01'))).toBe(1);
    expect(nextCount(habit('01', { stats: { todayCount: 1, doneToday: true } }))).toBe(0);
  });

  it('counts up to the target, then back to 0', () => {
    const water = (todayCount: number) => habit('01', { targetCount: 3, stats: { todayCount } });
    expect([0, 1, 2, 3].map((n) => nextCount(water(n)))).toEqual([1, 2, 3, 0]);
    // A day checked above a lowered target also resets.
    expect(nextCount(water(5))).toBe(0);
    expect(todayProgress(water(2))).toBeCloseTo(2 / 3);
    expect(todayProgress(water(5))).toBe(1);
  });

  it('rounds the 30-day completion and keeps "no due days" apart from 0 %', () => {
    expect(completionPercent(habit('01', { stats: { completionRateLast30Days: 0.666 } }))).toBe(67);
    expect(completionPercent(habit('01', { stats: { completionRateLast30Days: 0 } }))).toBe(0);
    expect(completionPercent(habit('01', { stats: { completionRateLast30Days: undefined } }))).toBe(
      null,
    );
  });

  it('describes the schedule', () => {
    expect(scheduleLabel(habit('01'))).toBe('Every day');
    expect(scheduleLabel(habit('01', { schedule: 'TIMES_PER_WEEK', timesPerWeek: 3 }))).toBe(
      '3× a week',
    );
    expect(
      scheduleLabel(
        habit('01', { schedule: 'WEEKDAYS', weekdays: ['FRIDAY', 'MONDAY'], targetCount: 2 }),
      ),
    ).toBe('Mon, Fri, 2× a day');
  });

  it('validates the draft', () => {
    expect(draftProblem(emptyDraft())).toMatch(/name/);
    const draft = { ...emptyDraft(), name: 'Run' };
    expect(draftProblem(draft)).toBeNull();
    expect(draftProblem({ ...draft, schedule: 'WEEKDAYS', weekdays: [] })).toMatch(/day/);
    expect(draftProblem({ ...draft, schedule: 'TIMES_PER_WEEK', timesPerWeek: 8 })).toMatch(/1/);
    expect(draftProblem({ ...draft, targetCount: NaN })).toMatch(/Times a day/);
  });

  it('sends only the fields of the schedule, and an empty reminder as null', () => {
    const draft = { ...emptyDraft(), name: '  Run ', schedule: 'TIMES_PER_WEEK' as const };
    expect(requestOf(draft)).toEqual({
      name: 'Run',
      schedule: 'TIMES_PER_WEEK',
      targetCount: 1,
      timesPerWeek: 3,
      reminderTime: null,
    });
    expect(
      requestOf({
        ...draft,
        schedule: 'WEEKDAYS',
        weekdays: ['FRIDAY', 'MONDAY'],
        reminderTime: '07:30',
      }),
    ).toEqual({
      name: 'Run',
      schedule: 'WEEKDAYS',
      targetCount: 1,
      weekdays: ['MONDAY', 'FRIDAY'],
      reminderTime: '07:30',
    });
  });

  it('fills the draft from a habit', () => {
    expect(
      draftOf(habit('01', { name: 'Stretch', reminderTime: '07:30:00', targetCount: 2 })),
    ).toEqual({
      name: 'Stretch',
      schedule: 'DAILY',
      timesPerWeek: 3,
      weekdays: ['MONDAY', 'WEDNESDAY', 'FRIDAY'],
      targetCount: 2,
      reminderTime: '07:30',
    });
  });

  it('keeps the days and times a week of the habit in its draft', () => {
    const gym = habit('01', { schedule: 'WEEKDAYS', weekdays: ['TUESDAY', 'SATURDAY'] });
    expect(draftOf(gym)).toMatchObject({ schedule: 'WEEKDAYS', weekdays: ['TUESDAY', 'SATURDAY'] });
    // A copy: editing the draft does not change the habit.
    draftOf(gym).weekdays.push('SUNDAY');
    expect(gym.weekdays).toEqual(['TUESDAY', 'SATURDAY']);

    const run = habit('02', { schedule: 'TIMES_PER_WEEK', timesPerWeek: 5 });
    expect(draftOf(run)).toMatchObject({ schedule: 'TIMES_PER_WEEK', timesPerWeek: 5 });
  });
});
