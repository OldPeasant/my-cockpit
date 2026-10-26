import { EnvironmentInjector, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideReminderNotifications } from './provide-reminder-notifications';
import { PushSubscriptionService } from './push-subscription.service';
import { ReminderNotificationsService } from './reminder-notifications.service';

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
