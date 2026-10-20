import { TaskResponse } from '../../../api/generated';
import { localDate } from '../data';
import {
  byWaitingSince,
  daysBetween,
  followUpTask,
  groupByCompletedDay,
  groupByScheduledDate,
  relativeDay,
} from './list-views';

function task(id: string, fields: Partial<TaskResponse> = {}): TaskResponse {
  return {
    id,
    title: `Task ${id}`,
    list: 'WAITING',
    position: Number(id.replace(/\D/g, '')) || 1,
    contextIds: [],
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-01T08:00:00Z',
    version: 1,
    ...fields,
  };
}

const ids = (tasks: readonly TaskResponse[]) => tasks.map((t) => t.id);

describe('byWaitingSince', () => {
  it('puts the longest waiting first, undated last, ties by position', () => {
    const sorted = byWaitingSince([
      task('t1', { waitingSince: '2026-10-10' }),
      task('t2'),
      task('t3', { waitingSince: '2026-09-01' }),
      task('t5', { waitingSince: '2026-10-10' }),
      task('t4', { waitingSince: '2026-10-10' }),
    ]);
    expect(ids(sorted)).toEqual(['t3', 't1', 't4', 't5', 't2']);
  });

  it('does not change its input', () => {
    const input = [
      task('t1', { waitingSince: '2026-10-10' }),
      task('t2', { waitingSince: '2026-01-01' }),
    ];
    byWaitingSince(input);
    expect(ids(input)).toEqual(['t1', 't2']);
  });
});

describe('daysBetween', () => {
  it.each([
    ['2026-10-20', '2026-10-20', 0],
    ['2026-10-19', '2026-10-20', 1],
    ['2026-09-30', '2026-10-20', 20],
    // Across the end of daylight saving time in Europe (25-hour day).
    ['2026-10-24', '2026-10-26', 2],
    ['2026-10-21', '2026-10-20', 0],
  ])('%s to %s is %d days', (since, today, days) => {
    expect(daysBetween(since, today)).toBe(days);
  });
});

describe('groupByScheduledDate', () => {
  it('groups by date, earliest first, positions within a date', () => {
    const groups = groupByScheduledDate([
      task('t3', { scheduledDate: '2026-10-22' }),
      task('t2', { scheduledDate: '2026-10-21' }),
      task('t4', { scheduledDate: '2026-10-22' }),
      task('t1', { scheduledDate: '2026-10-22' }),
    ]);
    expect(groups.map((g) => [g.date, ids(g.tasks)])).toEqual([
      ['2026-10-21', ['t2']],
      ['2026-10-22', ['t1', 't3', 't4']],
    ]);
  });

  it('puts tasks without a date into a last group', () => {
    const groups = groupByScheduledDate([task('t1'), task('t2', { scheduledDate: '2026-10-22' })]);
    expect(groups.map((g) => g.date)).toEqual(['2026-10-22', '']);
  });

  it('has no groups for no tasks', () => {
    expect(groupByScheduledDate([])).toEqual([]);
  });
});

describe('groupByCompletedDay', () => {
  it('groups by local completion day, newest day and task first', () => {
    const at = (day: number, hour: number) => new Date(2026, 9, day, hour).toISOString();
    const groups = groupByCompletedDay([
      task('t1', { completedAt: at(18, 9) }),
      task('t2', { completedAt: at(20, 8) }),
      task('t3', { completedAt: at(18, 17) }),
      task('t4', { completedAt: at(20, 23) }),
      task('t5'),
    ]);
    expect(groups.map((g) => [g.date, ids(g.tasks)])).toEqual([
      ['2026-10-20', ['t4', 't2']],
      ['2026-10-18', ['t3', 't1']],
    ]);
  });

  it('uses the browser day, not the UTC day', () => {
    const late = new Date(2026, 9, 20, 23, 59);
    const [group] = groupByCompletedDay([task('t1', { completedAt: late.toISOString() })]);
    expect(group.date).toBe(localDate(late));
  });
});

describe('followUpTask', () => {
  it('names the person waited for and keeps the project', () => {
    const body = followUpTask(
      task('t1', { title: 'Signed lease', waitingForText: ' Alex Example ', projectId: 'p1' }),
    );
    expect(body).toEqual({
      title: 'Follow up with Alex Example: Signed lease',
      list: 'NEXT',
      projectId: 'p1',
    });
  });

  it('treats a blank person as no person', () => {
    expect(followUpTask(task('t1', { title: 'Parcel', waitingForText: '   ' })).title).toBe(
      'Follow up: Parcel',
    );
  });

  it('works without a person', () => {
    expect(followUpTask(task('t1', { title: 'Parcel' }))).toEqual({
      title: 'Follow up: Parcel',
      list: 'NEXT',
      projectId: undefined,
    });
  });
});

describe('relativeDay', () => {
  it.each([
    ['2026-10-19', 'yesterday'],
    ['2026-10-20', 'today'],
    ['2026-10-21', 'tomorrow'],
    ['2026-10-22', undefined],
    ['2026-10-01', undefined],
  ])('%s is %s', (date, expected) => {
    expect(relativeDay(date, '2026-10-20')).toBe(expected);
  });
});
