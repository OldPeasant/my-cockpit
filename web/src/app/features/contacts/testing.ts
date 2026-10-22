import {
  ContactRelatedResponse,
  ContactResponse,
  OAuthConnectionResponse,
} from '../../api/generated';

/** A fictitious local contact for tests, with one email address. */
export function contact(id: string, fields: Partial<ContactResponse> = {}): ContactResponse {
  return {
    id,
    displayName: 'Ada Example',
    givenName: 'Ada',
    familyName: 'Example',
    emails: [{ address: 'ada@example.org', label: 'work' }],
    phones: [],
    addresses: [],
    source: 'LOCAL',
    createdAt: '2026-10-20T08:00:00Z',
    updatedAt: '2026-10-20T08:00:00Z',
    version: 1,
    ...fields,
  };
}

/** Nothing related to the contact. */
export function noRelated(contactId: string): ContactRelatedResponse {
  return { contactId, mails: [], events: [], waitingFor: [] };
}

/** A fictitious connected account with access to calendars, mail and contacts. */
export function connection(
  provider: 'GOOGLE' | 'MICROSOFT',
  fields: Partial<OAuthConnectionResponse> = {},
): OAuthConnectionResponse {
  return {
    id: `0192f0c4-0000-7000-8000-00000000c${provider === 'GOOGLE' ? '001' : '002'}`,
    provider,
    displayName: 'Jane Doe',
    email: `jane@${provider.toLowerCase()}.example`,
    scopes:
      provider === 'GOOGLE'
        ? ['openid', 'https://www.googleapis.com/auth/contacts']
        : ['https://graph.microsoft.com/Contacts.ReadWrite'],
    status: 'CONNECTED',
    createdAt: '2026-10-01T08:00:00Z',
    version: 1,
    ...fields,
  };
}
