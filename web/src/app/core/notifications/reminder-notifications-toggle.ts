import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  DOCUMENT,
  inject,
  signal,
} from '@angular/core';
import { MatSlideToggleChange, MatSlideToggleModule } from '@angular/material/slide-toggle';
import { ReminderNotificationsService } from './reminder-notifications.service';

let nextHintId = 0;

/**
 * The switch for browser notifications of reminders (Settings → General). Switching it on asks
 * for the browser's permission, so it must stay a direct user gesture. Explains why it can't be
 * switched on (no support, permission refused) and that reminders then show in the app. The
 * permission is re-read whenever the user comes back to the page, e.g. from the site settings.
 */
@Component({
  selector: 'app-reminder-notifications-toggle',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatSlideToggleModule],
  templateUrl: './reminder-notifications-toggle.html',
  styleUrl: './reminder-notifications-toggle.scss',
})
export class ReminderNotificationsToggle {
  private readonly service = inject(ReminderNotificationsService);

  /** The hint describes the switch, so screen readers announce why it is unavailable. */
  protected readonly hintId = `app-reminder-notifications-hint-${nextHintId++}`;
  protected readonly permission = this.service.permission;
  protected readonly asking = signal(false);
  protected readonly checked = computed(
    () => this.service.switchedOn() && this.permission() === 'granted',
  );
  protected readonly blocked = computed(
    () => this.permission() === 'denied' || this.permission() === 'unsupported',
  );

  constructor() {
    this.service.refresh();
    const document = inject(DOCUMENT);
    const window = document.defaultView;
    const refresh = () => this.service.refresh();
    const visible = () => {
      if (document.visibilityState === 'visible') refresh();
    };
    window?.addEventListener('focus', refresh);
    document.addEventListener('visibilitychange', visible);
    inject(DestroyRef).onDestroy(() => {
      window?.removeEventListener('focus', refresh);
      document.removeEventListener('visibilitychange', visible);
    });
  }

  protected async change(event: MatSlideToggleChange): Promise<void> {
    if (!event.checked) {
      this.service.disable();
      return;
    }
    this.asking.set(true);
    try {
      await this.service.enable();
    } finally {
      this.asking.set(false);
      // A refused permission leaves the switch off.
      event.source.checked = this.checked();
    }
  }
}
