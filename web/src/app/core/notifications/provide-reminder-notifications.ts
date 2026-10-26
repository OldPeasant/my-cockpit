import {
  effect,
  EnvironmentProviders,
  inject,
  makeEnvironmentProviders,
  provideEnvironmentInitializer,
} from '@angular/core';
import { PushSubscriptionService } from './push-subscription.service';
import { ReminderNotificationsService } from './reminder-notifications.service';

/**
 * Starts showing fired reminders (`app.routes.ts` provides this to the shell), and keeps this
 * browser's Web Push subscription in step with the reminder notifications switch: subscribed while
 * it is on and the permission granted, unsubscribed otherwise.
 */
export function provideReminderNotifications(): EnvironmentProviders {
  return makeEnvironmentProviders([
    provideEnvironmentInitializer(() => {
      const reminders = inject(ReminderNotificationsService);
      const push = inject(PushSubscriptionService);
      effect(() => {
        if (reminders.enabled()) void push.sync();
        else void push.unsubscribe();
      });
    }),
  ]);
}
