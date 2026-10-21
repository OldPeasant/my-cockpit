import { HttpContext } from '@angular/common/http';
import { DOCUMENT, inject, Injectable, InjectionToken } from '@angular/core';
import {
  Api,
  CalendarPatchRequest,
  CalendarResponse,
  CalendarSourceResponse,
  createCalendar,
  createCalendarSource,
  CreateCalendarRequest,
  CreateCalendarSourceRequest,
  listCalendars,
  listCalendarSources,
  listOAuthConnections,
  listOAuthProviders,
  OAuthConnectionResponse,
  OAuthProviderResponse,
  startOAuthConnection,
  syncCalendarSource,
  updateCalendar,
} from '../../../api/generated';
import { SILENT_ERRORS } from '../../../core/http';

/** Leaves the app for `url` (the OAuth consent screen); replaced in tests. */
export const BROWSER_NAVIGATE = new InjectionToken<(url: string) => void>('BROWSER_NAVIGATE', {
  providedIn: 'root',
  factory: () => {
    const location = inject(DOCUMENT).location;
    return (url) => location.assign(url);
  },
});

/** The external source types that can be added (LOCAL calendars are added one by one). */
export type ExternalSourceType = Exclude<CreateCalendarSourceRequest['type'], 'LOCAL'>;

/** A Google or Microsoft account, by the path segment of its OAuth endpoints. */
export type OAuthSlug = 'google' | 'microsoft';

/**
 * Calendars, calendar sources and OAuth connections for the settings (SPEC.md §6.1, §11). Errors
 * are not toasted: the sections show them next to the action that failed.
 */
@Injectable({ providedIn: 'root' })
export class CalendarSourcesService {
  private readonly api = inject(Api);
  private readonly navigate = inject(BROWSER_NAVIGATE);

  /** Every calendar of every source, ordered by name. */
  async calendars(): Promise<CalendarResponse[]> {
    return (await this.api.invoke(listCalendars, undefined, silent())).items;
  }

  /** Every source in creation order, with its sync status. */
  async sources(): Promise<CalendarSourceResponse[]> {
    return (await this.api.invoke(listCalendarSources, undefined, silent())).items;
  }

  /** Adds a local calendar. */
  createLocal(body: CreateCalendarRequest): Promise<CalendarResponse> {
    return this.api.invoke(createCalendar, { body }, silent());
  }

  /** Changes a calendar (color, visibility, default) at the version the page showed. */
  update(calendar: CalendarResponse, body: CalendarPatchRequest): Promise<CalendarResponse> {
    return this.api.invoke(
      updateCalendar,
      { id: calendar.id, 'If-Match': `"${calendar.version}"`, body },
      silent(),
    );
  }

  /** Adds a CalDAV account, an ICS feed or a connected Google/Microsoft account. */
  addSource(body: CreateCalendarSourceRequest): Promise<CalendarSourceResponse> {
    return this.api.invoke(createCalendarSource, { body }, silent());
  }

  /** Syncs a source now and returns it with its new status (a failed sync is in `sync.lastError`). */
  sync(id: string): Promise<CalendarSourceResponse> {
    return this.api.invoke(syncCalendarSource, { id }, silent());
  }

  /** Google and Microsoft, and whether the server has their OAuth app. */
  providers(): Promise<OAuthProviderResponse[]> {
    return this.api.invoke(listOAuthProviders, undefined, silent());
  }

  /** The connected accounts, oldest first. */
  connections(): Promise<OAuthConnectionResponse[]> {
    return this.api.invoke(listOAuthConnections, undefined, silent());
  }

  /**
   * Leaves for the provider's consent screen. The server sends the browser back to
   * `/settings/integrations` with the outcome, where the account becomes a calendar source.
   */
  connect(provider: OAuthSlug): void {
    const query = new URLSearchParams({ purpose: 'CALENDAR' });
    const path = startOAuthConnection.PATH.replace('{provider}', provider);
    this.navigate(`${this.api.rootUrl}${path}?${query}`);
  }
}

function silent(): HttpContext {
  return new HttpContext().set(SILENT_ERRORS, true);
}
