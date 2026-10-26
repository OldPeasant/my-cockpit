import {
  effect,
  EnvironmentProviders,
  inject,
  makeEnvironmentProviders,
  provideEnvironmentInitializer,
} from '@angular/core';
import { AuthService } from '../auth/auth.service';
import { PushSubscriptionService } from './push-subscription.service';
import { ReminderNotificationsService } from './reminder-notifications.service';

/**
 * Starts showing fired reminders (`app.routes.ts` provides this to the shell), and keeps this
 * browser's Web Push subscription in step with the reminder notifications switch: subscribed while
 * it is on and the permission granted, unsubscribed otherwise. The route's injector outlives a
 * logout, so it also follows the session: logout itself unsubscribes, and the next login in the
 * same tab subscribes again.
 */
export function provideReminderNotifications(): EnvironmentProviders {
  return makeEnvironmentProviders([
    provideEnvironmentInitializer(() => {
      const reminders = inject(ReminderNotificationsService);
      const push = inject(PushSubscriptionService);
      const auth = inject(AuthService);
      effect(() => {
        if (!auth.loggedIn()) return;
        if (reminders.enabled()) void push.sync();
        else void push.unsubscribe();
      });
    }),
  ]);
}
