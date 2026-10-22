import { HabitResponse } from '../../api/generated';

type HabitFields = Partial<Omit<HabitResponse, 'stats'>> & {
  stats?: Partial<HabitResponse['stats']>;
};

/** A fictitious habit for tests: daily, once a day, due and not done today. */
export function habit(id: string, fields: HabitFields = {}): HabitResponse {
  const { stats, ...rest } = fields;
  return {
    id,
    name: `Habit ${id}`,
    schedule: 'DAILY',
    targetCount: 1,
    weekdays: [],
    archived: false,
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-01T08:00:00Z',
    version: 1,
    ...rest,
    stats: {
      today: '2026-10-22',
      todayCount: 0,
      doneToday: false,
      dueToday: true,
      currentStreak: 0,
      longestStreak: 0,
      streakUnit: 'DAYS',
      doneLast30Days: 0,
      dueLast30Days: 21,
      completionRateLast30Days: 0,
      ...stats,
    },
  };
}
