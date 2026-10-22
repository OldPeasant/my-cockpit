import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { filter, Subject } from 'rxjs';
import { ContactResponse, OAuthConnectionResponse } from '../../api/generated';
import { ChangeEventsService } from '../../core/events';
import { NotificationService, provideHttp } from '../../core/http';
import { CONTACT_SEARCH_DEBOUNCE_MS } from './contacts-page';
import routes from './contacts.routes';
import { connection, contact } from './testing';

const LIST = '/api/v1/contacts';
const CONNECTIONS = '/api/v1/oauth/connections';
const ADA = contact('0192f0c4-0000-7000-8000-000000000001', {
  title: 'Engineer',
  organization: 'Example AG',
  phones: [{ number: '+41 44 000 00 00' }],
});
const BOB = contact('0192f0c4-0000-7000-8000-000000000002', {
  displayName: 'Bob Sample',
  givenName: 'Bob',
  familyName: 'Sample',
  emails: [],
  source: 'GOOGLE',
  externalId: 'people/c1',
});
const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

interface ChangeEvent {
  type: string;
  id: string;
  op: string;
}

describe('ContactsPage', () => {
  let harness: RouterTestingHarness;
  let root: HTMLElement;
  let http: HttpTestingController;
  let changes: Subject<ChangeEvent>;
  let notifications: { error: ReturnType<typeof vi.fn>; info: ReturnType<typeof vi.fn> };

  async function open(
    url = '/contacts',
    items: ContactResponse[] = [ADA, BOB],
    accounts: OAuthConnectionResponse[] = [],
  ): Promise<void> {
    notifications = { error: vi.fn(), info: vi.fn() };
    changes = new Subject();
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'contacts', children: routes }]),
        { provide: NotificationService, useValue: notifications },
        {
          provide: ChangeEventsService,
          useValue: {
            resync$: new Subject<void>(),
            events$: (type: string) => changes.pipe(filter((e) => e.type === type)),
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    root = harness.routeNativeElement as HTMLElement;
    http.expectOne(CONNECTIONS).flush(accounts);
    flushList(items);
    await settle();
  }

  function flushList(items: ContactResponse[], nextCursor?: string): void {
    http
      .expectOne((r) => r.method === 'GET' && r.url === LIST)
      .flush({ items, ...(nextCursor ? { nextCursor } : {}) });
  }

  async function settle(): Promise<void> {
    await tick();
    harness.detectChanges();
    await harness.fixture.whenStable();
  }

  function text(el: Element | null): string {
    return el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  }

  function rows(): string[] {
    return [...root.querySelectorAll('.contact .name')].map((el) => text(el));
  }

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  it('lists contacts with monogram, role, how to reach them and their source', async () => {
    await open();
    expect(rows()).toEqual(['Ada Example', 'Bob Sample']);
    const ada = root.querySelector(`[data-id="${ADA.id}"]`)!;
    expect(text(ada.querySelector('.monogram'))).toBe('AE');
    expect(text(ada.querySelector('.role'))).toBe('Engineer, Example AG');
    expect(text(ada.querySelector('.email'))).toBe('ada@example.org');
    expect(text(ada.querySelector('.phone'))).toBe('+41 44 000 00 00');
    expect(ada.querySelector('.source')).toBeNull();
    expect(ada.querySelector('a')?.getAttribute('href')).toBe(`/contacts/${ADA.id}`);
    const bob = root.querySelector(`[data-id="${BOB.id}"]`)!;
    expect(text(bob.querySelector('.source'))).toBe('Google');
  });

  it('searches as you type, keeps the search in the URL and in the detail links', async () => {
    await open();
    vi.useFakeTimers();
    const input = root.querySelector('input[type="search"]') as HTMLInputElement;
    input.value = 'ada';
    input.dispatchEvent(new Event('input'));
    await vi.advanceTimersByTimeAsync(CONTACT_SEARCH_DEBOUNCE_MS);
    vi.useRealTimers();
    const req = http.expectOne((r) => r.url === LIST);
    expect(req.request.params.get('q')).toBe('ada');
    req.flush({ items: [ADA] });
    await settle();
    expect(TestBed.inject(Router).url).toBe('/contacts?q=ada');
    expect(rows()).toEqual(['Ada Example']);
    expect(root.querySelector('.contact a')?.getAttribute('href')).toBe(
      `/contacts/${ADA.id}?q=ada`,
    );
  });

  it('says when nothing matches and shows all again', async () => {
    await open('/contacts?q=zed', []);
    expect(text(root.querySelector('.empty'))).toContain('No contact matches “zed”.');
    (root.querySelector('.empty button') as HTMLButtonElement).click();
    await settle();
    flushList([ADA, BOB]);
    await settle();
    expect(rows()).toEqual(['Ada Example', 'Bob Sample']);
  });

  it('invites to create or import when there are no contacts', async () => {
    await open('/contacts', []);
    expect(text(root.querySelector('.empty'))).toContain('No contacts yet.');
  });

  it('loads more pages', async () => {
    notifications = { error: vi.fn(), info: vi.fn() };
    await open('/contacts', [ADA]);
    // The first page was flushed without a cursor: reload with one.
    changes.next({ type: 'CONTACT', id: BOB.id, op: 'CREATED' });
    await new Promise((resolve) => setTimeout(resolve, CONTACT_SEARCH_DEBOUNCE_MS + 10));
    flushList([ADA], 'next');
    await settle();
    (root.querySelector('.more') as HTMLButtonElement).click();
    const req = http.expectOne((r) => r.url === LIST && r.params.get('cursor') === 'next');
    req.flush({ items: [ADA, BOB] });
    await settle();
    expect(rows()).toEqual(['Ada Example', 'Bob Sample']);
    expect(root.querySelector('.more')).toBeNull();
  });

  it('shows a load error with a retry', async () => {
    notifications = { error: vi.fn(), info: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'contacts', children: routes }]),
        { provide: NotificationService, useValue: notifications },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/contacts');
    root = harness.routeNativeElement as HTMLElement;
    http.expectOne(CONNECTIONS).flush(null, { status: 500, statusText: 'Server Error' });
    http.expectOne((r) => r.url === LIST).flush(null, { status: 503, statusText: 'Unavailable' });
    await settle();
    expect(text(root.querySelector('.load-error'))).toContain('could not be loaded');
    expect(root.querySelector('.sync')).toBeNull();
    (root.querySelector('.load-error button') as HTMLButtonElement).click();
    flushList([ADA]);
    await settle();
    expect(rows()).toEqual(['Ada Example']);
  });

  it('shows which accounts sync contacts, and a paused one', async () => {
    await open(
      '/contacts',
      [ADA],
      [
        connection('GOOGLE'),
        connection('MICROSOFT', { status: 'RECONNECT_REQUIRED' }),
        connection('GOOGLE', { id: 'other', scopes: ['openid', 'https://mail.google.com/'] }),
      ],
    );
    const accounts = [...root.querySelectorAll('.sync .account')].map((el) =>
      [...el.children].map((part) => text(part)).join(' '),
    );
    expect(accounts).toEqual([
      'Google jane@google.example Syncs both ways',
      'Microsoft jane@microsoft.example Paused: sign in again in Settings',
    ]);
    expect(root.querySelector('.sync .manage')?.getAttribute('href')).toBe(
      '/settings/integrations',
    );
  });

  it('says contacts are kept here only without a connected account', async () => {
    await open();
    expect(text(root.querySelector('.sync .none'))).toContain('Contacts are kept here only.');
  });

  it('offers the export of all contacts as a download', async () => {
    await open();
    const link = root.querySelector('a.export') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/api/v1/contacts/export');
    expect(link.hasAttribute('download')).toBe(true);
  });

  it('imports a vCard file, reports skipped cards and reloads the list', async () => {
    await open('/contacts', [ADA]);
    const input = root.querySelector('input[type="file"]') as HTMLInputElement;
    const file = new File(['BEGIN:VCARD\r\nEND:VCARD\r\n'], 'friends.vcf', { type: 'text/vcard' });
    Object.defineProperty(input, 'files', { value: [file] });
    input.dispatchEvent(new Event('change'));
    await settle();
    expect(text(root.querySelector('.import'))).toBe('Importing…');
    const req = http.expectOne((r) => r.method === 'POST' && r.url === `${LIST}/import`);
    expect((req.request.body as FormData).get('file')).toBeInstanceOf(Blob);
    req.flush({ imported: [BOB], skipped: [{ index: 1, reason: 'No name' }] });
    await settle();
    flushList([ADA, BOB]);
    await settle();
    expect(text(root.querySelector('.import-report'))).toContain(
      'friends.vcf: 1 contact imported.',
    );
    expect(text(root.querySelector('.skipped'))).toBe('Card 2: No name');
    expect(rows()).toEqual(['Ada Example', 'Bob Sample']);
    (root.querySelector('.import-report button') as HTMLButtonElement).click();
    await settle();
    expect(root.querySelector('.import-report')).toBeNull();
  });

  it('shows why an import failed', async () => {
    await open('/contacts', [ADA]);
    const input = root.querySelector('input[type="file"]') as HTMLInputElement;
    Object.defineProperty(input, 'files', { value: [new File(['x'], 'bad.vcf')] });
    input.dispatchEvent(new Event('change'));
    http
      .expectOne(`${LIST}/import`)
      .flush(
        { status: 400, title: 'Bad Request', detail: 'Not a vCard file' },
        { status: 400, statusText: 'Bad Request' },
      );
    await settle();
    expect(text(root.querySelector('.import-report.failed'))).toContain(
      'The file could not be imported',
    );
  });
});
