import {
  CalendarResponse,
  CalendarSourceResponse,
  OAuthConnectionResponse,
  OAuthProviderResponse,
} from '../../../api/generated';

/** Test data for the calendar and integration settings (fictitious). */
export function fakeCalendar(over: Partial<CalendarResponse> = {}): CalendarResponse {
  return {
    id: 'c1',
    sourceId: 'local',
    sourceType: 'LOCAL',
    name: 'Personal',
    color: '#3366ff',
    visible: true,
    readOnly: false,
    isDefault: false,
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-01T08:00:00Z',
    version: 0,
    ...over,
  };
}

export function fakeSource(over: Partial<CalendarSourceResponse> = {}): CalendarSourceResponse {
  return {
    id: 'local',
    type: 'LOCAL',
    name: 'Cockpit',
    syncable: false,
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-01T08:00:00Z',
    version: 0,
    ...over,
  };
}

export function fakeSyncedSource(
  over: Partial<CalendarSourceResponse> = {},
): CalendarSourceResponse {
  return fakeSource({
    id: 's1',
    type: 'CALDAV',
    name: 'dav.example.test',
    syncable: true,
    sync: {
      state: 'OK',
      lastAttemptAt: '2026-10-21T08:00:00Z',
      lastSuccessAt: '2026-10-21T08:00:00Z',
      nextSyncAt: '2026-10-21T08:10:00Z',
      pollIntervalSeconds: 600,
    },
    ...over,
  });
}

export const PROVIDERS: OAuthProviderResponse[] = [
  { provider: 'GOOGLE', slug: 'google', configured: true },
  { provider: 'MICROSOFT', slug: 'microsoft', configured: false },
];

export function fakeConnection(
  over: Partial<OAuthConnectionResponse> = {},
): OAuthConnectionResponse {
  return {
    id: 'o1',
    provider: 'GOOGLE',
    email: 'jane@gmail.example',
    scopes: ['openid'],
    status: 'CONNECTED',
    createdAt: '2026-10-01T08:00:00Z',
    version: 0,
    ...over,
  };
}
