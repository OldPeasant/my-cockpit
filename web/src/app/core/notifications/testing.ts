import {
  BrowserNotifications,
  NotificationPermissionState,
  ShownNotification,
} from './browser-notifications';

/** Notification API double: the permission is set by the test, shown notifications are recorded. */
export class FakeBrowserNotifications implements BrowserNotifications {
  current: NotificationPermissionState = 'default';
  /** What the permission prompt answers. */
  answer: NotificationPermission = 'granted';
  refuseToShow = false;
  /** How often the permission was asked for. */
  requests = 0;
  readonly shown: {
    title: string;
    options: NotificationOptions;
    notification: FakeNotification;
  }[] = [];

  permission(): NotificationPermissionState {
    return this.current;
  }

  requestPermission(): Promise<NotificationPermission> {
    this.requests++;
    this.current = this.answer;
    return Promise.resolve(this.answer);
  }

  show(title: string, options: NotificationOptions): ShownNotification {
    if (this.refuseToShow) throw new TypeError('Illegal constructor');
    const notification: FakeNotification = {
      onclick: null,
      closed: false,
      close: () => (notification.closed = true),
    };
    this.shown.push({ title, options, notification });
    return notification;
  }
}

/** A shown notification that records whether it was closed. */
export interface FakeNotification extends ShownNotification {
  closed: boolean;
}
