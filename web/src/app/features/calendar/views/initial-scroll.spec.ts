import { initialScrollTime } from './initial-scroll';

const ZONE = 'Europe/Zurich';
// Week of Monday 2026-03-23 (CET, UTC+1) and a day long before it.
const WEEK = { from: '2026-03-23', to: '2026-03-29' };
const OUTSIDE = new Date('2026-01-10T12:00:00Z');

describe('initialScrollTime', () => {
  it('scrolls to the full hour at or before the earliest start', () => {
    const starts = ['2026-03-25T13:20:00Z', '2026-03-24T15:00:00Z'];
    expect(initialScrollTime(starts, WEEK, ZONE, OUTSIDE)).toBe('14:00:00');
  });

  it('keeps a start on the full hour', () => {
    expect(initialScrollTime(['2026-03-23T08:00:00Z'], WEEK, ZONE, OUTSIDE)).toBe('09:00:00');
  });

  it('uses the wall clock of the zone, not UTC', () => {
    // 23:30 UTC on Sunday is 00:30 on Monday in Zurich.
    expect(initialScrollTime(['2026-03-22T23:30:00Z'], WEEK, ZONE, OUTSIDE)).toBe('00:00:00');
  });

  it('ignores entries that started before the period (continuing past midnight)', () => {
    // Sunday 22:00 Zurich, before the week.
    const starts = ['2026-03-22T21:00:00Z', '2026-03-24T09:00:00Z'];
    expect(initialScrollTime(starts, WEEK, ZONE, OUTSIDE)).toBe('10:00:00');
  });

  it('ignores entries after the period', () => {
    const starts = ['2026-03-30T05:00:00Z', '2026-03-24T09:00:00Z'];
    expect(initialScrollTime(starts, WEEK, ZONE, OUTSIDE)).toBe('10:00:00');
  });

  it('returns the last hour for an entry near midnight (FullCalendar clamps to the end)', () => {
    expect(initialScrollTime(['2026-03-24T22:45:00Z'], WEEK, ZONE, OUTSIDE)).toBe('23:00:00');
  });

  it('scrolls to the full hour before now when today is shown and nothing is timed', () => {
    const now = new Date('2026-03-25T15:42:00Z'); // 16:42 Zurich
    expect(initialScrollTime([], WEEK, ZONE, now)).toBe('16:00:00');
  });

  it('scrolls to 07:00 when nothing is timed and today is not shown', () => {
    expect(initialScrollTime([], WEEK, ZONE, OUTSIDE)).toBe('07:00:00');
  });

  it('prefers an entry over the current time', () => {
    const now = new Date('2026-03-25T15:42:00Z');
    expect(initialScrollTime(['2026-03-26T17:00:00Z'], WEEK, ZONE, now)).toBe('18:00:00');
  });

  it('works for a single day', () => {
    const day = { from: '2026-03-24', to: '2026-03-24' };
    const starts = ['2026-03-23T06:00:00Z', '2026-03-24T11:10:00Z'];
    expect(initialScrollTime(starts, day, ZONE, OUTSIDE)).toBe('12:00:00');
  });
});
