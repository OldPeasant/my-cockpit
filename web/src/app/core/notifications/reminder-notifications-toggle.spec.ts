import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideRouter } from '@angular/router';
import { EMPTY } from 'rxjs';
import { ChangeEventsService } from '../events';
import { BROWSER_NOTIFICATIONS, NotificationPermissionState } from './browser-notifications';
import { ReminderNotificationsToggle } from './reminder-notifications-toggle';
import { REMINDER_NOTIFICATIONS_STORAGE_KEY } from './reminder-notifications.service';
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
    expect(button().disabled).toBe(true);
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

    expect(button().disabled).toBe(true);
    expect(hint()).toContain("This browser can't show notifications");
  });

  it('shows the permission refused earlier in the browser', async () => {
    localStorage.setItem(REMINDER_NOTIFICATIONS_STORAGE_KEY, 'on');
    await setup('denied');

    expect(button().getAttribute('aria-checked')).toBe('false');
    expect(button().disabled).toBe(true);
  });
});
