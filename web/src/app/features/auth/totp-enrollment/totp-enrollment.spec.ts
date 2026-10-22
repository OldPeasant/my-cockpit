import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { TotpEnrollmentResponse } from '../../../api/generated';
import { AuthService } from '../../../core/auth';
import { ApiError, NotificationService } from '../../../core/http';
import { TotpEnrollment } from './totp-enrollment';

@Component({ template: 'target' })
class Target {}

const ENROLLMENT: TotpEnrollmentResponse = {
  otpauthUri: 'otpauth://totp/My%20Cockpit:demo?secret=JBSWY3DPEHPK3PXP&issuer=My%20Cockpit',
  totpSecret: 'JBSWY3DPEHPK3PXP',
};

describe('TotpEnrollment', () => {
  let harness: RouterTestingHarness;
  let router: Router;
  let root: HTMLElement;
  let auth: {
    startTotpEnrollment: ReturnType<typeof vi.fn>;
    confirmTotpEnrollment: ReturnType<typeof vi.fn>;
    logout: ReturnType<typeof vi.fn>;
    checkSession: ReturnType<typeof vi.fn>;
  };
  let notifications: { info: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };

  async function open(url = '/enroll-totp'): Promise<void> {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'enroll-totp', component: TotpEnrollment },
          { path: 'login', component: Target },
          { path: 'inbox', component: Target },
          { path: '', component: Target },
        ]),
        { provide: AuthService, useValue: auth },
        { provide: NotificationService, useValue: notifications },
      ],
    });
    harness = await RouterTestingHarness.create(url);
    router = TestBed.inject(Router);
    root = harness.routeNativeElement as HTMLElement;
    await settle();
  }

  beforeEach(() => {
    auth = {
      startTotpEnrollment: vi.fn(() => Promise.resolve(ENROLLMENT)),
      confirmTotpEnrollment: vi.fn(() => Promise.resolve()),
      logout: vi.fn(() => Promise.resolve()),
      checkSession: vi.fn(() => Promise.resolve(true)),
    };
    notifications = { info: vi.fn(), error: vi.fn() };
  });

  function type(value: string): void {
    const input = root.querySelector<HTMLInputElement>('input[name=code]');
    if (!input) throw new Error('no code input');
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

  it('starts the enrollment and shows the QR code and the key', async () => {
    await open();
    expect(auth.startTotpEnrollment).toHaveBeenCalledTimes(1);
    expect(root.querySelector('app-qr-code svg path')).not.toBeNull();
    expect(root.querySelector('.secret')?.textContent).toBe('JBSWY3DPEHPK3PXP');
  });

  it('confirms with the code, then opens the return URL', async () => {
    await open('/enroll-totp?returnUrl=%2Finbox');
    type('123 456');
    await submit();
    expect(auth.confirmTotpEnrollment).toHaveBeenCalledWith('123456');
    expect(notifications.info).toHaveBeenCalled();
    expect(router.url).toBe('/inbox');
  });

  it('opens the dashboard when the return URL points elsewhere', async () => {
    await open('/enroll-totp?returnUrl=%2F%2Fevil.example');
    type('123456');
    await submit();
    expect(router.url).toBe('/');
  });

  it("shows the server's validation error for a wrong code and clears it", async () => {
    await open();
    auth.confirmTotpEnrollment.mockRejectedValueOnce(
      new ApiError(400, 'Bad Request', undefined, undefined, undefined, [
        { field: 'code', message: 'is not a valid code for the new TOTP secret' },
      ]),
    );
    type('123456');
    await submit();
    expect(root.querySelector('[role=alert]')?.textContent).toContain(
      'is not a valid code for the new TOTP secret',
    );
    expect(root.querySelector<HTMLInputElement>('input[name=code]')!.value).toBe('');
    expect(router.url).toBe('/enroll-totp');
  });

  it('shows the wait time when throttled', async () => {
    await open();
    auth.confirmTotpEnrollment.mockRejectedValueOnce(
      new ApiError(429, 'Too Many Requests', undefined, undefined, undefined, [], 30),
    );
    type('123456');
    await submit();
    expect(root.querySelector('[role=alert]')?.textContent).toContain('Try again in 30 s.');
  });

  it('does not send a code that is not 6 digits', async () => {
    await open();
    type('12345');
    await submit();
    expect(auth.confirmTotpEnrollment).not.toHaveBeenCalled();
    expect(root.textContent).toContain('Enter the 6 digits shown in the app.');
  });

  it('offers to try again when starting fails', async () => {
    auth.startTotpEnrollment.mockRejectedValueOnce(
      new ApiError(0, 'The server cannot be reached.'),
    );
    await open();
    expect(root.querySelector('[role=alert]')?.textContent).toContain(
      'The server cannot be reached.',
    );
    expect(root.querySelector('form')).toBeNull();
    root.querySelector<HTMLButtonElement>('button.retry')!.click();
    await settle();
    expect(auth.startTotpEnrollment).toHaveBeenCalledTimes(2);
    expect(root.querySelector('.secret')?.textContent).toBe('JBSWY3DPEHPK3PXP');
  });

  it('signs out to the login page', async () => {
    await open();
    root.querySelector<HTMLButtonElement>('button.sign-out')!.click();
    await settle();
    expect(auth.logout).toHaveBeenCalled();
    expect(router.url).toBe('/login');
  });

  it('still goes to the login page when signing out fails', async () => {
    auth.logout.mockRejectedValueOnce(new ApiError(0, 'The server cannot be reached.'));
    await open();
    root.querySelector<HTMLButtonElement>('button.sign-out')!.click();
    await settle();
    expect(auth.logout).toHaveBeenCalledTimes(1);
    expect(router.url).toBe('/login');
  });

  it('offers sign out while starting failed', async () => {
    auth.startTotpEnrollment.mockRejectedValueOnce(new ApiError(502, 'Bad Gateway'));
    await open();
    expect(root.querySelector('[role=alert]')?.textContent).toContain('Bad Gateway');
    root.querySelector<HTMLButtonElement>('button.sign-out')!.click();
    await settle();
    expect(router.url).toBe('/login');
  });

  it('shows a loading state while the new key is being created', async () => {
    let resolve!: (value: TotpEnrollmentResponse) => void;
    auth.startTotpEnrollment.mockImplementationOnce(() => new Promise((r) => (resolve = r)));
    await open();
    expect(root.querySelector('.loading[role=status]')).not.toBeNull();
    expect(root.querySelector('form')).toBeNull();
    resolve(ENROLLMENT);
    await settle();
    expect(root.querySelector('.loading')).toBeNull();
    expect(root.querySelector('.secret')?.textContent).toBe('JBSWY3DPEHPK3PXP');
  });

  it('starts only once when "Try again" is clicked twice quickly', async () => {
    auth.startTotpEnrollment.mockRejectedValueOnce(
      new ApiError(0, 'The server cannot be reached.'),
    );
    await open();
    let resolve!: (value: TotpEnrollmentResponse) => void;
    auth.startTotpEnrollment.mockImplementationOnce(() => new Promise((r) => (resolve = r)));
    const retry = root.querySelector<HTMLButtonElement>('button.retry')!;
    retry.click();
    retry.click();
    expect(auth.startTotpEnrollment).toHaveBeenCalledTimes(2);
    resolve(ENROLLMENT);
    await settle();
    expect(root.querySelector('.secret')?.textContent).toBe('JBSWY3DPEHPK3PXP');
  });

  it('shows the failure again when the retry also fails', async () => {
    auth.startTotpEnrollment
      .mockRejectedValueOnce(new ApiError(0, 'The server cannot be reached.'))
      .mockRejectedValueOnce(new ApiError(502, 'Bad Gateway'));
    await open();
    root.querySelector<HTMLButtonElement>('button.retry')!.click();
    await settle();
    expect(auth.startTotpEnrollment).toHaveBeenCalledTimes(2);
    expect(root.querySelector('[role=alert]')?.textContent).toContain('Bad Gateway');
    expect(root.querySelector('.loading')).toBeNull();
    expect(root.querySelector<HTMLButtonElement>('button.retry')!.disabled).toBe(false);
  });

  it('sends a single confirmation when the form is submitted twice quickly', async () => {
    await open('/enroll-totp?returnUrl=%2Finbox');
    let resolve!: () => void;
    auth.confirmTotpEnrollment.mockImplementationOnce(
      () => new Promise<void>((r) => (resolve = r)),
    );
    type('123456');
    const form = root.querySelector('form')!;
    form.dispatchEvent(new Event('submit'));
    form.dispatchEvent(new Event('submit'));
    await harness.fixture.whenStable();
    expect(auth.confirmTotpEnrollment).toHaveBeenCalledTimes(1);
    expect(root.querySelector<HTMLButtonElement>('button[type=submit]')!.disabled).toBe(true);
    resolve();
    await vi.waitFor(() => expect(router.url).toBe('/inbox'));
  });

  it('removes a space around the code before sending it', async () => {
    await open();
    // The field allows 7 characters (maxlength), so at most one extra space fits.
    type(' 654321');
    await submit();
    expect(auth.confirmTotpEnrollment).toHaveBeenCalledWith('654321');
  });

  it('does not send an empty code', async () => {
    await open();
    await submit();
    expect(auth.confirmTotpEnrollment).not.toHaveBeenCalled();
    expect(router.url).toBe('/enroll-totp');
  });

  it('says to try again later when throttled without Retry-After', async () => {
    await open();
    auth.confirmTotpEnrollment.mockRejectedValueOnce(new ApiError(429, 'Too Many Requests'));
    type('123456');
    await submit();
    expect(root.querySelector('[role=alert]')?.textContent).toContain(
      'Too many failed attempts. Try again later.',
    );
  });

  it('refreshes the session and opens the return URL when confirming is no longer required (409)', async () => {
    await open('/enroll-totp?returnUrl=%2Finbox');
    auth.confirmTotpEnrollment.mockRejectedValueOnce(
      new ApiError(409, 'Conflict', 'TOTP re-enrollment is not required'),
    );
    type('123456');
    await submit();
    expect(auth.checkSession).toHaveBeenCalledWith(true);
    expect(router.url).toBe('/inbox');
  });

  it('refreshes the session and opens the app when starting is no longer required (409)', async () => {
    auth.startTotpEnrollment.mockRejectedValueOnce(
      new ApiError(409, 'Conflict', 'TOTP re-enrollment is not required'),
    );
    await open('/enroll-totp?returnUrl=%2F%2Fevil.example');
    expect(auth.checkSession).toHaveBeenCalledWith(true);
    expect(router.url).toBe('/');
  });

  it('still leaves the page after a 409 when refreshing the session fails', async () => {
    auth.startTotpEnrollment.mockRejectedValueOnce(
      new ApiError(409, 'Conflict', 'TOTP re-enrollment is not required'),
    );
    auth.checkSession.mockRejectedValueOnce(new ApiError(0, 'The server cannot be reached.'));
    await open('/enroll-totp?returnUrl=%2Finbox');
    expect(router.url).toBe('/inbox');
  });

  it('goes to the login page with the return URL when the session ended on confirm (401)', async () => {
    await open('/enroll-totp?returnUrl=%2Finbox');
    auth.confirmTotpEnrollment.mockRejectedValueOnce(new ApiError(401, 'Unauthorized'));
    type('123456');
    await submit();
    expect(router.url).toBe('/login?returnUrl=%2Finbox');
    expect(auth.checkSession).not.toHaveBeenCalled();
  });

  it('goes to the login page when the session ended before starting (401)', async () => {
    auth.startTotpEnrollment.mockRejectedValueOnce(new ApiError(401, 'Unauthorized'));
    await open('/enroll-totp?returnUrl=%2Finbox');
    expect(router.url).toBe('/login?returnUrl=%2Finbox');
  });

  it('drops a return URL of / on the way to the login page (401)', async () => {
    auth.startTotpEnrollment.mockRejectedValueOnce(new ApiError(401, 'Unauthorized'));
    await open();
    expect(router.url).toBe('/login');
  });

  it('creates a new key on request and shows it', async () => {
    await open();
    auth.startTotpEnrollment.mockResolvedValueOnce({
      otpauthUri: 'otpauth://totp/My%20Cockpit:demo?secret=KRSXG5CTMVRXEZLU&issuer=My%20Cockpit',
      totpSecret: 'KRSXG5CTMVRXEZLU',
    });
    root.querySelector<HTMLButtonElement>('button.new-key')!.click();
    await settle();
    expect(auth.startTotpEnrollment).toHaveBeenCalledTimes(2);
    expect(root.querySelector('.secret')?.textContent).toBe('KRSXG5CTMVRXEZLU');
    expect(router.url).toBe('/enroll-totp');
  });

  it('hints at a replaced key after a refused code and clears the hint with a new key', async () => {
    await open();
    auth.confirmTotpEnrollment.mockRejectedValueOnce(
      new ApiError(400, 'Bad Request', undefined, undefined, undefined, [
        { field: 'code', message: 'is not a valid code for the new TOTP secret' },
      ]),
    );
    type('123456');
    await submit();
    expect(root.querySelector('.key-replaced')?.textContent).toContain('Create a new key');
    root.querySelector<HTMLButtonElement>('button.new-key')!.click();
    await settle();
    expect(root.querySelector('.key-replaced')).toBeNull();
    expect(root.querySelector('[role=alert]')).toBeNull();
  });

  it('does not hint at a replaced key when throttled', async () => {
    await open();
    auth.confirmTotpEnrollment.mockRejectedValueOnce(new ApiError(429, 'Too Many Requests'));
    type('123456');
    await submit();
    expect(root.querySelector('.key-replaced')).toBeNull();
  });

  it('does not confirm while a new key is being created', async () => {
    await open();
    auth.startTotpEnrollment.mockImplementationOnce(
      () => new Promise<TotpEnrollmentResponse>(() => undefined),
    );
    root.querySelector<HTMLButtonElement>('button.new-key')!.click();
    await harness.fixture.whenStable();
    expect(root.querySelector<HTMLButtonElement>('button[type=submit]')!.disabled).toBe(true);
    type('123456');
    root.querySelector('form')!.dispatchEvent(new Event('submit'));
    await harness.fixture.whenStable();
    expect(auth.confirmTotpEnrollment).not.toHaveBeenCalled();
  });

  it('cannot create a new key while a confirmation is running', async () => {
    await open();
    auth.confirmTotpEnrollment.mockImplementationOnce(() => new Promise<void>(() => undefined));
    type('123456');
    root.querySelector('form')!.dispatchEvent(new Event('submit'));
    await harness.fixture.whenStable();
    expect(root.querySelector<HTMLButtonElement>('button.new-key')!.disabled).toBe(true);
  });

  it('joins several server validation messages', async () => {
    await open();
    auth.confirmTotpEnrollment.mockRejectedValueOnce(
      new ApiError(400, 'Bad Request', undefined, undefined, undefined, [
        { field: 'code', message: 'must not be blank' },
        { field: 'code', message: 'size must be between 0 and 16' },
      ]),
    );
    type('123456');
    await submit();
    expect(root.querySelector('[role=alert]')?.textContent).toContain(
      'must not be blank size must be between 0 and 16',
    );
  });

  it('accepts a new code after a wrong one and clears the error', async () => {
    await open('/enroll-totp?returnUrl=%2Finbox');
    auth.confirmTotpEnrollment.mockRejectedValueOnce(
      new ApiError(400, 'Bad Request', undefined, undefined, undefined, [
        { field: 'code', message: 'is not a valid code for the new TOTP secret' },
      ]),
    );
    type('111111');
    await submit();
    expect(root.querySelector('[role=alert]')).not.toBeNull();
    type('222222');
    await submit();
    expect(auth.confirmTotpEnrollment).toHaveBeenLastCalledWith('222222');
    expect(router.url).toBe('/inbox');
  });
});
