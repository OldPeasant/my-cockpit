import {
  addDays,
  addHours,
  instantOf,
  isTimeZone,
  nextFullHour,
  timeZones,
  wallClockOf,
} from './event-times';

describe('event times', () => {
  it('shows an instant as date and time in a zone', () => {
    expect(wallClockOf('2026-03-23T08:00:00Z', 'Europe/Zurich')).toEqual({
      date: '2026-03-23',
      time: '09:00',
    });
    expect(wallClockOf(new Date('2026-03-23T08:00:00Z'), 'Asia/Tokyo')).toEqual({
      date: '2026-03-23',
      time: '17:00',
    });
    expect(wallClockOf('2026-03-23T08:00:00Z', 'America/New_York')).toEqual({
      date: '2026-03-23',
      time: '04:00',
    });
  });

  it('shows midnight as 00:00 of the next day, not 24:00', () => {
    expect(wallClockOf('2026-03-22T23:00:00Z', 'Europe/Zurich')).toEqual({
      date: '2026-03-23',
      time: '00:00',
    });
  });

  it('converts a wall-clock time in a zone to its instant, in winter and in summer', () => {
    expect(instantOf('2026-03-23', '09:00', 'Europe/Zurich')).toBe('2026-03-23T08:00:00.000Z');
    expect(instantOf('2026-07-01', '09:00', 'Europe/Zurich')).toBe('2026-07-01T07:00:00.000Z');
    expect(instantOf('2026-03-23', '09:00', 'UTC')).toBe('2026-03-23T09:00:00.000Z');
    expect(instantOf('2026-01-01', '00:30', 'Asia/Tokyo')).toBe('2025-12-31T15:30:00.000Z');
  });

  it('resolves a time skipped by the spring DST change to the same distance after it', () => {
    // Zurich 2026-03-29: clocks jump from 02:00 to 03:00 (01:00Z).
    expect(instantOf('2026-03-29', '02:30', 'Europe/Zurich')).toBe('2026-03-29T01:30:00.000Z');
    expect(wallClockOf('2026-03-29T01:30:00.000Z', 'Europe/Zurich').time).toBe('03:30');
    // Right before and after the change.
    expect(instantOf('2026-03-29', '01:59', 'Europe/Zurich')).toBe('2026-03-29T00:59:00.000Z');
    expect(instantOf('2026-03-29', '03:00', 'Europe/Zurich')).toBe('2026-03-29T01:00:00.000Z');
  });

  it('resolves a skipped time to after the change in zones west of UTC too', () => {
    // New York 2026-03-08: clocks jump from 02:00 EST to 03:00 EDT (07:00Z).
    expect(instantOf('2026-03-08', '02:30', 'America/New_York')).toBe('2026-03-08T07:30:00.000Z');
    expect(wallClockOf('2026-03-08T07:30:00.000Z', 'America/New_York').time).toBe('03:30');
    expect(instantOf('2026-03-08', '02:00', 'America/New_York')).toBe('2026-03-08T07:00:00.000Z');
    expect(instantOf('2026-03-08', '01:59', 'America/New_York')).toBe('2026-03-08T06:59:00.000Z');
    expect(instantOf('2026-03-08', '03:00', 'America/New_York')).toBe('2026-03-08T07:00:00.000Z');
    // The same rule as in Zurich: 02:30 becomes 03:30 local time.
    expect(wallClockOf(instantOf('2026-03-29', '02:30', 'Europe/Zurich'), 'Europe/Zurich')).toEqual(
      { date: '2026-03-29', time: '03:30' },
    );
  });

  it('resolves a repeated time in New York to its first occurrence', () => {
    // 2026-11-01: clocks go back from 02:00 EDT to 01:00 EST (06:00Z).
    expect(instantOf('2026-11-01', '01:30', 'America/New_York')).toBe('2026-11-01T05:30:00.000Z');
    expect(instantOf('2026-11-01', '02:00', 'America/New_York')).toBe('2026-11-01T07:00:00.000Z');
  });

  it('round-trips every quarter hour of a New York DST change day except the skipped hour', () => {
    for (let minutes = 0; minutes < 24 * 60; minutes += 15) {
      const time = `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
      if (time >= '02:00' && time < '03:00') continue;
      const back = wallClockOf(
        instantOf('2026-03-08', time, 'America/New_York'),
        'America/New_York',
      );
      expect(back).toEqual({ date: '2026-03-08', time });
    }
  });

  it('finds the next full hour on the clocks of a zone', () => {
    expect(nextFullHour('2026-03-23T08:10:00Z', 'Europe/Zurich')).toBe('2026-03-23T09:00:00.000Z');
    // Kolkata is UTC+05:30: its full hours are at :30 UTC.
    expect(nextFullHour('2026-03-23T08:10:00Z', 'Asia/Kolkata')).toBe('2026-03-23T08:30:00.000Z');
    expect(nextFullHour('2026-03-23T08:40:00Z', 'Asia/Kolkata')).toBe('2026-03-23T09:30:00.000Z');
    // Right on the hour: the next one.
    expect(nextFullHour('2026-03-23T09:00:00Z', 'UTC')).toBe('2026-03-23T10:00:00.000Z');
    // Across midnight and into the next day.
    expect(nextFullHour('2026-03-22T22:59:00Z', 'Europe/Zurich')).toBe('2026-03-22T23:00:00.000Z');
    // Across the skipped hour of a DST change (01:30 EST: next is 03:00 EDT).
    expect(nextFullHour('2026-03-08T06:30:00Z', 'America/New_York')).toBe(
      '2026-03-08T07:00:00.000Z',
    );
  });

  it('resolves a time repeated by the autumn DST change to its first occurrence', () => {
    // Zurich 2026-10-25: clocks go back from 03:00 CEST to 02:00 CET (01:00Z).
    expect(instantOf('2026-10-25', '02:30', 'Europe/Zurich')).toBe('2026-10-25T00:30:00.000Z');
    expect(instantOf('2026-10-25', '03:00', 'Europe/Zurich')).toBe('2026-10-25T02:00:00.000Z');
    expect(instantOf('2026-10-25', '01:59', 'Europe/Zurich')).toBe('2026-10-24T23:59:00.000Z');
  });

  it('round-trips every quarter hour of a DST change day except the skipped hour', () => {
    for (let minutes = 0; minutes < 24 * 60; minutes += 15) {
      const time = `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
      const back = wallClockOf(instantOf('2026-03-29', time, 'Europe/Zurich'), 'Europe/Zurich');
      if (time >= '02:00' && time < '03:00') continue;
      expect(back).toEqual({ date: '2026-03-29', time });
    }
  });

  it('moves dates by days across months, years and leap days', () => {
    expect(addDays('2026-03-23', 1)).toBe('2026-03-24');
    expect(addDays('2026-12-31', 1)).toBe('2027-01-01');
    expect(addDays('2026-03-01', -1)).toBe('2026-02-28');
    expect(addDays('2028-02-28', 1)).toBe('2028-02-29');
    expect(addDays('2026-03-23', 0)).toBe('2026-03-23');
  });

  it('moves instants by hours', () => {
    expect(addHours('2026-03-23T08:00:00Z', 1)).toBe('2026-03-23T09:00:00.000Z');
    expect(addHours(new Date('2026-03-23T23:30:00Z'), 1)).toBe('2026-03-24T00:30:00.000Z');
    expect(addHours('2026-03-23T08:00:00Z', -2)).toBe('2026-03-23T06:00:00.000Z');
  });

  it('accepts only zones the browser knows', () => {
    expect(isTimeZone('Europe/Zurich')).toBe(true);
    expect(isTimeZone('UTC')).toBe(true);
    expect(isTimeZone('')).toBe(false);
    expect(isTimeZone('Mars/Olympus_Mons')).toBe(false);
  });

  it('lists the known zones, or none without Intl.supportedValuesOf', () => {
    expect(timeZones()).toContain('Europe/Zurich');
    const intl = Intl as { supportedValuesOf?: unknown };
    const original = intl.supportedValuesOf;
    Object.defineProperty(Intl, 'supportedValuesOf', { value: undefined, configurable: true });
    try {
      expect(timeZones()).toEqual([]);
    } finally {
      Object.defineProperty(Intl, 'supportedValuesOf', { value: original, configurable: true });
    }
  });
});
