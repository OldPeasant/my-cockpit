import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NotificationService, provideHttp } from '../../../core/http';
import { ThemeService } from '../../../shell/theme.service';
import { fakeSettings } from '../testing';
import GeneralSettings, { timeZones } from './general-settings';

const PATH = '/api/v1/settings';

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

describe('GeneralSettings', () => {
  let fixture: ComponentFixture<GeneralSettings>;
  let root: HTMLElement;
  let http: HttpTestingController;
  let notifications: { error: ReturnType<typeof vi.fn> };

  async function setup(): Promise<void> {
    localStorage.clear();
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
    fixture = TestBed.createComponent(GeneralSettings);
    root = fixture.nativeElement as HTMLElement;
    http
      .expectOne({ method: 'GET', url: PATH })
      .flush(fakeSettings(), { headers: { ETag: '"1"' } });
    await tick();
    await fixture.whenStable();
  }

  afterEach(() => http.verify());

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
    http
      .expectOne({ method: 'GET', url: PATH })
      .flush(fakeSettings({ theme: 'system' }), { headers: { ETag: '"7"' } });
    await tick();
    await fixture.whenStable();

    expect(TestBed.inject(ThemeService).mode()).toBe('system');
    expect(toggle('System').closest('mat-button-toggle')?.classList).toContain(
      'mat-button-toggle-checked',
    );
    expect(root.textContent).toContain('Not saved, previous value restored');
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
});
