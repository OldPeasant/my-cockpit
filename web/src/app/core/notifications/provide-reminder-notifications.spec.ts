import { EnvironmentInjector, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideRouter } from '@angular/router';
import { EMPTY } from 'rxjs';
import { ChangeEventsService } from '../events';
import { BROWSER_NOTIFICATIONS, NotificationPermissionState } from './browser-notifications';
import { provideReminderNotifications } from './provide-reminder-notifications';
import { PushSubscriptionService } from './push-subscription.service';
import {
  REMINDER_NOTIFICATIONS_STORAGE_KEY,
  ReminderNotificationsService,
} from './reminder-notifications.service';
import { FakeBrowserNotifications } from './testing';

describe('provideReminderNotifications', () => {
  let enabled: ReturnType<typeof signal<boolean>>;
  let push: { sync: ReturnType<typeof vi.fn>; unsubscribe: ReturnType<typeof vi.fn> };

  function setup(initiallyEnabled: boolean): void {
    enabled = signal(initiallyEnabled);
    push = {
      sync: vi.fn(() => Promise.resolve(true)),
      unsubscribe: vi.fn(() => Promise.resolve()),
    };
    TestBed.configureTestingModule({
      providers: [
        provideReminderNotifications(),
        { provide: ReminderNotificationsService, useValue: { enabled } },
        { provide: PushSubscriptionService, useValue: push },
      ],
    });
    TestBed.inject(EnvironmentInjector);
    TestBed.tick();
  }

  it('subscribes to push while reminder notifications are on', () => {
    setup(true);

    expect(push.sync).toHaveBeenCalledTimes(1);
    expect(push.unsubscribe).not.toHaveBeenCalled();
  });

  it('unsubscribes when they are off, and follows the switch', () => {
    setup(false);
    expect(push.unsubscribe).toHaveBeenCalledTimes(1);

    enabled.set(true);
    TestBed.tick();
    expect(push.sync).toHaveBeenCalledTimes(1);

    enabled.set(false);
    TestBed.tick();
    expect(push.unsubscribe).toHaveBeenCalledTimes(2);
  });
});

describe('provideReminderNotifications with the real switch and permission', () => {
  let browser: FakeBrowserNotifications;
  let push: { sync: ReturnType<typeof vi.fn>; unsubscribe: ReturnType<typeof vi.fn> };

  function setup(permission: NotificationPermissionState, switchedOn: boolean): void {
    if (switchedOn) localStorage.setItem(REMINDER_NOTIFICATIONS_STORAGE_KEY, 'on');
    browser = new FakeBrowserNotifications();
    browser.current = permission;
    push = {
      sync: vi.fn(() => Promise.resolve(true)),
      unsubscribe: vi.fn(() => Promise.resolve()),
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideReminderNotifications(),
        { provide: BROWSER_NOTIFICATIONS, useValue: browser },
        { provide: ChangeEventsService, useValue: { messages$: () => EMPTY } },
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
        { provide: PushSubscriptionService, useValue: push },
      ],
    });
    TestBed.inject(EnvironmentInjector);
    TestBed.tick();
  }

  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  it('subscribes only when the switch is on and the permission granted', () => {
    setup('granted', true);

    expect(push.sync).toHaveBeenCalledTimes(1);
    expect(push.unsubscribe).not.toHaveBeenCalled();
  });

  it('does not subscribe when the switch is on but the permission was refused', () => {
    setup('denied', true);

    expect(push.sync).not.toHaveBeenCalled();
    expect(push.unsubscribe).toHaveBeenCalledTimes(1);
  });

  it('unsubscribes once the permission is revoked in the browser settings', () => {
    setup('granted', true);
    const reminders = TestBed.inject(ReminderNotificationsService);

    browser.current = 'denied';
    reminders.refresh();
    TestBed.tick();

    expect(push.unsubscribe).toHaveBeenCalledTimes(1);
  });

  it('subscribes once the user switches notifications on and grants the permission', async () => {
    setup('default', false);
    expect(push.sync).not.toHaveBeenCalled();

    await TestBed.inject(ReminderNotificationsService).enable();
    TestBed.tick();

    expect(browser.requests).toBe(1);
    expect(push.sync).toHaveBeenCalledTimes(1);
  });
});
