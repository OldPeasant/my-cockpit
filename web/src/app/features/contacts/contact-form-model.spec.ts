import { hasName, toForm, toRequest } from './contact-form-model';
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
      displayName: 'Ada Example',
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
