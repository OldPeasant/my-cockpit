import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatSlideToggleChange, MatSlideToggleModule } from '@angular/material/slide-toggle';
import { ReminderNotificationsService } from './reminder-notifications.service';

/**
 * The switch for browser notifications of reminders (Settings → General). Switching it on asks
 * for the browser's permission, so it must stay a direct user gesture. Explains why it can't be
 * switched on (no support, permission refused) and that reminders then show in the app.
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
