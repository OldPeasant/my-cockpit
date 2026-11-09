import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Provider } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Settings } from '../../../api/generated';
import { NotificationService, provideHttp } from '../../../core/http';
import {
  BROWSER_NOTIFICATIONS,
  REMINDER_NOTIFICATIONS_STORAGE_KEY,
} from '../../../core/notifications';
import { FakeBrowserNotifications } from '../../../core/notifications/testing';
import { ThemeService } from '../../../shell/theme.service';
import { fakeSettings } from '../testing';
import { ANDROID_VERSION_PATH, fakeAndroidApp } from './android-app.testing';
import GeneralSettings, { timeZones } from './general-settings';

const PATH = '/api/v1/settings';

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

describe('GeneralSettings', () => {
  let fixture: ComponentFixture<GeneralSettings>;
  let root: HTMLElement;
  let http: HttpTestingController;
  let notifications: { error: ReturnType<typeof vi.fn> };

  async function setup(
    extraProviders: Provider[] = [],
    settings: Settings = fakeSettings(),
  ): Promise<void> {
    localStorage.clear();
    notifications = { error: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: notifications },
        ...extraProviders,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(GeneralSettings);
    root = fixture.nativeElement as HTMLElement;
    http.expectOne({ method: 'GET', url: PATH }).flush(settings, { headers: { ETag: '"1"' } });
    await tick();
    await fixture.whenStable();
  }

  afterEach(() => {
    // The Android app row asks for the app version once the settings show (own spec).
    for (const req of http.match(ANDROID_VERSION_PATH)) req.flush(fakeAndroidApp());
    http.verify();
  });

  function input(label: string): HTMLInputElement {
    return root.querySelector(`input[aria-label="${label}"]`) as HTMLInputElement;
  }

  function editLabel(label: string, value: string): void {
    const field = input(label);
    field.value = value;
    field.dispatchEvent(new Event('input'));
    field.dispatchEvent(new Event('change'));
  }

  function toggle(text: string): HTMLButtonElement {
    return [...root.querySelectorAll<HTMLButtonElement>('mat-button-toggle button')].find(
      (button) => button.textContent?.trim() === text,
    ) as HTMLButtonElement;
  }

  it('shows the loaded settings', async () => {
    await setup();

    expect(
      (root.querySelector('input[aria-labelledby="tz-label"]') as HTMLInputElement).value,
    ).toBe('Europe/Zurich');
    expect(input('Label for low energy').value).toBe('Low');
    expect(input('Label for creative work').value).toBe('Creative');
    expect(toggle('Light').closest('mat-button-toggle')?.classList).toContain(
      'mat-button-toggle-checked',
    );
  });

  it('saves a changed label group with the ETag and shows it as saved', async () => {
    await setup();

    editLabel('Label for high energy', '  Fully charged ');
    await tick();
    const req = http.expectOne({ method: 'PATCH', url: PATH });
    expect(req.request.headers.get('If-Match')).toBe('"1"');
    expect(req.request.body).toEqual({
      energyLabels: { LOW: 'Low', MEDIUM: 'Medium', HIGH: 'Fully charged' },
    });
    req.flush(
      fakeSettings({ energyLabels: { LOW: 'Low', MEDIUM: 'Medium', HIGH: 'Fully charged' } }),
      { headers: { ETag: '"2"' } },
    );
    await tick();
    await fixture.whenStable();

    expect(input('Label for high energy').value).toBe('Fully charged');
    expect(root.textContent).toContain('Saved');
  });

  it('applies a new theme at once and rolls it back when saving fails', async () => {
    await setup();
    const theme = TestBed.inject(ThemeService);

    toggle('Dark').click();
    await tick();
    expect(theme.mode()).toBe('dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');

    http
      .expectOne({ method: 'PATCH', url: PATH })
      .flush(
        { status: 500, title: 'Internal Server Error', detail: 'Database unavailable.' },
        { status: 500, statusText: 'Internal Server Error' },
      );
    await tick();
    await fixture.whenStable();

    expect(theme.mode()).toBe('light');
    expect(toggle('Light').closest('mat-button-toggle')?.classList).toContain(
      'mat-button-toggle-checked',
    );
    expect(notifications.error).toHaveBeenCalledWith('Database unavailable.');
    expect(root.textContent).toContain('Not saved, previous value restored');
  });

  it('puts the saved label back when saving fails', async () => {
    await setup();

    editLabel('Label for routine work', 'Chores');
    await tick();
    http
      .expectOne({ method: 'PATCH', url: PATH })
      .flush({ status: 400, title: 'Bad Request' }, { status: 400, statusText: 'Bad Request' });
    await tick();
    await fixture.whenStable();

    expect(input('Label for routine work').value).toBe('Routine');
  });

  it('does not save a blank label', async () => {
    await setup();

    editLabel('Label for medium energy', '   ');
    await tick();
    await fixture.whenStable();

    http.expectNone({ method: 'PATCH', url: PATH });
    expect(root.textContent).toContain('Each label needs 1 to 40 characters.');
  });

  it('does not save an unknown time zone and puts the saved one back on blur', async () => {
    await setup();
    const field = root.querySelector('input[aria-labelledby="tz-label"]') as HTMLInputElement;

    field.value = 'Mars/Olympus';
    field.dispatchEvent(new Event('input'));
    field.dispatchEvent(new Event('blur'));
    await tick();
    await fixture.whenStable();

    http.expectNone({ method: 'PATCH', url: PATH });
    expect(field.value).toBe('Europe/Zurich');
  });

  function zoneField(): HTMLInputElement {
    return root.querySelector('input[aria-labelledby="tz-label"]') as HTMLInputElement;
  }

  function typeZone(value: string): void {
    const field = zoneField();
    field.focus();
    field.value = value;
    field.dispatchEvent(new Event('input'));
  }

  it('saves a time zone from the list when the field is left', async () => {
    await setup();

    typeZone('America/New_York');
    zoneField().dispatchEvent(new Event('blur'));
    await tick();

    const req = http.expectOne({ method: 'PATCH', url: PATH });
    expect(req.request.body).toEqual({ timeZone: 'America/New_York' });
    req.flush(fakeSettings({ timeZone: 'America/New_York' }), { headers: { ETag: '"2"' } });
    await tick();
    await fixture.whenStable();

    expect(zoneField().value).toBe('America/New_York');
    expect(root.textContent).toContain('Saved');
  });

  it('saves the time zone on Enter', async () => {
    await setup();

    typeZone('UTC');
    zoneField().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    await tick();

    const req = http.expectOne({ method: 'PATCH', url: PATH });
    expect(req.request.body).toEqual({ timeZone: 'UTC' });
    req.flush(fakeSettings({ timeZone: 'UTC' }), { headers: { ETag: '"2"' } });
    await tick();
  });

  it('offers the matching zones while typing, also with spaces for underscores', async () => {
    await setup();

    typeZone('new york');
    await tick();
    fixture.detectChanges();
    await fixture.whenStable();

    const options = [...document.querySelectorAll('mat-option')].map((o) => o.textContent?.trim());
    expect(options).toContain('America/New_York');
    expect(options).not.toContain('Europe/Zurich');
  });

  it('does not save when a row is left unchanged or only gains surrounding spaces', async () => {
    await setup();

    zoneField().dispatchEvent(new Event('blur'));
    editLabel('Label for low energy', '  Low  ');
    await tick();

    http.expectNone({ method: 'PATCH', url: PATH });
  });

  it('saves the week start picked from the list', async () => {
    await setup();

    (root.querySelector('mat-select .mat-mdc-select-trigger') as HTMLElement).click();
    await fixture.whenStable();
    const sunday = [...document.querySelectorAll<HTMLElement>('mat-option')].find(
      (option) => option.textContent?.trim() === 'Sunday',
    ) as HTMLElement;
    sunday.click();
    await tick();

    const req = http.expectOne({ method: 'PATCH', url: PATH });
    expect(req.request.body).toEqual({ weekStart: 'SUNDAY' });
    expect(root.textContent).toContain('Saving…');
    req.flush(fakeSettings({ weekStart: 'SUNDAY' }), { headers: { ETag: '"2"' } });
    await tick();
    await fixture.whenStable();

    expect(root.querySelector('mat-select')?.textContent).toContain('Sunday');
    expect(root.textContent).toContain('Saved');
  });

  it('shows the reloaded server value after a 412', async () => {
    await setup();

    toggle('Dark').click();
    await tick();
    http
      .expectOne({ method: 'PATCH', url: PATH })
      .flush(
        { status: 412, title: 'Precondition Failed', detail: 'Changed elsewhere.' },
        { status: 412, statusText: 'Precondition Failed' },
      );
    await tick();
    // Another device changed the theme and the other rows meanwhile.
    http.expectOne({ method: 'GET', url: PATH }).flush(
      fakeSettings({
        theme: 'system',
        timeZone: 'UTC',
        weekStart: 'SUNDAY',
        energyLabels: { LOW: 'Tired', MEDIUM: 'Medium', HIGH: 'High' },
      }),
      { headers: { ETag: '"7"' } },
    );
    await tick();
    await fixture.whenStable();

    expect(TestBed.inject(ThemeService).mode()).toBe('system');
    expect(toggle('System').closest('mat-button-toggle')?.classList).toContain(
      'mat-button-toggle-checked',
    );
    expect(root.textContent).toContain('Not saved, previous value restored');
    expect(zoneField().value).toBe('UTC');
    expect(root.querySelector('mat-select')?.textContent).toContain('Sunday');
    expect(input('Label for low energy').value).toBe('Tired');
  });

  it('keeps a label typed in while another label of the group saves', async () => {
    await setup();

    editLabel('Label for low energy', 'Tired');
    await tick();
    const req = http.expectOne({ method: 'PATCH', url: PATH });
    const medium = input('Label for medium energy');
    medium.value = 'Okay-ish';
    medium.dispatchEvent(new Event('input'));
    req.flush(fakeSettings({ energyLabels: { LOW: 'Tired', MEDIUM: 'Medium', HIGH: 'High' } }), {
      headers: { ETag: '"2"' },
    });
    await tick();
    await fixture.whenStable();

    expect(input('Label for low energy').value).toBe('Tired');
    expect(medium.value).toBe('Okay-ish');
  });

  it('keeps a typed label when another label of the group fails to save', async () => {
    await setup();

    editLabel('Label for low energy', 'Tired');
    await tick();
    const req = http.expectOne({ method: 'PATCH', url: PATH });
    const medium = input('Label for medium energy');
    medium.value = 'Okay-ish';
    medium.dispatchEvent(new Event('input'));
    req.flush({ status: 400, title: 'Bad Request' }, { status: 400, statusText: 'Bad Request' });
    await tick();
    await fixture.whenStable();

    expect(input('Label for low energy').value).toBe('Low');
    expect(medium.value).toBe('Okay-ish');
  });

  it('keeps the second theme picked while the first one still saves', async () => {
    await setup();
    const theme = TestBed.inject(ThemeService);

    toggle('Dark').click();
    toggle('System').click();
    await tick();
    http
      .expectOne({ method: 'PATCH', url: PATH })
      .flush(fakeSettings({ theme: 'dark' }), { headers: { ETag: '"2"' } });
    await tick();
    await fixture.whenStable();

    expect(toggle('System').closest('mat-button-toggle')?.classList).toContain(
      'mat-button-toggle-checked',
    );
    const second = http.expectOne({ method: 'PATCH', url: PATH });
    expect(second.request.body).toEqual({ theme: 'system' });
    expect(second.request.headers.get('If-Match')).toBe('"2"');
    second.flush(fakeSettings({ theme: 'system' }), { headers: { ETag: '"3"' } });
    await tick();
    await fixture.whenStable();

    expect(theme.mode()).toBe('system');
    expect(toggle('System').closest('mat-button-toggle')?.classList).toContain(
      'mat-button-toggle-checked',
    );
  });

  it('keeps the second theme picked when the first one fails', async () => {
    await setup();
    const theme = TestBed.inject(ThemeService);

    toggle('Dark').click();
    toggle('System').click();
    await tick();
    http
      .expectOne({ method: 'PATCH', url: PATH })
      .flush({ status: 400, title: 'Bad Request' }, { status: 400, statusText: 'Bad Request' });
    await tick();
    await fixture.whenStable();

    expect(toggle('System').closest('mat-button-toggle')?.classList).toContain(
      'mat-button-toggle-checked',
    );
    const second = http.expectOne({ method: 'PATCH', url: PATH });
    expect(second.request.body).toEqual({ theme: 'system' });
    expect(second.request.headers.get('If-Match')).toBe('"1"');
    second.flush(fakeSettings({ theme: 'system' }), { headers: { ETag: '"2"' } });
    await tick();
    await fixture.whenStable();

    expect(theme.mode()).toBe('system');
    expect(toggle('System').closest('mat-button-toggle')?.classList).toContain(
      'mat-button-toggle-checked',
    );
  });

  it('saves a label changed back to its in-flight value while a newer save is queued', async () => {
    await setup();
    const labels = (low: string) => ({ LOW: low, MEDIUM: 'Medium', HIGH: 'High' });

    editLabel('Label for low energy', 'Tired');
    await tick();
    const first = http.expectOne({ method: 'PATCH', url: PATH });
    editLabel('Label for low energy', 'Sleepy');
    editLabel('Label for low energy', 'Tired');
    first.flush(fakeSettings({ energyLabels: labels('Tired') }), { headers: { ETag: '"2"' } });
    await tick();
    const second = http.expectOne({ method: 'PATCH', url: PATH });
    expect(second.request.body).toEqual({ energyLabels: labels('Sleepy') });
    second.flush(fakeSettings({ energyLabels: labels('Sleepy') }), { headers: { ETag: '"3"' } });
    await tick();
    const third = http.expectOne({ method: 'PATCH', url: PATH });
    expect(third.request.body).toEqual({ energyLabels: labels('Tired') });
    expect(third.request.headers.get('If-Match')).toBe('"3"');
    third.flush(fakeSettings({ energyLabels: labels('Tired') }), { headers: { ETag: '"4"' } });
    await tick();
    await fixture.whenStable();

    expect(input('Label for low energy').value).toBe('Tired');
  });

  it('saves a theme picked back while a newer theme save is queued', async () => {
    await setup();
    const theme = TestBed.inject(ThemeService);

    toggle('Dark').click();
    await tick();
    const first = http.expectOne({ method: 'PATCH', url: PATH });
    toggle('System').click();
    toggle('Dark').click();
    first.flush(fakeSettings({ theme: 'dark' }), { headers: { ETag: '"2"' } });
    await tick();
    const second = http.expectOne({ method: 'PATCH', url: PATH });
    expect(second.request.body).toEqual({ theme: 'system' });
    second.flush(fakeSettings({ theme: 'system' }), { headers: { ETag: '"3"' } });
    await tick();
    const third = http.expectOne({ method: 'PATCH', url: PATH });
    expect(third.request.body).toEqual({ theme: 'dark' });
    expect(third.request.headers.get('If-Match')).toBe('"3"');
    third.flush(fakeSettings({ theme: 'dark' }), { headers: { ETag: '"4"' } });
    await tick();
    await fixture.whenStable();

    expect(theme.mode()).toBe('dark');
    expect(toggle('Dark').closest('mat-button-toggle')?.classList).toContain(
      'mat-button-toggle-checked',
    );
  });

  it('does not send a label again that only gains spaces while its save is in flight', async () => {
    await setup();

    editLabel('Label for low energy', 'Tired');
    await tick();
    const first = http.expectOne({ method: 'PATCH', url: PATH });
    editLabel('Label for low energy', '  Tired ');
    first.flush(fakeSettings({ energyLabels: { LOW: 'Tired', MEDIUM: 'Medium', HIGH: 'High' } }), {
      headers: { ETag: '"2"' },
    });
    await tick();
    await fixture.whenStable();

    http.expectNone({ method: 'PATCH', url: PATH });
    expect(root.textContent).toContain('Saved');
  });

  it('saves the original label when it is changed back while its save is in flight', async () => {
    await setup();

    editLabel('Label for low energy', 'Tired');
    await tick();
    const first = http.expectOne({ method: 'PATCH', url: PATH });
    editLabel('Label for low energy', 'Low');
    first.flush(fakeSettings({ energyLabels: { LOW: 'Tired', MEDIUM: 'Medium', HIGH: 'High' } }), {
      headers: { ETag: '"2"' },
    });
    await tick();
    const second = http.expectOne({ method: 'PATCH', url: PATH });
    expect(second.request.body).toEqual({
      energyLabels: { LOW: 'Low', MEDIUM: 'Medium', HIGH: 'High' },
    });
    expect(second.request.headers.get('If-Match')).toBe('"2"');
    second.flush(fakeSettings(), { headers: { ETag: '"3"' } });
    await tick();
    await fixture.whenStable();

    expect(input('Label for low energy').value).toBe('Low');
  });

  it('keeps a label the user changes again while its own save fails', async () => {
    await setup();

    editLabel('Label for low energy', 'Tired');
    await tick();
    const req = http.expectOne({ method: 'PATCH', url: PATH });
    const low = input('Label for low energy');
    low.value = 'Exhausted';
    low.dispatchEvent(new Event('input'));
    req.flush({ status: 400, title: 'Bad Request' }, { status: 400, statusText: 'Bad Request' });
    await tick();
    await fixture.whenStable();

    expect(low.value).toBe('Exhausted');
    expect(input('Label for high energy').value).toBe('High');
  });

  it('keeps a time zone being typed while another row reloads after a 412', async () => {
    await setup();

    typeZone('Asia/Tok');
    toggle('Dark').click();
    await tick();
    http
      .expectOne({ method: 'PATCH', url: PATH })
      .flush(
        { status: 412, title: 'Precondition Failed' },
        { status: 412, statusText: 'Precondition Failed' },
      );
    await tick();
    http
      .expectOne({ method: 'GET', url: PATH })
      .flush(fakeSettings({ timeZone: 'UTC', weekStart: 'SUNDAY' }), { headers: { ETag: '"7"' } });
    await tick();
    await fixture.whenStable();

    expect(zoneField().value).toBe('Asia/Tok');
    expect(root.querySelector('mat-select')?.textContent).toContain('Sunday');
    expect(toggle('Light').closest('mat-button-toggle')?.classList).toContain(
      'mat-button-toggle-checked',
    );
  });

  describe('remote images in mail', () => {
    const senders = () =>
      [...root.querySelectorAll('.senders li .sender')].map((el) => el.textContent?.trim());
    const removeButton = (sender: string) =>
      root.querySelector<HTMLButtonElement>(`button[aria-label="Remove ${sender}"]`)!;

    it('shows "ask" by default and saves "always load"', async () => {
      await setup();
      expect(root.textContent).toContain('Remote images in mail');
      expect(toggle('Ask').closest('mat-button-toggle')?.classList).toContain(
        'mat-button-toggle-checked',
      );

      toggle('Always load').click();
      await tick();
      const request = http.expectOne({ method: 'PATCH', url: PATH });
      expect(request.request.body).toEqual({ mailImagePolicy: 'allow' });
      expect(request.request.headers.get('If-Match')).toBe('"1"');
      request.flush(fakeSettings({ mailImagePolicy: 'allow' }), { headers: { ETag: '"2"' } });
      await tick();
      await fixture.whenStable();
      expect(toggle('Always load').closest('mat-button-toggle')?.classList).toContain(
        'mat-button-toggle-checked',
      );
    });

    it('shows "always load" when the server has the policy "allow"', async () => {
      await setup([], fakeSettings({ mailImagePolicy: 'allow' }));
      expect(toggle('Always load').closest('mat-button-toggle')?.classList).toContain(
        'mat-button-toggle-checked',
      );
    });

    it('goes back to "ask" when saving "always load" fails', async () => {
      await setup();
      toggle('Always load').click();
      await tick();
      http
        .expectOne({ method: 'PATCH', url: PATH })
        .flush(
          { status: 500, title: 'Internal Server Error', detail: 'Database unavailable.' },
          { status: 500, statusText: 'Internal Server Error' },
        );
      await tick();
      await fixture.whenStable();
      expect(toggle('Ask').closest('mat-button-toggle')?.classList).toContain(
        'mat-button-toggle-checked',
      );
      expect(notifications.error).toHaveBeenCalledWith('Database unavailable.');
    });

    it('disables Remove while the removal saves and sends it once', async () => {
      await setup(
        [],
        fakeSettings({ mailImageAllowedSenders: ['max@example.test', 'example.org'] }),
      );
      removeButton('max@example.test').click();
      fixture.detectChanges();
      removeButton('max@example.test')?.click();
      await tick();
      fixture.detectChanges();
      const request = http.expectOne({ method: 'PATCH', url: PATH });
      expect(request.request.body).toEqual({ mailImageAllowedSenders: ['example.org'] });
      request.flush(fakeSettings({ mailImageAllowedSenders: ['example.org'] }), {
        headers: { ETag: '"2"' },
      });
      await tick();
      await fixture.whenStable();
      expect(senders()).toEqual(['example.org']);
    });

    it('says when no sender is allowed', async () => {
      await setup();
      expect(senders()).toEqual([]);
      expect(root.textContent).toContain('No senders yet.');
    });

    it('lists the allowed senders and removes one', async () => {
      await setup(
        [],
        fakeSettings({ mailImageAllowedSenders: ['max@example.test', 'example.org'] }),
      );
      expect(senders()).toEqual(['max@example.test', 'example.org']);

      removeButton('example.org').click();
      await tick();
      fixture.detectChanges();
      // Shown at once; the server's answer confirms it.
      expect(senders()).toEqual(['max@example.test']);
      const request = http.expectOne({ method: 'PATCH', url: PATH });
      expect(request.request.body).toEqual({ mailImageAllowedSenders: ['max@example.test'] });
      request.flush(fakeSettings({ mailImageAllowedSenders: ['max@example.test'] }), {
        headers: { ETag: '"2"' },
      });
      await tick();
      await fixture.whenStable();
      expect(senders()).toEqual(['max@example.test']);
    });

    it('brings a sender back when removing it fails', async () => {
      await setup([], fakeSettings({ mailImageAllowedSenders: ['max@example.test'] }));
      removeButton('max@example.test').click();
      await tick();
      http
        .expectOne({ method: 'PATCH', url: PATH })
        .flush(
          { status: 500, title: 'Internal Server Error', detail: 'Database unavailable.' },
          { status: 500, statusText: 'Internal Server Error' },
        );
      await tick();
      await fixture.whenStable();
      expect(senders()).toEqual(['max@example.test']);
      expect(removeButton('max@example.test').disabled).toBe(false);
      expect(notifications.error).toHaveBeenCalledWith('Database unavailable.');
    });
  });

  it('offers IANA time zones including UTC', () => {
    const zones = timeZones();
    expect(zones).toContain('Europe/Zurich');
    expect(zones).toContain('UTC');
  });

  it('shows the error and retries when loading fails', async () => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: { error: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(GeneralSettings);
    root = fixture.nativeElement as HTMLElement;
    http
      .expectOne(PATH)
      .flush(
        { status: 503, title: 'Service Unavailable', detail: 'Try later.' },
        { status: 503, statusText: 'Service Unavailable' },
      );
    await tick();
    await fixture.whenStable();
    expect(root.querySelector('[role="alert"]')?.textContent).toContain('Try later.');

    (root.querySelector('[role="alert"] button') as HTMLButtonElement).click();
    http.expectOne(PATH).flush(fakeSettings(), { headers: { ETag: '"1"' } });
    await tick();
    await fixture.whenStable();
    expect(input('Label for low energy').value).toBe('Low');
  });

  it('shows the Android app row with its version and download link', async () => {
    await setup();
    await fixture.whenStable();
    http.expectOne(ANDROID_VERSION_PATH).flush(fakeAndroidApp());
    await tick();
    await fixture.whenStable();

    expect(root.textContent).toContain('Android app');
    expect(root.querySelector('app-android-app-download .version')?.textContent?.trim()).toBe(
      'Version 1.4.0',
    );
    expect(root.querySelector('app-android-app-download a[download]')?.getAttribute('href')).toBe(
      '/download/android',
    );
  });

  describe('reminder notifications row', () => {
    const reminderSwitch = () =>
      root.querySelector<HTMLButtonElement>(
        'app-reminder-notifications-toggle button[role="switch"]',
      );
    const reminderHint = () =>
      root.querySelector('app-reminder-notifications-toggle .hint')?.textContent?.trim() ?? '';

    afterEach(() => localStorage.clear());

    it('offers the switch, explaining that this browser has no notifications (jsdom)', async () => {
      await setup();

      expect(root.textContent).toContain('Reminder notifications');
      expect(root.textContent).toContain('Only for this browser.');
      expect(reminderSwitch()?.getAttribute('aria-disabled')).toBe('true');
      expect(reminderHint()).toContain("This browser can't show notifications");
    });

    it('explains the in-app fallback only once while the switch is off', async () => {
      await setup([{ provide: BROWSER_NOTIFICATIONS, useValue: new FakeBrowserNotifications() }]);

      expect(reminderSwitch()?.getAttribute('aria-checked')).toBe('false');
      expect(reminderHint()).toContain('Reminders appear inside My Cockpit');
      expect(root.textContent?.match(/inside My Cockpit/g) ?? []).toHaveLength(1);
    });

    it('switches browser notifications on from the settings page without saving a setting', async () => {
      const browser = new FakeBrowserNotifications();
      await setup([{ provide: BROWSER_NOTIFICATIONS, useValue: browser }]);
      expect(reminderSwitch()?.getAttribute('aria-checked')).toBe('false');

      reminderSwitch()?.click();
      await tick();
      await fixture.whenStable();

      expect(browser.requests).toBe(1);
      expect(reminderSwitch()?.getAttribute('aria-checked')).toBe('true');
      // The fallback is explained once, by the switch's hint, not again by the row.
      expect(root.textContent?.match(/inside My Cockpit/g) ?? []).toHaveLength(0);
      expect(localStorage.getItem(REMINDER_NOTIFICATIONS_STORAGE_KEY)).toBe('on');
      // The switch is per browser: no PATCH to /api/v1/settings (verified in afterEach).
    });
  });

  describe('weekly review reminder', () => {
    function daySelect(): HTMLElement {
      return root.querySelector('.reminder mat-select') as HTMLElement;
    }

    function timeInput(): HTMLInputElement | null {
      return root.querySelector('input[aria-label="Reminder time"]');
    }

    async function pickDay(text: string): Promise<void> {
      (daySelect().querySelector('.mat-mdc-select-trigger') as HTMLElement).click();
      await fixture.whenStable();
      const option = [...document.querySelectorAll<HTMLElement>('mat-option')].find(
        (o) => o.textContent?.trim() === text,
      ) as HTMLElement;
      option.click();
      await tick();
    }

    async function flushPatch(body: unknown, settings: Settings): Promise<void> {
      const req = http.expectOne({ method: 'PATCH', url: PATH });
      expect(req.request.body).toEqual(body);
      req.flush(settings, { headers: { ETag: '"2"' } });
      await tick();
      await fixture.whenStable();
    }

    it('shows a stored day and time', async () => {
      await setup([], fakeSettings({ weeklyReviewReminder: 'FRIDAY@16:30' }));

      expect(daySelect().textContent).toContain('Friday');
      expect(timeInput()?.value).toBe('16:30');
    });

    it('shows off without a time field when the reminder is off or unset', async () => {
      await setup([], fakeSettings({ weeklyReviewReminder: 'off' }));

      expect(daySelect().textContent).toContain('Off');
      expect(timeInput()).toBeNull();
    });

    it('saves off', async () => {
      await setup([], fakeSettings({ weeklyReviewReminder: 'SUNDAY@18:00' }));

      await pickDay('Off');
      await flushPatch(
        { weeklyReviewReminder: 'off' },
        fakeSettings({ weeklyReviewReminder: 'off' }),
      );

      expect(daySelect().textContent).toContain('Off');
      expect(timeInput()).toBeNull();
      expect(root.textContent).toContain('Saved');
    });

    it('saves a day with the default time, then a changed time', async () => {
      await setup();

      await pickDay('Sunday');
      await flushPatch(
        { weeklyReviewReminder: 'SUNDAY@18:00' },
        fakeSettings({ weeklyReviewReminder: 'SUNDAY@18:00' }),
      );
      expect(timeInput()?.value).toBe('18:00');

      const time = timeInput() as HTMLInputElement;
      time.value = '09:15';
      time.dispatchEvent(new Event('input'));
      time.dispatchEvent(new Event('change'));
      await tick();
      await flushPatch(
        { weeklyReviewReminder: 'SUNDAY@09:15' },
        fakeSettings({ weeklyReviewReminder: 'SUNDAY@09:15' }),
      );

      expect(daySelect().textContent).toContain('Sunday');
      expect(timeInput()?.value).toBe('09:15');
      expect(root.textContent).toContain('Saved');
    });

    it('does not save an empty time', async () => {
      await setup([], fakeSettings({ weeklyReviewReminder: 'SUNDAY@18:00' }));

      const time = timeInput() as HTMLInputElement;
      time.value = '';
      time.dispatchEvent(new Event('input'));
      time.dispatchEvent(new Event('change'));
      await tick();

      http.expectNone({ method: 'PATCH', url: PATH });
    });

    it('asks for a time when the time field is emptied and left', async () => {
      await setup([], fakeSettings({ weeklyReviewReminder: 'SUNDAY@18:00' }));
      expect(root.querySelector('.reminder mat-error')).toBeNull();

      const time = timeInput() as HTMLInputElement;
      time.value = '';
      time.dispatchEvent(new Event('input'));
      time.dispatchEvent(new Event('change'));
      time.dispatchEvent(new Event('blur'));
      await tick();
      await fixture.whenStable();

      expect(root.querySelector('.reminder mat-error')?.textContent?.trim()).toBe('Enter a time.');
      http.expectNone({ method: 'PATCH', url: PATH });
    });

    it('shows the reloaded reminder after another row saves into a 412', async () => {
      await setup([], fakeSettings({ weeklyReviewReminder: 'SUNDAY@18:00' }));

      toggle('Dark').click();
      await tick();
      http
        .expectOne({ method: 'PATCH', url: PATH })
        .flush(
          { status: 412, title: 'Precondition Failed', detail: 'Changed elsewhere.' },
          { status: 412, statusText: 'Precondition Failed' },
        );
      await tick();
      // Another device changed the reminder meanwhile.
      http
        .expectOne({ method: 'GET', url: PATH })
        .flush(fakeSettings({ weeklyReviewReminder: 'FRIDAY@08:00' }), {
          headers: { ETag: '"7"' },
        });
      await tick();
      await fixture.whenStable();

      expect(daySelect().textContent).toContain('Friday');
      expect(timeInput()?.value).toBe('08:00');
    });

    it('puts the stored reminder back when the server refuses the save', async () => {
      await setup([], fakeSettings({ weeklyReviewReminder: 'SUNDAY@18:00' }));

      await pickDay('Monday');
      http
        .expectOne({ method: 'PATCH', url: PATH })
        .flush(
          { status: 400, title: 'Bad Request', detail: 'Invalid.' },
          { status: 400, statusText: 'Bad Request' },
        );
      await tick();
      await fixture.whenStable();

      expect(daySelect().textContent).toContain('Sunday');
      expect(root.textContent).toContain('Not saved, previous value restored');
    });

    it('shows an unknown stored value as off and saves a picked day with the default time', async () => {
      await setup([], fakeSettings({ weeklyReviewReminder: 'FUNDAY@25:99' }));

      expect(daySelect().textContent).toContain('Off');
      expect(timeInput()).toBeNull();

      await pickDay('Wednesday');
      await flushPatch(
        { weeklyReviewReminder: 'WEDNESDAY@18:00' },
        fakeSettings({ weeklyReviewReminder: 'WEDNESDAY@18:00' }),
      );
      expect(timeInput()?.value).toBe('18:00');
    });

    it('keeps the chosen time while off and uses it when switched on again', async () => {
      await setup([], fakeSettings({ weeklyReviewReminder: 'SUNDAY@07:45' }));

      await pickDay('Off');
      await flushPatch(
        { weeklyReviewReminder: 'off' },
        fakeSettings({ weeklyReviewReminder: 'off' }),
      );
      expect(timeInput()).toBeNull();

      await pickDay('Monday');
      await flushPatch(
        { weeklyReviewReminder: 'MONDAY@07:45' },
        fakeSettings({ weeklyReviewReminder: 'MONDAY@07:45' }),
      );
      expect(timeInput()?.value).toBe('07:45');
    });

    it('does not save a time changed back to the stored one', async () => {
      await setup([], fakeSettings({ weeklyReviewReminder: 'SUNDAY@18:00' }));

      const time = timeInput() as HTMLInputElement;
      time.value = '18:00';
      time.dispatchEvent(new Event('input'));
      time.dispatchEvent(new Event('change'));
      await tick();

      http.expectNone({ method: 'PATCH', url: PATH });
    });

    it('keeps the second day picked while the first save is still running', async () => {
      await setup([], fakeSettings({ weeklyReviewReminder: 'SUNDAY@18:00' }));

      await pickDay('Monday');
      const first = http.expectOne({ method: 'PATCH', url: PATH });
      expect(first.request.body).toEqual({ weeklyReviewReminder: 'MONDAY@18:00' });
      await pickDay('Tuesday');
      first.flush(fakeSettings({ weeklyReviewReminder: 'MONDAY@18:00' }), {
        headers: { ETag: '"2"' },
      });
      await tick();
      await fixture.whenStable();

      // The answer to the first save does not overwrite the newer pick.
      expect(daySelect().textContent).toContain('Tuesday');
      const second = http.expectOne({ method: 'PATCH', url: PATH });
      expect(second.request.body).toEqual({ weeklyReviewReminder: 'TUESDAY@18:00' });
      expect(second.request.headers.get('If-Match')).toBe('"2"');
      second.flush(fakeSettings({ weeklyReviewReminder: 'TUESDAY@18:00' }), {
        headers: { ETag: '"3"' },
      });
      await tick();
      await fixture.whenStable();

      expect(daySelect().textContent).toContain('Tuesday');
      expect(root.textContent).toContain('Saved');
    });

    it('saves the stored day again when it is picked back while a save is running', async () => {
      await setup([], fakeSettings({ weeklyReviewReminder: 'SUNDAY@18:00' }));

      await pickDay('Monday');
      const first = http.expectOne({ method: 'PATCH', url: PATH });
      await pickDay('Sunday');
      first.flush(fakeSettings({ weeklyReviewReminder: 'MONDAY@18:00' }), {
        headers: { ETag: '"2"' },
      });
      await tick();
      const second = http.expectOne({ method: 'PATCH', url: PATH });
      expect(second.request.body).toEqual({ weeklyReviewReminder: 'SUNDAY@18:00' });
      second.flush(fakeSettings({ weeklyReviewReminder: 'SUNDAY@18:00' }), {
        headers: { ETag: '"3"' },
      });
      await tick();
      await fixture.whenStable();

      expect(daySelect().textContent).toContain('Sunday');
    });
  });
});
