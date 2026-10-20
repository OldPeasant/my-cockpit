import { TaskResponse } from '../../../api/generated';

export type RecurrenceMode = NonNullable<TaskResponse['recurrenceMode']>;

/** A recurrence preset of the editor; `custom` takes any RRULE, `none` means no recurrence. */
export type RecurrencePreset =
  'none' | 'daily' | 'weekdays' | 'weekly' | 'biweekly' | 'monthly' | 'yearly' | 'custom';

/** The presets in display order, with their rule (empty for `none` and `custom`). */
export const RECURRENCE_PRESETS: readonly {
  readonly value: RecurrencePreset;
  readonly label: string;
  readonly rule: string;
}[] = [
  { value: 'none', label: $localize`:@@taskDetail.repeat.none:Does not repeat`, rule: '' },
  { value: 'daily', label: $localize`:@@taskDetail.repeat.daily:Every day`, rule: 'FREQ=DAILY' },
  {
    value: 'weekdays',
    label: $localize`:@@taskDetail.repeat.weekdays:Every weekday (Mon–Fri)`,
    rule: 'FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR',
  },
  {
    value: 'weekly',
    label: $localize`:@@taskDetail.repeat.weekly:Every week`,
    rule: 'FREQ=WEEKLY',
  },
  {
    value: 'biweekly',
    label: $localize`:@@taskDetail.repeat.biweekly:Every 2 weeks`,
    rule: 'FREQ=WEEKLY;INTERVAL=2',
  },
  {
    value: 'monthly',
    label: $localize`:@@taskDetail.repeat.monthly:Every month`,
    rule: 'FREQ=MONTHLY',
  },
  {
    value: 'yearly',
    label: $localize`:@@taskDetail.repeat.yearly:Every year`,
    rule: 'FREQ=YEARLY',
  },
  { value: 'custom', label: $localize`:@@taskDetail.repeat.custom:Custom rule…`, rule: '' },
];

/** The recurrence modes (SPEC.md §5.2) with their labels. */
export const RECURRENCE_MODES: readonly {
  readonly value: RecurrenceMode;
  readonly label: string;
}[] = [
  { value: 'FIXED', label: $localize`:@@taskDetail.repeat.fixed:On schedule` },
  {
    value: 'AFTER_COMPLETION',
    label: $localize`:@@taskDetail.repeat.afterCompletion:After completion`,
  },
];

/** The rule of a preset (empty for `none` and `custom`). */
export function presetRule(preset: RecurrencePreset): string {
  return RECURRENCE_PRESETS.find((option) => option.value === preset)?.rule ?? '';
}

/** The preset showing `rule`: `none` for no rule, `custom` for any rule that is not a preset. */
export function presetOf(rule: string | undefined): RecurrencePreset {
  const normalized = normalizeRule(rule ?? '');
  if (!normalized) return 'none';
  const preset = RECURRENCE_PRESETS.find(
    (option) => option.rule && option.rule === normalized && option.value !== 'custom',
  );
  return preset?.value ?? 'custom';
}

/** `rule` trimmed, upper-cased and without empty parts, as sent to the server. */
export function normalizeRule(rule: string): string {
  return rule
    .trim()
    .toUpperCase()
    .split(';')
    .map((part) => part.trim())
    .filter(Boolean)
    .join(';');
}

const DAY_FREQUENCIES = ['DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY'];
const WEEKDAY = /^([+-]?\d{1,2})?(MO|TU|WE|TH|FR|SA|SU)$/;
const POSITIVE = /^[1-9]\d*$/;
const SIGNED = /^[+-]?[1-9]\d*$/;
const UNTIL = /^\d{8}(T\d{6}Z?)?$/;

/** Checks each value of a comma list against `valid`. */
function every(value: string, valid: (part: string) => boolean): boolean {
  return value.split(',').every((part) => part !== '' && valid(part));
}

function inRange(part: string, min: number, max: number): boolean {
  if (!SIGNED.test(part)) return false;
  const n = Math.abs(Number(part));
  return n >= min && n <= max;
}

/**
 * Why `rule` is not a task recurrence rule the server accepts (`TaskRecurrence.problem`), or `null`.
 * Tasks are dated by day: only DAILY, WEEKLY, MONTHLY and YEARLY rules without time parts.
 */
export function recurrenceProblem(rule: string): string | null {
  const normalized = normalizeRule(rule);
  if (!normalized)
    return $localize`:@@taskDetail.repeat.required:Enter a rule, e.g. FREQ=WEEKLY;BYDAY=MO.`;
  if (normalized.startsWith('RRULE:')) {
    return $localize`:@@taskDetail.repeat.prefix:Leave out the RRULE: prefix.`;
  }
  const seen = new Map<string, string>();
  for (const part of normalized.split(';')) {
    const [key, value, ...rest] = part.split('=');
    if (!key || value === undefined || value === '' || rest.length || seen.has(key)) {
      return invalid();
    }
    seen.set(key, value);
  }
  const freq = seen.get('FREQ');
  if (!freq) return $localize`:@@taskDetail.repeat.freq:The rule needs a FREQ.`;
  if (!DAY_FREQUENCIES.includes(freq)) {
    return $localize`:@@taskDetail.repeat.dayFreq:Tasks repeat DAILY, WEEKLY, MONTHLY or YEARLY.`;
  }
  if (seen.has('COUNT') && seen.has('UNTIL')) {
    return $localize`:@@taskDetail.repeat.countUntil:Use either COUNT or UNTIL, not both.`;
  }
  for (const [key, value] of seen) {
    if (!validPart(key, value)) return invalid();
  }
  return null;
}

function validPart(key: string, value: string): boolean {
  switch (key) {
    case 'FREQ':
      return true;
    case 'INTERVAL':
    case 'COUNT':
      return POSITIVE.test(value);
    case 'UNTIL':
      return UNTIL.test(value);
    case 'BYDAY':
      return every(value, (part) => WEEKDAY.test(part));
    case 'WKST':
      return /^(MO|TU|WE|TH|FR|SA|SU)$/.test(value);
    case 'BYMONTHDAY':
      return every(value, (part) => inRange(part, 1, 31));
    case 'BYMONTH':
      return every(value, (part) => POSITIVE.test(part) && Number(part) <= 12);
    case 'BYYEARDAY':
    case 'BYSETPOS':
      return every(value, (part) => inRange(part, 1, 366));
    case 'BYWEEKNO':
      return every(value, (part) => inRange(part, 1, 53));
    default:
      // BYHOUR, BYMINUTE, BYSECOND and unknown parts cannot apply to days.
      return false;
  }
}

function invalid(): string {
  return $localize`:@@taskDetail.repeat.invalid:This is not a valid repeat rule.`;
}
