import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { filter, Subject } from 'rxjs';
import {
  ContactRelatedResponse,
  ContactResponse,
  OAuthConnectionResponse,
} from '../../api/generated';
import { ChangeEventsService } from '../../core/events';
import { NotificationService, provideHttp } from '../../core/http';
import routes from './contacts.routes';
import { connection, contact, noRelated } from './testing';

const ID = '0192f0c4-0000-7000-8000-000000000001';
const URL = `/api/v1/contacts/${ID}`;
const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

const RELATED: ContactRelatedResponse = {
  contactId: ID,
  waitingFor: [
    { id: 'task-1', title: 'Signed offer', waitingSince: '2026-10-10', dueDate: '2026-10-30' },
    { id: 'task-2', title: 'Slides', waitingSince: '2026-10-12' },
  ],
  events: [
    {
      eventId: 'event-1',
      calendarId: 'cal',
      title: 'Planning',
      allDay: false,
      start: '2026-10-23T09:00:00Z',
      end: '2026-10-23T10:00:00Z',
    },
    {
      eventId: 'event-2',
      calendarId: 'cal',
      title: 'Offsite',
      allDay: true,
      startDate: '2026-11-02',
      endDate: '2026-11-03',
    },
  ],
  mails: [
    {
      id: 'mail-1',
      accountId: 'acc',
      direction: 'FROM',
      subject: 'Quarterly numbers',
      from: [{ address: 'ada@example.org' }],
      to: [],
      seen: false,
      receivedAt: '2026-10-21T08:00:00Z',
    },
    {
      id: 'mail-2',
      accountId: 'acc',
      direction: 'TO',
      from: [],
      to: [{ address: 'ada@example.org' }],
      seen: true,
      sentAt: '2026-10-20T08:00:00Z',
    },
  ],
};

interface ChangeEvent {
  type: string;
  id: string;
  op: string;
}

const CARDDAV = '/api/v1/contacts/carddav-accounts';

describe('ContactDetail', () => {
  let harness: RouterTestingHarness;
  let root: HTMLElement;
  let http: HttpTestingController;
  let changes: Subject<ChangeEvent>;
  let notifications: { error: ReturnType<typeof vi.fn>; info: ReturnType<typeof vi.fn> };

  async function start(url: string): Promise<void> {
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
  }

  /** Opens the contact with its related items, linked items and (for synced ones) accounts. */
  async function open(
    shown: ContactResponse = contact(ID),
    related: ContactRelatedResponse = noRelated(ID),
    accounts: OAuthConnectionResponse[] = [],
    url = `/contacts/${ID}`,
  ): Promise<void> {
    await start(url);
    http.expectOne(URL).flush(shown);
    await settle();
    http.expectOne(`${URL}/related`).flush(related);
    if (shown.source === 'GOOGLE' || shown.source === 'MICROSOFT') {
      http.expectOne('/api/v1/oauth/connections').flush(accounts);
    }
    await settle();
    flushLinks();
    await settle();
  }

  function flushLinks(): void {
    for (const req of http.match((r) => r.url === '/api/v1/links')) req.flush({ items: [] });
  }

  async function settle(): Promise<void> {
    await tick();
    harness.detectChanges();
    await harness.fixture.whenStable();
  }

  /** The text of `el`, its parts separated by single spaces (the template has none between tags). */
  function text(el: Element | null): string {
    if (!el) return '';
    const parts: string[] = [];
    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      const part = node.textContent?.replace(/\s+/g, ' ').trim();
      if (part) parts.push(part);
    }
    return parts.join(' ');
  }

  function items(selector: string): string[] {
    return [...root.querySelectorAll(`${selector} li`)].map((el) => text(el));
  }

  function type(selector: string, value: string): void {
    const input = root.querySelector(selector) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  afterEach(() => http.verify());

  it('shows the fields of the contact', async () => {
    await open(
      contact(ID, {
        title: 'Engineer',
        organization: 'Example AG',
        phones: [{ number: '+41 44 000 00 00', label: 'mobile' }],
        addresses: [{ street: 'Sample St 1', postalCode: '8000', city: 'Zurich', label: 'office' }],
        birthday: '1990-02-28',
        notesMd: 'Likes **tea**',
      }),
    );
    expect(text(root.querySelector('h1'))).toBe('Ada Example');
    expect(text(root.querySelector('.identity .monogram'))).toBe('AE');
    expect(text(root.querySelector('.role'))).toBe('Engineer, Example AG');
    expect(text(root.querySelector('.sync-status'))).toBe('Kept in the cockpit only');
    expect(root.querySelector('dd.email a')?.getAttribute('href')).toBe('mailto:ada@example.org');
    expect(text(root.querySelector('dd.email'))).toBe('ada@example.org work');
    expect(root.querySelector('dd.phone a')?.getAttribute('href')).toBe('tel:+41440000000');
    expect(text(root.querySelector('dd.address'))).toBe('Sample St 1, 8000 Zurich office');
    expect(text(root.querySelector('dd.birthday'))).toBe('February 28, 1990');
    expect(text(root.querySelector('.notes strong'))).toBe('tea');
    expect(root.querySelector('a.export')?.getAttribute('href')).toBe(
      `/api/v1/contacts/export?ids=${ID}`,
    );
    expect(root.querySelector('app-linked-items')).toBeTruthy();
  });

  it('shows Waiting For items, upcoming events and recent mails with deep links', async () => {
    await open(contact(ID), RELATED);
    expect(items('.waiting')).toEqual([
      'Signed offer due Oct 30, 2026',
      'Slides since Oct 12, 2026',
    ]);
    expect(root.querySelector('.waiting a')?.getAttribute('href')).toBe('/tasks/task-1');

    const events = items('.events');
    expect(events[0]).toMatch(/^Planning Fri Oct 23, 2026 \d{1,2}:00 [AP]M$/);
    expect(events[1]).toBe('Offsite Mon Nov 2, 2026');
    expect(root.querySelector('.events a')?.getAttribute('href')).toBe('/calendar/events/event-1');

    expect(items('.mails')).toEqual([
      'From Quarterly numbers Oct 21, 2026',
      'To (no subject) Oct 20, 2026',
    ]);
    expect(root.querySelector('.mails li')?.classList).toContain('unread');
    expect(root.querySelector('.mails a')?.getAttribute('href')).toBe('/mail/messages/mail-1');
  });

  it('says when nothing is related', async () => {
    await open();
    expect(text(root.querySelector('.waiting .hint'))).toBe('Nothing you are waiting for.');
    expect(text(root.querySelector('.events .hint'))).toContain('No events');
    expect(text(root.querySelector('.mails .hint'))).toBe('No mail with them yet.');
  });

  it('offers to retry a failed load of the related items', async () => {
    await start(`/contacts/${ID}`);
    http.expectOne(URL).flush(contact(ID));
    await settle();
    http
      .expectOne(`${URL}/related`)
      .flush(null, { status: 503, statusText: 'Service Unavailable' });
    await settle();
    flushLinks();
    await settle();
    expect(text(root.querySelector('.related .load-error'))).toContain('could not be loaded');
    (root.querySelector('.related .load-error button') as HTMLButtonElement).click();
    http.expectOne(`${URL}/related`).flush(RELATED);
    await settle();
    expect(items('.waiting')).toHaveLength(2);
  });

  it('shows the sync status of a synced contact', async () => {
    await open(contact(ID, { source: 'GOOGLE' }), noRelated(ID), [connection('GOOGLE')]);
    expect(text(root.querySelector('.sync-status'))).toBe(
      'Synced with Google (jane@google.example)',
    );
  });

  it('leaves out the account name when the account has neither email nor name', async () => {
    await open(contact(ID, { source: 'GOOGLE' }), noRelated(ID), [
      connection('GOOGLE', { email: undefined, displayName: undefined }),
    ]);
    expect(text(root.querySelector('.sync-status'))).toBe('Synced with Google');
  });

  it('names the account by its display name when it has no email', async () => {
    await open(contact(ID, { source: 'MICROSOFT' }), noRelated(ID), [
      connection('MICROSOFT', { email: undefined }),
    ]);
    expect(text(root.querySelector('.sync-status'))).toBe('Synced with Microsoft (Jane Doe)');
  });

  it('says when the sync of a contact is paused', async () => {
    await open(contact(ID, { source: 'MICROSOFT' }), noRelated(ID), [
      connection('MICROSOFT', { status: 'RECONNECT_REQUIRED' }),
    ]);
    expect(text(root.querySelector('.sync-status'))).toBe(
      'Sync with Microsoft is paused: sign in again in Settings',
    );
    expect(root.querySelector('.sync-status a')?.getAttribute('href')).toBe(
      '/settings/integrations',
    );
  });

  it('says when a synced contact has no connected account', async () => {
    await open(contact(ID, { source: 'GOOGLE' }), noRelated(ID), []);
    expect(text(root.querySelector('.sync-status'))).toBe('From Google, not syncing');
  });

  it('edits the contact with its version as If-Match', async () => {
    await open();
    (root.querySelector('button.edit') as HTMLButtonElement).click();
    await settle();
    type('input[name="title"]', 'Engineer');
    (root.querySelector('.add-phone') as HTMLButtonElement).click();
    await settle();
    type('.phones .value', '+41 44 000 00 00');
    (root.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    const req = http.expectOne((r) => r.method === 'PATCH' && r.url === URL);
    expect(req.request.headers.get('If-Match')).toBe('"1"');
    expect(req.request.body).toMatchObject({
      title: 'Engineer',
      phones: [{ number: '+41 44 000 00 00' }],
      emails: [{ address: 'ada@example.org', label: 'work' }],
      organization: null,
    });
    req.flush(contact(ID, { title: 'Engineer', version: 2 }));
    await settle();
    http.expectOne(`${URL}/related`).flush(noRelated(ID));
    await settle();
    expect(root.querySelector('app-contact-form')).toBeNull();
    expect(text(root.querySelector('.role'))).toBe('Engineer');
  });

  it('keeps editing and the typed values after a conflict, with the current version for the next try', async () => {
    await open();
    (root.querySelector('button.edit') as HTMLButtonElement).click();
    await settle();
    type('input[name="title"]', 'Engineer');
    (root.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    http
      .expectOne((r) => r.method === 'PATCH')
      .flush(
        { status: 412, title: 'Precondition Failed', detail: 'Changed elsewhere' },
        { status: 412, statusText: 'Precondition Failed' },
      );
    await settle();
    http.expectOne(URL).flush(contact(ID, { version: 3 }));
    await settle();
    expect(text(root.querySelector('.form-error'))).toContain('changed elsewhere');
    expect(text(root.querySelector('.form-error'))).toContain('Your changes are kept');
    expect((root.querySelector('input[name="title"]') as HTMLInputElement).value).toBe('Engineer');
    (root.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    const retry = http.expectOne((r) => r.method === 'PATCH');
    expect(retry.request.headers.get('If-Match')).toBe('"3"');
    expect(retry.request.body).toMatchObject({ title: 'Engineer' });
    retry.flush(contact(ID, { version: 4 }));
    await settle();
    http.expectOne(`${URL}/related`).flush(noRelated(ID));
  });

  it('renames a contact whose display name was derived and shows the new name', async () => {
    await open();
    (root.querySelector('button.edit') as HTMLButtonElement).click();
    await settle();
    expect((root.querySelector('input[name="displayName"]') as HTMLInputElement).value).toBe('');
    type('input[name="familyName"]', 'Lovelace');
    (root.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    const req = http.expectOne((r) => r.method === 'PATCH' && r.url === URL);
    expect(req.request.body).toMatchObject({
      displayName: null,
      givenName: 'Ada',
      familyName: 'Lovelace',
    });
    req.flush(contact(ID, { displayName: 'Ada Lovelace', familyName: 'Lovelace', version: 2 }));
    await settle();
    http.expectOne(`${URL}/related`).flush(noRelated(ID));
    await settle();
    expect(text(root.querySelector('h1'))).toBe('Ada Lovelace');
  });

  it('after a conflict, Cancel shows the current version and Edit starts from it', async () => {
    await open();
    (root.querySelector('button.edit') as HTMLButtonElement).click();
    await settle();
    type('input[name="title"]', 'Engineer');
    (root.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    http
      .expectOne((r) => r.method === 'PATCH')
      .flush(
        { status: 409, title: 'Conflict', detail: 'Changed elsewhere' },
        { status: 409, statusText: 'Conflict' },
      );
    await settle();
    http.expectOne(URL).flush(contact(ID, { title: 'Lead', version: 3 }));
    await settle();
    (root.querySelector('button.cancel') as HTMLButtonElement).click();
    await settle();
    expect(root.querySelector('app-contact-form')).toBeNull();
    expect(text(root.querySelector('.role'))).toBe('Lead');
    (root.querySelector('button.edit') as HTMLButtonElement).click();
    await settle();
    expect((root.querySelector('input[name="title"]') as HTMLInputElement).value).toBe('Lead');
    expect(root.querySelector('.form-error')).toBeNull();
  });

  it('keeps the form, the typed values and the conflict message when the reload fails', async () => {
    await open();
    (root.querySelector('button.edit') as HTMLButtonElement).click();
    await settle();
    type('input[name="title"]', 'Engineer');
    (root.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    http
      .expectOne((r) => r.method === 'PATCH')
      .flush(
        { status: 412, title: 'Precondition Failed', detail: 'Changed elsewhere' },
        { status: 412, statusText: 'Precondition Failed' },
      );
    await settle();
    http.expectOne(URL).flush(null, { status: 503, statusText: 'Service Unavailable' });
    await settle();
    expect(root.querySelector('.load-error')).toBeNull();
    expect(text(root.querySelector('.form-error'))).toContain('Your changes are kept');
    expect((root.querySelector('input[name="title"]') as HTMLInputElement).value).toBe('Engineer');
    expect((root.querySelector('button.save') as HTMLButtonElement).disabled).toBe(false);
  });

  it('cancels editing without saving', async () => {
    await open();
    (root.querySelector('button.edit') as HTMLButtonElement).click();
    await settle();
    (root.querySelector('button.cancel') as HTMLButtonElement).click();
    await settle();
    expect(root.querySelector('app-contact-form')).toBeNull();
    expect(root.querySelector('dl')).toBeTruthy();
  });

  it('creates a contact and opens it', async () => {
    await start('/contacts/new');
    http.expectOne(CARDDAV).flush({ items: [] });
    await settle();
    expect(text(root.querySelector('h1'))).toBe('New contact');
    expect(root.querySelector('.save-to')).toBeNull();
    (root.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    await settle();
    expect(text(root.querySelector('.form-error'))).toBe(
      'Enter a name, an organization or an email address.',
    );
    type('input[name="givenName"]', 'Grace');
    (root.querySelector('.add-email') as HTMLButtonElement).click();
    await settle();
    type('.emails .value', 'grace@example.org');
    (root.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/contacts');
    expect(req.request.body).toEqual({
      givenName: 'Grace',
      emails: [{ address: 'grace@example.org' }],
    });
    const NEW = '0192f0c4-0000-7000-8000-000000000009';
    req.flush(contact(NEW, { displayName: 'Grace', givenName: 'Grace', familyName: undefined }));
    await settle();
    expect(TestBed.inject(Router).url).toBe(`/contacts/${NEW}`);
    expect(notifications.info).toHaveBeenCalledWith('Contact “Grace” created');
    http.expectOne(`/api/v1/contacts/${NEW}`).flush(contact(NEW, { displayName: 'Grace' }));
    await settle();
    http.expectOne(`/api/v1/contacts/${NEW}/related`).flush(noRelated(NEW));
    await settle();
    flushLinks();
  });

  describe('a new contact in a CardDAV account', () => {
    const HOME = {
      id: '0192f0c4-0000-7000-8000-0000000000d1',
      name: 'Home server',
      url: 'https://dav.example.test/dav/',
      username: 'alice',
      hasPassword: true,
      sync: { state: 'OK' as const },
    };
    const WORK = {
      id: '0192f0c4-0000-7000-8000-0000000000d2',
      name: 'Work server',
      url: 'https://cards.example.test/',
      hasPassword: false,
      sync: { state: 'NEVER' as const },
    };
    const NEW = '0192f0c4-0000-7000-8000-000000000009';

    async function startNew(): Promise<HTMLSelectElement> {
      await start('/contacts/new');
      http.expectOne((r) => r.method === 'GET' && r.url === CARDDAV).flush({ items: [HOME, WORK] });
      await settle();
      return root.querySelector('.save-to select[name="target"]') as HTMLSelectElement;
    }

    async function choose(select: HTMLSelectElement, value: string): Promise<void> {
      select.value = value;
      select.dispatchEvent(new Event('change'));
      await settle();
    }

    async function fillAndSubmit(): Promise<void> {
      type('input[name="givenName"]', 'Grace');
      await settle();
      (root.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
      await settle();
    }

    it('offers the CardDAV accounts and POSTs to the chosen account', async () => {
      const select = await startNew();
      expect([...select.options].map((o) => o.value)).toEqual(['', HOME.id, WORK.id]);
      await choose(select, WORK.id);
      await fillAndSubmit();

      http.expectNone((r) => r.method === 'POST' && r.url === '/api/v1/contacts');
      const req = http.expectOne(`${CARDDAV}/${WORK.id}/contacts`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ givenName: 'Grace' });
      req.flush(
        contact(NEW, {
          displayName: 'Grace',
          givenName: 'Grace',
          familyName: undefined,
          emails: [],
          source: 'CARDDAV',
          externalId: `https://cards.example.test/${NEW}.vcf`,
        }),
        { status: 201, statusText: 'Created' },
      );
      await settle();
      expect(TestBed.inject(Router).url).toBe(`/contacts/${NEW}`);
      expect(notifications.info).toHaveBeenCalledWith('Contact “Grace” created');
      http.expectOne(`/api/v1/contacts/${NEW}`).flush(contact(NEW, { displayName: 'Grace' }));
      await settle();
      http.expectOne(`/api/v1/contacts/${NEW}/related`).flush(noRelated(NEW));
      await settle();
      flushLinks();
    });

    it('creates the contact here only when My Cockpit only stays chosen', async () => {
      const select = await startNew();
      await choose(select, HOME.id);
      await choose(select, '');
      await fillAndSubmit();
      http.expectNone((r) => r.url.startsWith(CARDDAV));
      http
        .expectOne((r) => r.method === 'POST' && r.url === '/api/v1/contacts')
        .flush(contact(NEW, { displayName: 'Grace' }));
      await settle();
      http.expectOne(`/api/v1/contacts/${NEW}`).flush(contact(NEW, { displayName: 'Grace' }));
      await settle();
      http.expectOne(`/api/v1/contacts/${NEW}/related`).flush(noRelated(NEW));
      await settle();
      flushLinks();
    });

    it('hides the choice when the accounts cannot be loaded and creates the contact here', async () => {
      await start('/contacts/new');
      http
        .expectOne(CARDDAV)
        .flush(
          { status: 500, title: 'Internal Server Error', detail: 'An unexpected error occurred' },
          { status: 500, statusText: 'Server Error' },
        );
      await settle();
      expect(root.querySelector('.save-to')).toBeNull();
      expect(root.querySelector('.load-error')).toBeNull();
      await fillAndSubmit();
      http
        .expectOne((r) => r.method === 'POST' && r.url === '/api/v1/contacts')
        .flush(contact(NEW, { displayName: 'Grace' }));
      await settle();
      http.expectOne(`/api/v1/contacts/${NEW}`).flush(contact(NEW, { displayName: 'Grace' }));
      await settle();
      http.expectOne(`/api/v1/contacts/${NEW}/related`).flush(noRelated(NEW));
      await settle();
      flushLinks();
    });

    it('shows why the account could not take the contact (409) and keeps the form', async () => {
      const select = await startNew();
      await choose(select, WORK.id);
      await fillAndSubmit();
      http.expectOne(`${CARDDAV}/${WORK.id}/contacts`).flush(
        {
          status: 409,
          title: 'Conflict',
          detail: 'Finding the address book: the CardDAV server could not be reached',
        },
        { status: 409, statusText: 'Conflict' },
      );
      await settle();
      // Taken out of the queue first, so a failure here does not leave an open request behind.
      const reloads = http.match('/api/v1/contacts/new');
      expect(text(root.querySelector('.form-error'))).toBe(
        'The contact could not be saved: Finding the address book: the CardDAV server could not be reached',
      );
      // A new contact has nothing to reload: no request for a contact called "new".
      expect(reloads.map((r) => r.request.url)).toEqual([]);
      expect(TestBed.inject(Router).url).toBe('/contacts/new');
      expect((root.querySelector('input[name="givenName"]') as HTMLInputElement).value).toBe(
        'Grace',
      );
    });
  });

  it('leaves a new contact with Cancel', async () => {
    await start('/contacts/new?q=gr');
    http.expectOne(CARDDAV).flush({ items: [] });
    (root.querySelector('button.cancel') as HTMLButtonElement).click();
    await settle();
    expect(TestBed.inject(Router).url).toBe('/contacts?q=gr');
    http.expectOne('/api/v1/oauth/connections').flush([]);
    http.expectOne(CARDDAV).flush({ items: [] });
    http.expectOne((r) => r.url === '/api/v1/contacts').flush({ items: [] });
  });

  it('moves the contact to the Trash and goes back to the list', async () => {
    await open(contact(ID), noRelated(ID), [], `/contacts/${ID}?q=ada`);
    expect(root.querySelector('.back')?.getAttribute('href')).toBe('/contacts?q=ada');
    (root.querySelector('button.delete') as HTMLButtonElement).click();
    const req = http.expectOne((r) => r.method === 'DELETE' && r.url === URL);
    expect(req.request.headers.get('If-Match')).toBe('"1"');
    req.flush(null, { status: 204, statusText: 'No Content' });
    await settle();
    expect(notifications.info).toHaveBeenCalledWith('Moved “Ada Example” to the Trash');
    expect(TestBed.inject(Router).url).toBe('/contacts?q=ada');
    http.expectOne('/api/v1/oauth/connections').flush([]);
    http.expectOne(CARDDAV).flush({ items: [] });
    http.expectOne((r) => r.url === '/api/v1/contacts').flush({ items: [] });
  });

  it('shows changes made elsewhere and when the contact is gone', async () => {
    await open();
    changes.next({ type: 'CONTACT', id: ID, op: 'UPDATED' });
    http.expectOne(URL).flush(contact(ID, { displayName: 'Ada Lovelace', version: 2 }));
    await settle();
    http.expectOne(`${URL}/related`).flush(noRelated(ID));
    await settle();
    expect(text(root.querySelector('h1'))).toBe('Ada Lovelace');

    changes.next({ type: 'CONTACT', id: 'someone-else', op: 'DELETED' });
    changes.next({ type: 'CONTACT', id: ID, op: 'DELETED' });
    await settle();
    expect(text(root.querySelector('.load-error'))).toContain('moved to the Trash');
  });

  it('shows a load error for a missing contact', async () => {
    await start(`/contacts/${ID}`);
    http
      .expectOne(URL)
      .flush(
        { status: 404, title: 'Not Found', detail: 'No such contact' },
        { status: 404, statusText: 'Not Found' },
      );
    await settle();
    expect(text(root.querySelector('.load-error'))).toContain('could not be loaded');
  });

  it('loads a missing contact again with Try again', async () => {
    await start(`/contacts/${ID}`);
    http.expectOne(URL).flush(null, { status: 503, statusText: 'Service Unavailable' });
    await settle();
    (root.querySelector('.load-error button') as HTMLButtonElement).click();
    await settle();
    expect(text(root.querySelector('.hint'))).toBe('Loading contact…');
    http.expectOne(URL).flush(contact(ID));
    await settle();
    expect(text(root.querySelector('.related .hint'))).toBe('Loading related items…');
    http.expectOne(`${URL}/related`).flush(noRelated(ID));
    await settle();
    flushLinks();
    await settle();
    expect(root.querySelector('.load-error')).toBeNull();
    expect(text(root.querySelector('h1'))).toBe('Ada Example');
  });

  it('says how to add fields to a contact without any', async () => {
    await open(contact(ID, { emails: [] }));
    expect(root.querySelectorAll('dd')).toHaveLength(0);
    expect(text(root.querySelector('.fields .hint'))).toBe(
      'No email, phone or address yet. Use Edit to add them.',
    );
    expect(root.querySelector('.notes')).toBeNull();
  });

  it('shows the first account of the provider and ignores accounts of the other one', async () => {
    await open(contact(ID, { source: 'GOOGLE' }), noRelated(ID), [
      connection('MICROSOFT'),
      connection('GOOGLE', { id: 'first', email: 'first@google.example' }),
      connection('GOOGLE', { id: 'second', email: 'second@google.example' }),
    ]);
    expect(text(root.querySelector('.sync-status'))).toBe(
      'Synced with Google (first@google.example)',
    );
  });

  it('still shows a synced contact when its accounts cannot be loaded', async () => {
    await start(`/contacts/${ID}`);
    http.expectOne(URL).flush(contact(ID, { source: 'MICROSOFT' }));
    await settle();
    http.expectOne(`${URL}/related`).flush(noRelated(ID));
    http
      .expectOne('/api/v1/oauth/connections')
      .flush(null, { status: 500, statusText: 'Server Error' });
    await settle();
    flushLinks();
    await settle();
    expect(text(root.querySelector('h1'))).toBe('Ada Example');
    expect(text(root.querySelector('.sync-status'))).toBe('From Microsoft, not syncing');
  });

  it('shows the contact of the latest route when an earlier one answers last', async () => {
    const OTHER = '0192f0c4-0000-7000-8000-000000000002';
    await start(`/contacts/${ID}`);
    const first = http.expectOne(URL);
    await harness.navigateByUrl(`/contacts/${OTHER}`);
    const second = http.expectOne(`/api/v1/contacts/${OTHER}`);
    second.flush(
      contact(OTHER, { displayName: 'Bob Sample', givenName: 'Bob', familyName: 'Sample' }),
    );
    await settle();
    first.flush(contact(ID));
    await settle();
    http.expectOne(`/api/v1/contacts/${OTHER}/related`).flush(noRelated(OTHER));
    await settle();
    flushLinks();
    await settle();
    expect(text(root.querySelector('h1'))).toBe('Bob Sample');
    expect(root.querySelector('a.export')?.getAttribute('href')).toBe(
      `/api/v1/contacts/export?ids=${OTHER}`,
    );
  });

  it('drops related items and accounts of the previous contact that arrive late', async () => {
    const OTHER = '0192f0c4-0000-7000-8000-000000000002';
    await start(`/contacts/${ID}`);
    http.expectOne(URL).flush(contact(ID, { source: 'GOOGLE' }));
    await settle();
    const lateRelated = http.expectOne(`${URL}/related`);
    const lateAccounts = http.expectOne('/api/v1/oauth/connections');
    await harness.navigateByUrl(`/contacts/${OTHER}`);
    http.expectOne(`/api/v1/contacts/${OTHER}`).flush(contact(OTHER, { source: 'MICROSOFT' }));
    await settle();
    const related = http.expectOne(`/api/v1/contacts/${OTHER}/related`);
    const accounts = http.expectOne('/api/v1/oauth/connections');
    related.flush(noRelated(OTHER));
    accounts.flush([]);
    await settle();
    lateRelated.flush(RELATED);
    lateAccounts.flush([connection('MICROSOFT')]);
    await settle();
    flushLinks();
    await settle();
    expect(text(root.querySelector('.waiting .hint'))).toBe('Nothing you are waiting for.');
    expect(text(root.querySelector('.mails .hint'))).toBe('No mail with them yet.');
    expect(text(root.querySelector('.sync-status'))).toBe('From Microsoft, not syncing');
  });

  it('keeps editing with the error when saving fails for another reason', async () => {
    await open();
    (root.querySelector('button.edit') as HTMLButtonElement).click();
    await settle();
    type('input[name="title"]', 'Engineer');
    (root.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    await settle();
    expect(text(root.querySelector('button.save'))).toBe('Saving…');
    http
      .expectOne((r) => r.method === 'PATCH')
      .flush(
        { status: 400, title: 'Bad Request', detail: 'Invalid birthday' },
        { status: 400, statusText: 'Bad Request' },
      );
    await settle();
    // No reload of the contact: only a conflict asks for the current version.
    expect(root.querySelector('app-contact-form')).toBeTruthy();
    expect(text(root.querySelector('.form-error'))).toContain('could not be saved');
    expect((root.querySelector('input[name="title"]') as HTMLInputElement).value).toBe('Engineer');
    expect((root.querySelector('button.save') as HTMLButtonElement).disabled).toBe(false);
  });

  it('shows that the contact is gone when a conflict reload finds nothing', async () => {
    await open();
    (root.querySelector('button.edit') as HTMLButtonElement).click();
    await settle();
    type('input[name="title"]', 'Engineer');
    (root.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    http
      .expectOne((r) => r.method === 'PATCH')
      .flush(
        { status: 409, title: 'Conflict', detail: 'Changed elsewhere' },
        { status: 409, statusText: 'Conflict' },
      );
    await settle();
    http
      .expectOne(URL)
      .flush(
        { status: 404, title: 'Not Found', detail: 'No such contact' },
        { status: 404, statusText: 'Not Found' },
      );
    await settle();
    expect(text(root.querySelector('.load-error'))).toContain('could not be loaded');
  });

  it('stays on the contact and toasts the error when the Trash move fails', async () => {
    await open();
    (root.querySelector('button.delete') as HTMLButtonElement).click();
    await settle();
    expect((root.querySelector('button.delete') as HTMLButtonElement).disabled).toBe(true);
    http
      .expectOne((r) => r.method === 'DELETE' && r.url === URL)
      .flush(null, { status: 500, statusText: 'Server Error' });
    await settle();
    expect(notifications.error).toHaveBeenCalledTimes(1);
    expect(notifications.info).not.toHaveBeenCalled();
    expect(TestBed.inject(Router).url).toBe(`/contacts/${ID}`);
    expect(text(root.querySelector('h1'))).toBe('Ada Example');
    expect((root.querySelector('button.delete') as HTMLButtonElement).disabled).toBe(false);
  });

  it('after a conflicting Trash move, retries with the current version', async () => {
    await open();
    (root.querySelector('button.delete') as HTMLButtonElement).click();
    http
      .expectOne((r) => r.method === 'DELETE')
      .flush(
        { status: 412, title: 'Precondition Failed', detail: 'Changed elsewhere' },
        { status: 412, statusText: 'Precondition Failed' },
      );
    await settle();
    http.expectOne(URL).flush(contact(ID, { displayName: 'Ada Lovelace', version: 5 }));
    await settle();
    expect(notifications.error).toHaveBeenCalledTimes(1);
    expect(text(root.querySelector('h1'))).toBe('Ada Lovelace');
    (root.querySelector('button.delete') as HTMLButtonElement).click();
    const retry = http.expectOne((r) => r.method === 'DELETE');
    expect(retry.request.headers.get('If-Match')).toBe('"5"');
    retry.flush(null, { status: 204, statusText: 'No Content' });
    await settle();
    expect(notifications.info).toHaveBeenCalledWith('Moved “Ada Lovelace” to the Trash');
    http.expectOne('/api/v1/oauth/connections').flush([]);
    http.expectOne(CARDDAV).flush({ items: [] });
    http.expectOne((r) => r.url === '/api/v1/contacts').flush({ items: [] });
  });

  it('does not replace a contact being edited with a change from elsewhere', async () => {
    await open();
    (root.querySelector('button.edit') as HTMLButtonElement).click();
    await settle();
    type('input[name="title"]', 'Draft');
    changes.next({ type: 'CONTACT', id: ID, op: 'UPDATED' });
    await settle();
    // http.verify() in afterEach: no reload was requested.
    expect((root.querySelector('input[name="title"]') as HTMLInputElement).value).toBe('Draft');
  });

  it('does not reload for the change event of its own pending save', async () => {
    await open();
    (root.querySelector('button.edit') as HTMLButtonElement).click();
    await settle();
    type('input[name="title"]', 'Engineer');
    (root.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    const save = http.expectOne((r) => r.method === 'PATCH');
    // The echo of this very save arrives before its response: no reload (http.verify in afterEach).
    changes.next({ type: 'CONTACT', id: ID, op: 'UPDATED' });
    await settle();
    save.flush(contact(ID, { title: 'Engineer', version: 2 }));
    await settle();
    http.expectOne(`${URL}/related`).flush(noRelated(ID));
    await settle();
    expect(text(root.querySelector('.role'))).toBe('Engineer');
  });

  it('ignores a change from elsewhere that is not newer, or that fails to load', async () => {
    await open(contact(ID, { version: 3 }));
    changes.next({ type: 'CONTACT', id: ID, op: 'UPDATED' });
    http.expectOne(URL).flush(contact(ID, { displayName: 'Stale Name', version: 3 }));
    await settle();
    expect(text(root.querySelector('h1'))).toBe('Ada Example');

    changes.next({ type: 'CONTACT', id: ID, op: 'UPDATED' });
    http.expectOne(URL).flush(null, { status: 503, statusText: 'Service Unavailable' });
    await settle();
    expect(text(root.querySelector('h1'))).toBe('Ada Example');
    expect(root.querySelector('.load-error')).toBeNull();
  });

  it('sends one change when Save is pressed again while the first save is pending', async () => {
    await open();
    (root.querySelector('button.edit') as HTMLButtonElement).click();
    await settle();
    type('input[name="title"]', 'Engineer');
    const form = root.querySelector('form') as HTMLFormElement;
    form.dispatchEvent(new Event('submit'));
    form.dispatchEvent(new Event('submit'));
    await settle();
    const save = http.expectOne((r) => r.method === 'PATCH' && r.url === URL);
    save.flush(contact(ID, { title: 'Engineer', version: 2 }));
    await settle();
    http.expectOne(`${URL}/related`).flush(noRelated(ID));
    await settle();
    expect(text(root.querySelector('.role'))).toBe('Engineer');
  });

  it('moves the contact to the Trash once when Delete is pressed twice quickly', async () => {
    await open();
    const remove = root.querySelector('button.delete') as HTMLButtonElement;
    remove.click();
    remove.click();
    const req = http.expectOne((r) => r.method === 'DELETE' && r.url === URL);
    req.flush(null, { status: 204, statusText: 'No Content' });
    await settle();
    expect(notifications.info).toHaveBeenCalledTimes(1);
    http.expectOne('/api/v1/oauth/connections').flush([]);
    http.expectOne(CARDDAV).flush({ items: [] });
    http.expectOne((r) => r.url === '/api/v1/contacts').flush({ items: [] });
  });

  it('ignores a load error of an earlier route that arrives last', async () => {
    const OTHER = '0192f0c4-0000-7000-8000-000000000002';
    await start(`/contacts/${ID}`);
    const first = http.expectOne(URL);
    await harness.navigateByUrl(`/contacts/${OTHER}`);
    http
      .expectOne(`/api/v1/contacts/${OTHER}`)
      .flush(contact(OTHER, { displayName: 'Bob Sample', givenName: 'Bob', familyName: 'Sample' }));
    await settle();
    http.expectOne(`/api/v1/contacts/${OTHER}/related`).flush(noRelated(OTHER));
    first.flush(null, { status: 503, statusText: 'Service Unavailable' });
    await settle();
    flushLinks();
    await settle();
    expect(root.querySelector('.load-error')).toBeNull();
    expect(text(root.querySelector('h1'))).toBe('Bob Sample');
  });

  it('ignores a failed related-items load of the previous contact that arrives late', async () => {
    const OTHER = '0192f0c4-0000-7000-8000-000000000002';
    await start(`/contacts/${ID}`);
    http.expectOne(URL).flush(contact(ID));
    await settle();
    const lateRelated = http.expectOne(`${URL}/related`);
    await harness.navigateByUrl(`/contacts/${OTHER}`);
    http.expectOne(`/api/v1/contacts/${OTHER}`).flush(contact(OTHER));
    await settle();
    http.expectOne(`/api/v1/contacts/${OTHER}/related`).flush(RELATED);
    await settle();
    lateRelated.flush(null, { status: 503, statusText: 'Service Unavailable' });
    await settle();
    flushLinks();
    await settle();
    expect(root.querySelector('.related .load-error')).toBeNull();
    expect(items('.waiting')).toHaveLength(2);
  });
});
