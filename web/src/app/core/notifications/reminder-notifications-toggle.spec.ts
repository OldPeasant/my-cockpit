import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideRouter } from '@angular/router';
import { EMPTY } from 'rxjs';
import { ChangeEventsService } from '../events';
import { BROWSER_NOTIFICATIONS, NotificationPermissionState } from './browser-notifications';
import { ReminderNotificationsToggle } from './reminder-notifications-toggle';
import {
  REMINDER_NOTIFICATIONS_STORAGE_KEY,
  ReminderNotificationsService,
} from './reminder-notifications.service';
import { FakeBrowserNotifications } from './testing';

describe('ReminderNotificationsToggle', () => {
  let browser: FakeBrowserNotifications;
  let fixture: ComponentFixture<ReminderNotificationsToggle>;
  let root: HTMLElement;

  async function setup(permission: NotificationPermissionState): Promise<void> {
    browser = new FakeBrowserNotifications();
    browser.current = permission;
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: BROWSER_NOTIFICATIONS, useValue: browser },
        { provide: ChangeEventsService, useValue: { messages$: () => EMPTY } },
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
      ],
    });
    fixture = TestBed.createComponent(ReminderNotificationsToggle);
    root = fixture.nativeElement as HTMLElement;
    await fixture.whenStable();
  }

  const button = () => root.querySelector('button[role="switch"]') as HTMLButtonElement;
  const hint = () => root.querySelector('.hint')?.textContent?.trim() ?? '';
  /** Disabled but still focusable (`disabledInteractive`), so its description is announced. */
  const disabled = () => button().getAttribute('aria-disabled') === 'true';

  async function click(): Promise<void> {
    button().click();
    await new Promise((resolve) => setTimeout(resolve));
    await fixture.whenStable();
  }

  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  it('asks for the permission when switched on, and is on once granted', async () => {
    await setup('default');
    expect(button().getAttribute('aria-checked')).toBe('false');
    expect(root.textContent).toContain('Show reminders as browser notifications');
    expect(hint()).toContain('Reminders appear inside My Cockpit');

    await click();

    expect(browser.requests).toBe(1);
    expect(button().getAttribute('aria-checked')).toBe('true');
    expect(localStorage.getItem(REMINDER_NOTIFICATIONS_STORAGE_KEY)).toBe('on');
    expect(hint()).toBe('');
  });

  it('springs back and explains the block when the permission is refused', async () => {
    await setup('default');
    browser.answer = 'denied';

    await click();

    expect(button().getAttribute('aria-checked')).toBe('false');
    expect(disabled()).toBe(true);
    expect(hint()).toContain('Notifications are blocked for this site');
  });

  it('switches off without asking', async () => {
    localStorage.setItem(REMINDER_NOTIFICATIONS_STORAGE_KEY, 'on');
    await setup('granted');
    expect(button().getAttribute('aria-checked')).toBe('true');

    await click();

    expect(browser.requests).toBe(0);
    expect(button().getAttribute('aria-checked')).toBe('false');
    expect(localStorage.getItem(REMINDER_NOTIFICATIONS_STORAGE_KEY)).toBeNull();
  });

  it('is disabled when the browser has no notifications', async () => {
    await setup('unsupported');

    expect(disabled()).toBe(true);
    expect(hint()).toContain("This browser can't show notifications");
  });

  it('disables the switch and says to answer the prompt while the browser asks', async () => {
    await setup('default');
    let answer: (permission: NotificationPermission) => void = () => undefined;
    browser.requestPermission = () =>
      new Promise<NotificationPermission>((resolve) => {
        answer = resolve;
      });

    await click();

    expect(disabled()).toBe(true);
    expect(hint()).toBe("Allow notifications in the browser's prompt.");

    answer('granted');
    browser.current = 'granted';
    await new Promise((resolve) => setTimeout(resolve));
    await fixture.whenStable();

    expect(disabled()).toBe(false);
    expect(button().getAttribute('aria-checked')).toBe('true');
    expect(hint()).toBe('');
  });

  it('ignores a second click while the browser asks', async () => {
    await setup('default');
    let answer: (permission: NotificationPermission) => void = () => undefined;
    let requests = 0;
    browser.requestPermission = () => {
      requests++;
      return new Promise<NotificationPermission>((resolve) => {
        answer = resolve;
      });
    };

    await click();
    // Still focusable and clickable (disabledInteractive), but it must not ask twice.
    await click();

    expect(requests).toBe(1);
    expect(disabled()).toBe(true);

    answer('denied');
    browser.current = 'denied';
    await new Promise((resolve) => setTimeout(resolve));
    await fixture.whenStable();

    expect(requests).toBe(1);
    expect(button().getAttribute('aria-checked')).toBe('false');
    expect(localStorage.getItem(REMINDER_NOTIFICATIONS_STORAGE_KEY)).toBeNull();
  });

  it('ignores clicks when the browser has no notifications', async () => {
    await setup('unsupported');

    await click();

    expect(browser.requests).toBe(0);
    expect(button().getAttribute('aria-checked')).toBe('false');
    expect(localStorage.getItem(REMINDER_NOTIFICATIONS_STORAGE_KEY)).toBeNull();
  });

  it('springs back but stays usable when the permission prompt fails', async () => {
    await setup('default');
    browser.requestPermission = () => Promise.reject(new Error('prompt dismissed'));

    await click();

    expect(button().getAttribute('aria-checked')).toBe('false');
    expect(disabled()).toBe(false);
    expect(hint()).toContain('Reminders appear inside My Cockpit');
    expect(localStorage.getItem(REMINDER_NOTIFICATIONS_STORAGE_KEY)).toBeNull();
  });

  it('switches on without asking when the permission was granted before', async () => {
    await setup('granted');
    expect(button().getAttribute('aria-checked')).toBe('false');
    expect(disabled()).toBe(false);
    expect(hint()).toContain('Reminders appear inside My Cockpit');

    await click();

    expect(browser.requests).toBe(0);
    expect(button().getAttribute('aria-checked')).toBe('true');
    expect(localStorage.getItem(REMINDER_NOTIFICATIONS_STORAGE_KEY)).toBe('on');
  });

  it('shows a permission changed in the browser since the service started', async () => {
    browser = new FakeBrowserNotifications();
    browser.current = 'default';
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: BROWSER_NOTIFICATIONS, useValue: browser },
        { provide: ChangeEventsService, useValue: { messages$: () => EMPTY } },
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
      ],
    });
    const service = TestBed.inject(ReminderNotificationsService);
    expect(service.permission()).toBe('default');
    browser.current = 'denied';

    fixture = TestBed.createComponent(ReminderNotificationsToggle);
    root = fixture.nativeElement as HTMLElement;
    await fixture.whenStable();

    expect(disabled()).toBe(true);
    expect(root.querySelector('.hint')?.getAttribute('data-permission')).toBe('denied');
    expect(hint()).toContain('Notifications are blocked for this site');
  });

  it('shows the permission refused earlier in the browser', async () => {
    localStorage.setItem(REMINDER_NOTIFICATIONS_STORAGE_KEY, 'on');
    await setup('denied');

    expect(button().getAttribute('aria-checked')).toBe('false');
    expect(disabled()).toBe(true);
  });

  it('is described by its hint and stays focusable while disabled', async () => {
    await setup('denied');

    const hintElement = root.querySelector('.hint') as HTMLElement;
    expect(hintElement.id).not.toBe('');
    expect(button().getAttribute('aria-describedby')).toBe(hintElement.id);
    expect(button().disabled).toBe(false);
    expect(button().tabIndex).toBe(0);
    expect(document.getElementById(hintElement.id)?.textContent).toContain(
      'Notifications are blocked for this site',
    );

    await click();

    expect(browser.requests).toBe(0);
    expect(button().getAttribute('aria-checked')).toBe('false');
  });

  it('re-reads the permission when the window gets the focus back', async () => {
    await setup('denied');
    expect(disabled()).toBe(true);

    browser.current = 'default';
    window.dispatchEvent(new Event('focus'));
    await fixture.whenStable();

    expect(disabled()).toBe(false);
    expect(hint()).toContain('Reminders appear inside My Cockpit');
  });

  it('re-reads the permission when the page becomes visible again', async () => {
    await setup('denied');

    browser.current = 'granted';
    document.dispatchEvent(new Event('visibilitychange'));
    await fixture.whenStable();

    expect(disabled()).toBe(false);
    expect(root.querySelector('.hint')?.getAttribute('data-permission')).toBe('granted');
  });

  it('does not re-read the permission when the page is hidden', async () => {
    await setup('denied');
    const refresh = vi.spyOn(TestBed.inject(ReminderNotificationsService), 'refresh');
    const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden');
    try {
      browser.current = 'granted';
      document.dispatchEvent(new Event('visibilitychange'));
      await fixture.whenStable();

      expect(refresh).not.toHaveBeenCalled();
      expect(disabled()).toBe(true);
      expect(hint()).toContain('Notifications are blocked for this site');
    } finally {
      visibility.mockRestore();
    }
  });

  it('can be switched on without a prompt once the permission was allowed in the site settings', async () => {
    await setup('denied');

    browser.current = 'granted';
    window.dispatchEvent(new Event('focus'));
    await fixture.whenStable();
    await click();

    expect(browser.requests).toBe(0);
    expect(button().getAttribute('aria-checked')).toBe('true');
    expect(localStorage.getItem(REMINDER_NOTIFICATIONS_STORAGE_KEY)).toBe('on');
  });

  it('gives each switch its own hint id', async () => {
    await setup('default');
    const second = TestBed.createComponent(ReminderNotificationsToggle);
    await second.whenStable();
    const secondRoot = second.nativeElement as HTMLElement;

    const firstId = root.querySelector('.hint')?.id;
    const secondId = secondRoot.querySelector('.hint')?.id;
    expect(firstId).toBeTruthy();
    expect(secondId).toBeTruthy();
    expect(secondId).not.toBe(firstId);
    expect(
      secondRoot.querySelector('button[role="switch"]')?.getAttribute('aria-describedby'),
    ).toBe(secondId);
  });

  it('stops listening for focus and visibility once destroyed', async () => {
    await setup('denied');
    const service = TestBed.inject(ReminderNotificationsService);
    const refresh = vi.spyOn(service, 'refresh');

    fixture.destroy();
    window.dispatchEvent(new Event('focus'));
    document.dispatchEvent(new Event('visibilitychange'));

    expect(refresh).not.toHaveBeenCalled();
  });
});
