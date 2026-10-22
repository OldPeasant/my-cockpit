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
});
