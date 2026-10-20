import { Clipboard } from '@angular/cdk/clipboard';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { SetupResponse } from '../../../api/generated';
import { AuthService } from '../../../core/auth';
import { ApiError, NotificationService } from '../../../core/http';
import { RECOVERY_CODES_FILE, Setup } from './setup';

@Component({ template: 'login' })
class LoginPage {}

const ENROLLMENT: SetupResponse = {
  otpauthUri: 'otpauth://totp/My%20Cockpit:demo?secret=JBSWY3DPEHPK3PXP&issuer=My%20Cockpit',
  totpSecret: 'JBSWY3DPEHPK3PXP',
  recoveryCodes: Array.from({ length: 10 }, (_, i) => `code${i}-abcde`),
};

describe('Setup', () => {
  let harness: RouterTestingHarness;
  let root: HTMLElement;
  let auth: { startSetup: ReturnType<typeof vi.fn>; confirmSetup: ReturnType<typeof vi.fn> };
  let notifications: { info: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };
  let clipboard: { copy: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    auth = {
      startSetup: vi.fn(() => Promise.resolve(ENROLLMENT)),
      confirmSetup: vi.fn(() => Promise.resolve()),
    };
    notifications = { info: vi.fn(), error: vi.fn() };
    clipboard = { copy: vi.fn(() => true) };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'setup', component: Setup },
          { path: 'login', component: LoginPage },
        ]),
        { provide: AuthService, useValue: auth },
        { provide: NotificationService, useValue: notifications },
        { provide: Clipboard, useValue: clipboard },
      ],
    });
    harness = await RouterTestingHarness.create('/setup');
    root = harness.routeNativeElement as HTMLElement;
  });

  function type(name: string, value: string): void {
    const input = root.querySelector<HTMLInputElement>(`input[name=${name}]`);
    if (!input) throw new Error(`no input ${name}`);
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  async function settle(): Promise<void> {
    await harness.fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    await harness.fixture.whenStable();
  }

  async function submit(): Promise<void> {
    root.querySelector('form')!.dispatchEvent(new Event('submit'));
    await settle();
  }

  async function createAccount(): Promise<void> {
    type('username', 'demo');
    type('password', 'correct horse battery');
    type('confirm', 'correct horse battery');
    await submit();
  }

  it('creates the account with username and password', async () => {
    expect(root.querySelector('h1')?.textContent).toContain('Create your account');
    await createAccount();
    expect(auth.startSetup).toHaveBeenCalledWith({
      username: 'demo',
      password: 'correct horse battery',
    });
    expect(root.querySelector('h1')?.textContent).toContain('Connect your authenticator app');
  });

  it('requires 12 characters and matching passwords', async () => {
    type('username', 'demo');
    type('password', 'short');
    type('confirm', 'other');
    await submit();
    expect(auth.startSetup).not.toHaveBeenCalled();
    expect(root.textContent).toContain('Use at least 12 characters.');

    type('password', 'correct horse battery');
    await submit();
    expect(auth.startSetup).not.toHaveBeenCalled();
    expect(root.textContent).toContain('The passwords do not match.');
  });

  it('rejects usernames the server would reject', async () => {
    type('username', 'de mo');
    type('password', 'correct horse battery');
    type('confirm', 'correct horse battery');
    await submit();
    expect(auth.startSetup).not.toHaveBeenCalled();
  });

  it('shows a conflict from the server', async () => {
    auth.startSetup.mockRejectedValueOnce(
      new ApiError(409, 'Conflict', 'Setup is already complete.'),
    );
    await createAccount();
    expect(root.querySelector('[role=alert]')?.textContent).toContain('Setup is already complete.');
  });

  it('shows the QR code of the otpauth URI, the secret and the 10 recovery codes', async () => {
    await createAccount();
    const qr = root.querySelector('app-qr-code svg');
    expect(qr?.getAttribute('aria-label')).toBe('QR code for your authenticator app');
    expect(qr?.querySelector('path')?.getAttribute('d')).toMatch(/^M\d+ \d+h1v1h-1z/);
    expect(root.querySelector('.secret')?.textContent).toBe('JBSWY3DPEHPK3PXP');
    const codes = Array.from(root.querySelectorAll('.codes li')).map((li) =>
      li.textContent?.trim(),
    );
    expect(codes).toEqual(ENROLLMENT.recoveryCodes);
  });

  it('copies the recovery codes', async () => {
    await createAccount();
    root.querySelector<HTMLButtonElement>('button.copy')!.click();
    await settle();
    const text = clipboard.copy.mock.calls[0][0] as string;
    for (const code of ENROLLMENT.recoveryCodes) expect(text).toContain(code);
    expect(root.querySelector('button.copy')?.textContent?.trim()).toBe('Copied');
  });

  it('downloads the recovery codes as a text file', async () => {
    await createAccount();
    const createObjectURL = vi.fn<(blob: Blob) => string>(() => 'blob:codes');
    const revokeObjectURL = vi.fn();
    Object.assign(URL, { createObjectURL, revokeObjectURL });
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    root.querySelector<HTMLButtonElement>('button.download')!.click();
    const anchor = click.mock.contexts[0] as HTMLAnchorElement;
    expect(anchor.download).toBe(RECOVERY_CODES_FILE);
    const text = await createObjectURL.mock.calls[0][0].text();
    for (const code of ENROLLMENT.recoveryCodes) expect(text).toContain(code);
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:codes');
    click.mockRestore();
  });

  it('needs the saved-codes checkbox before confirming', async () => {
    await createAccount();
    type('code', '123456');
    await submit();
    expect(auth.confirmSetup).not.toHaveBeenCalled();
    expect(root.textContent).toContain('Save the codes first');
  });

  it('confirms the TOTP code, then goes to the login page', async () => {
    await createAccount();
    root.querySelector<HTMLInputElement>('mat-checkbox input')!.click();
    type('code', '123 456');
    await submit();
    expect(auth.confirmSetup).toHaveBeenCalledWith('123456');
    expect(notifications.info).toHaveBeenCalled();
    expect(TestBed.inject(Router).url).toBe('/login');
  });

  it('shows a wrong code and lets the user retry', async () => {
    auth.confirmSetup.mockRejectedValueOnce(new ApiError(400, 'Bad Request'));
    await createAccount();
    root.querySelector<HTMLInputElement>('mat-checkbox input')!.click();
    type('code', '000000');
    await submit();
    expect(root.querySelector('[role=alert]')?.textContent).toContain('That code is not valid.');
    expect(root.querySelector<HTMLInputElement>('input[name=code]')!.value).toBe('');
    expect(TestBed.inject(Router).url).toBe('/setup');
  });
});
