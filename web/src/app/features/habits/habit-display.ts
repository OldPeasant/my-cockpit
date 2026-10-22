import { HabitRequest, HabitResponse } from '../../api/generated';

export type Weekday = HabitResponse['weekdays'][number];
export type Schedule = HabitResponse['schedule'];

/** The weekdays in week order (ISO, Monday first), with their short labels. */
export const WEEKDAYS: readonly { readonly day: Weekday; readonly label: string }[] = [
  { day: 'MONDAY', label: $localize`:@@habits.day.mon:Mon` },
  { day: 'TUESDAY', label: $localize`:@@habits.day.tue:Tue` },
  { day: 'WEDNESDAY', label: $localize`:@@habits.day.wed:Wed` },
  { day: 'THURSDAY', label: $localize`:@@habits.day.thu:Thu` },
  { day: 'FRIDAY', label: $localize`:@@habits.day.fri:Fri` },
  { day: 'SATURDAY', label: $localize`:@@habits.day.sat:Sat` },
  { day: 'SUNDAY', label: $localize`:@@habits.day.sun:Sun` },
];

/**
 * The count a tap on the check sets for today: one more until the target is reached, then back to
 * 0, so a single tap checks (and unchecks) a habit done once a day.
 */
export function nextCount(habit: HabitResponse): number {
  const count = habit.stats.todayCount;
  return count >= habit.targetCount ? 0 : count + 1;
}

/** Today's progress towards the target, 0 to 1. */
export function todayProgress(habit: HabitResponse): number {
  return Math.min(1, habit.stats.todayCount / Math.max(1, habit.targetCount));
}

/** "5 days" / "1 week": a streak with its unit. */
export function streakLabel(count: number, unit: HabitResponse['stats']['streakUnit']): string {
  return unit === 'WEEKS'
    ? count === 1
      ? $localize`:@@habits.streak.week:${count}:count: week`
      : $localize`:@@habits.streak.weeks:${count}:count: weeks`
    : count === 1
      ? $localize`:@@habits.streak.day:${count}:count: day`
      : $localize`:@@habits.streak.days:${count}:count: days`;
}

/** The 30-day completion as a whole percent; null without due days. */
export function completionPercent(habit: HabitResponse): number | null {
  const rate = habit.stats.completionRateLast30Days;
  return rate === undefined || rate === null ? null : Math.round(rate * 100);
}

/** "Every day", "3× a week", "Mon, Wed, Fri"; with "· 2× a day" for a target above 1. */
export function scheduleLabel(habit: HabitResponse): string {
  let label: string;
  if (habit.schedule === 'TIMES_PER_WEEK') {
    const times = habit.timesPerWeek ?? 1;
    label = $localize`:@@habits.schedule.perWeek:${times}:times:× a week`;
  } else if (habit.schedule === 'WEEKDAYS') {
    label = WEEKDAYS.filter((w) => habit.weekdays.includes(w.day))
      .map((w) => w.label)
      .join(', ');
  } else {
    label = $localize`:@@habits.schedule.daily:Every day`;
  }
  if (habit.targetCount > 1) {
    const target = habit.targetCount;
    label += ', ' + $localize`:@@habits.schedule.perDay:${target}:target:× a day`;
  }
  return label;
}

/** The values of the create/edit form. */
export interface HabitDraft {
  name: string;
  schedule: Schedule;
  timesPerWeek: number;
  weekdays: Weekday[];
  targetCount: number;
  /** `HH:mm`, or empty for no reminder. */
  reminderTime: string;
}

export function emptyDraft(): HabitDraft {
  return {
    name: '',
    schedule: 'DAILY',
    timesPerWeek: 3,
    weekdays: ['MONDAY', 'WEDNESDAY', 'FRIDAY'],
    targetCount: 1,
    reminderTime: '',
  };
}

export function draftOf(habit: HabitResponse): HabitDraft {
  return {
    name: habit.name,
    schedule: habit.schedule,
    timesPerWeek: habit.timesPerWeek ?? 3,
    weekdays: habit.weekdays.length ? [...habit.weekdays] : emptyDraft().weekdays,
    targetCount: habit.targetCount,
    reminderTime: habit.reminderTime?.slice(0, 5) ?? '',
  };
}

/** Why the draft cannot be saved, or null when it can. */
export function draftProblem(draft: HabitDraft): string | null {
  if (!draft.name.trim()) return $localize`:@@habits.form.needName:Give the habit a name.`;
  if (draft.schedule === 'WEEKDAYS' && !draft.weekdays.length) {
    return $localize`:@@habits.form.needDay:Pick at least one day.`;
  }
  if (draft.schedule === 'TIMES_PER_WEEK' && !inRange(draft.timesPerWeek, 1, 7)) {
    return $localize`:@@habits.form.timesRange:Times a week must be between 1 and 7.`;
  }
  if (!inRange(draft.targetCount, 1, 1000)) {
    return $localize`:@@habits.form.targetRange:Times a day must be between 1 and 1000.`;
  }
  return null;
}

/**
 * The request for a valid draft. Only the fields of its schedule are sent; an empty reminder is sent
 * as `null`, which removes it.
 */
export function requestOf(draft: HabitDraft): HabitRequest {
  return {
    name: draft.name.trim(),
    schedule: draft.schedule,
    targetCount: draft.targetCount,
    ...(draft.schedule === 'TIMES_PER_WEEK' ? { timesPerWeek: draft.timesPerWeek } : {}),
    ...(draft.schedule === 'WEEKDAYS'
      ? { weekdays: WEEKDAYS.map((w) => w.day).filter((d) => draft.weekdays.includes(d)) }
      : {}),
    // The generated type has no null, but the API documents `null` as "no reminder".
    reminderTime: (draft.reminderTime || null) as string | undefined,
  };
}

function inRange(value: number, min: number, max: number): boolean {
  return Number.isInteger(value) && value >= min && value <= max;
}
