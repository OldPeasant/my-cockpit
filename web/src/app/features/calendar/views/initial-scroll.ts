import { wallClockOf } from '../edit/event-times';
import { DayRange } from '../overlay/task-overlay';

/** The hour the day and week grid shows at the top when the period has no timed entry and no today. */
export const DEFAULT_SCROLL_HOUR = 7;

/**
 * Where the day or week grid of the days `period` opens (as a FullCalendar duration `HH:00:00`):
 *
 * - the full hour at or before the earliest wall-clock start (in `timeZone`) of the timed entries
 *   that start on one of the shown days; entries that started before the period (continuing past
 *   midnight into it) are left out, so are all-day entries (the caller passes timed starts only);
 * - without such an entry: the full hour before `now` when today is shown, else 07:00.
 *
 * FullCalendar clamps the scroll position to the grid, so a late entry scrolls to the end.
 */
export function initialScrollTime(
  starts: readonly string[],
  period: DayRange,
  timeZone: string,
  now: Date,
): string {
  let earliest: number | undefined;
  for (const start of starts) {
    const wall = wallClockOf(start, timeZone);
    if (wall.date < period.from || wall.date > period.to) continue;
    const minutes = minutesOf(wall.time);
    if (earliest === undefined || minutes < earliest) earliest = minutes;
  }
  if (earliest === undefined) {
    const today = wallClockOf(now, timeZone);
    earliest =
      today.date >= period.from && today.date <= period.to
        ? minutesOf(today.time)
        : DEFAULT_SCROLL_HOUR * 60;
  }
  const hour = Math.min(23, Math.max(0, Math.floor(earliest / 60)));
  return `${String(hour).padStart(2, '0')}:00:00`;
}

function minutesOf(time: string): number {
  const [hours, minutes] = time.split(':').map(Number);
  return hours * 60 + minutes;
}
