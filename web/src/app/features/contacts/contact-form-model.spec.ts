import { derivedName, hasName, toForm, toRequest } from './contact-form-model';
import { contact } from './testing';

const ID = '0192f0c4-0000-7000-8000-000000000001';

describe('contact form model', () => {
  it('fills the form from a contact, empty text for what is not set', () => {
    const form = toForm(
      contact(ID, {
        organization: 'Example AG',
        phones: [{ number: '+41 44 000 00 00' }],
        addresses: [{ city: 'Zurich' }],
      }),
    );
    expect(form.givenName).toBe('Ada');
    expect(form.title).toBe('');
    expect(form.emails).toEqual([{ address: 'ada@example.org', label: 'work' }]);
    expect(form.phones).toEqual([{ number: '+41 44 000 00 00', label: '' }]);
    expect(form.addresses[0]).toEqual({
      label: '',
      street: '',
      postalCode: '',
      city: 'Zurich',
      region: '',
      country: '',
    });
  });

  it('fills empty text for a missing family name and an email without label', () => {
    const form = toForm(
      contact(ID, {
        displayName: 'Ada',
        familyName: undefined,
        emails: [{ address: 'ada@example.org' }],
      }),
    );
    expect(form.familyName).toBe('');
    expect(form.displayName).toBe('');
    expect(form.emails).toEqual([{ address: 'ada@example.org', label: '' }]);
  });

  it('leaves empty fields and rows out of a new contact', () => {
    const form = toForm(null);
    form.givenName = '  Grace ';
    form.emails = [
      { address: ' grace@example.org ', label: '' },
      { address: '  ', label: 'home' },
    ];
    form.phones = [{ number: '', label: 'mobile' }];
    form.addresses = [
      { label: 'home', street: '', postalCode: '', city: '', region: '', country: '' },
    ];
    expect(toRequest(form, false)).toEqual({
      givenName: 'Grace',
      emails: [{ address: 'grace@example.org' }],
    });
  });

  it('sends every field of a change, null for emptied ones and empty lists', () => {
    const form = toForm(contact(ID, { title: 'Engineer' }));
    form.title = '';
    form.emails = [];
    form.notesMd = 'Met at the **meetup**\n\n';
    form.addresses = [
      {
        label: ' office ',
        street: 'Sample St 1',
        postalCode: '8000',
        city: 'Zurich',
        region: '',
        country: '',
      },
    ];
    expect(toRequest(form, true)).toEqual({
      displayName: null,
      givenName: 'Ada',
      familyName: 'Example',
      organization: null,
      title: null,
      birthday: null,
      notesMd: 'Met at the **meetup**',
      emails: [],
      phones: [],
      addresses: [{ label: 'office', street: 'Sample St 1', postalCode: '8000', city: 'Zurich' }],
    });
  });

  it('derives the display name again when a contact named after its name is renamed', () => {
    const form = toForm(contact(ID));
    expect(form.displayName).toBe('');
    form.familyName = 'Lovelace';
    expect(toRequest(form, true)).toMatchObject({
      displayName: null,
      givenName: 'Ada',
      familyName: 'Lovelace',
    });
  });

  it('keeps a display name of its own', () => {
    const form = toForm(contact(ID, { displayName: 'Countess Ada' }));
    expect(form.displayName).toBe('Countess Ada');
    form.familyName = 'Lovelace';
    expect(toRequest(form, true)).toMatchObject({ displayName: 'Countess Ada' });
  });

  it('derives the name like the server: name, else organization, else first email', () => {
    const base = { emails: [{ address: 'ada@example.org' }, { address: 'b@example.org' }] };
    expect(derivedName({ ...base, givenName: 'Ada', familyName: 'Example' })).toBe('Ada Example');
    expect(derivedName({ ...base, familyName: 'Example' })).toBe('Example');
    expect(derivedName({ ...base, organization: 'Example AG' })).toBe('Example AG');
    expect(derivedName(base)).toBe('ada@example.org');
    expect(derivedName({ emails: [] })).toBeNull();
    expect(
      toForm(
        contact(ID, {
          displayName: 'Example AG',
          givenName: undefined,
          familyName: undefined,
          organization: 'Example AG',
        }),
      ).displayName,
    ).toBe('');
  });

  it('treats a display name taken from the email as derived, the organization of a named contact as its own', () => {
    const byEmail = toForm(
      contact(ID, {
        displayName: 'ada@example.org',
        givenName: undefined,
        familyName: undefined,
      }),
    );
    expect(byEmail.displayName).toBe('');
    byEmail.givenName = 'Ada';
    expect(toRequest(byEmail, true)).toMatchObject({ displayName: null, givenName: 'Ada' });

    // The server derives "Ada Example" here, so "Example AG" was chosen by the user.
    const named = toForm(contact(ID, { displayName: 'Example AG', organization: 'Example AG' }));
    expect(named.displayName).toBe('Example AG');
  });

  it('starts a new contact with an empty display name', () => {
    expect(toForm(null).displayName).toBe('');
  });

  it('needs a name, an organization or an email address', () => {
    const form = toForm(null);
    expect(hasName(form)).toBe(false);
    form.emails = [{ address: 'x@example.org', label: '' }];
    expect(hasName(form)).toBe(true);
    form.emails = [];
    form.organization = 'Example AG';
    expect(hasName(form)).toBe(true);
  });
});
