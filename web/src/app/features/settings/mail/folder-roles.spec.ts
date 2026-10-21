import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MailFolderResponse } from '../../../api/generated';
import { NotificationService, provideHttp } from '../../../core/http';
import { BROWSER_NAVIGATE } from '../calendars/calendar-sources.service';
import { FolderRoles } from './folder-roles';
import { fakeAccount, fakeFolder } from './testing';

const FOLDERS = '/api/v1/mail/accounts/a1/folders';

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

const problem = (status: number, title: string) => ({
  body: { status, title, errors: [] },
  options: {
    status,
    statusText: title,
    headers: { 'Content-Type': 'application/problem+json' },
  },
});

const ALL_ROLES: MailFolderResponse[] = [
  fakeFolder(),
  fakeFolder({ id: 'f2', path: 'Sent', name: 'Sent', role: 'SENT' }),
  fakeFolder({ id: 'f3', path: 'Drafts', name: 'Drafts', role: 'DRAFTS' }),
  fakeFolder({ id: 'f4', path: 'Trash', name: 'Trash', role: 'TRASH' }),
  fakeFolder({ id: 'f5', path: 'Archive', name: 'Archive', role: 'ARCHIVE' }),
  fakeFolder({ id: 'f6', path: 'Junk', name: 'Junk', role: 'SPAM' }),
];

describe('FolderRoles', () => {
  let fixture: ComponentFixture<FolderRoles>;
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
    fixture = TestBed.createComponent(FolderRoles);
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

  /** Loads the folders; the selects show their value once their options are known. */
  async function load(folders: MailFolderResponse[]): Promise<void> {
    await setup();
    http.expectOne({ method: 'GET', url: FOLDERS }).flush({ items: folders });
    await settle();
    await settle();
  }

  afterEach(() => http.verify());

  function text(): string {
    return root.textContent?.replace(/\s+/g, ' ') ?? '';
  }

  function button(label: string): HTMLButtonElement {
    const found = [...root.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === label,
    );
    if (!found) throw new Error(`No button "${label}"`);
    return found;
  }

  function row(path: string): HTMLElement {
    const found = [...root.querySelectorAll<HTMLElement>('li')].find(
      (r) => r.querySelector('.path')?.textContent === path,
    );
    if (!found) throw new Error(`No folder ${path}`);
    return found;
  }

  async function choose(path: string, label: string): Promise<void> {
    row(path).querySelector<HTMLElement>('mat-select')!.click();
    await settle();
    const option = [...document.querySelectorAll<HTMLElement>('mat-option')].find(
      (o) => o.textContent?.trim() === label,
    );
    if (!option) throw new Error(`No option "${label}"`);
    option.click();
    await settle();
  }

  it('says that it loads the folders', async () => {
    await setup();
    fixture.detectChanges();

    expect(text()).toContain('Loading folders…');
    http.expectOne(FOLDERS).flush({ items: [] });
    await settle();
    expect(text()).not.toContain('Loading folders…');
  });

  it('shows a load error with a retry', async () => {
    await setup();
    const { body, options } = problem(500, 'Server error');
    http.expectOne(FOLDERS).flush(body, options);
    await settle();

    expect(root.querySelector('[role="alert"]')?.textContent).toContain(
      'Folders could not be loaded: Server error',
    );
    button('Try again').click();
    await settle();
    http.expectOne(FOLDERS).flush({ items: [fakeFolder()] });
    await settle();

    expect(root.querySelector('[role="alert"]')).toBeNull();
    expect(root.querySelectorAll('li').length).toBe(1);
  });

  it('labels each select with its folder and names the manual role or No role', async () => {
    await load([
      fakeFolder({ id: 'f2', path: 'Work/Done', role: 'ARCHIVE', roleOverridden: true }),
      fakeFolder({ id: 'f3', path: 'Old', role: undefined, roleOverridden: true }),
      fakeFolder({ id: 'f4', path: 'Misc', role: undefined, roleOverridden: false }),
    ]);

    expect(row('Work/Done').querySelector('mat-select')?.getAttribute('aria-label')).toBe(
      'Role of Work/Done',
    );
    expect(row('Work/Done').querySelector('mat-select')?.textContent).toContain('Archive');
    expect(row('Old').querySelector('mat-select')?.textContent).toContain('No role');
    expect(row('Misc').querySelector('mat-select')?.textContent?.trim()).toBe('Automatic');
  });

  it('names no missing role when every role has a folder', async () => {
    await load(ALL_ROLES);

    expect(root.querySelectorAll('li').length).toBe(6);
    expect(text()).not.toContain('No folder for');
  });

  it('hands a role back to detection with Automatic', async () => {
    await load([fakeFolder({ id: 'f2', path: 'Old', role: 'ARCHIVE', roleOverridden: true })]);

    await choose('Old', 'Automatic');

    const patch = http.expectOne({ method: 'PATCH', url: `${FOLDERS}/f2` });
    expect(patch.request.body).toEqual({ automatic: true });
    expect(patch.request.headers.get('If-Match')).toBe('"0"');
    patch.flush(
      fakeFolder({ id: 'f2', path: 'Old', role: undefined, roleOverridden: false, version: 1 }),
    );
    await settle();
    await settle();

    // The saved folder replaces the old one in place; no reload is needed.
    http.expectNone({ method: 'GET', url: FOLDERS });
    expect(row('Old').querySelector('mat-select')?.textContent?.trim()).toBe('Automatic');
  });

  it('takes a role away with No role', async () => {
    await load([fakeFolder({ id: 'f1', path: 'INBOX' })]);

    await choose('INBOX', 'No role');

    const patch = http.expectOne({ method: 'PATCH', url: `${FOLDERS}/f1` });
    expect(patch.request.body).toEqual({});
    patch.flush(fakeFolder({ role: undefined, roleOverridden: true, version: 1 }));
    await settle();
    await settle();

    http.expectNone({ method: 'GET', url: FOLDERS });
    expect(row('INBOX').querySelector('mat-select')?.textContent).toContain('No role');
    expect(text()).toContain('No folder for: Inbox, Sent, Drafts, Trash, Archive, Spam.');
  });

  it('disables a folder while its role is saved', async () => {
    await load([fakeFolder()]);

    await choose('INBOX', 'Spam');

    expect(row('INBOX').querySelector('mat-select')?.getAttribute('aria-disabled')).toBe('true');
    http
      .expectOne({ method: 'PATCH', url: `${FOLDERS}/f1` })
      .flush(fakeFolder({ role: 'SPAM', roleOverridden: true, version: 1 }));
    await settle();
    http.expectOne({ method: 'GET', url: FOLDERS }).flush({
      items: [fakeFolder({ role: 'SPAM', roleOverridden: true, version: 1 })],
    });
    await settle();

    expect(row('INBOX').querySelector('mat-select')?.getAttribute('aria-disabled')).toBe('false');
  });

  it('shows a failed role change and the folders as they are on the server', async () => {
    await load([fakeFolder({ id: 'f2', path: 'Old', role: undefined })]);

    await choose('Old', 'Trash');
    const { body, options } = problem(412, 'Precondition Failed');
    http.expectOne({ method: 'PATCH', url: `${FOLDERS}/f2` }).flush(body, options);
    await settle();
    http.expectOne({ method: 'GET', url: FOLDERS }).flush({
      items: [fakeFolder({ id: 'f2', path: 'Old', role: 'SENT', roleOverridden: true })],
    });
    await settle();
    await settle();

    expect(root.querySelector('[role="alert"]')?.textContent).toContain(
      'The role of Old could not be changed: It was changed elsewhere.',
    );
    expect(row('Old').querySelector('mat-select')?.textContent).toContain('Sent');
    expect(row('Old').querySelector('mat-select')?.getAttribute('aria-disabled')).toBe('false');
  });

  it('disables every folder while a moved role is saved and the folders are read again', async () => {
    await load([fakeFolder(), fakeFolder({ id: 'f2', path: 'Old', role: undefined })]);

    await choose('Old', 'Inbox');
    http
      .expectOne({ method: 'PATCH', url: `${FOLDERS}/f2` })
      .flush(
        fakeFolder({ id: 'f2', path: 'Old', role: 'INBOX', roleOverridden: true, version: 1 }),
      );
    await settle();

    // The reload runs: INBOX lost its role on the server, so its version changed too.
    const reload = http.expectOne({ method: 'GET', url: FOLDERS });
    expect(row('INBOX').querySelector('mat-select')?.getAttribute('aria-disabled')).toBe('true');
    expect(row('Old').querySelector('mat-select')?.getAttribute('aria-disabled')).toBe('true');
    reload.flush({
      items: [
        fakeFolder({ role: undefined, version: 1 }),
        fakeFolder({ id: 'f2', path: 'Old', role: 'INBOX', roleOverridden: true, version: 1 }),
      ],
    });
    await settle();

    expect(row('INBOX').querySelector('mat-select')?.getAttribute('aria-disabled')).toBe('false');
    expect(row('Old').querySelector('mat-select')?.getAttribute('aria-disabled')).toBe('false');
  });

  it('reports a failed reload after a saved role as a load error, not a failed change', async () => {
    await load([fakeFolder(), fakeFolder({ id: 'f2', path: 'Old', role: undefined })]);

    await choose('Old', 'Inbox');
    http
      .expectOne({ method: 'PATCH', url: `${FOLDERS}/f2` })
      .flush(
        fakeFolder({ id: 'f2', path: 'Old', role: 'INBOX', roleOverridden: true, version: 1 }),
      );
    await settle();
    const { body, options } = problem(500, 'Server error');
    http.expectOne({ method: 'GET', url: FOLDERS }).flush(body, options);
    await settle();

    expect(text()).not.toContain('could not be changed');
    expect(root.querySelector('[role="alert"]')?.textContent).toContain(
      'Folders could not be loaded: Server error',
    );
    button('Try again').click();
    await settle();
    http.expectOne({ method: 'GET', url: FOLDERS }).flush({
      items: [
        fakeFolder({ role: undefined, version: 1 }),
        fakeFolder({ id: 'f2', path: 'Old', role: 'INBOX', roleOverridden: true, version: 1 }),
      ],
    });
    await settle();
    await settle();

    expect(root.querySelector('[role="alert"]')).toBeNull();
    expect(row('Old').querySelector('mat-select')?.textContent).toContain('Inbox');
  });

  it('shows a failed detection with a retry', async () => {
    await load([fakeFolder()]);

    button('Detect folders').click();
    await settle();
    expect(button('Detect folders').disabled).toBe(true);
    const { body, options } = problem(502, 'Bad Gateway');
    http.expectOne({ method: 'POST', url: `${FOLDERS}/sync` }).flush(body, options);
    await settle();

    expect(root.querySelector('[role="alert"]')?.textContent).toContain(
      'Folders could not be loaded: Bad Gateway',
    );
    expect(button('Detect folders').disabled).toBe(false);
  });

  it('detects only once while a detection runs', async () => {
    await load([]);

    button('Detect folders').click();
    button('Detect folders').click();
    await settle();

    http.expectOne({ method: 'POST', url: `${FOLDERS}/sync` }).flush({ items: ALL_ROLES });
    await settle();
    expect(root.querySelectorAll('li').length).toBe(6);
  });
});
