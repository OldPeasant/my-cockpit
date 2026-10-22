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
import { CreationOptionsJson, DeviceResponse, PasskeyResponse } from '../../../api/generated';
import { NotificationService, provideHttp } from '../../../core/http';
import { CredentialJson, PasskeyError, WebAuthn } from '../../auth/passkey/webauthn';
import SecuritySettings from './security-settings';
import { SecurityService } from './security.service';

const DEVICES = '/api/v1/auth/devices';
const CODES = '/api/v1/auth/recovery-codes';
const PASSKEYS = '/api/v1/auth/passkeys';

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
    http.expectOne({ method: 'GET', url: PASSKEYS }).flush([]);
    await settle();
  }

  async function settle(): Promise<void> {
    await tick();
    await fixture.whenStable();
  }

  afterEach(() => {
    TestBed.inject(MatDialog).closeAll();
    // Tests that create the component themselves don't care about the passkey list.
    http.match({ method: 'GET', url: PASSKEYS }).forEach((request) => request.flush([]));
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
      http.expectOne(PASSKEYS).flush([]);
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

  describe('passkeys', () => {
    const OPTIONS = `${PASSKEYS}/registration/options`;
    const REGISTRATION = `${PASSKEYS}/registration`;
    const CREATION: CreationOptionsJson = {
      rp: { id: 'localhost', name: 'My Cockpit' },
      user: { id: 'AQID', name: 'demo', displayName: 'Demo Owner' },
      challenge: 'Y2hhbGxlbmdl',
      pubKeyCredParams: [{ type: 'public-key', alg: -7 }],
      timeout: 300_000,
      excludeCredentials: [],
      authenticatorSelection: {
        requireResidentKey: true,
        residentKey: 'required',
        userVerification: 'required',
      },
      attestation: 'none',
    };
    const CREDENTIAL: CredentialJson = {
      id: 'AQIDBA',
      rawId: 'AQIDBA',
      type: 'public-key',
      response: { clientDataJSON: 'e30', attestationObject: 'AA', transports: ['internal'] },
      clientExtensionResults: {},
    };

    let webAuthn: { supported: ReturnType<typeof vi.fn>; create: ReturnType<typeof vi.fn> };

    function passkey(id: string, name: string, lastUsedAt?: string): PasskeyResponse {
      return { id, name, createdAt: '2026-10-01T08:00:00Z', ...(lastUsedAt ? { lastUsedAt } : {}) };
    }

    /** Creates the page; the passkey list stays pending when `passkeys` is null. */
    async function open(
      passkeys: PasskeyResponse[] | null = [passkey('p1', 'Laptop'), passkey('p2', 'YubiKey')],
      supported = true,
    ): Promise<void> {
      notifications = { error: vi.fn() };
      webAuthn = {
        supported: vi.fn(() => supported),
        create: vi.fn(() => Promise.resolve(CREDENTIAL)),
      };
      TestBed.configureTestingModule({
        providers: [
          provideHttp(),
          provideHttpClientTesting(),
          provideRouter([]),
          { provide: NotificationService, useValue: notifications },
          { provide: WebAuthn, useValue: webAuthn },
        ],
      });
      http = TestBed.inject(HttpTestingController);
      fixture = TestBed.createComponent(SecuritySettings);
      root = fixture.nativeElement as HTMLElement;
      await settle();
      http.expectOne({ method: 'GET', url: DEVICES }).flush([]);
      if (passkeys) http.expectOne({ method: 'GET', url: PASSKEYS }).flush(passkeys);
      await settle();
    }

    function passkeyNames(): string[] {
      return [...root.querySelectorAll('.passkeys .name')].map(
        (el) => el.textContent?.trim() ?? '',
      );
    }

    function deleteButton(name: string): HTMLButtonElement {
      return root.querySelector(`button[aria-label="Delete ${name}"]`) as HTMLButtonElement;
    }

    function deleteDialogButton(text: string): HTMLButtonElement {
      return [
        ...document.querySelectorAll<HTMLButtonElement>('app-delete-passkey-dialog button'),
      ].find((b) => b.textContent?.trim() === text) as HTMLButtonElement;
    }

    function addInput(name: 'name' | 'totp'): HTMLInputElement {
      return root.querySelector(
        `form.add-passkey input[formControlName=${name}]`,
      ) as HTMLInputElement;
    }

    function addError(): string | undefined {
      return root.querySelector('form.add-passkey + .error')?.textContent?.trim();
    }

    async function add(name: string, totp: string): Promise<void> {
      addInput('name').value = name;
      addInput('name').dispatchEvent(new Event('input'));
      addInput('totp').value = totp;
      addInput('totp').dispatchEvent(new Event('input'));
      button('Add passkey').click();
      await settle();
    }

    /** Lets the add get past the options request and the (fake) browser ceremony. */
    async function startAdd(name = 'Phone', totp = '123456'): Promise<void> {
      await add(name, totp);
      http.expectOne(OPTIONS).flush({ ceremonyId: 'ceremony-1', publicKey: CREATION });
      await settle();
    }

    async function startDelete(name: string, id: string): Promise<TestRequest> {
      deleteButton(name).click();
      await settle();
      deleteDialogButton('Delete passkey').click();
      return confirmed(`${PASSKEYS}/${id}`);
    }

    it('lists the passkeys with their last use, or when they were added', async () => {
      await open([passkey('p1', 'Laptop', '2026-10-19T17:30:00Z'), passkey('p2', 'YubiKey')]);

      expect(passkeyNames()).toEqual(['Laptop', 'YubiKey']);
      const seen = [...root.querySelectorAll('.passkeys .seen')].map((el) =>
        el.textContent?.replace(/\s+/g, ' ').trim(),
      );
      expect(seen[0]).toMatch(/^Last used .*2026/);
      expect(seen[1]).toMatch(/^Added .*2026.*, not used yet$/);
    });

    it('says so when there are no passkeys', async () => {
      await open([]);

      expect(root.querySelector('.passkeys')).toBeNull();
      expect(root.textContent).toContain('No passkeys yet.');
    });

    it('shows the loading state until the passkey list arrives', async () => {
      await open(null);

      expect(root.textContent).toContain('Loading passkeys');
      http.expectOne(PASSKEYS).flush([passkey('p1', 'Laptop')]);
      await settle();
      expect(root.textContent).not.toContain('Loading passkeys');
      expect(passkeyNames()).toEqual(['Laptop']);
    });

    it('shows a passkey load error with a retry, without a toast', async () => {
      await open(null);
      http
        .expectOne(PASSKEYS)
        .flush(
          { title: 'Server error', status: 500, detail: 'Database down' },
          { status: 500, statusText: 'Error' },
        );
      await settle();

      expect(root.querySelector('[role="alert"]')?.textContent).toContain(
        'Passkeys could not be loaded: Database down',
      );
      expect(notifications.error).not.toHaveBeenCalled();

      button('Try again').click();
      await settle();
      http.expectOne(PASSKEYS).flush([passkey('p1', 'Laptop')]);
      await settle();
      expect(root.querySelector('[role="alert"]')).toBeNull();
      expect(passkeyNames()).toEqual(['Laptop']);
    });

    it('offers no add form in a browser without WebAuthn, but still lists and deletes', async () => {
      await open([passkey('p1', 'Laptop')], false);

      expect(root.querySelector('form.add-passkey')).toBeNull();
      expect(root.textContent).toContain('This browser cannot create passkeys.');
      expect(deleteButton('Laptop')).not.toBeNull();
    });

    it('adds a passkey with a name and a current TOTP code', async () => {
      await open();

      await add('  Phone  ', ' 123456 ');
      const start = http.expectOne({ method: 'POST', url: OPTIONS });
      expect(start.request.body).toEqual({ totp: '123456' });
      start.flush({ ceremonyId: 'ceremony-1', publicKey: CREATION });
      await settle();

      expect(webAuthn.create).toHaveBeenCalledWith(CREATION);
      const finish = await confirmed(REGISTRATION);
      expect(finish.request.body).toEqual({
        ceremonyId: 'ceremony-1',
        credential: CREDENTIAL,
        name: 'Phone',
      });
      finish.flush(passkey('p3', 'Phone'), { status: 201, statusText: 'Created' });
      await settle();

      expect(passkeyNames()).toEqual(['Laptop', 'YubiKey', 'Phone']);
      expect(root.querySelector('.added[role="status"]')?.textContent?.trim()).toBe(
        'Passkey Phone added.',
      );
      expect(addInput('name').value).toBe('');
      expect(addInput('totp').value).toBe('');
      expect(addError()).toBeUndefined();
      expect(notifications.error).not.toHaveBeenCalled();
    });

    it('adds the first passkey to an empty list', async () => {
      await open([]);

      await startAdd('Phone');
      (await confirmed(REGISTRATION)).flush(passkey('p3', 'Phone'));
      await settle();

      expect(root.textContent).not.toContain('No passkeys yet.');
      expect(passkeyNames()).toEqual(['Phone']);
    });

    it.each([
      ['an empty name', '', '123456', 'Name the passkey.'],
      ['a blank name', '   ', '123456', 'Name the passkey.'],
      ['an empty code', 'Phone', '', 'Enter the 6-digit code.'],
      ['a five-digit code', 'Phone', '12345', 'Enter the 6-digit code.'],
      ['a code with letters', 'Phone', '12ab56', 'Enter the 6-digit code.'],
    ])('does not start a ceremony with %s', async (_label, name, totp, message) => {
      await open();

      await add(name, totp);

      http.expectNone(OPTIONS);
      expect(webAuthn.create).not.toHaveBeenCalled();
      expect(root.querySelector('form.add-passkey mat-error')?.textContent).toContain(message);
    });

    it.each([
      ['cancelled', 'No passkey was created. Enter a new code and try again.'],
      ['duplicate', 'This authenticator already has a passkey for your account.'],
      ['unsupported', 'This browser cannot create passkeys here.'],
      ['failed', 'The passkey could not be created. Enter a new code and try again.'],
    ] as const)(
      'explains a %s browser ceremony, keeps the name and clears the used code',
      async (failure, message) => {
        await open();
        webAuthn.create.mockRejectedValueOnce(new PasskeyError(failure));

        await startAdd('Phone');

        http.expectNone(REGISTRATION);
        expect(addError()).toBe(message);
        expect(addInput('name').value).toBe('Phone');
        expect(addInput('totp').value).toBe('');
        expect(button('Add passkey').disabled).toBe(false);
        expect(passkeyNames()).toEqual(['Laptop', 'YubiKey']);
      },
    );

    it('explains a wrong TOTP code (403) without starting the ceremony', async () => {
      await open();

      await add('Phone', '000000');
      http
        .expectOne(OPTIONS)
        .flush(
          { title: 'Forbidden', status: 403, detail: 'Invalid TOTP code' },
          { status: 403, statusText: 'Forbidden' },
        );
      await settle();

      expect(webAuthn.create).not.toHaveBeenCalled();
      expect(addError()).toBe(
        'That code is not valid. Enter the current code from your authenticator app.',
      );
      expect(addInput('totp').value).toBe('');
      expect(notifications.error).not.toHaveBeenCalled();
    });

    it('explains a throttled attempt with and without Retry-After', async () => {
      await open();

      await add('Phone', '123456');
      http
        .expectOne(OPTIONS)
        .flush(
          { title: 'Too Many Requests', status: 429 },
          { status: 429, statusText: 'Too Many Requests', headers: { 'Retry-After': '30' } },
        );
      await settle();
      expect(addError()).toBe('Too many wrong codes. Try again in 30 seconds.');

      await add('Phone', '123456');
      http
        .expectOne(OPTIONS)
        .flush(
          { title: 'Too Many Requests', status: 429 },
          { status: 429, statusText: 'Too Many Requests' },
        );
      await settle();
      expect(addError()).toBe('Too many wrong codes. Try again later.');
    });

    it('explains a passkey that is already registered (409)', async () => {
      await open();

      await startAdd('Phone');
      (await confirmed(REGISTRATION)).flush(
        { title: 'Conflict', status: 409, detail: 'Passkey already registered' },
        { status: 409, statusText: 'Conflict' },
      );
      await settle();

      expect(addError()).toBe('This passkey is already registered.');
      expect(passkeyNames()).toEqual(['Laptop', 'YubiKey']);
    });

    it('explains a registration the server rejects (400, e.g. an expired ceremony)', async () => {
      await open();

      await startAdd('Phone');
      (await confirmed(REGISTRATION)).flush(
        { title: 'Bad Request', status: 400, detail: 'Unknown or expired ceremony' },
        { status: 400, statusText: 'Bad Request' },
      );
      await settle();

      expect(addError()).toBe('The passkey was not accepted. Enter a new code and try again.');
      expect(addInput('name').value).toBe('Phone');
      expect(addInput('totp').value).toBe('');
    });

    it('shows the server message for other failures and a generic one for unexpected errors', async () => {
      await open();

      await startAdd('Phone');
      (await confirmed(REGISTRATION)).flush(
        { title: 'Server error', status: 500, detail: 'Database down' },
        { status: 500, statusText: 'Error' },
      );
      await settle();
      expect(addError()).toBe('Database down');

      webAuthn.create.mockRejectedValueOnce(new Error('fictitious internal failure'));
      await startAdd('Phone');
      expect(addError()).toBe('Something went wrong.');
    });

    it('adds a passkey while the list is still loading, without a duplicate once it arrives', async () => {
      await open(null);

      await startAdd('Phone');
      (await confirmed(REGISTRATION)).flush(passkey('p3', 'Phone'));
      await settle();
      expect(passkeyNames()).toEqual(['Phone']);
      expect(root.querySelector('.added')?.textContent).toContain('Passkey Phone added.');

      // The list request was answered after the registration, so it already holds the new passkey.
      http.expectOne(PASSKEYS).flush([passkey('p1', 'Laptop'), passkey('p3', 'Phone')]);
      await settle();
      expect(passkeyNames()).toEqual(['Laptop', 'Phone']);
    });

    it('starts only one ceremony while an add is pending', async () => {
      await open();
      let finish!: (credential: CredentialJson) => void;
      webAuthn.create.mockImplementationOnce(() => new Promise((resolve) => (finish = resolve)));

      await startAdd('Phone');
      expect(button('Add passkey').disabled).toBe(true);
      root
        .querySelector('form.add-passkey')!
        .dispatchEvent(new Event('submit', { cancelable: true }));
      await settle();
      http.expectNone(OPTIONS);
      expect(webAuthn.create).toHaveBeenCalledTimes(1);

      finish(CREDENTIAL);
      (await confirmed(REGISTRATION)).flush(passkey('p3', 'Phone'));
      await settle();
      expect(button('Add passkey').disabled).toBe(false);
      expect(passkeyNames()).toEqual(['Laptop', 'YubiKey', 'Phone']);
    });

    it('clears the previous error once an add succeeds', async () => {
      await open();
      webAuthn.create.mockRejectedValueOnce(new PasskeyError('cancelled'));
      await startAdd('Phone');
      expect(addError()).toContain('No passkey was created');

      await startAdd('Phone');
      (await confirmed(REGISTRATION)).flush(passkey('p3', 'Phone'));
      await settle();

      expect(addError()).toBeUndefined();
      expect(root.querySelector('.added')?.textContent).toContain('Passkey Phone added.');
    });

    it('deletes a passkey after the confirmation', async () => {
      await open();

      deleteButton('Laptop').click();
      await settle();
      expect(document.querySelector('app-delete-passkey-dialog h2')?.textContent).toContain(
        'Delete Laptop?',
      );
      deleteDialogButton('Delete passkey').click();

      const req = await confirmed(`${PASSKEYS}/p1`);
      expect(req.request.method).toBe('DELETE');
      req.flush(null, { status: 204, statusText: 'No Content' });
      await settle();
      expect(passkeyNames()).toEqual(['YubiKey']);
      expect(root.querySelector('.passkeys + .error')).toBeNull();
    });

    it('keeps the passkey when the confirmation is cancelled', async () => {
      await open();

      deleteButton('YubiKey').click();
      await settle();
      deleteDialogButton('Cancel').click();
      await vi.waitFor(() => expect(TestBed.inject(MatDialog).openDialogs).toHaveLength(0));
      await settle();

      http.expectNone({ method: 'DELETE' });
      expect(passkeyNames()).toEqual(['Laptop', 'YubiKey']);
    });

    it('disables the delete button of the passkey being deleted while it is pending', async () => {
      await open();

      const req = await startDelete('Laptop', 'p1');
      await settle();
      expect(deleteButton('Laptop').disabled).toBe(true);
      expect(deleteButton('YubiKey').disabled).toBe(false);

      req.flush(null, { status: 204, statusText: 'No Content' });
      await settle();
      expect(passkeyNames()).toEqual(['YubiKey']);
    });

    it('shows a failed delete and keeps the passkey', async () => {
      await open();

      (await startDelete('Laptop', 'p1')).flush(
        { title: 'Server error', status: 500, detail: 'Try later' },
        { status: 500, statusText: 'Error' },
      );
      await settle();

      expect(passkeyNames()).toEqual(['Laptop', 'YubiKey']);
      expect(root.querySelector('[role="alert"]')?.textContent).toContain(
        'Laptop could not be deleted: Try later',
      );
      expect(deleteButton('Laptop').disabled).toBe(false);
      expect(notifications.error).not.toHaveBeenCalled();
    });

    it('drops a passkey that was already deleted elsewhere (404)', async () => {
      await open();

      (await startDelete('Laptop', 'p1')).flush(
        { title: 'Not Found', status: 404, detail: 'No such passkey' },
        { status: 404, statusText: 'Not Found' },
      );
      await settle();

      expect(passkeyNames()).toEqual(['YubiKey']);
      expect(root.querySelector('[role="alert"]')).toBeNull();
    });

    it('deletes the last passkey and then shows the empty state', async () => {
      await open([passkey('p1', 'Laptop')]);

      (await startDelete('Laptop', 'p1')).flush(null, { status: 204, statusText: 'No Content' });
      await settle();

      expect(root.querySelector('.passkeys')).toBeNull();
      expect(root.textContent).toContain('No passkeys yet.');
    });

    it('hides the "added" note once a passkey is deleted', async () => {
      await open();
      await startAdd('Phone');
      (await confirmed(REGISTRATION)).flush(passkey('p3', 'Phone'));
      await settle();
      expect(root.querySelector('.added')).not.toBeNull();

      (await startDelete('Phone', 'p3')).flush(null, { status: 204, statusText: 'No Content' });
      await settle();

      expect(root.querySelector('.added')).toBeNull();
      expect(passkeyNames()).toEqual(['Laptop', 'YubiKey']);
    });

    it('moves the focus to the next passkey, then the heading, after deletes', async () => {
      await open([passkey('p1', 'Laptop'), passkey('p2', 'YubiKey')]);

      deleteButton('Laptop').focus();
      const first = await startDelete('Laptop', 'p1');
      await vi.waitFor(() => expect(TestBed.inject(MatDialog).openDialogs).toHaveLength(0));
      first.flush(null, { status: 204, statusText: 'No Content' });
      await settle();
      expect(document.activeElement).toBe(deleteButton('YubiKey'));

      deleteButton('YubiKey').focus();
      const second = await startDelete('YubiKey', 'p2');
      await vi.waitFor(() => expect(TestBed.inject(MatDialog).openDialogs).toHaveLength(0));
      second.flush(null, { status: 204, statusText: 'No Content' });
      await settle();
      expect(document.activeElement).toBe(root.querySelector('#passkeys-label'));
    });
  });
});
