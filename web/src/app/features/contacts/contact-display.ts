import { ContactAddressDto, ContactEventDto, ContactResponse } from '../../api/generated';

export type ContactSource = ContactResponse['source'];

/** Up to two letters for the contact's monogram: given and family name, else the display name. */
export function initials(
  contact: Pick<ContactResponse, 'displayName' | 'givenName' | 'familyName'>,
): string {
  const parts =
    contact.givenName || contact.familyName
      ? [contact.givenName, contact.familyName]
      : contact.displayName.split(/\s+/);
  const letters = parts
    .filter((part): part is string => !!part?.trim())
    .map((part) => firstLetter(part))
    .filter((letter) => letter !== '');
  if (letters.length > 2) letters.splice(1, letters.length - 2);
  return letters.join('').toUpperCase() || '?';
}

function firstLetter(text: string): string {
  const match = /[\p{L}\p{N}]/u.exec(text);
  return match ? match[0] : '';
}

/** Where the contact lives, as the user knows it. */
export function sourceLabel(source: ContactSource): string {
  switch (source) {
    case 'GOOGLE':
      return $localize`:@@contacts.source.google:Google`;
    case 'MICROSOFT':
      return $localize`:@@contacts.source.microsoft:Microsoft`;
    case 'CARDDAV':
      return $localize`:@@contacts.source.carddav:CardDAV`;
    default:
      return $localize`:@@contacts.source.local:Local`;
  }
}

/** The provider of an account a contact syncs with; `null` for local contacts. */
export function syncProvider(source: ContactSource): 'GOOGLE' | 'MICROSOFT' | null {
  return source === 'GOOGLE' || source === 'MICROSOFT' ? source : null;
}

/** Role and organization in one line, e.g. "Product lead, Example AG". */
export function roleLine(contact: Pick<ContactResponse, 'title' | 'organization'>): string {
  return [contact.title, contact.organization].filter((part) => !!part?.trim()).join(', ');
}

/** The address on one line, parts that are set separated by commas. */
export function addressLine(address: ContactAddressDto): string {
  const place = [address.postalCode, address.city].filter((part) => !!part?.trim()).join(' ');
  return [address.street, place, address.region, address.country]
    .filter((part) => !!part?.trim())
    .join(', ');
}

/** A `tel:` URL of a phone number as entered: only digits and a leading `+` are kept. */
export function telUrl(number: string): string {
  const trimmed = number.trim();
  return `tel:${trimmed.startsWith('+') ? '+' : ''}${trimmed.replace(/[^\d]/g, '')}`;
}

/** Whether the occurrence is a whole-day event, so it shows a date without time. */
export function isAllDay(event: ContactEventDto): boolean {
  return event.allDay || (!event.start && !!event.startDate);
}

/**
 * The deep link of the event of an occurrence. The calendar opens the event at its own (first)
 * date: for a recurring event that is the start of the series, not this occurrence, as the
 * calendar's deep link takes no occurrence date (`?event=` overrides `?date=`).
 */
export function eventLink(event: ContactEventDto): string {
  return `/calendar/events/${encodeURIComponent(event.eventId)}`;
}
