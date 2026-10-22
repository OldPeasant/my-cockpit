import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Component } from '@angular/core';
import { AuthService } from '../../../core/auth';
import { ApiError } from '../../../core/http';
import { PasskeyLogin } from '../passkey/passkey-login.service';
import { PasskeyError } from '../passkey/webauthn';
import { Login } from './login';

@Component({ template: 'target' })
class Target {}

describe('Login', () => {
  let harness: RouterTestingHarness;
  let router: Router;
  let login: ReturnType<typeof vi.fn>;
  let root: HTMLElement;

  async function open(url = '/login'): Promise<void> {
    login = vi.fn(() => Promise.resolve({ username: 'demo' }));
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'login', component: Login },
          { path: 'inbox', component: Target },
          { path: 'enroll-totp', component: Target },
          { path: '', component: Target },
        ]),
        { provide: AuthService, useValue: { login } },
      ],
    });
    harness = await RouterTestingHarness.create(url);
    router = TestBed.inject(Router);
    root = harness.routeNativeElement as HTMLElement;
  }

  function fixture(): ComponentFixture<unknown> {
    return harness.fixture;
  }

  function type(selector: string, value: string): void {
    const input = root.querySelector<HTMLInputElement>(selector);
    if (!input) throw new Error(`no ${selector}`);
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  async function submit(): Promise<void> {
    root.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture().whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    await fixture().whenStable();
  }

  function fill(code = '123 456'): void {
    type('input[formControlName=username]', ' demo ');
    type('input[formControlName=password]', 'a long password');
    type('input[name=totp]', code);
  }

  afterEach(() => vi.useRealTimers());

  it('logs in with password and TOTP, then goes to the return URL', async () => {
    await open('/login?returnUrl=%2Finbox');
    fill();
    await submit();
    expect(login).toHaveBeenCalledWith({
      username: 'demo',
      password: 'a long password',
      rememberDevice: false,
      totp: '123456',
    });
    expect(router.url).toBe('/inbox');
  });

  it('goes to the dashboard when the return URL points elsewhere', async () => {
    await open('/login?returnUrl=https%3A%2F%2Fevil.example');
    fill();
    await submit();
    expect(router.url).toBe('/');
  });

  it('does not submit an incomplete form', async () => {
    await open();
    type('input[formControlName=username]', 'demo');
    await submit();
    expect(login).not.toHaveBeenCalled();
  });

  it('rejects a TOTP that is not 6 digits', async () => {
    await open();
    fill('12345');
    await submit();
    expect(login).not.toHaveBeenCalled();
    expect(root.textContent).toContain('Enter the 6 digits shown in the app.');
  });

  it('switches to a recovery code and sends it instead of the TOTP', async () => {
    await open();
    root.querySelector<HTMLButtonElement>('button.switch')!.click();
    await fixture().whenStable();
    expect(root.querySelector('input[name=totp]')).toBeNull();
    type('input[formControlName=username]', 'demo');
    type('input[formControlName=password]', 'a long password');
    type('input[name=recoveryCode]', ' abcde-fghjk ');
    root.querySelector<HTMLInputElement>('mat-checkbox input')!.click();
    await submit();
    expect(login).toHaveBeenCalledWith({
      username: 'demo',
      password: 'a long password',
      rememberDevice: true,
      recoveryCode: 'abcde-fghjk',
    });
  });

  it('shows a message on wrong credentials and clears the code', async () => {
    await open();
    login.mockRejectedValueOnce(new ApiError(401, 'Unauthorized'));
    fill();
    await submit();
    expect(root.querySelector('[role=alert]')?.textContent).toContain(
      'Wrong username, password or code.',
    );
    expect(root.querySelector<HTMLInputElement>('input[name=totp]')!.value).toBe('');
    expect(root.querySelector<HTMLInputElement>('input[formControlName=password]')!.value).toBe(
      'a long password',
    );
  });

  it('shows the backoff wait time on 429 and counts it down', async () => {
    await open();
    vi.useFakeTimers();
    login.mockRejectedValueOnce(
      new ApiError(429, 'Too Many Requests', undefined, undefined, undefined, [], 3),
    );
    fill();
    root.querySelector('form')!.dispatchEvent(new Event('submit'));
    await vi.advanceTimersByTimeAsync(0);
    fixture().detectChanges();
    const notice = () =>
      root.querySelector('.notice.backoff')?.textContent?.replace(/\s+/g, ' ').trim();
    const alert = () => root.querySelector('[role=alert]')?.textContent?.trim();
    const button = () => root.querySelector<HTMLButtonElement>('button[type=submit]')!;
    expect(notice()).toBe('Too many failed attempts. Try again in 3 s.');
    // Only the static sentence is announced; the ticking countdown stays out of the live region.
    expect(alert()).toBe('Too many failed attempts.');
    expect(button().disabled).toBe(true);

    await vi.advanceTimersByTimeAsync(1000);
    fixture().detectChanges();
    expect(notice()).toBe('Too many failed attempts. Try again in 2 s.');
    expect(alert()).toBe('Too many failed attempts.');

    await vi.advanceTimersByTimeAsync(2000);
    fixture().detectChanges();
    expect(notice()).toBeUndefined();
    expect(alert()).toBeUndefined();
    expect(button().disabled).toBe(false);
  });

  it('waits 60 s when a 429 comes without Retry-After', async () => {
    await open();
    vi.useFakeTimers();
    login.mockRejectedValueOnce(new ApiError(429, 'Too Many Requests'));
    fill();
    root.querySelector('form')!.dispatchEvent(new Event('submit'));
    await vi.advanceTimersByTimeAsync(0);
    fixture().detectChanges();
    const notice = () =>
      root.querySelector('.notice.backoff')?.textContent?.replace(/\s+/g, ' ').trim();
    expect(notice()).toBe('Too many failed attempts. Try again in 60 s.');

    await vi.advanceTimersByTimeAsync(59_000);
    fixture().detectChanges();
    expect(notice()).toBe('Too many failed attempts. Try again in 1 s.');
    await vi.advanceTimersByTimeAsync(1000);
    fixture().detectChanges();
    expect(notice()).toBeUndefined();
  });

  it('ignores a submit (e.g. Enter) during the backoff and accepts one afterwards', async () => {
    await open();
    vi.useFakeTimers();
    login.mockRejectedValueOnce(
      new ApiError(429, 'Too Many Requests', undefined, undefined, undefined, [], 2),
    );
    fill();
    root.querySelector('form')!.dispatchEvent(new Event('submit'));
    await vi.advanceTimersByTimeAsync(0);
    fixture().detectChanges();
    expect(login).toHaveBeenCalledTimes(1);
    // The code was cleared after the failure: type a new one and press Enter while blocked.
    type('input[name=totp]', '654321');
    root.querySelector('form')!.dispatchEvent(new Event('submit'));
    await vi.advanceTimersByTimeAsync(0);
    expect(login).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(2000);
    root.querySelector('form')!.dispatchEvent(new Event('submit'));
    await vi.advanceTimersByTimeAsync(0);
    expect(login).toHaveBeenCalledTimes(2);
  });

  it('stops the backoff countdown when the page is left', async () => {
    await open();
    vi.useFakeTimers();
    login.mockRejectedValueOnce(
      new ApiError(429, 'Too Many Requests', undefined, undefined, undefined, [], 30),
    );
    const setIntervalSpy = vi.spyOn(globalThis, 'setInterval');
    const clearIntervalSpy = vi.spyOn(globalThis, 'clearInterval');
    fill();
    root.querySelector('form')!.dispatchEvent(new Event('submit'));
    await vi.advanceTimersByTimeAsync(0);
    const index = setIntervalSpy.mock.calls.findIndex((call) => call[1] === 1000);
    const countdown = setIntervalSpy.mock.results[index]?.value;
    expect(countdown).toBeDefined();
    expect(clearIntervalSpy).not.toHaveBeenCalledWith(countdown);
    fixture().destroy();
    expect(clearIntervalSpy).toHaveBeenCalledWith(countdown);
    setIntervalSpy.mockRestore();
    clearIntervalSpy.mockRestore();
  });

  it('sends a single login request when the form is submitted twice quickly', async () => {
    await open();
    let resolve!: (value: { username: string }) => void;
    login.mockImplementationOnce(() => new Promise((r) => (resolve = r)));
    fill();
    const form = root.querySelector('form')!;
    form.dispatchEvent(new Event('submit'));
    form.dispatchEvent(new Event('submit'));
    await fixture().whenStable();
    expect(login).toHaveBeenCalledTimes(1);
    expect(root.querySelector<HTMLButtonElement>('button[type=submit]')!.disabled).toBe(true);
    resolve({ username: 'demo' });
    await vi.waitFor(() => expect(router.url).toBe('/'));
  });

  it('switches back from the recovery code to the authenticator code', async () => {
    await open();
    const toggle = () => root.querySelector<HTMLButtonElement>('button.switch')!;
    expect(toggle().textContent?.trim()).toBe('Use a recovery code');
    toggle().click();
    await fixture().whenStable();
    expect(toggle().textContent?.trim()).toBe('Use the authenticator app instead');
    expect(root.querySelector('input[name=recoveryCode]')).not.toBeNull();
    toggle().click();
    await fixture().whenStable();
    expect(root.querySelector('input[name=recoveryCode]')).toBeNull();
    fill();
    await submit();
    expect(login).toHaveBeenCalledWith({
      username: 'demo',
      password: 'a long password',
      rememberDevice: false,
      totp: '123456',
    });
  });

  it('does not send an empty recovery code', async () => {
    await open();
    root.querySelector<HTMLButtonElement>('button.switch')!.click();
    await fixture().whenStable();
    type('input[formControlName=username]', 'demo');
    type('input[formControlName=password]', 'a long password');
    await submit();
    expect(login).not.toHaveBeenCalled();
  });

  it('lets the user retry with a new code after wrong credentials', async () => {
    await open();
    login.mockRejectedValueOnce(new ApiError(401, 'Unauthorized'));
    fill();
    await submit();
    expect(root.querySelector('[role=alert]')).not.toBeNull();
    type('input[name=totp]', '654321');
    await submit();
    expect(login).toHaveBeenCalledTimes(2);
    expect(router.url).toBe('/');
  });

  it('shows the server message on other errors', async () => {
    await open();
    login.mockRejectedValueOnce(new ApiError(0, 'The server cannot be reached.'));
    fill();
    await submit();
    expect(root.querySelector('[role=alert]')?.textContent).toContain(
      'The server cannot be reached.',
    );
  });

  it('opens the TOTP enrollment instead of the app when the server requires it', async () => {
    await open('/login?returnUrl=%2Finbox');
    login.mockResolvedValueOnce({ username: 'demo', totpEnrollmentRequired: true });
    root.querySelector<HTMLButtonElement>('button.switch')!.click();
    await fixture().whenStable();
    type('input[formControlName=username]', 'demo');
    type('input[formControlName=password]', 'a long password');
    type('input[name=recoveryCode]', 'abcde-fghjk');
    await submit();
    expect(login).toHaveBeenCalledWith({
      username: 'demo',
      password: 'a long password',
      rememberDevice: false,
      recoveryCode: 'abcde-fghjk',
    });
    expect(router.url).toBe('/enroll-totp?returnUrl=%2Finbox');
  });

  it('opens the TOTP enrollment without a return URL when going to the dashboard', async () => {
    await open();
    login.mockResolvedValueOnce({ username: 'demo', totpEnrollmentRequired: true });
    fill();
    await submit();
    expect(router.url).toBe('/enroll-totp');
  });

  it('opens the app when the server says no enrollment is required', async () => {
    await open();
    login.mockResolvedValueOnce({ username: 'demo', totpEnrollmentRequired: false });
    fill();
    await submit();
    expect(router.url).toBe('/');
  });

  it('logs in with the password alone after a restore with no recovery code left', async () => {
    await open();
    expect(root.querySelector('button.password-switch')).toBeNull();
    root.querySelector<HTMLButtonElement>('button.switch')!.click();
    await fixture().whenStable();
    root.querySelector<HTMLButtonElement>('button.password-switch')!.click();
    await fixture().whenStable();
    expect(root.querySelector('input[name=totp]')).toBeNull();
    expect(root.querySelector('input[name=recoveryCode]')).toBeNull();
    expect(root.querySelector('.password-only')?.textContent).toContain('password alone');
    login.mockResolvedValueOnce({ username: 'demo', totpEnrollmentRequired: true });
    type('input[formControlName=username]', 'demo');
    type('input[formControlName=password]', 'a long password');
    await submit();
    expect(login).toHaveBeenCalledWith({
      username: 'demo',
      password: 'a long password',
      rememberDevice: false,
    });
    expect(router.url).toBe('/enroll-totp');
  });

  async function usePasswordOnly(): Promise<void> {
    root.querySelector<HTMLButtonElement>('button.switch')!.click();
    await fixture().whenStable();
    root.querySelector<HTMLButtonElement>('button.password-switch')!.click();
    await fixture().whenStable();
    type('input[formControlName=username]', 'demo');
    type('input[formControlName=password]', 'a long password');
  }

  it('drops an unsafe return URL when routing to the TOTP enrollment', async () => {
    await open('/login?returnUrl=%2F%2Fevil.example');
    login.mockResolvedValueOnce({ username: 'demo', totpEnrollmentRequired: true });
    fill();
    await submit();
    expect(router.url).toBe('/enroll-totp');
  });

  it('opens the return URL after a password-only login that needs no enrollment', async () => {
    await open('/login?returnUrl=%2Finbox');
    await usePasswordOnly();
    login.mockResolvedValueOnce({ username: 'demo', totpEnrollmentRequired: false });
    await submit();
    expect(login).toHaveBeenCalledWith({
      username: 'demo',
      password: 'a long password',
      rememberDevice: false,
    });
    expect(router.url).toBe('/inbox');
  });

  it('does not send a password-only login without a password', async () => {
    await open();
    root.querySelector<HTMLButtonElement>('button.switch')!.click();
    await fixture().whenStable();
    root.querySelector<HTMLButtonElement>('button.password-switch')!.click();
    await fixture().whenStable();
    type('input[formControlName=username]', 'demo');
    await submit();
    expect(login).not.toHaveBeenCalled();
  });

  it('starts the backoff when a password-only login is throttled', async () => {
    await open();
    await usePasswordOnly();
    login.mockRejectedValueOnce(
      new ApiError(429, 'Too Many Requests', undefined, undefined, undefined, [], 5),
    );
    await submit();
    expect(root.querySelector('.notice.backoff')?.textContent?.replace(/\s+/g, ' ')).toContain(
      'Try again in 5 s.',
    );
    expect(root.querySelector<HTMLButtonElement>('button[type=submit]')!.disabled).toBe(true);
  });

  it('shows the server message on other errors of a password-only login', async () => {
    await open();
    await usePasswordOnly();
    login.mockRejectedValueOnce(new ApiError(0, 'The server cannot be reached.'));
    await submit();
    const alert = root.querySelector('[role=alert]')?.textContent;
    expect(alert).toContain('The server cannot be reached.');
    expect(alert).not.toContain('recovery code');
  });

  it('explains a refused password-only login and goes back to the app code', async () => {
    await open();
    root.querySelector<HTMLButtonElement>('button.switch')!.click();
    await fixture().whenStable();
    root.querySelector<HTMLButtonElement>('button.password-switch')!.click();
    await fixture().whenStable();
    login.mockRejectedValueOnce(new ApiError(401, 'Unauthorized'));
    type('input[formControlName=username]', 'demo');
    type('input[formControlName=password]', 'a long password');
    await submit();
    expect(root.querySelector('[role=alert]')?.textContent).toContain(
      'a recovery code is still needed',
    );
    const toggle = root.querySelector<HTMLButtonElement>('button.switch')!;
    expect(toggle.textContent?.trim()).toBe('Use the authenticator app instead');
    toggle.click();
    await fixture().whenStable();
    expect(root.querySelector('[role=alert]')).toBeNull();
    expect(root.querySelector('input[name=totp]')).not.toBeNull();
  });

  it('offers no passkey button when the browser has no WebAuthn', async () => {
    // jsdom has neither PublicKeyCredential nor navigator.credentials, like an old browser.
    await open();
    expect(root.querySelector('button.passkey-button')).toBeNull();
    expect(root.textContent).not.toContain('Sign in with a passkey');
  });
});

describe('Login with a passkey', () => {
  let harness: RouterTestingHarness;
  let router: Router;
  let passkeyLogin: ReturnType<typeof vi.fn>;
  let passwordLogin: ReturnType<typeof vi.fn>;
  let root: HTMLElement;

  async function open(url = '/login'): Promise<void> {
    passkeyLogin = vi.fn(() => Promise.resolve({ username: 'demo' }));
    passwordLogin = vi.fn(() => Promise.resolve({ username: 'demo' }));
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'login', component: Login },
          { path: 'inbox', component: Target },
          { path: 'enroll-totp', component: Target },
          { path: '', component: Target },
        ]),
        { provide: AuthService, useValue: { login: passwordLogin } },
        { provide: PasskeyLogin, useValue: { supported: () => true, login: passkeyLogin } },
      ],
    });
    harness = await RouterTestingHarness.create(url);
    router = TestBed.inject(Router);
    root = harness.routeNativeElement as HTMLElement;
  }

  function passkeyButton(): HTMLButtonElement {
    const button = root.querySelector<HTMLButtonElement>('button.passkey-button');
    if (!button) throw new Error('no passkey button');
    return button;
  }

  async function clickPasskey(): Promise<void> {
    passkeyButton().click();
    await harness.fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    await harness.fixture.whenStable();
  }

  const alert = () => root.querySelector('[role=alert]')?.textContent?.trim();

  afterEach(() => vi.useRealTimers());

  it('signs in with a passkey alone and goes to the return URL', async () => {
    await open('/login?returnUrl=%2Finbox');
    expect(passkeyButton().textContent?.trim()).toBe('Sign in with a passkey');
    expect(passkeyButton().type).toBe('button');

    await clickPasskey();

    expect(passkeyLogin).toHaveBeenCalledWith(false);
    expect(passwordLogin).not.toHaveBeenCalled();
    expect(router.url).toBe('/inbox');
  });

  it('keeps the session for 30 days when "keep me signed in" is ticked', async () => {
    await open();
    root.querySelector<HTMLInputElement>('mat-checkbox input')!.click();
    await harness.fixture.whenStable();

    await clickPasskey();

    expect(passkeyLogin).toHaveBeenCalledWith(true);
    expect(router.url).toBe('/');
  });

  it('opens the TOTP enrollment when the server requires it after a passkey login', async () => {
    await open('/login?returnUrl=%2Finbox');
    passkeyLogin.mockResolvedValueOnce({ username: 'demo', totpEnrollmentRequired: true });

    await clickPasskey();

    expect(router.url).toBe('/enroll-totp?returnUrl=%2Finbox');
  });

  it('drops an unsafe return URL after a passkey login', async () => {
    await open('/login?returnUrl=https%3A%2F%2Fevil.example');

    await clickPasskey();

    expect(router.url).toBe('/');
  });

  it.each([
    ['cancelled', 'No passkey was used. Try again, or sign in with your password and code.'],
    ['unsupported', 'This browser cannot use passkeys here. Sign in with your password and code.'],
    ['failed', 'The passkey could not be used. Try again, or sign in with your password and code.'],
    [
      'duplicate',
      'The passkey could not be used. Try again, or sign in with your password and code.',
    ],
  ] as const)('explains a %s ceremony and stays on the page', async (failure, message) => {
    await open();
    passkeyLogin.mockRejectedValueOnce(new PasskeyError(failure));

    await clickPasskey();

    expect(alert()).toBe(message);
    expect(router.url).toBe('/login');
    expect(passkeyButton().disabled).toBe(false);
  });

  it('explains a passkey the server does not accept (401)', async () => {
    await open();
    passkeyLogin.mockRejectedValueOnce(new ApiError(401, 'Unauthorized'));

    await clickPasskey();

    expect(alert()).toContain('This passkey was not accepted.');
    expect(alert()).not.toContain('Wrong username');
    expect(router.url).toBe('/login');
  });

  it('shows the server message on other API errors', async () => {
    await open();
    passkeyLogin.mockRejectedValueOnce(new ApiError(0, 'The server cannot be reached.'));

    await clickPasskey();

    expect(alert()).toBe('The server cannot be reached.');
  });

  it('keeps username and password typed so far when the passkey fails', async () => {
    await open();
    const username = root.querySelector<HTMLInputElement>('input[formControlName=username]')!;
    username.value = 'demo';
    username.dispatchEvent(new Event('input'));
    passkeyLogin.mockRejectedValueOnce(new PasskeyError('cancelled'));

    await clickPasskey();

    expect(username.value).toBe('demo');
  });

  it('blocks both sign-in buttons during a throttled passkey login and counts down', async () => {
    await open();
    vi.useFakeTimers();
    passkeyLogin.mockRejectedValueOnce(
      new ApiError(429, 'Too Many Requests', undefined, undefined, undefined, [], 2),
    );

    passkeyButton().click();
    await vi.advanceTimersByTimeAsync(0);
    harness.fixture.detectChanges();
    const notice = () =>
      root.querySelector('.notice.backoff')?.textContent?.replace(/\s+/g, ' ').trim();
    const submit = () => root.querySelector<HTMLButtonElement>('button[type=submit]')!;
    expect(notice()).toBe('Too many failed attempts. Try again in 2 s.');
    expect(passkeyButton().disabled).toBe(true);
    expect(submit().disabled).toBe(true);

    // A click during the backoff (e.g. on a stale render) does not start a ceremony.
    passkeyButton().disabled = false;
    passkeyButton().click();
    await vi.advanceTimersByTimeAsync(0);
    expect(passkeyLogin).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(2000);
    harness.fixture.detectChanges();
    expect(notice()).toBeUndefined();
    expect(passkeyButton().disabled).toBe(false);
    expect(submit().disabled).toBe(false);
  });

  it('waits 60 s when a throttled passkey login has no Retry-After', async () => {
    await open();
    vi.useFakeTimers();
    passkeyLogin.mockRejectedValueOnce(new ApiError(429, 'Too Many Requests'));

    passkeyButton().click();
    await vi.advanceTimersByTimeAsync(0);
    harness.fixture.detectChanges();

    expect(root.querySelector('.notice.backoff')?.textContent?.replace(/\s+/g, ' ')).toContain(
      'Try again in 60 s.',
    );
  });

  it('starts one ceremony at a time and disables the buttons while it runs', async () => {
    await open();
    let resolve!: (value: { username: string }) => void;
    passkeyLogin.mockImplementationOnce(() => new Promise((r) => (resolve = r)));

    passkeyButton().click();
    await harness.fixture.whenStable();
    expect(passkeyButton().disabled).toBe(true);
    expect(root.querySelector<HTMLButtonElement>('button[type=submit]')!.disabled).toBe(true);
    // A second click on the (now disabled) button, or Enter in the form, starts nothing.
    passkeyButton().disabled = false;
    passkeyButton().click();
    await harness.fixture.whenStable();
    expect(passkeyLogin).toHaveBeenCalledTimes(1);

    resolve({ username: 'demo' });
    await vi.waitFor(() => expect(router.url).toBe('/'));
  });

  it('clears a passkey error when the next passkey attempt succeeds', async () => {
    await open('/login?returnUrl=%2Finbox');
    passkeyLogin.mockRejectedValueOnce(new PasskeyError('cancelled'));
    await clickPasskey();
    expect(alert()).toContain('No passkey was used.');

    let resolve!: (value: { username: string }) => void;
    passkeyLogin.mockImplementationOnce(() => new Promise((r) => (resolve = r)));
    await clickPasskey();
    expect(alert()).toBeUndefined();

    resolve({ username: 'demo' });
    await vi.waitFor(() => expect(router.url).toBe('/inbox'));
    expect(passkeyLogin).toHaveBeenCalledTimes(2);
    expect(router.url).toBe('/inbox');
  });
});
