/** The repeat choices of the event dialog; `custom` keeps a rule the presets can't express. */
export type Repeat = 'none' | 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'YEARLY' | 'custom';

export const REPEAT_PRESETS: readonly Exclude<Repeat, 'none' | 'custom'>[] = [
  'DAILY',
  'WEEKLY',
  'MONTHLY',
  'YEARLY',
];

/** The choice showing `rrule` (with or without the `RRULE:` prefix). */
export function repeatOf(rrule: string | null | undefined): Repeat {
  const rule = normalize(rrule);
  if (!rule) return 'none';
  const preset = REPEAT_PRESETS.find((freq) => rule === `FREQ=${freq}`);
  return preset ?? 'custom';
}

/** The RRULE of a choice; `custom` keeps `current`. */
export function rruleOf(repeat: Repeat, current: string | null | undefined): string | null {
  if (repeat === 'none') return null;
  if (repeat === 'custom') return normalize(current) || null;
  // Without BYDAY/BYMONTHDAY the series repeats on the weekday or day of its start (RFC 5545).
  return `FREQ=${repeat}`;
}

/** `rrule` without the prefix, `''` for none. */
export function normalize(rrule: string | null | undefined): string {
  return (rrule ?? '').replace(/^RRULE:/i, '').trim();
}
