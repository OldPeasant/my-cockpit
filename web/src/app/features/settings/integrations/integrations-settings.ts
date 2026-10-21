import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { CalendarSourceResponse, OAuthConnectionResponse } from '../../../api/generated';
import { ApiError } from '../../../core/http';
import { CalendarSourcesService, OAuthSlug } from '../calendars/calendar-sources.service';

/** The outcome of the OAuth redirect, shown above the lists. */
interface Outcome {
  readonly ok: boolean;
  readonly text: string;
}

const SOURCE_TYPES: Record<CalendarSourceResponse['type'], string> = {
  LOCAL: $localize`:@@integrations.type.local:Cockpit`,
  CALDAV: $localize`:@@integrations.type.caldav:CalDAV`,
  ICS_FEED: $localize`:@@integrations.type.ics:ICS feed`,
  GOOGLE: $localize`:@@integrations.type.google:Google`,
  MICROSOFT: $localize`:@@integrations.type.microsoft:Microsoft`,
};

const OAUTH_ERRORS: Record<string, string> = {
  access_denied: $localize`:@@integrations.oauth.denied:Access was not allowed at the provider.`,
  invalid_state: $localize`:@@integrations.oauth.invalidState:The sign-in took too long or was started in another browser. Try again.`,
  token_exchange_failed: $localize`:@@integrations.oauth.exchangeFailed:The provider did not accept the sign-in. Try again.`,
  no_refresh_token: $localize`:@@integrations.oauth.noRefreshToken:The provider gave no lasting access. Remove the cockpit in the account's app permissions, then connect again.`,
};

/**
 * Settings → Integrations (SPEC.md §11 "Integrations status"): per external calendar source its
 * last sync, error and "Sync now"; the connected Google/Microsoft accounts. The OAuth callback
 * lands here (`?oauth=connected&purpose=CALENDAR&provider=&connectionId=`), and a calendar
 * connection becomes a calendar source at once.
 */
@Component({
  selector: 'app-integrations-settings',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, MatButtonModule, RouterLink],
  templateUrl: './integrations-settings.html',
  styleUrl: './integrations-settings.scss',
})
export default class IntegrationsSettings implements OnInit {
  private readonly service = inject(CalendarSourcesService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  protected readonly sources = signal<CalendarSourceResponse[] | null>(null);
  protected readonly connections = signal<OAuthConnectionResponse[]>([]);
  protected readonly loadError = signal<string | null>(null);
  protected readonly outcome = signal<Outcome | null>(null);
  /** Ids of the sources syncing now. */
  protected readonly syncing = signal<ReadonlySet<string>>(new Set());
  /** A "Sync now" that failed as a request (a failed sync itself shows as the source's error). */
  protected readonly syncError = signal<{ name: string; message: string } | null>(null);

  /** The synced sources; the local one has nothing to show here. */
  protected readonly external = computed(
    () => this.sources()?.filter((s) => s.type !== 'LOCAL') ?? null,
  );

  protected readonly typeLabels = SOURCE_TYPES;

  async ngOnInit(): Promise<void> {
    await this.finishOAuth();
    await this.load();
  }

  protected async load(): Promise<void> {
    this.loadError.set(null);
    this.sources.set(null);
    try {
      const [sources, connections] = await Promise.all([
        this.service.sources(),
        this.service.connections().catch(() => []),
      ]);
      this.connections.set(connections);
      this.sources.set(sources);
    } catch (error) {
      this.loadError.set(message(error));
    }
  }

  protected async syncNow(source: CalendarSourceResponse): Promise<void> {
    if (this.syncing().has(source.id)) return;
    this.syncing.update((ids) => new Set(ids).add(source.id));
    this.syncError.set(null);
    try {
      const synced = await this.service.sync(source.id);
      this.sources.update((all) => all?.map((s) => (s.id === synced.id ? synced : s)) ?? null);
    } catch (error) {
      this.syncError.set({ name: source.name, message: message(error) });
    } finally {
      this.syncing.update((ids) => {
        const rest = new Set(ids);
        rest.delete(source.id);
        return rest;
      });
    }
  }

  protected reconnect(connection: OAuthConnectionResponse): void {
    this.service.reconnect(connection.provider.toLowerCase() as OAuthSlug, connection.id);
  }

  protected syncLabel(source: CalendarSourceResponse): string {
    return this.syncing().has(source.id)
      ? $localize`:@@integrations.syncingAria:Syncing ${source.name}:name:…`
      : $localize`:@@integrations.syncAria:Sync ${source.name}:name: now`;
  }

  /** Reads the OAuth outcome from the URL once, acts on it and removes it from the URL. */
  private async finishOAuth(): Promise<void> {
    const params = this.route.snapshot.queryParamMap;
    const result = params.get('oauth');
    if (!result) return;
    const provider = params.get('provider');
    const name = provider === 'microsoft' ? SOURCE_TYPES.MICROSOFT : SOURCE_TYPES.GOOGLE;
    try {
      if (result === 'error') {
        const reason = OAUTH_ERRORS[params.get('error') ?? ''] ?? (params.get('error') || '');
        this.outcome.set({
          ok: false,
          text: $localize`:@@integrations.oauth.failed:The ${name}:provider: account could not be connected. ${reason}:reason:`,
        });
        return;
      }
      const connectionId = params.get('connectionId');
      if (result !== 'connected' || !connectionId) return;
      if (this.service.takeReconnect(connectionId)) {
        this.outcome.set({
          ok: true,
          text: $localize`:@@integrations.oauth.reconnected:${name}:provider: account reconnected.`,
        });
        return;
      }
      if (params.get('purpose') !== 'CALENDAR') {
        this.outcome.set({
          ok: true,
          text: $localize`:@@integrations.oauth.connected:${name}:provider: account connected.`,
        });
        return;
      }
      await this.addCalendarSource(
        provider === 'microsoft' ? 'MICROSOFT' : 'GOOGLE',
        connectionId,
        name,
      );
    } finally {
      await this.router.navigate([], { relativeTo: this.route, queryParams: {}, replaceUrl: true });
    }
  }

  private async addCalendarSource(
    type: 'GOOGLE' | 'MICROSOFT',
    connectionId: string,
    name: string,
  ): Promise<void> {
    try {
      const source = await this.service.addSource({ type, oauthConnectionId: connectionId });
      const synced = await this.service.sync(source.id).catch(() => source);
      const error = synced.sync?.lastError;
      this.outcome.set(
        error
          ? {
              ok: false,
              text: $localize`:@@integrations.oauth.syncFailed:${source.name}:name: is connected, but its calendars could not be fetched: ${error}:error:`,
            }
          : {
              ok: true,
              text: $localize`:@@integrations.oauth.calendarConnected:${source.name}:name: is connected. Its calendars are in Settings → Calendars.`,
            },
      );
    } catch (error) {
      this.outcome.set({
        ok: false,
        text: $localize`:@@integrations.oauth.addFailed:The ${name}:provider: account was connected, but its calendars could not be added: ${message(error)}:message:`,
      });
    }
  }
}

function message(error: unknown): string {
  return error instanceof ApiError
    ? error.userMessage
    : $localize`:@@integrations.error:Something went wrong.`;
}
