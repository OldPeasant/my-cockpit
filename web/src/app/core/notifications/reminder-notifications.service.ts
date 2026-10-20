import { DestroyRef, DOCUMENT, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router } from '@angular/router';
import { ReminderResponse } from '../../api/generated';
import { ChangeEventsService } from '../events';
import { BROWSER_NOTIFICATIONS, NotificationPermissionState } from './browser-notifications';

/** The SSE `event:` name of a fired reminder (server: `ReminderNotifier.EVENT_NAME`). */
export const REMINDER_EVENT_NAME = 'reminder';

/**
 * localStorage key of the "browser notifications for reminders" switch. Per browser, like the
 * permission it depends on, so it is not a server setting.
 */
export const REMINDER_NOTIFICATIONS_STORAGE_KEY = 'cockpit.reminderNotifications';

const REMINDER = $localize`:@@reminders.untitled:Reminder`;

/** How long the in-app reminder toast stays. */
export const REMINDER_TOAST_DURATION_MS = 15_000;

/**
 * Shows each fired reminder (the `reminder` SSE event, SPEC.md §4.6): as a browser notification
 * when the user switched them on and the browser granted the permission, otherwise (or when the
 * browser refuses to show one) as an in-app toast. Clicking either opens the reminded item.
 */
@Injectable({ providedIn: 'root' })
export class ReminderNotificationsService {
  private readonly browser = inject(BROWSER_NOTIFICATIONS);
  private readonly snackBar = inject(MatSnackBar);
  private readonly router = inject(Router);
  private readonly window = inject(DOCUMENT).defaultView;

  private readonly permissionState = signal<NotificationPermissionState>(this.browser.permission());
  private readonly wanted = signal(this.readStored());
  /**
   * Reminder toasts waiting for the one on screen: MatSnackBar shows one at a time, and a burst of
   * reminders (the server fires them in batches) would otherwise replace each other.
   */
  private readonly toasts: { title: string | undefined; link: string | undefined }[] = [];
  private toasting = false;

  /** The browser's permission, as last seen (see {@link refresh}). */
  readonly permission = this.permissionState.asReadonly();
  /** Whether the user switched browser notifications on in this browser. */
  readonly switchedOn = this.wanted.asReadonly();

  constructor() {
    const destroyRef = inject(DestroyRef);
    inject(ChangeEventsService)
      .messages$<ReminderResponse>(REMINDER_EVENT_NAME)
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe((reminder) => this.remind(reminder));
    destroyRef.onDestroy(() => (this.toasts.length = 0));
  }

  /** Whether reminders show as browser notifications right now. */
  enabled(): boolean {
    return this.wanted() && this.permissionState() === 'granted';
  }

  /** Re-reads the permission, which the user can change in the browser at any time. */
  refresh(): void {
    this.permissionState.set(this.browser.permission());
  }

  /**
   * Switches browser notifications on, asking for the permission first if it was never given or
   * refused. Call it directly from a click handler: browsers only ask from a user gesture.
   */
  async enable(): Promise<NotificationPermissionState> {
    const current = this.browser.permission();
    const permission =
      current === 'default'
        ? await this.browser.requestPermission().catch((): NotificationPermissionState => current)
        : current;
    this.permissionState.set(permission);
    this.store(permission === 'granted');
    return permission;
  }

  disable(): void {
    this.store(false);
  }

  private remind(reminder: ReminderResponse): void {
    if (!reminder?.item) return;
    const title = reminder.item.title || undefined;
    const link = reminder.item.available ? reminder.item.deepLink : undefined;
    this.refresh();
    if (this.enabled() && this.notify(reminder, title ?? REMINDER, link)) return;
    this.toast(title, link);
  }

  /** Shows a browser notification; false when the browser refuses. */
  private notify(reminder: ReminderResponse, title: string, link: string | undefined): boolean {
    try {
      const notification = this.browser.show(title, {
        body: $localize`:@@reminders.notification.body:Reminder from My Cockpit`,
        // One notification per reminder, even with several tabs open.
        tag: `reminder-${reminder.id}`,
      });
      notification.onclick = () => {
        this.window?.focus();
        notification.close();
        if (link) void this.router.navigateByUrl(link);
      };
      return true;
    } catch {
      return false;
    }
  }

  /** Queues a reminder toast; it shows once the toasts before it are dismissed. */
  private toast(title: string | undefined, link: string | undefined): void {
    this.toasts.push({ title, link });
    if (!this.toasting) this.showNextToast();
  }

  private showNextToast(): void {
    const next = this.toasts.shift();
    this.toasting = next !== undefined;
    if (!next) return;
    const { title, link } = next;
    const message = title ? $localize`:@@reminders.toast:Reminder: ${title}:title:` : REMINDER;
    const action = link
      ? $localize`:@@reminders.toast.open:Open`
      : $localize`:@@notification.dismiss:Dismiss`;
    const ref = this.snackBar.open(message, action, { duration: REMINDER_TOAST_DURATION_MS });
    if (link) ref.onAction().subscribe(() => void this.router.navigateByUrl(link));
    ref.afterDismissed().subscribe(() => this.showNextToastAfterOthers());
  }

  /**
   * Shows the next reminder toast once no other snackbar is on screen: an app message that replaced
   * a reminder toast keeps its own duration instead of being pushed away by the next reminder.
   */
  private showNextToastAfterOthers(): void {
    const other = this.snackBar._openedSnackBarRef;
    if (other) other.afterDismissed().subscribe(() => this.showNextToastAfterOthers());
    else this.showNextToast();
  }

  private readStored(): boolean {
    try {
      return this.window?.localStorage.getItem(REMINDER_NOTIFICATIONS_STORAGE_KEY) === 'on';
    } catch {
      return false;
    }
  }

  private store(on: boolean): void {
    this.wanted.set(on);
    try {
      if (on) this.window?.localStorage.setItem(REMINDER_NOTIFICATIONS_STORAGE_KEY, 'on');
      else this.window?.localStorage.removeItem(REMINDER_NOTIFICATIONS_STORAGE_KEY);
    } catch {
      // Storage disabled or full: the switch still holds for this session.
    }
  }
}
