import { addressLine, initials, roleLine, sourceLabel, telUrl } from './contact-display';

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
  });
});
