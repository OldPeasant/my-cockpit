import {
  ContactAddressDto,
  ContactEmailDto,
  ContactPhoneDto,
  ContactRequest,
  ContactResponse,
} from '../../api/generated';

/** An address row of the form: every part as text, empty when not set. */
export type AddressRow = Required<{ [K in keyof ContactAddressDto]: string }>;

/** The editable fields of a contact as the form holds them: text, empty when not set. */
export interface ContactFormValue {
  displayName: string;
  givenName: string;
  familyName: string;
  organization: string;
  title: string;
  /** `yyyy-MM-dd` or empty. */
  birthday: string;
  notesMd: string;
  emails: { address: string; label: string }[];
  phones: { number: string; label: string }[];
  addresses: AddressRow[];
}

export const EMPTY_ADDRESS: AddressRow = {
  label: '',
  street: '',
  postalCode: '',
  city: '',
  region: '',
  country: '',
};

/**
 * The form for `contact`, or an empty one for a new contact. A display name the server derived
 * from the name (it equals `derivedName`) is left empty, so it is derived again when the name
 * changes.
 */
export function toForm(contact: ContactResponse | null): ContactFormValue {
  const displayName = contact?.displayName ?? '';
  return {
    displayName: contact && displayName === derivedName(contact) ? '' : displayName,
    givenName: contact?.givenName ?? '',
    familyName: contact?.familyName ?? '',
    organization: contact?.organization ?? '',
    title: contact?.title ?? '',
    birthday: contact?.birthday ?? '',
    notesMd: contact?.notesMd ?? '',
    emails: (contact?.emails ?? []).map((e) => ({ address: e.address, label: e.label ?? '' })),
    phones: (contact?.phones ?? []).map((p) => ({ number: p.number, label: p.label ?? '' })),
    addresses: (contact?.addresses ?? []).map((a) => ({
      label: a.label ?? '',
      street: a.street ?? '',
      postalCode: a.postalCode ?? '',
      city: a.city ?? '',
      region: a.region ?? '',
      country: a.country ?? '',
    })),
  };
}

/**
 * The display name the server derives for a contact without one (`ContactService.derivedName`):
 * given and family name, else the organization, else the first email address.
 */
export function derivedName(
  contact: Pick<ContactResponse, 'givenName' | 'familyName' | 'organization' | 'emails'>,
): string | null {
  const name = [contact.givenName, contact.familyName]
    .filter((part) => part != null)
    .join(' ')
    .trim();
  return name || contact.organization?.trim() || contact.emails[0]?.address.trim() || null;
}

const TEXT_FIELDS = [
  'displayName',
  'givenName',
  'familyName',
  'organization',
  'title',
  'birthday',
  'notesMd',
] as const;

/**
 * The request for the form. A new contact (`patch` false) leaves out what is empty; a change sends
 * every field, `null` for an emptied one (a cleared display name is derived again by the server).
 * Rows without their main value (address, number, any address part) are left out.
 */
export function toRequest(form: ContactFormValue, patch: boolean): ContactRequest {
  const request: Record<string, unknown> = {};
  for (const field of TEXT_FIELDS) {
    const value = field === 'notesMd' ? form[field].trimEnd() : form[field].trim();
    if (value) request[field] = value;
    else if (patch) request[field] = null;
  }
  const emails: ContactEmailDto[] = form.emails
    .filter((e) => e.address.trim())
    .map((e) => withLabel<ContactEmailDto>({ address: e.address.trim() }, e.label));
  const phones: ContactPhoneDto[] = form.phones
    .filter((p) => p.number.trim())
    .map((p) => withLabel<ContactPhoneDto>({ number: p.number.trim() }, p.label));
  const addresses: ContactAddressDto[] = form.addresses
    .map((a) => {
      const address: ContactAddressDto = {};
      for (const key of ['street', 'postalCode', 'city', 'region', 'country'] as const) {
        if (a[key].trim()) address[key] = a[key].trim();
      }
      return Object.keys(address).length ? withLabel(address, a.label) : null;
    })
    .filter((a): a is ContactAddressDto => a !== null);
  if (patch || emails.length) request['emails'] = emails;
  if (patch || phones.length) request['phones'] = phones;
  if (patch || addresses.length) request['addresses'] = addresses;
  return request as ContactRequest;
}

function withLabel<T extends { label?: string }>(value: T, label: string): T {
  return label.trim() ? { ...value, label: label.trim() } : value;
}

/** Whether the form names the contact: a new contact needs a name, an organization or an email. */
export function hasName(form: ContactFormValue): boolean {
  return [form.displayName, form.givenName, form.familyName, form.organization]
    .concat(form.emails.map((e) => e.address))
    .some((value) => value.trim() !== '');
}
