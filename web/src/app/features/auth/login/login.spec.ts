import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Component } from '@angular/core';
import { AuthService } from '../../../core/auth';
import { ApiError } from '../../../core/http';
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
    const alert = () => root.querySelector('[role=alert]')?.textContent?.trim();
    const button = () => root.querySelector<HTMLButtonElement>('button[type=submit]')!;
    expect(alert()).toBe('Too many failed attempts. Try again in 3 s.');
    expect(button().disabled).toBe(true);

    await vi.advanceTimersByTimeAsync(1000);
    fixture().detectChanges();
    expect(alert()).toBe('Too many failed attempts. Try again in 2 s.');

    await vi.advanceTimersByTimeAsync(2000);
    fixture().detectChanges();
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
    const alert = () => root.querySelector('[role=alert]')?.textContent?.trim();
    expect(alert()).toBe('Too many failed attempts. Try again in 60 s.');

    await vi.advanceTimersByTimeAsync(59_000);
    fixture().detectChanges();
    expect(alert()).toBe('Too many failed attempts. Try again in 1 s.');
    await vi.advanceTimersByTimeAsync(1000);
    fixture().detectChanges();
    expect(alert()).toBeUndefined();
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
});
