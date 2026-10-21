import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { CalendarSourceResponse, OAuthConnectionResponse } from '../../../api/generated';
import { NotificationService, provideHttp } from '../../../core/http';
import { BROWSER_NAVIGATE } from '../calendars/calendar-sources.service';
import { fakeConnection, fakeSource, fakeSyncedSource } from '../calendars/testing';
import IntegrationsSettings from './integrations-settings';

const SOURCES = '/api/v1/calendar/sources';
const CONNECTIONS = '/api/v1/oauth/connections';

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

@Component({ template: '' })
class Empty {}

describe('IntegrationsSettings', () => {
  let harness: RouterTestingHarness;
  let http: HttpTestingController;
  let navigate: ReturnType<typeof vi.fn>;

  const failing = fakeSyncedSource({
    id: 's2',
    type: 'GOOGLE',
    name: 'jane@gmail.example',
    sync: {
      state: 'ERROR',
      lastAttemptAt: '2026-10-21T09:00:00Z',
      lastSuccessAt: '2026-10-21T08:00:00Z',
      lastError: 'Fetching the events failed at Google (HTTP 500)',
      nextSyncAt: '2026-10-21T09:05:00Z',
      pollIntervalSeconds: 300,
    },
  });
  const fresh = fakeSyncedSource({
    id: 's3',
    type: 'ICS_FEED',
    name: 'Holidays',
    sync: { state: 'NEVER', nextSyncAt: '2026-10-21T09:00:00Z', pollIntervalSeconds: 900 },
  });

  async function open(url = '/settings/integrations'): Promise<void> {
    navigate = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([
          { path: 'settings/integrations', component: IntegrationsSettings },
          { path: 'settings/calendars', component: Empty },
        ]),
        { provide: NotificationService, useValue: { error: vi.fn() } },
        { provide: BROWSER_NAVIGATE, useValue: navigate },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    await settle();
  }

  async function load(
    sources: CalendarSourceResponse[] = [fakeSource(), fakeSyncedSource(), failing, fresh],
    connections: OAuthConnectionResponse[] = [],
  ): Promise<void> {
    http.expectOne(SOURCES).flush({ items: sources });
    http.expectOne(CONNECTIONS).flush(connections);
    await settle();
  }

  async function settle(): Promise<void> {
    for (let i = 0; i < 3; i++) {
      await tick();
      await harness.fixture.whenStable();
    }
  }

  const root = (): HTMLElement => harness.routeNativeElement as HTMLElement;

  function item(name: string): HTMLElement {
    return [...root().querySelectorAll<HTMLElement>('.sources > li')].find(
      (li) => li.querySelector('.name')?.textContent?.trim() === name,
    )!;
  }

  afterEach(() => http.verify());

  it('shows per external source its type, last sync, error and next sync', async () => {
    await open();
    await load();

    const names = [...root().querySelectorAll('.sources .name')].map((el) =>
      el.textContent?.trim(),
    );
    expect(names).toEqual(['dav.example.test', 'jane@gmail.example', 'Holidays']);

    const ok = item('dav.example.test');
    expect(ok.dataset['state']).toBe('OK');
    expect(ok.querySelector('.type')?.textContent?.trim()).toBe('CalDAV');
    expect(ok.querySelector('.last-success')?.textContent).toContain('2026');
    expect(ok.querySelector('.last-attempt')).toBeNull();
    expect(ok.querySelector('.source-error')).toBeNull();

    const error = item('jane@gmail.example');
    expect(error.dataset['state']).toBe('ERROR');
    expect(error.querySelector('.last-attempt')).not.toBeNull();
    expect(error.querySelector('.source-error')?.textContent).toContain(
      'Last sync failed: Fetching the events failed at Google (HTTP 500)',
    );

    expect(item('Holidays').querySelector('.last-success')?.textContent?.trim()).toBe('Never');
  });

  it('syncs a source now and shows its new status', async () => {
    await open();
    await load();

    const button = root().querySelector<HTMLButtonElement>(
      'button[aria-label="Sync jane@gmail.example now"]',
    )!;
    button.click();
    await settle();
    expect(button.disabled).toBe(true);
    expect(button.textContent?.trim()).toBe('Syncing…');
    http.expectOne({ method: 'POST', url: `${SOURCES}/s2/sync` }).flush({
      ...failing,
      sync: {
        ...failing.sync!,
        state: 'OK',
        lastError: undefined,
        lastSuccessAt: '2026-10-21T09:10:00Z',
        lastAttemptAt: '2026-10-21T09:10:00Z',
      },
    });
    await settle();

    expect(item('jane@gmail.example').dataset['state']).toBe('OK');
    expect(item('jane@gmail.example').querySelector('.source-error')).toBeNull();
    expect(button.disabled).toBe(false);
  });

  it('shows a sync request that failed', async () => {
    await open();
    await load();
    root().querySelector<HTMLButtonElement>('button[aria-label="Sync Holidays now"]')!.click();
    await settle();
    http
      .expectOne({ method: 'POST', url: `${SOURCES}/s3/sync` })
      .flush(
        { status: 409, title: 'Conflict', detail: 'Sources of this type cannot be synced yet' },
        {
          status: 409,
          statusText: 'Conflict',
          headers: { 'Content-Type': 'application/problem+json' },
        },
      );
    await settle();

    expect(root().querySelector('[role="alert"]')?.textContent).toContain(
      'Holidays could not be synced: Sources of this type cannot be synced yet',
    );
  });

  it('points to Calendars when nothing is connected', async () => {
    await open();
    await load([fakeSource()]);

    expect(root().textContent).toContain('No connected calendars.');
    expect(root().querySelector('a[href="/settings/calendars"]')).not.toBeNull();
  });

  it('shows a load error with a retry', async () => {
    await open();
    http.expectOne(SOURCES).flush(
      { status: 500, title: 'Server error' },
      {
        status: 500,
        statusText: 'Server error',
        headers: { 'Content-Type': 'application/problem+json' },
      },
    );
    http.expectOne(CONNECTIONS).flush([]);
    await settle();
    expect(root().querySelector('[role="alert"]')?.textContent).toContain(
      'Sync status could not be loaded',
    );

    [...root().querySelectorAll('button')]
      .find((b) => b.textContent?.trim() === 'Try again')!
      .click();
    await settle();
    await load();
    expect(root().querySelectorAll('.sources > li').length).toBe(3);
  });

  it('turns a connected Google account into a calendar source and clears the URL', async () => {
    await open(
      '/settings/integrations?oauth=connected&provider=google&purpose=CALENDAR&connectionId=o1',
    );

    const add = http.expectOne({ method: 'POST', url: SOURCES });
    expect(add.request.body).toEqual({ type: 'GOOGLE', oauthConnectionId: 'o1' });
    const added = fakeSyncedSource({ id: 's5', type: 'GOOGLE', name: 'jane@gmail.example' });
    add.flush(added, { status: 201, statusText: 'Created' });
    await settle();
    http.expectOne({ method: 'POST', url: `${SOURCES}/s5/sync` }).flush(added);
    await settle();
    await load([fakeSource(), added], [fakeConnection()]);

    expect(root().querySelector('.outcome')?.textContent).toContain(
      'jane@gmail.example is connected.',
    );
    expect(TestBed.inject(Router).url).toBe('/settings/integrations');
  });

  it('reports an OAuth error from the redirect', async () => {
    await open('/settings/integrations?oauth=error&provider=microsoft&error=access_denied');
    await load();

    const outcome = root().querySelector('.outcome');
    expect(outcome?.getAttribute('role')).toBe('alert');
    expect(outcome?.textContent).toContain(
      'The Microsoft account could not be connected. Access was not allowed at the provider.',
    );
    expect(TestBed.inject(Router).url).toBe('/settings/integrations');
  });

  it('reports a connection that could not become a calendar source', async () => {
    await open(
      '/settings/integrations?oauth=connected&provider=microsoft&purpose=CALENDAR&connectionId=o2',
    );
    http
      .expectOne({ method: 'POST', url: SOURCES })
      .flush(
        { status: 400, title: 'Bad Request', detail: 'Request validation failed' },
        {
          status: 400,
          statusText: 'Bad Request',
          headers: { 'Content-Type': 'application/problem+json' },
        },
      );
    await settle();
    await load();

    expect(root().querySelector('.outcome')?.textContent).toContain(
      'The Microsoft account was connected, but its calendars could not be added: Request validation failed',
    );
  });

  it('only confirms connections made for mail or contacts', async () => {
    await open(
      '/settings/integrations?oauth=connected&provider=google&purpose=MAIL&connectionId=o1',
    );
    await load();

    http.expectNone({ method: 'POST', url: SOURCES });
    expect(root().querySelector('.outcome')?.textContent).toContain('Google account connected.');
  });

  it('lists accounts and reconnects an expired one', async () => {
    await open();
    await load(
      [fakeSource()],
      [
        fakeConnection(),
        fakeConnection({
          id: 'o2',
          provider: 'MICROSOFT',
          email: undefined,
          displayName: 'Jane Doe',
          status: 'RECONNECT_REQUIRED',
        }),
      ],
    );

    const accounts = [...root().querySelectorAll('.accounts > li')];
    expect(accounts.map((li) => li.querySelector('.name')?.textContent?.trim())).toEqual([
      'jane@gmail.example',
      'Jane Doe',
    ]);
    expect(accounts[1].textContent).toContain('Access expired');
    (accounts[1].querySelector('button') as HTMLButtonElement).click();
    expect(navigate).toHaveBeenCalledWith('/api/v1/oauth/microsoft/start?purpose=CALENDAR');
  });
});
