import { daySpans, hiddenEntries, scrollTargetFor } from './hidden-entries';

describe('hidden entries', () => {
  const span = (top: number, bottom: number) => ({ top, bottom });

  it('counts entries entirely above and below the visible part', () => {
    const spans = [span(0, 50), span(60, 100), span(100, 150), span(390, 420), span(400, 500)];
    // Visible: 100..400.
    const hidden = hiddenEntries(spans, 100, 300);
    expect(hidden.above).toBe(2);
    expect(hidden.below).toBe(1);
    expect(hidden.nearestAbove).toEqual(span(60, 100));
    expect(hidden.nearestBelow).toEqual(span(400, 500));
  });

  it('finds the nearest hidden entries whatever their order', () => {
    // Nearest first: later entries farther away must not replace them.
    const hidden = hiddenEntries(
      [span(60, 100), span(0, 50), span(400, 500), span(600, 700)],
      100,
      300,
    );
    expect(hidden.above).toBe(2);
    expect(hidden.below).toBe(2);
    expect(hidden.nearestAbove).toEqual(span(60, 100));
    expect(hidden.nearestBelow).toEqual(span(400, 500));
  });

  it('counts partly visible entries as visible', () => {
    const hidden = hiddenEntries([span(50, 101), span(399, 450)], 100, 300);
    expect(hidden).toEqual({
      above: 0,
      below: 0,
      nearestAbove: undefined,
      nearestBelow: undefined,
    });
  });

  it('finds nothing hidden without entries', () => {
    expect(hiddenEntries([], 0, 300)).toEqual({
      above: 0,
      below: 0,
      nearestAbove: undefined,
      nearestBelow: undefined,
    });
  });

  it('picks the nearest hidden entry regardless of order', () => {
    const hidden = hiddenEntries(
      [span(700, 800), span(0, 10), span(500, 520), span(20, 40)],
      100,
      300,
    );
    expect(hidden.nearestAbove).toEqual(span(20, 40));
    expect(hidden.nearestBelow).toEqual(span(500, 520));
  });

  it('scrolls an entry above to its top and one below to its bottom', () => {
    expect(scrollTargetFor(span(60, 100), 'above', 300)).toBe(60);
    expect(scrollTargetFor(span(500, 550), 'below', 300)).toBe(250);
    // Higher than the visible part: its top.
    expect(scrollTargetFor(span(500, 900), 'below', 300)).toBe(500);
    expect(scrollTargetFor(span(-5, 10), 'above', 300)).toBe(0);
  });

  describe('day spans', () => {
    const zone = 'Europe/Zurich';
    // 1440 px for 24 h: one pixel per minute.
    const spans = (entries: { start: string; end?: string | null }[], date = '2026-03-24') =>
      daySpans(entries, date, zone, 1440);

    it('places an entry by its wall-clock time in the zone', () => {
      // 09:30-10:15 in Zurich (UTC+1 in March before DST).
      expect(spans([{ start: '2026-03-24T08:30:00Z', end: '2026-03-24T09:15:00Z' }])).toEqual([
        span(570, 615),
      ]);
    });

    it('cuts an entry crossing midnight at the day edges', () => {
      const night = { start: '2026-03-23T21:00:00Z', end: '2026-03-24T01:00:00Z' }; // 22:00-02:00
      expect(spans([night], '2026-03-23')).toEqual([span(1320, 1440)]);
      expect(spans([night], '2026-03-24')).toEqual([span(0, 120)]);
    });

    it('leaves out entries of other days and one ending right at midnight', () => {
      const evening = { start: '2026-03-23T22:00:00Z', end: '2026-03-23T23:00:00Z' }; // 23:00-24:00
      expect(spans([evening], '2026-03-24')).toEqual([]);
      expect(spans([evening], '2026-03-23')).toEqual([span(1380, 1440)]);
      expect(spans([{ start: '2026-03-26T08:00:00Z', end: '2026-03-26T09:00:00Z' }])).toEqual([]);
    });

    it('gives an entry without duration the height of a minute', () => {
      expect(spans([{ start: '2026-03-24T08:00:00Z' }])).toEqual([span(540, 541)]);
      expect(spans([{ start: '2026-03-24T08:00:00Z', end: '2026-03-24T08:00:00Z' }])).toEqual([
        span(540, 541),
      ]);
    });

    it('scales to the grid height', () => {
      expect(
        daySpans(
          [{ start: '2026-03-24T11:00:00Z', end: '2026-03-24T13:00:00Z' }],
          '2026-03-24',
          zone,
          720,
        ),
      ).toEqual([span(360, 420)]);
    });
  });
});
