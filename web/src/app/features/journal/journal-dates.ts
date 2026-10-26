import { JournalDayResponse } from '../../api/generated';

/** A day of the heatmap. */
export interface HeatmapCell {
  /** `yyyy-MM-dd`. */
  readonly date: string;
  /** Whether the day is in the heatmap's range (the first and last weeks are padded). */
  readonly inRange: boolean;
  readonly hasEntry: boolean;
  /** The entry's mood, 1 to 5, else null. */
  readonly mood: number | null;
}

/** A column of the heatmap: seven days from the week start. */
export interface HeatmapWeek {
  readonly cells: readonly HeatmapCell[];
  /** The short month name when a month begins in this week (its first day in range), else null. */
  readonly month: string | null;
}

/** The ISO weekday (1 = Monday … 7 = Sunday) of the settings' `weekStart`. */
const WEEKDAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];

export function isoWeekday(weekStart: string | undefined): number {
  const index = WEEKDAYS.indexOf(weekStart ?? '');
  return index < 0 ? 1 : index + 1;
}

/** Whether `value` is a real calendar date `yyyy-MM-dd`. */
export function isDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value;
}

/** `date` (`yyyy-MM-dd`) moved by `days`. */
export function addDays(date: string, days: number): string {
  const day = new Date(`${date}T00:00:00Z`);
  day.setUTCDate(day.getUTCDate() + days);
  return day.toISOString().slice(0, 10);
}

/** The ISO weekday of `date`: 1 = Monday … 7 = Sunday. */
function weekdayOf(date: string): number {
  return new Date(`${date}T00:00:00Z`).getUTCDay() || 7;
}

/**
 * The heatmap from `from` to `to` (both included) as weeks starting on `firstDay` (ISO weekday):
 * the first and last weeks are padded with days out of range, so every week has seven days.
 */
export function heatmapWeeks(
  from: string,
  to: string,
  days: readonly JournalDayResponse[],
  firstDay: number,
  locale = 'en',
): HeatmapWeek[] {
  if (to < from) return [];
  const moods = new Map(days.map((day) => [day.date, day.mood ?? null]));
  const months = new Intl.DateTimeFormat(locale, { month: 'short', timeZone: 'UTC' });
  const weeks: HeatmapWeek[] = [];
  let date = addDays(from, -((weekdayOf(from) - firstDay + 7) % 7));
  while (date <= to) {
    const cells: HeatmapCell[] = [];
    let month: string | null = null;
    for (let i = 0; i < 7; i++, date = addDays(date, 1)) {
      const inRange = date >= from && date <= to;
      if (inRange && (date.endsWith('-01') || (date === from && weeks.length === 0))) {
        month = months.format(new Date(`${date}T00:00:00Z`));
      }
      cells.push({
        date,
        inRange,
        hasEntry: inRange && moods.has(date),
        mood: inRange ? (moods.get(date) ?? null) : null,
      });
    }
    weeks.push({ cells, month });
  }
  return weeks;
}

/** The first 160 characters of a Markdown body as plain text, for a preview line. */
export function excerpt(bodyMd: string, length = 160): string {
  const text = bodyMd
    .replace(/!\[[^\]]*]\([^)]*\)/g, '')
    .replace(/\[\[[A-Za-z_]+:[0-9A-Fa-f-]{36}]]/g, '')
    .replace(/\[([^\]]*)]\([^)]*\)/g, '$1')
    .replace(/^\s{0,3}(#{1,6}\s+|[-*+]\s+(\[[ xX]]\s+)?|>\s?|\d+[.)]\s+)/gm, '')
    .replace(/[*_`~]+/g, '')
    .replace(/\s+/g, ' ')
    .trim();
  return text.length > length ? `${text.slice(0, length - 1).trimEnd()}…` : text;
}

/** Tags typed as text: split at commas, a leading `#` removed, each once regardless of case. */
export function parseTags(text: string): string[] {
  const seen = new Set<string>();
  const tags: string[] = [];
  for (const raw of text.split(',')) {
    const tag = raw.trim().replace(/^#/, '').trim();
    if (!tag || seen.has(tag.toLowerCase())) continue;
    seen.add(tag.toLowerCase());
    tags.push(tag);
  }
  return tags;
}

const DAY_PARTS: Intl.DateTimeFormatOptions = { year: 'numeric', month: '2-digit', day: '2-digit' };

/**
 * The day (`yyyy-MM-dd`) of `now` in `timeZone` (IANA); in the browser's time zone when it has
 * none or an unknown one.
 */
export function dayIn(timeZone: string | undefined, now = new Date()): string {
  let format: Intl.DateTimeFormat;
  try {
    format = new Intl.DateTimeFormat('en', { timeZone, ...DAY_PARTS });
  } catch {
    format = new Intl.DateTimeFormat('en', DAY_PARTS);
  }
  const parts = Object.fromEntries(
    format.formatToParts(now).map((part) => [part.type, part.value]),
  );
  return `${parts['year']}-${parts['month']}-${parts['day']}`;
}
