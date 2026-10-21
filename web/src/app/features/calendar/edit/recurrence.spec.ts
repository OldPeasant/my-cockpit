import { normalize, REPEAT_PRESETS, repeatOf, rruleOf } from './recurrence';

describe('recurrence', () => {
  it('shows no rule as "does not repeat"', () => {
    expect(repeatOf(undefined)).toBe('none');
    expect(repeatOf(null)).toBe('none');
    expect(repeatOf('')).toBe('none');
    expect(repeatOf('   ')).toBe('none');
  });

  it('recognizes the preset rules, with or without the RRULE: prefix', () => {
    for (const freq of REPEAT_PRESETS) {
      expect(repeatOf(`FREQ=${freq}`)).toBe(freq);
      expect(repeatOf(`RRULE:FREQ=${freq}`)).toBe(freq);
    }
    expect(repeatOf('rrule:FREQ=WEEKLY')).toBe('WEEKLY');
  });

  it('keeps rules the presets cannot express as custom', () => {
    expect(repeatOf('FREQ=WEEKLY;BYDAY=MO,WE')).toBe('custom');
    expect(repeatOf('FREQ=DAILY;COUNT=5')).toBe('custom');
    expect(repeatOf('FREQ=HOURLY')).toBe('custom');
  });

  it('builds the rule of a choice', () => {
    expect(rruleOf('none', 'FREQ=DAILY')).toBeNull();
    expect(rruleOf('DAILY', null)).toBe('FREQ=DAILY');
    expect(rruleOf('WEEKLY', 'FREQ=WEEKLY;BYDAY=MO')).toBe('FREQ=WEEKLY');
    expect(rruleOf('MONTHLY', undefined)).toBe('FREQ=MONTHLY');
    expect(rruleOf('YEARLY', undefined)).toBe('FREQ=YEARLY');
  });

  it('keeps the current rule for custom, without its prefix', () => {
    expect(rruleOf('custom', 'RRULE:FREQ=WEEKLY;BYDAY=MO,WE')).toBe('FREQ=WEEKLY;BYDAY=MO,WE');
    expect(rruleOf('custom', '')).toBeNull();
    expect(rruleOf('custom', undefined)).toBeNull();
  });

  it('normalizes rules', () => {
    expect(normalize('RRULE:FREQ=DAILY ')).toBe('FREQ=DAILY');
    expect(normalize(null)).toBe('');
  });
});
