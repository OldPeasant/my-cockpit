import { wallClockOf } from '../edit/event-times';

/** The vertical extent of an entry in a time-grid column, in pixels from the top of the grid. */
export interface Span {
  readonly top: number;
  readonly bottom: number;
}

/** The entries of a column that are entirely outside the visible part of the grid. */
export interface HiddenEntries {
  /** How many entries end at or above the top of the visible part. */
  readonly above: number;
  /** How many entries start at or below the bottom of the visible part. */
  readonly below: number;
  /** The hidden entry above that is nearest to the visible part. */
  readonly nearestAbove?: Span;
  /** The hidden entry below that is nearest to the visible part. */
  readonly nearestBelow?: Span;
}

/**
 * Which of `spans` are entirely above or below the visible part of a scrolled grid, which starts
 * `scrollTop` pixels from the grid's top and is `viewportHeight` pixels high. Partly visible entries
 * count as visible.
 */
export function hiddenEntries(
  spans: readonly Span[],
  scrollTop: number,
  viewportHeight: number,
): HiddenEntries {
  const visibleBottom = scrollTop + viewportHeight;
  let above = 0;
  let below = 0;
  let nearestAbove: Span | undefined;
  let nearestBelow: Span | undefined;
  for (const span of spans) {
    if (span.bottom <= scrollTop) {
      above++;
      if (!nearestAbove || span.bottom > nearestAbove.bottom) nearestAbove = span;
    } else if (span.top >= visibleBottom) {
      below++;
      if (!nearestBelow || span.top < nearestBelow.top) nearestBelow = span;
    }
  }
  return { above, below, nearestAbove, nearestBelow };
}

/**
 * The scroll offset that brings `span` into view: its top at the top of the visible part when
 * scrolling up, its bottom at the bottom when scrolling down (its top when it is higher than the
 * visible part).
 */
export function scrollTargetFor(
  span: Span,
  direction: 'above' | 'below',
  viewportHeight: number,
): number {
  if (direction === 'above') return Math.max(0, span.top);
  return Math.max(0, Math.min(span.top, span.bottom - viewportHeight));
}

/** A timed entry: start and end instants (ISO-8601). */
export interface TimedEntry {
  readonly start: string;
  readonly end?: string | null;
}

const DAY_MINUTES = 24 * 60;

/**
 * The spans of the timed `entries` in the column of `date` (`yyyy-MM-dd` in `timeZone`) of a grid
 * `gridHeight` pixels high for 24 hours. An entry crossing midnight is cut at the day's edges;
 * entries not on `date` are left out. An entry without end, or ending at its start, is as high as
 * a minute would be.
 */
export function daySpans(
  entries: readonly TimedEntry[],
  date: string,
  timeZone: string,
  gridHeight: number,
): Span[] {
  const pxPerMinute = gridHeight / DAY_MINUTES;
  const spans: Span[] = [];
  for (const entry of entries) {
    const start = minutesOn(entry.start, date, timeZone);
    const end = entry.end ? minutesOn(entry.end, date, timeZone) : start;
    if (end < 0 || start >= DAY_MINUTES || (end === 0 && start < 0)) continue;
    const from = Math.max(0, start);
    const to = Math.min(DAY_MINUTES, Math.max(end, from + 1));
    spans.push({ top: from * pxPerMinute, bottom: to * pxPerMinute });
  }
  return spans;
}

/**
 * The minutes from the start of `date` to `instant` on the clocks of `timeZone`: negative before
 * the day, 1440 and more after it.
 */
function minutesOn(instant: string, date: string, timeZone: string): number {
  const wall = wallClockOf(instant, timeZone);
  const [hours, minutes] = wall.time.split(':').map(Number);
  const days = Math.round(
    (Date.parse(`${wall.date}T00:00:00Z`) - Date.parse(`${date}T00:00:00Z`)) / 86_400_000,
  );
  return days * DAY_MINUTES + hours * 60 + minutes;
}
