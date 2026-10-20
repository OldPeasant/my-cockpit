import { addDays, localDate } from './dates';

describe('localDate', () => {
  it('formats the local date with zero-padded month and day', () => {
    expect(localDate(new Date(2026, 0, 5, 23, 59))).toBe('2026-01-05');
    expect(localDate(new Date(2026, 11, 31, 0, 0))).toBe('2026-12-31');
  });

  it('defaults to today', () => {
    expect(localDate()).toBe(localDate(new Date()));
  });
});

describe('addDays', () => {
  it('adds days within a month', () => {
    expect(addDays('2026-10-20', 6)).toBe('2026-10-26');
    expect(addDays('2026-10-20', 0)).toBe('2026-10-20');
  });

  it('crosses month and year ends', () => {
    expect(addDays('2026-10-28', 6)).toBe('2026-11-03');
    expect(addDays('2026-12-29', 6)).toBe('2027-01-04');
  });

  it('handles leap years', () => {
    expect(addDays('2028-02-28', 1)).toBe('2028-02-29');
    expect(addDays('2026-02-28', 1)).toBe('2026-03-01');
  });

  it('subtracts with negative days', () => {
    expect(addDays('2026-03-01', -1)).toBe('2026-02-28');
  });

  it('counts calendar days across a daylight saving change', () => {
    // Europe and the US switch clocks around these dates; the result is a calendar date either way.
    expect(addDays('2026-10-22', 6)).toBe('2026-10-28');
    expect(addDays('2026-03-26', 6)).toBe('2026-04-01');
  });
});
