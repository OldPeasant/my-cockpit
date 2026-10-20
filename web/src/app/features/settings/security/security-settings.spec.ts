import { InteractivityChecker } from '@angular/cdk/a11y';
import { Clipboard } from '@angular/cdk/clipboard';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideRouter } from '@angular/router';
import { DeviceResponse } from '../../../api/generated';
import { NotificationService, provideHttp } from '../../../core/http';
import SecuritySettings from './security-settings';
import { SecurityService } from './security.service';

const DEVICES = '/api/v1/auth/devices';
const CODES = '/api/v1/auth/recovery-codes';

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

function device(id: string, name: string): DeviceResponse {
  return {
    id,
    name,
    createdAt: '2026-10-01T08:00:00Z',
    lastSeenAt: '2026-10-19T17:30:00Z',
  };
}

const NEW_CODES = Array.from({ length: 10 }, (_, i) => `abcde-fgh${String(i).padStart(2, '2')}`);

describe('SecuritySettings', () => {
  let fixture: ComponentFixture<SecuritySettings>;
  let root: HTMLElement;
  let http: HttpTestingController;
  let notifications: { error: ReturnType<typeof vi.fn> };
  let clipboard: { copy: ReturnType<typeof vi.fn> };

  async function setup(
    devices: DeviceResponse[] = [device('d1', 'Pixel 9'), device('d2', 'Tablet')],
  ): Promise<void> {
    notifications = { error: vi.fn() };
    clipboard = { copy: vi.fn(() => true) };
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: notifications },
        { provide: Clipboard, useValue: clipboard },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(SecuritySettings);
    root = fixture.nativeElement as HTMLElement;
    await settle();
    http.expectOne({ method: 'GET', url: DEVICES }).flush(devices);
    await settle();
  }

  async function settle(): Promise<void> {
    await tick();
    await fixture.whenStable();
  }

  afterEach(() => {
    TestBed.inject(MatDialog).closeAll();
    http.verify();
  });

  function deviceNames(): string[] {
    return [...root.querySelectorAll('.devices .name')].map((el) => el.textContent?.trim() ?? '');
  }

  function revokeButton(name: string): HTMLButtonElement {
    return root.querySelector(`button[aria-label="Revoke ${name}"]`) as HTMLButtonElement;
  }

  function dialogButton(text: string): HTMLButtonElement {
    return [
      ...document.querySelectorAll<HTMLButtonElement>('app-revoke-device-dialog button'),
    ].find((button) => button.textContent?.trim() === text) as HTMLButtonElement;
  }

  function button(text: string): HTMLButtonElement {
    return [...root.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === text,
    ) as HTMLButtonElement;
  }

  /** The dialog closes (and the revoke starts) only after its exit animation. */
  async function confirmed(url: string): Promise<TestRequest> {
    let request: TestRequest | undefined;
    await vi.waitFor(() => {
      [request] = http.match(url);
      if (!request) throw new Error(`no request to ${url}`);
    });
    return request as TestRequest;
  }

  async function enterTotp(value: string): Promise<void> {
    const input = root.querySelector('.regenerate input') as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    button('Create new codes').click();
    await settle();
  }

  it('lists the devices with name and last use', async () => {
    await setup();

    expect(deviceNames()).toEqual(['Pixel 9', 'Tablet']);
    expect(root.querySelector('.devices .seen')?.textContent).toContain('Last used');
    expect(root.querySelector('.devices .seen')?.textContent).toContain('2026');
  });

  it('says so when there are no devices', async () => {
    await setup([]);

    expect(root.querySelector('.devices')).toBeNull();
    expect(root.textContent).toContain('No devices');
  });

  it('shows a load error with a retry', async () => {
    notifications = { error: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: notifications },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(SecuritySettings);
    root = fixture.nativeElement as HTMLElement;
    await settle();
    http
      .expectOne(DEVICES)
      .flush(
        { title: 'Server error', status: 500, detail: 'Database down' },
        { status: 500, statusText: 'Error' },
      );
    await settle();

    expect(root.querySelector('[role="alert"]')?.textContent).toContain('Database down');
    expect(notifications.error).not.toHaveBeenCalled();

    button('Try again').click();
    await settle();
    http.expectOne(DEVICES).flush([device('d1', 'Pixel 9')]);
    await settle();
    expect(deviceNames()).toEqual(['Pixel 9']);
  });

  it('revokes a device after the confirmation', async () => {
    await setup();

    revokeButton('Pixel 9').click();
    await settle();
    expect(document.querySelector('app-revoke-device-dialog h2')?.textContent).toContain('Pixel 9');
    dialogButton('Revoke device').click();

    const req = await confirmed(`${DEVICES}/d1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });
    await settle();
    expect(deviceNames()).toEqual(['Tablet']);
  });

  it('keeps the device when the confirmation is cancelled', async () => {
    await setup();

    revokeButton('Tablet').click();
    await settle();
    dialogButton('Cancel').click();
    await vi.waitFor(() => expect(TestBed.inject(MatDialog).openDialogs).toHaveLength(0));
    await settle();

    http.expectNone({ method: 'DELETE' });
    expect(deviceNames()).toEqual(['Pixel 9', 'Tablet']);
  });

  it('shows a failed revoke and keeps the device', async () => {
    await setup();

    revokeButton('Pixel 9').click();
    await settle();
    dialogButton('Revoke device').click();
    (await confirmed(`${DEVICES}/d1`)).flush(
      { title: 'Server error', status: 500, detail: 'Try later' },
      { status: 500, statusText: 'Error' },
    );
    await settle();

    expect(deviceNames()).toEqual(['Pixel 9', 'Tablet']);
    expect(root.querySelector('.error')?.textContent).toContain(
      'Pixel 9 could not be revoked: Try later',
    );
    expect(revokeButton('Pixel 9').disabled).toBe(false);
  });

  it('drops a device that was already revoked elsewhere', async () => {
    await setup();

    revokeButton('Pixel 9').click();
    await settle();
    dialogButton('Revoke device').click();
    (await confirmed(`${DEVICES}/d1`)).flush(
      { title: 'Not Found', status: 404, detail: 'Device not found' },
      { status: 404, statusText: 'Not Found' },
    );
    await settle();

    expect(deviceNames()).toEqual(['Tablet']);
    expect(root.querySelector('.error')).toBeNull();
  });

  it('shows new recovery codes once, for a current TOTP code', async () => {
    await setup();

    await enterTotp(' 123456 ');
    const req = http.expectOne({ method: 'POST', url: CODES });
    expect(req.request.body).toEqual({ totp: '123456' });
    req.flush({ recoveryCodes: NEW_CODES });
    await settle();

    const shown = [...root.querySelectorAll('.codes code')].map((el) => el.textContent?.trim());
    expect(shown).toEqual(NEW_CODES);
    button('Copy codes').click();
    await settle();
    expect(clipboard.copy).toHaveBeenCalledWith(NEW_CODES.join('\n'));
    expect(button('Copied')).toBeTruthy();

    button('I saved them').click();
    await settle();
    expect(root.querySelector('.codes')).toBeNull();
    expect((root.querySelector('.regenerate input') as HTMLInputElement).value).toBe('');
  });

  it('does not send a code that is not 6 digits', async () => {
    await setup();

    await enterTotp('12ab');

    http.expectNone(CODES);
    expect(root.querySelector('mat-error')?.textContent).toContain('6-digit');
  });

  it('explains a wrong TOTP code and a throttled attempt', async () => {
    await setup();

    await enterTotp('123456');
    http.expectOne(CODES).flush(
      {
        title: 'Bad Request',
        status: 400,
        errors: [{ field: 'totp', message: 'is not a valid code' }],
      },
      { status: 400, statusText: 'Bad Request' },
    );
    await settle();
    expect(root.querySelector('.error')?.textContent).toContain('That code is not valid');
    expect(root.querySelector('.codes')).toBeNull();

    button('Create new codes').click();
    await settle();
    http
      .expectOne(CODES)
      .flush(
        { title: 'Too Many Requests', status: 429 },
        { status: 429, statusText: 'Too Many Requests', headers: { 'Retry-After': '30' } },
      );
    await settle();
    expect(root.querySelector('.error')?.textContent).toContain('Try again in 30 seconds');
    expect(notifications.error).not.toHaveBeenCalled();
  });

  describe('edge cases', () => {
    async function startRevoke(name: string, id: string): Promise<TestRequest> {
      revokeButton(name).click();
      await settle();
      dialogButton('Revoke device').click();
      return confirmed(`${DEVICES}/${id}`);
    }

    function codesForm(): HTMLFormElement {
      return root.querySelector('form.regenerate') as HTMLFormElement;
    }

    it('disables only the revoke button of the device being revoked while it is pending', async () => {
      await setup();

      const req = await startRevoke('Pixel 9', 'd1');
      await settle();

      expect(revokeButton('Pixel 9').disabled).toBe(true);
      expect(revokeButton('Tablet').disabled).toBe(false);
      expect(deviceNames()).toEqual(['Pixel 9', 'Tablet']);

      req.flush(null, { status: 204, statusText: 'No Content' });
      await settle();
      expect(deviceNames()).toEqual(['Tablet']);
      expect(revokeButton('Tablet').disabled).toBe(false);
    });

    it('does not revoke when the dialog is dismissed without an answer', async () => {
      await setup();

      revokeButton('Pixel 9').click();
      await settle();
      TestBed.inject(MatDialog).closeAll();
      await vi.waitFor(() => expect(TestBed.inject(MatDialog).openDialogs).toHaveLength(0));
      await settle();

      http.expectNone({ method: 'DELETE' });
      expect(deviceNames()).toEqual(['Pixel 9', 'Tablet']);
    });

    it('shows an unreachable server on revoke and clears the error after a later success', async () => {
      await setup();

      (await startRevoke('Pixel 9', 'd1')).error(new ProgressEvent('error'), {
        status: 0,
        statusText: 'Unknown Error',
      });
      await settle();
      expect(root.querySelector('.error')?.textContent).toContain('cannot be reached');
      expect(deviceNames()).toEqual(['Pixel 9', 'Tablet']);
      expect(notifications.error).not.toHaveBeenCalled();

      (await startRevoke('Tablet', 'd2')).flush(null, { status: 204, statusText: 'No Content' });
      await settle();
      expect(deviceNames()).toEqual(['Pixel 9']);
      expect(root.querySelector('.error')).toBeNull();
    });

    it('revokes the last device and then shows the empty state', async () => {
      await setup([device('d1', 'Pixel 9')]);

      (await startRevoke('Pixel 9', 'd1')).flush(null, { status: 204, statusText: 'No Content' });
      await settle();

      expect(root.querySelector('.devices')).toBeNull();
      expect(root.textContent).toContain('No devices');
    });

    it('shows the loading state until the device list arrives', async () => {
      notifications = { error: vi.fn() };
      TestBed.configureTestingModule({
        providers: [
          provideHttp(),
          provideHttpClientTesting(),
          provideRouter([]),
          { provide: NotificationService, useValue: notifications },
        ],
      });
      http = TestBed.inject(HttpTestingController);
      fixture = TestBed.createComponent(SecuritySettings);
      root = fixture.nativeElement as HTMLElement;
      await settle();

      expect(root.querySelector('[role="status"]')?.textContent).toContain('Loading devices');
      http.expectOne(DEVICES).flush([device('d1', 'Pixel 9')]);
      await settle();
      expect(root.querySelector('[role="status"]')).toBeNull();
      expect(deviceNames()).toEqual(['Pixel 9']);
    });

    it.each([
      ['empty', ''],
      ['five digits', '12345'],
      ['seven digits', '1234567'],
      ['spaces only', '      '],
    ])('does not send a TOTP code that is %s', async (_label, value) => {
      await setup();

      await enterTotp(value);

      http.expectNone(CODES);
      expect(root.querySelector('mat-error')?.textContent).toContain('6-digit');
    });

    it('sends the code only once while the request is pending', async () => {
      await setup();

      await enterTotp('123456');
      expect(button('Create new codes').disabled).toBe(true);
      codesForm().dispatchEvent(new Event('submit', { cancelable: true }));
      await settle();

      const [req, ...extra] = http.match(CODES);
      expect(extra).toHaveLength(0);
      req.flush({ recoveryCodes: NEW_CODES });
      await settle();
      expect(root.querySelectorAll('.codes code')).toHaveLength(NEW_CODES.length);
    });

    it('re-enables the form after a failed attempt and clears the error on success', async () => {
      await setup();

      await enterTotp('123456');
      http
        .expectOne(CODES)
        .flush(
          { title: 'Server error', status: 500, detail: 'Database down' },
          { status: 500, statusText: 'Error' },
        );
      await settle();
      const error = root.querySelector('.error')?.textContent ?? '';
      expect(error).toContain('Database down');
      expect(error).not.toContain('That code is not valid');
      expect(button('Create new codes').disabled).toBe(false);
      // The code is kept so the user can retry.
      expect((root.querySelector('.regenerate input') as HTMLInputElement).value).toBe('123456');

      button('Create new codes').click();
      await settle();
      http.expectOne(CODES).flush({ recoveryCodes: NEW_CODES });
      await settle();
      expect(root.querySelector('.error')).toBeNull();
      expect(root.querySelectorAll('.codes code')).toHaveLength(NEW_CODES.length);
    });

    it('asks to try later when a throttled answer has no Retry-After', async () => {
      await setup();

      await enterTotp('123456');
      http
        .expectOne(CODES)
        .flush(
          { title: 'Too Many Requests', status: 429 },
          { status: 429, statusText: 'Too Many Requests' },
        );
      await settle();

      expect(root.querySelector('.error')?.textContent).toContain(
        'Too many wrong codes. Try again later.',
      );
    });

    it('shows a generic message for an unexpected (non-API) failure', async () => {
      await setup();
      vi.spyOn(TestBed.inject(SecurityService), 'regenerateRecoveryCodes').mockRejectedValue(
        new Error('fictitious internal failure'),
      );

      await enterTotp('123456');
      await settle();

      const error = root.querySelector('.error')?.textContent ?? '';
      expect(error).toContain('Something went wrong.');
      expect(error).not.toContain('fictitious internal failure');
      expect(button('Create new codes').disabled).toBe(false);
    });

    it('keeps the copy label when the clipboard refuses', async () => {
      await setup();
      clipboard.copy.mockReturnValue(false);

      await enterTotp('123456');
      http.expectOne(CODES).flush({ recoveryCodes: NEW_CODES });
      await settle();
      button('Copy codes').click();
      await settle();

      expect(clipboard.copy).toHaveBeenCalledWith(NEW_CODES.join('\n'));
      expect(button('Copy codes')).toBeTruthy();
      expect(button('Copied')).toBeUndefined();
    });

    it('does not keep the codes after the page is left', async () => {
      await setup();

      await enterTotp('123456');
      http.expectOne(CODES).flush({ recoveryCodes: NEW_CODES });
      await settle();
      expect(root.querySelectorAll('.codes code')).toHaveLength(NEW_CODES.length);

      fixture.destroy();
      const again = TestBed.createComponent(SecuritySettings);
      await tick();
      await again.whenStable();
      http.expectOne(DEVICES).flush([]);
      await tick();
      await again.whenStable();
      const element = again.nativeElement as HTMLElement;

      expect(element.querySelector('.codes')).toBeNull();
      expect(element.querySelector('form.regenerate')).not.toBeNull();
      const stored = JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage });
      for (const code of NEW_CODES) expect(stored).not.toContain(code);
    });

    it('keeps each revoke button disabled while its own request runs, when revokes overlap', async () => {
      await setup([device('d1', 'Pixel 9'), device('d2', 'Tablet'), device('d3', 'Laptop')]);

      const first = await startRevoke('Pixel 9', 'd1');
      await settle();
      const second = await startRevoke('Tablet', 'd2');
      await settle();
      expect(revokeButton('Pixel 9').disabled).toBe(true);
      expect(revokeButton('Tablet').disabled).toBe(true);
      expect(revokeButton('Laptop').disabled).toBe(false);

      second.flush(null, { status: 204, statusText: 'No Content' });
      await settle();
      expect(deviceNames()).toEqual(['Pixel 9', 'Laptop']);
      expect(revokeButton('Pixel 9').disabled).toBe(true);

      first.flush(
        { title: 'Server error', status: 500, detail: 'Try later' },
        { status: 500, statusText: 'Error' },
      );
      await settle();
      expect(revokeButton('Pixel 9').disabled).toBe(false);
      expect(root.querySelector('.error')?.textContent).toContain(
        'Pixel 9 could not be revoked: Try later',
      );
    });
  });

  describe('keyboard focus', () => {
    async function revokeWithKeyboard(name: string, id: string): Promise<TestRequest> {
      revokeButton(name).focus();
      revokeButton(name).click();
      await settle();
      dialogButton('Revoke device').click();
      await vi.waitFor(() => expect(TestBed.inject(MatDialog).openDialogs).toHaveLength(0));
      return confirmed(`${DEVICES}/${id}`);
    }

    async function revoked(req: TestRequest): Promise<void> {
      req.flush(null, { status: 204, statusText: 'No Content' });
      await settle();
    }

    it('focuses Cancel when the revoke dialog opens', async () => {
      // jsdom has no layout, so the CDK would treat every element as invisible and unfocusable.
      TestBed.overrideProvider(InteractivityChecker, {
        useFactory: () => {
          const checker = new InteractivityChecker();
          checker.isVisible = () => true;
          return checker;
        },
      });
      await setup();

      revokeButton('Pixel 9').click();
      await settle();

      await vi.waitFor(() => expect(document.activeElement).toBe(dialogButton('Cancel')));
    });

    it('moves the focus to the next row after a revoke, then the previous one, then the heading', async () => {
      await setup([device('d1', 'Pixel 9'), device('d2', 'Tablet'), device('d3', 'Laptop')]);

      await revoked(await revokeWithKeyboard('Pixel 9', 'd1'));
      expect(document.activeElement).toBe(revokeButton('Tablet'));

      await revoked(await revokeWithKeyboard('Laptop', 'd3'));
      expect(document.activeElement).toBe(revokeButton('Tablet'));

      await revoked(await revokeWithKeyboard('Tablet', 'd2'));
      expect(root.querySelector('.devices')).toBeNull();
      expect(document.activeElement).toBe(root.querySelector('#devices-label'));
    });

    it('moves the focus on when a device was already revoked elsewhere', async () => {
      await setup();

      (await revokeWithKeyboard('Pixel 9', 'd1')).flush(
        { title: 'Not Found', status: 404, detail: 'Device not found' },
        { status: 404, statusText: 'Not Found' },
      );
      await settle();

      expect(document.activeElement).toBe(revokeButton('Tablet'));
    });

    it('skips a next row whose own revoke is still pending when moving the focus', async () => {
      await setup([device('d1', 'Pixel 9'), device('d2', 'Tablet'), device('d3', 'Laptop')]);

      // Laptop's revoke is still running, so its button is disabled and cannot take the focus.
      revokeButton('Laptop').click();
      await settle();
      dialogButton('Revoke device').click();
      const laptop = await confirmed(`${DEVICES}/d3`);
      await settle();
      expect(revokeButton('Laptop').disabled).toBe(true);

      await revoked(await revokeWithKeyboard('Tablet', 'd2'));
      expect(deviceNames()).toEqual(['Pixel 9', 'Laptop']);
      expect(document.activeElement).toBe(revokeButton('Pixel 9'));

      await revoked(laptop);
      expect(deviceNames()).toEqual(['Pixel 9']);
      // The focus the user kept on Pixel 9 is not taken away by the late answer.
      expect(document.activeElement).toBe(revokeButton('Pixel 9'));
    });

    it('focuses the heading when the only neighbouring row is still being revoked', async () => {
      await setup([device('d1', 'Pixel 9'), device('d2', 'Tablet')]);

      revokeButton('Tablet').click();
      await settle();
      dialogButton('Revoke device').click();
      const tablet = await confirmed(`${DEVICES}/d2`);
      await settle();

      await revoked(await revokeWithKeyboard('Pixel 9', 'd1'));
      expect(deviceNames()).toEqual(['Tablet']);
      expect(document.activeElement).toBe(root.querySelector('#devices-label'));

      await revoked(tablet);
      expect(root.textContent).toContain('No devices');
    });

    it('leaves the focus where the user moved it while the revoke ran', async () => {
      await setup();

      const req = await revokeWithKeyboard('Pixel 9', 'd1');
      const input = root.querySelector('.regenerate input') as HTMLInputElement;
      input.focus();
      await revoked(req);

      expect(document.activeElement).toBe(input);
    });

    it('focuses the new codes, and the code field again once they are saved', async () => {
      await setup();

      const input = root.querySelector('.regenerate input') as HTMLInputElement;
      input.focus();
      await enterTotp('123456');
      http.expectOne(CODES).flush({ recoveryCodes: NEW_CODES });
      await settle();

      const title = root.querySelector('#fresh-label') as HTMLElement;
      expect(title.getAttribute('tabindex')).toBe('-1');
      expect(document.activeElement).toBe(title);

      button('I saved them').focus();
      button('I saved them').click();
      await settle();

      expect(document.activeElement).toBe(root.querySelector('.regenerate input'));
    });
  });
});
