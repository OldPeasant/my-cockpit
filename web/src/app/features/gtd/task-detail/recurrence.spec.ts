import {
  normalizeRule,
  presetOf,
  presetRule,
  RECURRENCE_PRESETS,
  recurrenceProblem,
} from './recurrence';

describe('recurrence presets', () => {
  it.each([
    [undefined, 'none'],
    ['', 'none'],
    ['FREQ=DAILY', 'daily'],
    ['freq=daily', 'daily'],
    ['FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR', 'weekdays'],
    ['FREQ=WEEKLY', 'weekly'],
    ['FREQ=WEEKLY;INTERVAL=2', 'biweekly'],
    ['FREQ=MONTHLY', 'monthly'],
    ['FREQ=YEARLY', 'yearly'],
    ['FREQ=WEEKLY;BYDAY=MO', 'custom'],
    ['FREQ=DAILY;COUNT=3', 'custom'],
  ] as const)('presetOf(%s) is %s', (rule, preset) => {
    expect(presetOf(rule)).toBe(preset);
  });

  it('round-trips every preset with a rule', () => {
    for (const option of RECURRENCE_PRESETS.filter((o) => o.rule)) {
      expect(presetOf(presetRule(option.value))).toBe(option.value);
      expect(recurrenceProblem(option.rule)).toBeNull();
    }
  });

  it('has no rule for none and custom', () => {
    expect(presetRule('none')).toBe('');
    expect(presetRule('custom')).toBe('');
  });

  it('normalizes case, spaces and empty parts', () => {
    expect(normalizeRule(' freq=weekly; byday=mo ;')).toBe('FREQ=WEEKLY;BYDAY=MO');
  });
});

describe('recurrenceProblem', () => {
  it.each([
    'FREQ=WEEKLY;BYDAY=MO',
    'FREQ=MONTHLY;BYDAY=-1FR',
    'FREQ=MONTHLY;BYMONTHDAY=1,15',
    'FREQ=YEARLY;BYMONTH=12;BYMONTHDAY=24',
    'FREQ=DAILY;INTERVAL=3;COUNT=5',
    'FREQ=WEEKLY;UNTIL=20271231',
    'FREQ=WEEKLY;UNTIL=20271231T000000Z;WKST=MO',
    'FREQ=MONTHLY;BYDAY=MO,TU;BYSETPOS=1',
    'FREQ=MONTHLY;BYDAY=MO,TU;BYSETPOS=-1',
    'FREQ=YEARLY;BYWEEKNO=1,53',
    'FREQ=YEARLY;BYWEEKNO=-1',
    'FREQ=YEARLY;BYYEARDAY=1,366,-366',
  ])('accepts %s', (rule) => {
    expect(recurrenceProblem(rule)).toBeNull();
  });

  it.each([
    ['', 'Enter a rule'],
    ['RRULE:FREQ=DAILY', 'RRULE: prefix'],
    ['BYDAY=MO', 'needs a FREQ'],
    ['FREQ=HOURLY', 'DAILY, WEEKLY, MONTHLY or YEARLY'],
    ['FREQ=DAILY;COUNT=2;UNTIL=20271231', 'either COUNT or UNTIL'],
    ['FREQ=DAILY;COUNT=0', 'not a valid'],
    ['FREQ=DAILY;INTERVAL=abc', 'not a valid'],
    ['FREQ=WEEKLY;BYDAY=XX', 'not a valid'],
    ['FREQ=DAILY;BYHOUR=9', 'not a valid'],
    ['FREQ=DAILY;FREQ=WEEKLY', 'not a valid'],
    ['garbage', 'not a valid'],
    ['FREQ=MONTHLY;BYMONTHDAY=32', 'not a valid'],
    ['FREQ=YEARLY;BYMONTH=13', 'not a valid'],
    ['FREQ=YEARLY;BYWEEKNO=54', 'not a valid'],
    ['FREQ=YEARLY;BYWEEKNO=0', 'not a valid'],
    ['FREQ=YEARLY;BYYEARDAY=367', 'not a valid'],
    ['FREQ=MONTHLY;BYDAY=MO;BYSETPOS=1,', 'not a valid'],
    ['FREQ=DAILY;BYMONTHDAY=', 'not a valid'],
    ['FREQ=WEEKLY;WKST=XX', 'not a valid'],
    ['FREQ=WEEKLY;UNTIL=2027-12-31', 'not a valid'],
  ])('rejects %s', (rule, message) => {
    expect(recurrenceProblem(rule)).toContain(message);
  });
});
