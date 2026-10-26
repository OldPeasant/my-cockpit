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
const CARDDAV = '/api/v1/contacts/carddav-accounts';
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
  let resync: Subject<void>;
  let notifications: { error: ReturnType<typeof vi.fn>; info: ReturnType<typeof vi.fn> };

  async function open(
    url = '/contacts',
    items: ContactResponse[] = [ADA, BOB],
    accounts: OAuthConnectionResponse[] = [],
    nextCursor?: string,
  ): Promise<void> {
    notifications = { error: vi.fn(), info: vi.fn() };
    changes = new Subject();
    resync = new Subject();
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'contacts', children: routes }]),
        { provide: NotificationService, useValue: notifications },
        {
          provide: ChangeEventsService,
          useValue: {
            resync$: resync,
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
    http.expectOne(CARDDAV).flush({ items: [] });
    flushList(items, nextCursor);
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
    http.expectOne(CARDDAV).flush({ items: [] });
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

  it('leaves out the account name when the account has neither email nor name', async () => {
    await open('/contacts', [ADA], [connection('GOOGLE', { email: undefined, displayName: '' })]);
    const account = root.querySelector('.sync .account');
    expect(account?.querySelector('.who')).toBeNull();
    expect([...(account?.children ?? [])].map((part) => text(part))).toEqual([
      'Google',
      'Syncs both ways',
    ]);
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
    (root.querySelector('.import-report button') as HTMLButtonElement).click();
    await settle();
    expect(root.querySelector('.import-report')).toBeNull();
  });

  it('does nothing when the file dialog is closed without a file', async () => {
    await open('/contacts', [ADA]);
    const input = root.querySelector('input[type="file"]') as HTMLInputElement;
    Object.defineProperty(input, 'files', { value: [] });
    input.dispatchEvent(new Event('change'));
    await settle();
    // http.verify() in afterEach: no import was sent.
    expect(root.querySelector('.import-report')).toBeNull();
  });

  it('reloads the list once after a burst of contact changes, keeping it on screen', async () => {
    await open('/contacts', [ADA]);
    vi.useFakeTimers();
    changes.next({ type: 'CONTACT', id: BOB.id, op: 'CREATED' });
    changes.next({ type: 'CONTACT', id: BOB.id, op: 'UPDATED' });
    changes.next({ type: 'BOOKMARK', id: 'other', op: 'CREATED' });
    await vi.advanceTimersByTimeAsync(CONTACT_SEARCH_DEBOUNCE_MS - 1);
    http.expectNone((r) => r.url === LIST);
    await vi.advanceTimersByTimeAsync(1);
    vi.useRealTimers();
    const req = http.expectOne((r) => r.url === LIST);
    expect(req.request.params.has('cursor')).toBe(false);
    harness.detectChanges();
    expect(rows()).toEqual(['Ada Example']);
    expect(root.querySelector('.hint')).toBeNull();
    req.flush({ items: [ADA, BOB] });
    await settle();
    expect(rows()).toEqual(['Ada Example', 'Bob Sample']);
  });

  it('reloads the list after a resync, keeping the search', async () => {
    await open('/contacts?q=ada', [ADA]);
    resync.next();
    const req = http.expectOne((r) => r.url === LIST);
    expect(req.request.params.get('q')).toBe('ada');
    req.flush({ items: [] });
    await settle();
    expect(text(root.querySelector('.empty'))).toContain('No contact matches “ada”.');
  });

  it('keeps the list when a quiet reload fails', async () => {
    await open('/contacts', [ADA]);
    resync.next();
    http.expectOne((r) => r.url === LIST).flush(null, { status: 503, statusText: 'Unavailable' });
    await settle();
    expect(rows()).toEqual(['Ada Example']);
    expect(root.querySelector('.load-error')).toBeNull();
  });

  it('does not reload over pages loaded with Show more', async () => {
    await open('/contacts', [ADA], [], 'next');
    resync.next();
    await settle();
    // http.verify() in afterEach: no reload was requested.
    expect(root.querySelector('.more')).toBeTruthy();
  });

  it('toasts a failed Show more and keeps the list and the button', async () => {
    await open('/contacts', [ADA], [], 'next');
    (root.querySelector('.more') as HTMLButtonElement).click();
    await settle();
    expect((root.querySelector('.more') as HTMLButtonElement).disabled).toBe(true);
    http
      .expectOne((r) => r.url === LIST && r.params.get('cursor') === 'next')
      .flush(null, { status: 503, statusText: 'Unavailable' });
    await settle();
    expect(notifications.error).toHaveBeenCalledTimes(1);
    expect(rows()).toEqual(['Ada Example']);
    const more = root.querySelector('.more') as HTMLButtonElement;
    expect(more.disabled).toBe(false);
    more.click();
    http
      .expectOne((r) => r.url === LIST && r.params.get('cursor') === 'next')
      .flush({ items: [BOB] });
    await settle();
    expect(rows()).toEqual(['Ada Example', 'Bob Sample']);
  });

  it('searches at once on Enter and does not search the same text again', async () => {
    await open();
    const input = root.querySelector('input[type="search"]') as HTMLInputElement;
    input.value = '  bob ';
    input.dispatchEvent(new Event('input'));
    (root.querySelector('form.search') as HTMLFormElement).dispatchEvent(new Event('submit'));
    await settle();
    const req = http.expectOne((r) => r.url === LIST);
    expect(req.request.params.get('q')).toBe('bob');
    req.flush({ items: [BOB] });
    await settle();
    expect(TestBed.inject(Router).url).toBe('/contacts?q=bob');
    // The typing pause ends with the same search: nothing is loaded again.
    await new Promise((resolve) => setTimeout(resolve, CONTACT_SEARCH_DEBOUNCE_MS + 10));
    await settle();
    expect(rows()).toEqual(['Bob Sample']);
  });

  it('shows the latest search when an earlier one answers last', async () => {
    await open();
    await harness.navigateByUrl('/contacts?q=ada');
    await harness.navigateByUrl('/contacts?q=bob');
    const [ada, bob] = http.match((r) => r.url === LIST);
    expect(ada.request.params.get('q')).toBe('ada');
    expect(bob.request.params.get('q')).toBe('bob');
    bob.flush({ items: [BOB] });
    await settle();
    ada.flush({ items: [ADA] });
    await settle();
    expect(rows()).toEqual(['Bob Sample']);
  });

  it('drops a Show more page that arrives after the search changed', async () => {
    await open('/contacts', [ADA], [], 'next');
    (root.querySelector('.more') as HTMLButtonElement).click();
    const more = http.expectOne((r) => r.url === LIST && r.params.get('cursor') === 'next');
    await harness.navigateByUrl('/contacts?q=bob');
    http.expectOne((r) => r.url === LIST && r.params.get('q') === 'bob').flush({ items: [BOB] });
    await settle();
    more.flush({ items: [ADA, { ...BOB, id: 'late' }] });
    await settle();
    expect(rows()).toEqual(['Bob Sample']);
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('shows the latest search when an earlier one fails last', async () => {
    await open();
    await harness.navigateByUrl('/contacts?q=ada');
    await harness.navigateByUrl('/contacts?q=bob');
    const [ada, bob] = http.match((r) => r.url === LIST);
    bob.flush({ items: [BOB] });
    await settle();
    ada.flush(null, { status: 503, statusText: 'Unavailable' });
    await settle();
    expect(root.querySelector('.load-error')).toBeNull();
    expect(rows()).toEqual(['Bob Sample']);
  });

  it('opens the file picker from the Import vCard button', async () => {
    await open('/contacts', [ADA]);
    const input = root.querySelector('input[type="file"]') as HTMLInputElement;
    expect(input.getAttribute('accept')).toContain('.vcf');
    const picker = vi.spyOn(input, 'click').mockImplementation(() => undefined);
    (root.querySelector('button.import') as HTMLButtonElement).click();
    expect(picker).toHaveBeenCalledTimes(1);
  });

  it('reports an import without skipped cards and leaves out the skipped list', async () => {
    await open('/contacts', [ADA]);
    const input = root.querySelector('input[type="file"]') as HTMLInputElement;
    Object.defineProperty(input, 'files', { value: [new File(['x'], 'team.vcf')] });
    input.dispatchEvent(new Event('change'));
    http
      .expectOne((r) => r.method === 'POST' && r.url === `${LIST}/import`)
      .flush({ imported: [ADA, BOB], skipped: [] });
    await settle();
    flushList([ADA, BOB]);
    await settle();
    expect(text(root.querySelector('.import-report'))).toContain('team.vcf: 2 contacts imported.');
    expect(root.querySelector('.skipped')).toBeNull();
    expect(text(root.querySelector('.import-report'))).not.toContain('skipped');
  });

  it('asks for the next page once when Show more is pressed twice quickly', async () => {
    await open('/contacts', [ADA], [], 'next');
    const more = root.querySelector('.more') as HTMLButtonElement;
    more.click();
    more.click();
    http
      .expectOne((r) => r.url === LIST && r.params.get('cursor') === 'next')
      .flush({ items: [BOB] });
    await settle();
    expect(rows()).toEqual(['Ada Example', 'Bob Sample']);
  });

  it('does not toast a failed Show more that arrives after the search changed', async () => {
    await open('/contacts', [ADA], [], 'next');
    (root.querySelector('.more') as HTMLButtonElement).click();
    const more = http.expectOne((r) => r.url === LIST && r.params.get('cursor') === 'next');
    await harness.navigateByUrl('/contacts?q=bob');
    http.expectOne((r) => r.url === LIST && r.params.get('q') === 'bob').flush({ items: [BOB] });
    await settle();
    more.flush(null, { status: 503, statusText: 'Unavailable' });
    await settle();
    expect(notifications.error).not.toHaveBeenCalled();
    expect(rows()).toEqual(['Bob Sample']);
  });

  it('drops a quiet reload that answers after the search changed', async () => {
    await open('/contacts', [ADA]);
    resync.next();
    const quiet = http.expectOne((r) => r.url === LIST && !r.params.has('q'));
    await harness.navigateByUrl('/contacts?q=bob');
    http.expectOne((r) => r.url === LIST && r.params.get('q') === 'bob').flush({ items: [BOB] });
    await settle();
    quiet.flush({ items: [ADA, BOB] });
    await settle();
    expect(rows()).toEqual(['Bob Sample']);
  });
});
