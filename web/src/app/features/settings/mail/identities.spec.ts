import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MailIdentityResponse } from '../../../api/generated';
import { NotificationService, provideHttp } from '../../../core/http';
import { BROWSER_NAVIGATE } from '../calendars/calendar-sources.service';
import { MailIdentities } from './identities';
import { fakeAccount, fakeIdentity } from './testing';

const IDENTITIES = '/api/v1/mail/accounts/a1/identities';

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

const problem = (status: number, title: string) => ({
  body: { status, title, errors: [] },
  options: {
    status,
    statusText: title,
    headers: { 'Content-Type': 'application/problem+json' },
  },
});

describe('MailIdentities', () => {
  let fixture: ComponentFixture<MailIdentities>;
  let root: HTMLElement;
  let http: HttpTestingController;

  async function setup(): Promise<void> {
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: { error: vi.fn() } },
        { provide: BROWSER_NAVIGATE, useValue: vi.fn() },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(MailIdentities);
    fixture.componentRef.setInput('account', fakeAccount());
    root = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    await tick();
  }

  async function settle(): Promise<void> {
    await tick();
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function load(identities: MailIdentityResponse[]): Promise<void> {
    await setup();
    http.expectOne({ method: 'GET', url: IDENTITIES }).flush({ items: identities });
    await settle();
  }

  /** Answers the list request that follows every change. */
  async function reload(identities: MailIdentityResponse[]): Promise<void> {
    await tick();
    http.expectOne({ method: 'GET', url: IDENTITIES }).flush({ items: identities });
    await settle();
  }

  afterEach(() => http.verify());

  function text(): string {
    return root.textContent?.replace(/\s+/g, ' ') ?? '';
  }

  function button(label: string, scope: HTMLElement = root): HTMLButtonElement {
    const found = [...scope.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === label || b.getAttribute('aria-label') === label,
    );
    if (!found) throw new Error(`No button "${label}"`);
    return found;
  }

  function input(name: string): HTMLInputElement | HTMLTextAreaElement {
    const found = root.querySelector<HTMLInputElement | HTMLTextAreaElement>(
      `[formcontrolname="${name}"]`,
    );
    if (!found) throw new Error(`No field ${name}`);
    return found;
  }

  function type(name: string, value: string): void {
    const field = input(name);
    field.value = value;
    field.dispatchEvent(new Event('input'));
  }

  async function click(target: HTMLElement): Promise<void> {
    target.click();
    await settle();
  }

  it('says that it loads, then lists the identities with their details', async () => {
    await setup();
    expect(text()).toContain('Loading identities…');
    http.expectOne(IDENTITIES).flush({
      items: [
        fakeIdentity({ isDefault: true, replyTo: 'support@mail.example.test' }),
        fakeIdentity({
          id: 'i2',
          name: 'Billing',
          email: 'billing@mail.example.test',
          signatureMd: 'B',
        }),
      ],
    });
    await settle();

    const items = [...root.querySelectorAll('li')].map((li) => li.getAttribute('aria-label'));
    expect(items).toEqual(['info@mail.example.test', 'billing@mail.example.test']);
    expect(text()).toContain('Info Desk');
    expect(text()).toContain('Default');
    expect(text()).toContain('Replies to support@mail.example.test');
    expect(text()).toContain('Own signature');
    expect(text()).not.toContain('Loading identities…');
  });

  it('says when there are no further identities', async () => {
    await load([]);
    expect(text()).toContain('No further identities. Mail is sent as jane@mail.example.test.');
  });

  it('shows a load error with a retry', async () => {
    await setup();
    const { body, options } = problem(500, 'Server error');
    http.expectOne(IDENTITIES).flush(body, options);
    await settle();

    expect(root.querySelector('[role="alert"]')?.textContent).toContain(
      'Identities could not be loaded: Server error',
    );
    await click(button('Try again'));
    http.expectOne(IDENTITIES).flush({ items: [] });
    await settle();
    expect(root.querySelector('[role="alert"]')).toBeNull();
  });

  it('adds an identity, prefilled with the account name, and leaves out empty optional fields', async () => {
    await load([]);
    await click(button('Add identity'));
    expect((input('name') as HTMLInputElement).value).toBe('Jane Doe');
    expect(button('Add identity').disabled).toBe(true);

    type('email', ' info@mail.example.test ');
    await click(button('Save identity'));

    const req = http.expectOne({ method: 'POST', url: IDENTITIES });
    expect(req.request.body).toEqual({
      name: 'Jane Doe',
      email: 'info@mail.example.test',
      isDefault: false,
    });
    req.flush(fakeIdentity({ name: 'Jane Doe' }), { status: 201, statusText: 'Created' });
    await reload([fakeIdentity({ name: 'Jane Doe' })]);

    expect(text()).toContain('info@mail.example.test added.');
    expect(root.querySelector('form')).toBeNull();
    expect(root.querySelectorAll('li').length).toBe(1);
  });

  it('does not send an invalid identity', async () => {
    await load([]);
    await click(button('Add identity'));
    type('name', ' ');
    type('email', 'not an address');
    type('replyTo', 'nope');
    await click(button('Save identity'));

    expect(text()).toContain('Enter the name to send as.');
    expect(text()).toContain('Enter an email address.');
    expect(text()).toContain('Enter one email address, or leave it empty.');
  });

  it('edits an identity at its version; emptied fields are sent empty to remove them', async () => {
    const identity = fakeIdentity({
      version: 3,
      replyTo: 'support@mail.example.test',
      signatureMd: 'Sig',
    });
    await load([identity]);
    await click(button('Edit info@mail.example.test'));
    expect((input('replyTo') as HTMLInputElement).value).toBe('support@mail.example.test');

    type('name', 'Front Desk');
    type('replyTo', '');
    type('signatureMd', '  ');
    await click(button('Save identity'));

    const req = http.expectOne({ method: 'PATCH', url: `${IDENTITIES}/i1` });
    expect(req.request.headers.get('If-Match')).toBe('"3"');
    expect(req.request.body).toEqual({
      name: 'Front Desk',
      email: 'info@mail.example.test',
      replyTo: '',
      signatureMd: '',
      isDefault: false,
    });
    const saved = fakeIdentity({ name: 'Front Desk', version: 4 });
    req.flush(saved);
    await reload([saved]);

    expect(text()).toContain('Front Desk');
    expect(text()).toContain('info@mail.example.test saved.');
    expect(text()).not.toContain('Replies to');
  });

  it('makes an identity the default and shows the flag moved after reloading', async () => {
    await load([
      fakeIdentity({ isDefault: true }),
      fakeIdentity({ id: 'i2', email: 'b@mail.example.test' }),
    ]);
    await click(button('Edit b@mail.example.test'));
    root.querySelector<HTMLButtonElement>('mat-slide-toggle button')!.click();
    await settle();
    await click(button('Save identity'));

    const req = http.expectOne({ method: 'PATCH', url: `${IDENTITIES}/i2` });
    expect(req.request.body.isDefault).toBe(true);
    req.flush(
      fakeIdentity({ id: 'i2', email: 'b@mail.example.test', isDefault: true, version: 1 }),
    );
    await reload([
      fakeIdentity({ version: 1 }),
      fakeIdentity({ id: 'i2', email: 'b@mail.example.test', isDefault: true, version: 1 }),
    ]);

    const items = [...root.querySelectorAll('li')];
    expect(items[0].textContent).not.toContain('Default');
    expect(items[1].textContent).toContain('Default');
  });

  it('cancels an edit without a request', async () => {
    await load([fakeIdentity()]);
    await click(button('Edit info@mail.example.test'));
    await click(button('Cancel'));
    expect(root.querySelector('form')).toBeNull();
  });

  it('says that an address is already used and keeps the form', async () => {
    await load([fakeIdentity()]);
    await click(button('Add identity'));
    type('email', 'INFO@mail.example.test');
    await click(button('Save identity'));

    const { body, options } = problem(409, 'Conflict');
    http.expectOne({ method: 'POST', url: IDENTITIES }).flush(body, options);
    await settle();

    expect(root.querySelector('[role="alert"]')?.textContent).toContain(
      'The identity could not be saved: This account already has an identity with that address.',
    );
    expect(root.querySelector('form')).not.toBeNull();
  });

  it('reports a stale version as changed elsewhere, reloads, and retries at the current version', async () => {
    await load([fakeIdentity()]);
    await click(button('Edit info@mail.example.test'));
    await click(button('Save identity'));

    const { body, options } = problem(412, 'Precondition Failed');
    http.expectOne({ method: 'PATCH', url: `${IDENTITIES}/i1` }).flush(body, options);
    await reload([fakeIdentity({ version: 5 })]);

    expect(root.querySelector('[role="alert"]')?.textContent).toContain(
      'It was changed elsewhere. The list now shows the current state; try again.',
    );
    expect(root.querySelector('form')).not.toBeNull();

    await click(button('Save identity'));
    const retry = http.expectOne({ method: 'PATCH', url: `${IDENTITIES}/i1` });
    expect(retry.request.headers.get('If-Match')).toBe('"5"');
    retry.flush(fakeIdentity({ version: 6 }));
    await reload([fakeIdentity({ version: 6 })]);
    expect(text()).toContain('info@mail.example.test saved.');
  });

  it('reloads the list when the identity to remove was already removed elsewhere', async () => {
    await load([fakeIdentity(), fakeIdentity({ id: 'i2', email: 'b@mail.example.test' })]);
    await click(button('Remove info@mail.example.test'));
    await click(button('Remove identity'));

    const { body, options } = problem(404, 'Not Found');
    http.expectOne({ method: 'DELETE', url: `${IDENTITIES}/i1` }).flush(body, options);
    await reload([fakeIdentity({ id: 'i2', email: 'b@mail.example.test' })]);

    expect(root.querySelector('[role="alert"]')?.textContent).toContain('Not Found');
    const items = [...root.querySelectorAll('li')].map((li) => li.getAttribute('aria-label'));
    expect(items).toEqual(['b@mail.example.test']);
  });

  it('disables Add, Edit and Remove while a change is saving', async () => {
    await load([fakeIdentity(), fakeIdentity({ id: 'i2', email: 'b@mail.example.test' })]);
    await click(button('Edit info@mail.example.test'));
    await click(button('Save identity'));

    expect(button('Add identity').disabled).toBe(true);
    expect(button('Edit b@mail.example.test').disabled).toBe(true);
    expect(button('Remove b@mail.example.test').disabled).toBe(true);

    http
      .expectOne({ method: 'PATCH', url: `${IDENTITIES}/i1` })
      .flush(fakeIdentity({ version: 1 }));
    await reload([
      fakeIdentity({ version: 1 }),
      fakeIdentity({ id: 'i2', email: 'b@mail.example.test' }),
    ]);

    expect(button('Add identity').disabled).toBe(false);
    expect(button('Edit b@mail.example.test').disabled).toBe(false);
    expect(button('Remove b@mail.example.test').disabled).toBe(false);
  });

  it('says that new mail starts with the default identity, else the account address', async () => {
    await load([fakeIdentity()]);
    expect(text()).toContain(
      'New mail starts with the default identity, or with jane@mail.example.test when none is the default.',
    );
    expect(text()).not.toContain('first one');
    expect(text()).not.toContain('is always available');

    await click(button('Edit info@mail.example.test'));
    expect(text()).toContain("Without a default, new mail starts with the account's own address.");
    expect(text()).not.toContain('first identity');
  });

  it('removes an identity after confirming', async () => {
    await load([fakeIdentity({ version: 2 })]);
    await click(button('Remove info@mail.example.test'));
    expect(text()).toContain('Stop sending as info@mail.example.test?');

    await click(button('Remove identity'));
    const req = http.expectOne({ method: 'DELETE', url: `${IDENTITIES}/i1` });
    expect(req.request.headers.get('If-Match')).toBe('"2"');
    req.flush(null, { status: 204, statusText: 'No Content' });
    await reload([]);

    expect(text()).toContain('info@mail.example.test removed.');
    expect(text()).toContain('No further identities.');
  });

  it('adds a default identity with reply-to and keeps the signature as typed', async () => {
    await load([]);
    await click(button('Add identity'));
    type('name', ' Billing ');
    type('email', 'billing@mail.example.test');
    type('replyTo', ' accounts@mail.example.test ');
    type('signatureMd', '  -- \nBilling team');
    root.querySelector<HTMLButtonElement>('mat-slide-toggle button')!.click();
    await settle();
    await click(button('Save identity'));

    const req = http.expectOne({ method: 'POST', url: IDENTITIES });
    expect(req.request.body).toEqual({
      name: 'Billing',
      email: 'billing@mail.example.test',
      replyTo: 'accounts@mail.example.test',
      signatureMd: '  -- \nBilling team',
      isDefault: true,
    });
    req.flush(fakeIdentity(), { status: 201, statusText: 'Created' });
    await reload([fakeIdentity()]);
  });

  it('sends a change only once while it is saving', async () => {
    await load([fakeIdentity()]);
    await click(button('Edit info@mail.example.test'));
    const save = button('Save identity');
    save.click();
    await settle();
    expect(save.disabled).toBe(true);
    // A second submit (e.g. Enter in a field) while the first is pending.
    root.querySelector('form')!.dispatchEvent(new Event('submit'));
    await settle();

    http
      .expectOne({ method: 'PATCH', url: `${IDENTITIES}/i1` })
      .flush(fakeIdentity({ version: 1 }));
    await reload([fakeIdentity({ version: 1 })]);
    expect(text()).toContain('info@mail.example.test saved.');
  });

  it('says that the list could not be read again after a change, and retries', async () => {
    await load([fakeIdentity()]);
    await click(button('Remove info@mail.example.test'));
    await click(button('Remove identity'));
    http
      .expectOne({ method: 'DELETE', url: `${IDENTITIES}/i1` })
      .flush(null, { status: 204, statusText: 'No Content' });
    await tick();
    const { body, options } = problem(503, 'Unavailable');
    http.expectOne({ method: 'GET', url: IDENTITIES }).flush(body, options);
    await settle();

    expect(root.querySelector('[role="alert"]')?.textContent).toContain(
      'Identities could not be loaded: Unavailable',
    );
    await click(button('Try again'));
    http.expectOne({ method: 'GET', url: IDENTITIES }).flush({ items: [] });
    await settle();
    expect(text()).toContain('No further identities.');
  });

  it('shows the server message when adding fails for another reason', async () => {
    await load([]);
    await click(button('Add identity'));
    type('email', 'info@mail.example.test');
    await click(button('Save identity'));

    const { body, options } = problem(500, 'Server error');
    http.expectOne({ method: 'POST', url: IDENTITIES }).flush(body, options);
    await settle();

    const alert = root.querySelector('[role="alert"]')?.textContent ?? '';
    expect(alert).toContain('Server error');
    expect(alert).not.toContain('already has an identity');
    expect(button('Save identity').disabled).toBe(false);
  });

  it('closes the removal question on cancel and when editing, without a request', async () => {
    await load([fakeIdentity()]);
    await click(button('Remove info@mail.example.test'));
    expect(button('Remove info@mail.example.test').getAttribute('aria-expanded')).toBe('true');
    await click(button('Cancel'));
    expect(text()).not.toContain('Stop sending as');

    await click(button('Remove info@mail.example.test'));
    await click(button('Add identity'));
    expect(text()).not.toContain('Stop sending as');
    expect(root.querySelector('[role="group"][aria-label="New identity"] form')).not.toBeNull();
  });

  it('reloads after a stale removal and removes at the current version on retry', async () => {
    await load([fakeIdentity({ version: 1 })]);
    await click(button('Remove info@mail.example.test'));
    await click(button('Remove identity'));

    const { body, options } = problem(412, 'Precondition Failed');
    const stale = http.expectOne({ method: 'DELETE', url: `${IDENTITIES}/i1` });
    expect(stale.request.headers.get('If-Match')).toBe('"1"');
    stale.flush(body, options);
    await reload([fakeIdentity({ version: 2 })]);

    expect(root.querySelector('[role="alert"]')?.textContent).toContain(
      'It was changed elsewhere. The list now shows the current state; try again.',
    );
    expect(text()).not.toContain('Reload the page');
    // The removal question stays open, so the user can confirm again.
    await click(button('Remove identity'));
    const retry = http.expectOne({ method: 'DELETE', url: `${IDENTITIES}/i1` });
    expect(retry.request.headers.get('If-Match')).toBe('"2"');
    retry.flush(null, { status: 204, statusText: 'No Content' });
    await reload([]);
    expect(text()).toContain('info@mail.example.test removed.');
  });

  it('reloads the list when the edited identity was removed elsewhere', async () => {
    await load([fakeIdentity(), fakeIdentity({ id: 'i2', email: 'b@mail.example.test' })]);
    await click(button('Edit info@mail.example.test'));
    await click(button('Save identity'));

    const { body, options } = problem(404, 'Not Found');
    http.expectOne({ method: 'PATCH', url: `${IDENTITIES}/i1` }).flush(body, options);
    await reload([fakeIdentity({ id: 'i2', email: 'b@mail.example.test' })]);

    expect(root.querySelector('[role="alert"]')?.textContent).toContain('Not Found');
    const items = [...root.querySelectorAll('li')].map((li) => li.getAttribute('aria-label'));
    expect(items).toEqual(['b@mail.example.test']);
    expect(button('Edit b@mail.example.test').disabled).toBe(false);
  });

  it('shows a load error with a retry when the reload after a stale change fails', async () => {
    await load([fakeIdentity()]);
    await click(button('Edit info@mail.example.test'));
    await click(button('Save identity'));

    const stale = problem(412, 'Precondition Failed');
    http.expectOne({ method: 'PATCH', url: `${IDENTITIES}/i1` }).flush(stale.body, stale.options);
    await tick();
    const down = problem(503, 'Unavailable');
    http.expectOne({ method: 'GET', url: IDENTITIES }).flush(down.body, down.options);
    await settle();

    expect(text()).toContain('Identities could not be loaded: Unavailable');
    await click(button('Try again'));
    http.expectOne({ method: 'GET', url: IDENTITIES }).flush({ items: [fakeIdentity()] });
    await settle();
    expect(text()).not.toContain('could not be loaded');
    expect(root.querySelectorAll('li').length).toBe(1);
  });

  it('does not reload the list after a duplicate address or another error', async () => {
    await load([fakeIdentity(), fakeIdentity({ id: 'i2', email: 'b@mail.example.test' })]);
    await click(button('Edit b@mail.example.test'));
    type('email', 'info@mail.example.test');
    await click(button('Save identity'));

    const { body, options } = problem(409, 'Conflict');
    http.expectOne({ method: 'PATCH', url: `${IDENTITIES}/i2` }).flush(body, options);
    await settle();
    await tick();
    // afterEach's http.verify() fails if a list request was made.
    http.expectNone({ method: 'GET', url: IDENTITIES });
    expect(root.querySelector('[role="alert"]')?.textContent).toContain(
      'This account already has an identity with that address.',
    );
    expect((input('email') as HTMLInputElement).value).toBe('info@mail.example.test');
  });

  it('disables the removal confirmation and Edit while a removal is running', async () => {
    await load([fakeIdentity(), fakeIdentity({ id: 'i2', email: 'b@mail.example.test' })]);
    await click(button('Remove info@mail.example.test'));
    const confirm = button('Remove identity');
    await click(confirm);

    expect(confirm.disabled).toBe(true);
    expect(button('Edit b@mail.example.test').disabled).toBe(true);
    expect(button('Add identity').disabled).toBe(true);
    // A second click while pending sends nothing more (http.verify in afterEach).
    confirm.click();
    await settle();

    http
      .expectOne({ method: 'DELETE', url: `${IDENTITIES}/i1` })
      .flush(null, { status: 204, statusText: 'No Content' });
    await reload([fakeIdentity({ id: 'i2', email: 'b@mail.example.test' })]);
    expect(button('Edit b@mail.example.test').disabled).toBe(false);
  });

  it('keeps an identity whose removal failed and says why', async () => {
    await load([fakeIdentity()]);
    await click(button('Remove info@mail.example.test'));
    await click(button('Remove identity'));

    const { body, options } = problem(500, 'Server error');
    http.expectOne({ method: 'DELETE', url: `${IDENTITIES}/i1` }).flush(body, options);
    await settle();

    expect(root.querySelector('[role="alert"]')?.textContent).toContain('Server error');
    expect(root.querySelectorAll('li').length).toBe(1);
  });
});
