import { BrowserPush, BrowserPushSubscription } from './browser-push';
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

/** Push API double: subscriptions are plain objects, the test controls support and failures. */
export class FakeBrowserPush implements BrowserPush {
  isSupported = true;
  current: FakeBrowserPushSubscription | null = null;
  /** Makes the next subscribe() fail, like a refused permission or an unreachable push service. */
  failSubscribe = false;
  readonly subscribed: Uint8Array[] = [];
  private nextId = 0;

  supported(): boolean {
    return this.isSupported;
  }

  subscription(): Promise<BrowserPushSubscription | null> {
    return Promise.resolve(this.current);
  }

  subscribe(applicationServerKey: Uint8Array<ArrayBuffer>): Promise<BrowserPushSubscription> {
    if (this.failSubscribe) return Promise.reject(new DOMException('denied', 'NotAllowedError'));
    this.subscribed.push(applicationServerKey);
    this.current = new FakeBrowserPushSubscription(
      this,
      `https://push.example.test/send/${++this.nextId}`,
      applicationServerKey.slice().buffer,
    );
    return Promise.resolve(this.current);
  }
}

export class FakeBrowserPushSubscription implements BrowserPushSubscription {
  unsubscribed = false;

  constructor(
    private readonly push: FakeBrowserPush,
    readonly endpoint: string,
    readonly applicationServerKey: ArrayBuffer | null,
  ) {}

  toJSON(): PushSubscriptionJSON {
    return { endpoint: this.endpoint, keys: { p256dh: 'BFakeP256dh', auth: 'fakeAuth' } };
  }

  unsubscribe(): Promise<boolean> {
    this.unsubscribed = true;
    if (this.push.current === this) this.push.current = null;
    return Promise.resolve(true);
  }
}
