import { TestbedHarnessEnvironment } from '@angular/cdk/testing/testbed';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatRadioGroupHarness } from '@angular/material/radio/testing';
import { MatSelectHarness } from '@angular/material/select/testing';
import { MailAccountResponse } from '../../../api/generated';
import { ApiError } from '../../../core/http';
import { InvitationDialog, InvitationDialogData, InvitationService } from './invitation-dialog';

const settle = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

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

  async function open(accounts: Promise<MailAccountResponse[]>): Promise<void> {
    ref = { close: vi.fn(), disableClose: false };
    service.accounts.mockReturnValue(accounts);
    TestBed.configureTestingModule({
      imports: [InvitationDialog],
      providers: [
        { provide: MAT_DIALOG_DATA, useValue: DATA },
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
    expect(button('Send').disabled).toBe(true);
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
});
