import { Settings } from '../../api/generated';

/** Fictitious settings as the server returns them. */
export function fakeSettings(overrides: Partial<Settings> = {}): Settings {
  return {
    timeZone: 'Europe/Zurich',
    weekStart: 'MONDAY',
    theme: 'light',
    energyLabels: { LOW: 'Low', MEDIUM: 'Medium', HIGH: 'High' },
    creativityLabels: { ROUTINE: 'Routine', MIXED: 'Mixed', CREATIVE: 'Creative' },
    mailImagePolicy: 'block',
    mailImageAllowedSenders: [],
    ...overrides,
  };
}
