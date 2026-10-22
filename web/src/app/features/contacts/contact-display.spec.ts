import {
  addressLine,
  eventLink,
  initials,
  isAllDay,
  roleLine,
  sourceLabel,
  syncProvider,
  telUrl,
} from './contact-display';

describe('contact display', () => {
  it('builds the monogram from given and family name, else the display name', () => {
    expect(initials({ displayName: 'x', givenName: 'ada', familyName: 'Example' })).toBe('AE');
    expect(initials({ displayName: 'Example AG' })).toBe('EA');
    expect(initials({ displayName: 'Jean Paul de la Croix' })).toBe('JC');
    expect(initials({ displayName: 'Émile' })).toBe('É');
    expect(initials({ displayName: '+41 44 000' })).toBe('40');
    expect(initials({ displayName: '***' })).toBe('?');
  });

  it('joins role and organization, and the parts of an address', () => {
    expect(roleLine({ title: 'Engineer', organization: 'Example AG' })).toBe(
      'Engineer, Example AG',
    );
    expect(roleLine({ organization: 'Example AG' })).toBe('Example AG');
    expect(
      addressLine({ street: 'Sample St 1', postalCode: '8000', city: 'Zurich', country: 'CH' }),
    ).toBe('Sample St 1, 8000 Zurich, CH');
    expect(addressLine({ city: 'Bern', label: 'home' })).toBe('Bern');
  });

  it('names sources and dials numbers as entered', () => {
    expect(sourceLabel('GOOGLE')).toBe('Google');
    expect(sourceLabel('MICROSOFT')).toBe('Microsoft');
    expect(sourceLabel('LOCAL')).toBe('Local');
    expect(telUrl(' +41 (44) 000-00 00 ')).toBe('tel:+41440000000');
    expect(telUrl('044 000')).toBe('tel:044000');
    expect(sourceLabel('CARDDAV')).toBe('CardDAV');
  });

  it('only syncs Google and Microsoft contacts with an account', () => {
    expect(syncProvider('GOOGLE')).toBe('GOOGLE');
    expect(syncProvider('MICROSOFT')).toBe('MICROSOFT');
    expect(syncProvider('CARDDAV')).toBeNull();
    expect(syncProvider('LOCAL')).toBeNull();
  });

  it('tells whole-day events apart and deep-links them by encoded id', () => {
    const base = { eventId: 'ev/1 a', calendarId: 'cal', title: 'Offsite' };
    expect(isAllDay({ ...base, allDay: true, start: '2026-11-02T00:00:00Z' })).toBe(true);
    expect(isAllDay({ ...base, allDay: false, startDate: '2026-11-02' })).toBe(true);
    expect(
      isAllDay({ ...base, allDay: false, start: '2026-11-02T09:00:00Z', startDate: '2026-11-02' }),
    ).toBe(false);
    expect(isAllDay({ ...base, allDay: false })).toBe(false);
    expect(eventLink({ ...base, allDay: false })).toBe('/calendar/events/ev%2F1%20a');
  });
});
