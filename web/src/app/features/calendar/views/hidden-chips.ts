import { daySpans, hiddenEntries, scrollTargetFor, TimedEntry } from './hidden-entries';

/** A chip at the top or bottom edge of a day column, for the entries scrolled out of view. */
export interface HiddenChip {
  readonly key: string;
  readonly direction: 'above' | 'below';
  readonly count: number;
  /** Position relative to the container the chips are placed in, in pixels. */
  readonly left: number;
  readonly width: number;
  readonly top: number;
  /** The scroll offset that brings the nearest hidden entry into view. */
  readonly target: number;
}

/** The chip height in pixels (also in the SCSS). */
export const CHIP_HEIGHT = 22;
const CHIP_GAP = 2;

/**
 * The vertical scroller of FullCalendar's time grid in `root`, and its day columns (the tallest
 * element with a `data-date`; the header cells are short), or undefined outside the time grid.
 */
export function timeGridOf(
  root: HTMLElement,
): { scroller: HTMLElement; columns: Map<string, HTMLElement> } | undefined {
  const columns = new Map<string, HTMLElement>();
  for (const el of Array.from(root.querySelectorAll<HTMLElement>('full-calendar [data-date]'))) {
    const date = el.dataset['date'];
    if (!date) continue;
    const known = columns.get(date);
    if (!known || el.offsetHeight > known.offsetHeight) columns.set(date, el);
  }
  const first = columns.values().next().value;
  let scroller = first?.parentElement ?? null;
  while (scroller && scroller !== root) {
    const overflow = getComputedStyle(scroller).overflowY;
    if (overflow === 'auto' || overflow === 'scroll') break;
    scroller = scroller.parentElement;
  }
  if (!scroller || scroller === root || !first || !scroller.contains(first)) return undefined;
  return { scroller, columns };
}

/** The chips for the `entries` (timed, of the shown days) hidden above or below the grid's visible part. */
export function hiddenChips(
  container: HTMLElement,
  scroller: HTMLElement,
  columns: ReadonlyMap<string, HTMLElement>,
  entries: readonly TimedEntry[],
  timeZone: string,
): HiddenChip[] {
  const box = container.getBoundingClientRect();
  const view = scroller.getBoundingClientRect();
  const viewportHeight = scroller.clientHeight;
  if (viewportHeight <= 0) return [];
  const chips: HiddenChip[] = [];
  for (const [date, column] of columns) {
    const rect = column.getBoundingClientRect();
    // The column's top in the scrolled content.
    const offset = rect.top - view.top + scroller.scrollTop;
    const spans = daySpans(entries, date, timeZone, rect.height).map((span) => ({
      top: span.top + offset,
      bottom: span.bottom + offset,
    }));
    const hidden = hiddenEntries(spans, scroller.scrollTop, viewportHeight);
    const left = rect.left - box.left + CHIP_GAP;
    const width = Math.max(0, rect.width - 2 * CHIP_GAP);
    if (hidden.nearestAbove)
      chips.push({
        key: `${date}:above`,
        direction: 'above',
        count: hidden.above,
        left,
        width,
        top: view.top - box.top + CHIP_GAP,
        target: scrollTargetFor(hidden.nearestAbove, 'above', viewportHeight),
      });
    if (hidden.nearestBelow)
      chips.push({
        key: `${date}:below`,
        direction: 'below',
        count: hidden.below,
        left,
        width,
        top: view.top - box.top + viewportHeight - CHIP_HEIGHT - CHIP_GAP,
        target: scrollTargetFor(hidden.nearestBelow, 'below', viewportHeight),
      });
  }
  return chips;
}
