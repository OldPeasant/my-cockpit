import { CHIP_HEIGHT, hiddenChips, timeGridOf } from './hidden-chips';
import { TimedEntry } from './hidden-entries';

/** A DOMRect-like box (jsdom lays out nothing, so the tests stub the boxes). */
function box(left: number, top: number, width: number, height: number): DOMRect {
  return {
    left,
    top,
    width,
    height,
    x: left,
    y: top,
    right: left + width,
    bottom: top + height,
    toJSON: () => ({}),
  } as DOMRect;
}

function sized(el: HTMLElement, offsetHeight: number): HTMLElement {
  Object.defineProperty(el, 'offsetHeight', { configurable: true, value: offsetHeight });
  return el;
}

function withDate(date: string, offsetHeight: number): HTMLElement {
  const el = document.createElement('div');
  el.dataset['date'] = date;
  return sized(el, offsetHeight);
}

/** Fictitious timed entries on 2026-03-23 in Zurich (CET, UTC+1). */
const early: TimedEntry = { start: '2026-03-23T01:00:00Z', end: '2026-03-23T02:00:00Z' }; // 02:00-03:00
const morning: TimedEntry = { start: '2026-03-23T07:00:00Z', end: '2026-03-23T08:00:00Z' }; // 08:00-09:00
const evening: TimedEntry = { start: '2026-03-23T19:00:00Z', end: '2026-03-23T20:00:00Z' }; // 20:00-21:00
const nextDay: TimedEntry = { start: '2026-03-24T01:00:00Z', end: '2026-03-24T02:00:00Z' }; // 02:00-03:00

describe('timeGridOf', () => {
  let root: HTMLElement;

  beforeEach(() => {
    root = document.createElement('div');
    document.body.append(root);
  });
  afterEach(() => root.remove());

  /** A fake FullCalendar time grid: header cells (short) and day columns (tall) inside a scroller. */
  function grid(overflowY = 'auto') {
    const calendar = document.createElement('full-calendar');
    const header = document.createElement('div');
    header.append(withDate('2026-03-23', 20), withDate('2026-03-24', 20));
    const scroller = document.createElement('div');
    scroller.style.overflowY = overflowY;
    const body = document.createElement('div');
    const monday = withDate('2026-03-23', 1440);
    const tuesday = withDate('2026-03-24', 1440);
    body.append(monday, tuesday);
    scroller.append(body);
    calendar.append(header, scroller);
    root.append(calendar);
    return { calendar, scroller, monday, tuesday };
  }

  it('finds the scroller and the tall day column of each date, not the header cells', () => {
    const { scroller, monday, tuesday } = grid();
    const found = timeGridOf(root)!;
    expect(found.scroller).toBe(scroller);
    expect([...found.columns.entries()]).toEqual([
      ['2026-03-23', monday],
      ['2026-03-24', tuesday],
    ]);
  });

  it('accepts a scroller with overflow-y scroll', () => {
    const { scroller } = grid('scroll');
    expect(timeGridOf(root)?.scroller).toBe(scroller);
  });

  it('ignores elements with an empty data-date and outside full-calendar', () => {
    const { calendar, monday } = grid();
    calendar.prepend(withDate('', 5000));
    root.prepend(withDate('2026-03-23', 9999));
    const found = timeGridOf(root)!;
    expect(found.columns.get('2026-03-23')).toBe(monday);
    expect(found.columns.has('')).toBe(false);
  });

  it('is undefined outside the time grid (no dated cells)', () => {
    root.append(document.createElement('full-calendar'));
    expect(timeGridOf(root)).toBeUndefined();
  });

  it('is undefined when no element between the columns and the root scrolls vertically', () => {
    // The month view: dated cells without a vertical scroller.
    grid('hidden');
    expect(timeGridOf(root)).toBeUndefined();
  });
});

describe('hiddenChips', () => {
  const zone = 'Europe/Zurich';
  let container: HTMLElement;
  let scroller: HTMLElement;
  let scrollTop: number;
  let clientHeight: number;

  /** A day column of 1440 px (1 px per minute) at the top of the scrolled content, `left` px from the page edge. */
  function column(left = 80, width = 100): HTMLElement {
    const el = document.createElement('div');
    el.getBoundingClientRect = () => box(left, 100 - scrollTop, width, 1440);
    return el;
  }

  beforeEach(() => {
    scrollTop = 420; // 07:00
    clientHeight = 400; // visible 07:00 - 13:40
    container = document.createElement('div');
    container.getBoundingClientRect = () => box(10, 50, 900, 600);
    scroller = document.createElement('div');
    scroller.getBoundingClientRect = () => box(70, 100, 800, clientHeight);
    Object.defineProperty(scroller, 'scrollTop', { get: () => scrollTop });
    Object.defineProperty(scroller, 'clientHeight', { get: () => clientHeight });
  });

  it('places a chip at the top and the bottom edge of the column with counts and scroll targets', () => {
    const chips = hiddenChips(
      container,
      scroller,
      new Map([['2026-03-23', column()]]),
      [early, morning, evening],
      zone,
    );
    expect(chips).toEqual([
      {
        key: '2026-03-23:above',
        direction: 'above',
        count: 1,
        left: 72,
        width: 96,
        top: 52,
        // The top of the 02:00 entry at the top of the visible part.
        target: 120,
      },
      {
        key: '2026-03-23:below',
        direction: 'below',
        count: 1,
        left: 72,
        width: 96,
        top: 50 + 400 - CHIP_HEIGHT - 2,
        // The bottom of the 20:00 entry (1260) at the bottom of the visible part.
        target: 860,
      },
    ]);
  });

  it('counts all hidden entries and targets the nearest one', () => {
    scrollTop = 0; // visible 00:00 - 06:40
    const chips = hiddenChips(
      container,
      scroller,
      new Map([['2026-03-23', column()]]),
      [early, evening, morning],
      zone,
    );
    expect(chips.map((c) => [c.key, c.count, c.target])).toEqual([
      // 08:00-09:00 is nearest: its bottom (540) at the bottom of the visible part.
      ['2026-03-23:below', 2, 140],
    ]);
  });

  it('shows no chip when all entries are visible or partly visible', () => {
    // 02:00-03:00 ends at 180: the visible part starts at 179.
    scrollTop = 179;
    const chips = hiddenChips(
      container,
      scroller,
      new Map([['2026-03-23', column()]]),
      [early, morning],
      zone,
    );
    expect(chips).toEqual([]);
  });

  it('gives each column the chips of its own day only', () => {
    const chips = hiddenChips(
      container,
      scroller,
      new Map([
        ['2026-03-23', column(80)],
        ['2026-03-24', column(180)],
      ]),
      [evening, nextDay],
      zone,
    );
    expect(chips.map((c) => [c.key, c.count, c.left])).toEqual([
      ['2026-03-23:below', 1, 72],
      ['2026-03-24:above', 1, 172],
    ]);
  });

  it('places the columns by their offset in the scrolled content', () => {
    // A column starting 30 px below the top of the scrolled content (e.g. below a spacer).
    const el = document.createElement('div');
    el.getBoundingClientRect = () => box(80, 130 - scrollTop, 100, 1440);
    scrollTop = 0;
    // 02:00-03:00 is at 150..210 in the content: visible (0..400).
    expect(hiddenChips(container, scroller, new Map([['2026-03-23', el]]), [early], zone)).toEqual(
      [],
    );
    // Visible 211..611: entirely above.
    scrollTop = 211;
    const [chip] = hiddenChips(container, scroller, new Map([['2026-03-23', el]]), [early], zone);
    expect(chip).toMatchObject({ direction: 'above', count: 1, target: 150 });
  });

  it('shows nothing while the scroller has no height', () => {
    clientHeight = 0;
    expect(
      hiddenChips(container, scroller, new Map([['2026-03-23', column()]]), [early, evening], zone),
    ).toEqual([]);
  });

  it('never gives a chip a negative width in a very narrow column', () => {
    const [chip] = hiddenChips(
      container,
      scroller,
      new Map([['2026-03-23', column(80, 3)]]),
      [early],
      zone,
    );
    expect(chip.width).toBe(0);
  });
});
