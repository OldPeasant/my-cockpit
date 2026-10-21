/**
 * Wall-clock times in a named time zone, as the event dialog edits them: a date (`yyyy-MM-dd`) and
 * a time (`HH:mm`) in the event's zone, converted to and from ISO-8601 instants.
 */
export interface WallClock {
  readonly date: string;
  readonly time: string;
}

const HOUR_MS = 3_600_000;
const DAY_MS = 24 * HOUR_MS;

/** The date and time `instant` shows in `timeZone`. */
export function wallClockOf(instant: string | Date, timeZone: string): WallClock {
  const parts = partsIn(new Date(instant).getTime(), timeZone);
  return {
    date: `${pad(parts.year, 4)}-${pad(parts.month)}-${pad(parts.day)}`,
    time: `${pad(parts.hour)}:${pad(parts.minute)}`,
  };
}

/**
 * The instant (`...Z`) at which clocks in `timeZone` show `date` `time`. A time skipped by a DST
 * change resolves to the same distance after the change, an ambiguous one to its first occurrence.
 */
export function instantOf(date: string, time: string, timeZone: string): string {
  const [year, month, day] = date.split('-').map(Number);
  const [hour, minute] = time.split(':').map(Number);
  const wall = Date.UTC(year, month - 1, day, hour, minute);
  // The offset before the instant, then corrected once: right on both sides of a DST change.
  const first = offsetAt(wall, timeZone);
  let instant = wall - first;
  const second = offsetAt(instant, timeZone);
  if (second !== first) {
    instant = wall - second;
    // Neither offset shows the typed clock: a time skipped by a DST change. The offset from before
    // the change (the smaller one) puts it the same distance after the change, in every zone.
    if (instant + offsetAt(instant, timeZone) !== wall) instant = wall - Math.min(first, second);
  }
  // An ambiguous time (clocks set back): prefer the earlier instant if it shows the same clock.
  const earlier = instant - HOUR_MS;
  if (earlier + offsetAt(earlier, timeZone) === wall) instant = earlier;
  return new Date(instant).toISOString();
}

/** The next full hour after `now` on the clocks of `timeZone`, as `...Z`. */
export function nextFullHour(now: string | Date, timeZone: string): string {
  const { date, time } = wallClockOf(now, timeZone);
  const hour = Number(time.slice(0, 2)) + 1;
  return hour < 24
    ? instantOf(date, `${pad(hour)}:00`, timeZone)
    : instantOf(addDays(date, 1), '00:00', timeZone);
}

/** `date` (`yyyy-MM-dd`) moved by `days`. */
export function addDays(date: string, days: number): string {
  const [year, month, day] = date.split('-').map(Number);
  return new Date(Date.UTC(year, month - 1, day) + days * DAY_MS).toISOString().slice(0, 10);
}

/** `instant` moved by `hours`, as `...Z`. */
export function addHours(instant: string | Date, hours: number): string {
  return new Date(new Date(instant).getTime() + hours * HOUR_MS).toISOString();
}

/** Whether `timeZone` is an IANA zone the browser knows. */
export function isTimeZone(timeZone: string): boolean {
  if (!timeZone) return false;
  try {
    new Intl.DateTimeFormat('en-US', { timeZone });
    return true;
  } catch {
    return false;
  }
}

/** The IANA zones the browser knows (empty where `Intl.supportedValuesOf` is missing). */
export function timeZones(): readonly string[] {
  const intl = Intl as { supportedValuesOf?: (key: string) => string[] };
  return intl.supportedValuesOf?.('timeZone') ?? [];
}

interface Parts {
  year: number;
  month: number;
  day: number;
  hour: number;
  minute: number;
  second: number;
}

const formatters = new Map<string, Intl.DateTimeFormat>();

function partsIn(ms: number, timeZone: string): Parts {
  let formatter = formatters.get(timeZone);
  if (!formatter) {
    formatter = new Intl.DateTimeFormat('en-US', {
      timeZone,
      hourCycle: 'h23',
      year: 'numeric',
      month: 'numeric',
      day: 'numeric',
      hour: 'numeric',
      minute: 'numeric',
      second: 'numeric',
    });
    formatters.set(timeZone, formatter);
  }
  const parts: Record<string, number> = {};
  for (const part of formatter.formatToParts(new Date(ms))) {
    if (part.type !== 'literal') parts[part.type] = Number(part.value);
  }
  return parts as unknown as Parts;
}

/** How far clocks in `timeZone` are ahead of UTC at `ms`, in milliseconds. */
function offsetAt(ms: number, timeZone: string): number {
  const p = partsIn(ms, timeZone);
  const wall = Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second);
  return wall - Math.floor(ms / 1000) * 1000;
}

function pad(value: number, length = 2): string {
  return String(value).padStart(length, '0');
}
