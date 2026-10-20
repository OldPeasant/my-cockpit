import {
  EnvironmentProviders,
  inject,
  makeEnvironmentProviders,
  provideEnvironmentInitializer,
} from '@angular/core';
import { ReminderNotificationsService } from './reminder-notifications.service';

/** Starts showing fired reminders (`app.routes.ts` provides this to the shell). */
export function provideReminderNotifications(): EnvironmentProviders {
  return makeEnvironmentProviders([
    provideEnvironmentInitializer(() => void inject(ReminderNotificationsService)),
  ]);
}
