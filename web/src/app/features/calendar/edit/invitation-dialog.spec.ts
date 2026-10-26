import { TestbedHarnessEnvironment } from '@angular/cdk/testing/testbed';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { MatRadioGroupHarness } from '@angular/material/radio/testing';
import { MatSelectHarness } from '@angular/material/select/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { MailAccountResponse } from '../../../api/generated';
import { ApiError, NotificationService, provideHttp, SILENT_ERRORS } from '../../../core/http';
import {
  InvitationDialog,
  InvitationDialogData,
  InvitationPrompt,
  InvitationService,
} from './invitation-dialog';

const settle = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

/** A promise the test settles. */
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function account(id: string, emailAddress: string): MailAccountResponse {
  return { id, displayName: `Jane ${id}`, emailAddress } as MailAccountResponse;
}

const DATA: InvitationDialogData = {
  eventId: 'e1',
  title: 'Project kickoff',
  attendees: ['anna@example.test', 'ben@example.test'],
};

describe('InvitationDialog', () => {
  let fixture: ComponentFixture<InvitationDialog>;
  let ref: { close: ReturnType<typeof vi.fn>; disableClose: boolean };
  let service: { accounts: ReturnType<typeof vi.fn>; send: ReturnType<typeof vi.fn> };

  const el = () => fixture.nativeElement as HTMLElement;
  const button = (text: string) =>
    [...el().querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent!.trim() === text,
    )!;
  const alerts = () =>
    [...el().querySelectorAll<HTMLElement>('[role="alert"]')].map((a) => a.textContent!.trim());

  async function stable(): Promise<void> {
    await settle();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function open(
    accounts: Promise<MailAccountResponse[]>,
    data: InvitationDialogData = DATA,
  ): Promise<void> {
    ref = { close: vi.fn(), disableClose: false };
    service.accounts.mockReturnValue(accounts);
    TestBed.configureTestingModule({
      imports: [InvitationDialog],
      providers: [
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: ref },
        { provide: InvitationService, useValue: service },
      ],
    });
    fixture = TestBed.createComponent(InvitationDialog);
    fixture.detectChanges();
    await stable();
  }

  beforeEach(() => {
    service = {
      accounts: vi.fn(),
      send: vi.fn(() => Promise.resolve({ method: 'REQUEST', recipients: DATA.attendees })),
    };
  });

  it('shows the event and its guests and sends an invitation over the first account', async () => {
    await open(
      Promise.resolve([account('a1', 'jane@example.test'), account('a2', 'jd@example.test')]),
    );
    expect(el().textContent).toContain('Project kickoff');
    expect(el().textContent).toContain('anna@example.test, ben@example.test');

    button('Send').click();
    await stable();

    expect(service.send).toHaveBeenCalledExactlyOnceWith('e1', 'REQUEST', 'a1');
    expect(ref.close).toHaveBeenCalledExactlyOnceWith({
      method: 'REQUEST',
      recipients: DATA.attendees,
    });
  });

  it('sends a cancellation over the chosen account', async () => {
    await open(
      Promise.resolve([account('a1', 'jane@example.test'), account('a2', 'jd@example.test')]),
    );
    const loader = TestbedHarnessEnvironment.loader(fixture);
    await (
      await loader.getHarness(MatRadioGroupHarness)
    ).checkRadioButton({ label: 'Cancellation' });
    const select = await loader.getHarness(MatSelectHarness);
    await select.open();
    await select.clickOptions({ text: 'Jane a2 (jd@example.test)' });

    button('Send').click();
    await stable();

    expect(service.send).toHaveBeenCalledExactlyOnceWith('e1', 'CANCEL', 'a2');
  });

  it('cannot send without a mail account', async () => {
    await open(Promise.resolve([]));
    expect(alerts()).toContain('Add a mail account in the settings to send invitations.');
    expect(button('Try again')).toBeUndefined();
    expect(button('Send').disabled).toBe(true);
  });

  it('leaves the address of the chosen account out of the guests', async () => {
    await open(
      Promise.resolve([account('a1', 'jane@example.test'), account('a2', 'jd@example.test')]),
      { ...DATA, attendees: ['Jane@Example.test', 'anna@example.test', 'jd@example.test'] },
    );
    const guests = () => el().querySelector('#calendar-invite-guests')!.textContent!;
    expect(guests()).toContain('anna@example.test, jd@example.test');
    expect(guests()).not.toContain('Jane@Example.test');

    const select = await TestbedHarnessEnvironment.loader(fixture).getHarness(MatSelectHarness);
    await select.open();
    await select.clickOptions({ text: 'Jane a2 (jd@example.test)' });
    fixture.detectChanges();

    expect(guests()).toContain('Jane@Example.test, anna@example.test');
    expect(guests()).not.toContain('jd@example.test');
  });

  it('shows a refusal of the mail server and stays open', async () => {
    await open(Promise.resolve([account('a1', 'jane@example.test')]));
    service.send.mockRejectedValue(new ApiError(422, 'The mail server refused the message'));

    button('Send').click();
    await stable();

    expect(ref.close).not.toHaveBeenCalled();
    expect(ref.disableClose).toBe(false);
    expect(alerts()).toContain("The mail server refused the message. Check the guests' addresses.");
    expect(button('Send').disabled).toBe(false);
  });

  it('shows other errors with their message', async () => {
    await open(Promise.resolve([account('a1', 'jane@example.test')]));
    service.send.mockRejectedValue(new ApiError(502, 'The server could not be reached'));

    button('Send').click();
    await stable();

    expect(alerts()).toContain('The server could not be reached');
  });

  it('shows the first field error of a rejected request', async () => {
    await open(Promise.resolve([account('a1', 'jane@example.test')]));
    service.send.mockRejectedValue(
      new ApiError(400, 'Bad Request', 'Request validation failed', undefined, undefined, [
        { field: 'recipients', message: 'may only name attendees of the event for a REQUEST' },
      ]),
    );

    button('Send').click();
    await stable();

    expect(alerts()).toContain('may only name attendees of the event for a REQUEST');
    expect(alerts()).not.toContain('Request validation failed');
  });

  it('shows a generic message when sending fails without an API error', async () => {
    await open(Promise.resolve([account('a1', 'jane@example.test')]));
    service.send.mockRejectedValue(new Error('fictitious failure'));

    button('Send').click();
    await stable();

    expect(ref.close).not.toHaveBeenCalled();
    expect(alerts()).toContain('The invitations could not be sent.');
  });

  it('cannot send while the mail accounts are loading', async () => {
    const accounts = deferred<MailAccountResponse[]>();
    await open(accounts.promise);

    expect(el().textContent).toContain('Loading mail accounts…');
    expect(button('Send').disabled).toBe(true);

    accounts.resolve([account('a1', 'jane@example.test')]);
    await stable();
    expect(el().textContent).not.toContain('Loading mail accounts…');
    expect(button('Send').disabled).toBe(false);
  });

  it('shows why the mail accounts could not be loaded and cannot send', async () => {
    await open(Promise.reject(new ApiError(503, 'Service Unavailable', 'Try again later')));

    expect(el().textContent).not.toContain('Loading mail accounts…');
    expect(alerts()).toContain('Try again later');
    expect(alerts()).not.toContain('Add a mail account in the settings to send invitations.');
    expect(button('Send').disabled).toBe(true);
    expect(service.send).not.toHaveBeenCalled();
  });

  it('loads the mail accounts again after a failure', async () => {
    await open(Promise.reject(new ApiError(503, 'Service Unavailable', 'Try again later')));
    service.accounts.mockReturnValue(Promise.resolve([account('a1', 'jane@example.test')]));

    button('Try again').click();
    await stable();

    expect(service.accounts).toHaveBeenCalledTimes(2);
    expect(alerts()).toEqual([]);
    expect(button('Try again')).toBeUndefined();
    expect(button('Send').disabled).toBe(false);
    button('Send').click();
    await stable();
    expect(service.send).toHaveBeenCalledExactlyOnceWith('e1', 'REQUEST', 'a1');
  });

  it('shows a generic message when the mail accounts fail without an API error', async () => {
    await open(Promise.reject(new Error('fictitious failure')));

    expect(alerts()).toContain('The mail accounts could not be loaded.');
    expect(alerts()).not.toContain('Add a mail account in the settings to send invitations.');
    expect(button('Send').disabled).toBe(true);
  });

  it('sends once and cannot be closed while the mail is on its way', async () => {
    await open(Promise.resolve([account('a1', 'jane@example.test')]));
    const sent = deferred<{ method: 'REQUEST'; recipients: string[] }>();
    service.send.mockReturnValue(sent.promise);

    button('Send').click();
    await stable();

    expect(ref.disableClose).toBe(true);
    expect(button('Send').disabled).toBe(true);
    expect(button('Cancel').disabled).toBe(true);
    // A second click (e.g. a double-click before the button disables) sends nothing more.
    void (fixture.componentInstance as unknown as { send(): Promise<void> }).send();
    button('Send').click();
    await stable();
    expect(service.send).toHaveBeenCalledTimes(1);
    expect(ref.close).not.toHaveBeenCalled();

    sent.resolve({ method: 'REQUEST', recipients: ['anna@example.test'] });
    await stable();
    expect(ref.close).toHaveBeenCalledExactlyOnceWith({
      method: 'REQUEST',
      recipients: ['anna@example.test'],
    });
  });

  it('can be closed again after a failed send', async () => {
    await open(Promise.resolve([account('a1', 'jane@example.test')]));
    const sent = deferred<never>();
    service.send.mockReturnValue(sent.promise);

    button('Send').click();
    await stable();
    expect(ref.disableClose).toBe(true);

    sent.reject(new ApiError(0, 'The server could not be reached'));
    await stable();
    expect(ref.disableClose).toBe(false);
    expect(button('Cancel').disabled).toBe(false);
    expect(button('Send').disabled).toBe(false);
  });
});

describe('InvitationService', () => {
  let http: HttpTestingController;
  let invitations: InvitationService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: { error: vi.fn(), info: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    invitations = TestBed.inject(InvitationService);
  });

  afterEach(() => http.verify());

  it('lists the mail accounts silently', async () => {
    const accounts = invitations.accounts();
    const req = http.expectOne((r) => r.method === 'GET' && r.url === '/api/v1/mail/accounts');
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush({ items: [account('a1', 'jane@example.test')] });

    await expect(accounts).resolves.toEqual([account('a1', 'jane@example.test')]);
  });

  it('posts the method and account to the invitations of the event, silently', async () => {
    const sent = invitations.send('e1', 'CANCEL', 'a1');
    const req = http.expectOne(
      (r) => r.method === 'POST' && r.url === '/api/v1/calendar/events/e1/invitations',
    );
    expect(req.request.body).toEqual({ method: 'CANCEL', accountId: 'a1' });
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush({ method: 'CANCEL', recipients: ['anna@example.test'] });

    await expect(sent).resolves.toEqual({ method: 'CANCEL', recipients: ['anna@example.test'] });
  });

  it('rejects with the Problem Details of a failed send', async () => {
    const sent = invitations.send('e1', 'REQUEST', 'a1');
    http.expectOne('/api/v1/calendar/events/e1/invitations').flush(
      {
        status: 422,
        title: 'Unprocessable Content',
        detail: 'Refused',
        errorCode: 'SEND_REJECTED',
      },
      {
        status: 422,
        statusText: 'Unprocessable Content',
        headers: { 'Content-Type': 'application/problem+json' },
      },
    );

    await expect(sent).rejects.toMatchObject({ status: 422, detail: 'Refused' });
  });
});

describe('InvitationPrompt', () => {
  it('opens the invitation dialog with the event and resolves what was sent', async () => {
    const open = vi.fn(() => ({
      afterClosed: () => of({ method: 'REQUEST', recipients: ['anna@example.test'] }),
    }));
    TestBed.configureTestingModule({ providers: [{ provide: MatDialog, useValue: { open } }] });

    const sent = await TestBed.inject(InvitationPrompt).open(DATA);

    expect(sent).toEqual({ method: 'REQUEST', recipients: ['anna@example.test'] });
    expect(open).toHaveBeenCalledWith(InvitationDialog, expect.objectContaining({ data: DATA }));
  });

  it('resolves undefined when the user cancels', async () => {
    const open = vi.fn(() => ({ afterClosed: () => of(undefined) }));
    TestBed.configureTestingModule({ providers: [{ provide: MatDialog, useValue: { open } }] });

    await expect(TestBed.inject(InvitationPrompt).open(DATA)).resolves.toBeUndefined();
  });
});
