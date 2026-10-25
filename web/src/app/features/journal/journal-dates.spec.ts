import { addDays, excerpt, heatmapWeeks, isDate, isoWeekday, parseTags } from './journal-dates';

describe('journal dates', () => {
  describe('isoWeekday', () => {
    it('maps the week start of the settings to an ISO weekday', () => {
      expect(isoWeekday('MONDAY')).toBe(1);
      expect(isoWeekday('SATURDAY')).toBe(6);
      expect(isoWeekday('SUNDAY')).toBe(7);
    });

    it('falls back to Monday for a missing or unknown week start', () => {
      expect(isoWeekday(undefined)).toBe(1);
      expect(isoWeekday('')).toBe(1);
      expect(isoWeekday('sunday')).toBe(1);
    });
  });

  describe('isDate', () => {
    it('accepts real calendar dates', () => {
      expect(isDate('2026-10-25')).toBe(true);
      expect(isDate('2024-02-29')).toBe(true);
    });

    it('rejects impossible dates and other formats', () => {
      expect(isDate('2026-02-29')).toBe(false);
      expect(isDate('2026-13-01')).toBe(false);
      expect(isDate('2026-04-31')).toBe(false);
      expect(isDate('2026-10-5')).toBe(false);
      expect(isDate('25.10.2026')).toBe(false);
      expect(isDate('2026-10-25T00:00')).toBe(false);
      expect(isDate('')).toBe(false);
      expect(isDate('today')).toBe(false);
    });
  });

  describe('addDays', () => {
    it('moves across months, years and leap days', () => {
      expect(addDays('2026-10-25', 1)).toBe('2026-10-26');
      expect(addDays('2026-10-31', 1)).toBe('2026-11-01');
      expect(addDays('2026-01-01', -1)).toBe('2025-12-31');
      expect(addDays('2024-02-28', 1)).toBe('2024-02-29');
      expect(addDays('2026-03-01', -365)).toBe('2025-03-01');
      expect(addDays('2026-10-25', 0)).toBe('2026-10-25');
    });

    it('is not shifted by a daylight saving change', () => {
      // 2026-10-25 is the end of summer time in Europe.
      expect(addDays('2026-10-24', 1)).toBe('2026-10-25');
      expect(addDays('2026-03-29', -1)).toBe('2026-03-28');
    });
  });

  describe('heatmapWeeks', () => {
    it('pads the first and last weeks to whole weeks from Monday', () => {
      // 2026-10-07 is a Wednesday, 2026-10-20 a Tuesday.
      const weeks = heatmapWeeks('2026-10-07', '2026-10-20', [], 1);
      expect(weeks).toHaveLength(3);
      expect(weeks.every((week) => week.cells.length === 7)).toBe(true);
      expect(weeks[0].cells[0]).toEqual({
        date: '2026-10-05',
        inRange: false,
        hasEntry: false,
        mood: null,
      });
      expect(weeks[0].cells[2]).toMatchObject({ date: '2026-10-07', inRange: true });
      expect(weeks[2].cells[1]).toMatchObject({ date: '2026-10-20', inRange: true });
      expect(weeks[2].cells[2]).toMatchObject({ date: '2026-10-21', inRange: false });
      expect(weeks[2].cells[6].date).toBe('2026-10-25');
    });

    it('starts the weeks on the configured day', () => {
      // Sunday start: the week of Wednesday 2026-10-07 begins on Sunday 2026-10-04.
      expect(heatmapWeeks('2026-10-07', '2026-10-07', [], 7)[0].cells[0].date).toBe('2026-10-04');
      // A range that starts on the week start needs no padding.
      expect(heatmapWeeks('2026-10-05', '2026-10-11', [], 1)).toHaveLength(1);
      expect(heatmapWeeks('2026-10-05', '2026-10-11', [], 1)[0].cells[0]).toMatchObject({
        date: '2026-10-05',
        inRange: true,
      });
    });

    it('marks the days with an entry and their mood, only in range', () => {
      const weeks = heatmapWeeks(
        '2026-10-07',
        '2026-10-11',
        [
          { date: '2026-10-07', mood: 5 },
          { date: '2026-10-08' },
          // Outside the range (in the padding): not shown as an entry.
          { date: '2026-10-05', mood: 2 },
        ],
        1,
      );
      const cells = weeks[0].cells;
      expect(cells[0]).toMatchObject({ date: '2026-10-05', hasEntry: false, mood: null });
      expect(cells[2]).toMatchObject({ date: '2026-10-07', hasEntry: true, mood: 5 });
      expect(cells[3]).toMatchObject({ date: '2026-10-08', hasEntry: true, mood: null });
      expect(cells[4]).toMatchObject({ date: '2026-10-09', hasEntry: false, mood: null });
    });

    it('names the month of the first week and of each week a month begins in', () => {
      const weeks = heatmapWeeks('2026-09-20', '2026-11-03', [], 1);
      const months = weeks.map((week) => week.month).filter((month) => month !== null);
      expect(months).toEqual(['Sep', 'Oct', 'Nov']);
      // 2026-10-01 is a Thursday: its week (from Monday 2026-09-28) carries "Oct".
      expect(weeks.find((week) => week.month === 'Oct')?.cells[0].date).toBe('2026-09-28');
    });

    it('uses the locale for month names', () => {
      // The exact short name depends on the ICU data ("Mär" or "März").
      const month = heatmapWeeks('2026-03-02', '2026-03-08', [], 1, 'de')[0].month;
      expect(month).toMatch(/^Mär/);
      expect(heatmapWeeks('2026-03-02', '2026-03-08', [], 1)[0].month).toBe('Mar');
    });

    it('returns a single week for a one-day range and nothing for a reversed one', () => {
      const one = heatmapWeeks('2026-10-25', '2026-10-25', [{ date: '2026-10-25', mood: 3 }], 1);
      expect(one).toHaveLength(1);
      expect(one[0].cells.filter((cell) => cell.inRange)).toEqual([
        { date: '2026-10-25', inRange: true, hasEntry: true, mood: 3 },
      ]);
      expect(heatmapWeeks('2026-10-26', '2026-10-25', [], 1)).toEqual([]);
    });

    it('covers a whole year in 53 or 54 weeks', () => {
      const weeks = heatmapWeeks('2025-10-26', '2026-10-25', [], 1);
      expect(weeks.length).toBeGreaterThanOrEqual(53);
      expect(weeks.length).toBeLessThanOrEqual(54);
      expect(weeks.flatMap((week) => week.cells).filter((cell) => cell.inRange)).toHaveLength(365);
    });
  });

  describe('excerpt', () => {
    it('turns Markdown into one line of plain text', () => {
      expect(
        excerpt(
          '# Lake day\n\n- [x] **Swim** in the _cold_ water\n> Quote\n1. First\n`code` ~~old~~',
        ),
      ).toBe('Lake day Swim in the cold water Quote First code old');
    });

    it('drops images and inline links, and keeps the text of links', () => {
      expect(
        excerpt(
          'Before ![tent](cockpit-file:0192f0c4-0000-7000-8000-0000000000f1) see ' +
            '[the map](https://example.test/map) and [[task:0192f0c4-0000-7000-8000-0000000000a1]] after',
        ),
      ).toBe('Before see the map and after');
    });

    it('shortens long text with an ellipsis', () => {
      const long = 'word '.repeat(100);
      const short = excerpt(long);
      expect(short.length).toBeLessThanOrEqual(160);
      expect(short.endsWith('…')).toBe(true);
      expect(excerpt('abcdefghij', 5)).toBe('abcd…');
      expect(excerpt('abcde', 5)).toBe('abcde');
    });

    it('returns an empty text for an empty or image-only body', () => {
      expect(excerpt('')).toBe('');
      expect(excerpt('   \n ')).toBe('');
      expect(excerpt('![x](cockpit-file:0192f0c4-0000-7000-8000-0000000000f1)')).toBe('');
    });
  });

  describe('parseTags', () => {
    it('splits at commas, removes a leading # and blanks', () => {
      expect(parseTags('travel, #family ,, lake ')).toEqual(['travel', 'family', 'lake']);
    });

    it('keeps each tag once regardless of case, in the first spelling', () => {
      expect(parseTags('Travel, travel, #TRAVEL, Lake')).toEqual(['Travel', 'Lake']);
    });

    it('returns no tags for empty or whitespace-only text', () => {
      expect(parseTags('')).toEqual([]);
      expect(parseTags('  ,  , # ')).toEqual([]);
    });
  });
});
