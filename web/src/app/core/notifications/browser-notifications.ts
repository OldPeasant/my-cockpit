import { DOCUMENT, inject, InjectionToken } from '@angular/core';

/** Notification permission of this browser, or `unsupported` without the Notification API. */
export type NotificationPermissionState = NotificationPermission | 'unsupported';

/** What the app uses of a shown browser notification: its click handler and closing it. */
export interface ShownNotification {
  onclick: ((event: Event) => void) | null;
  close(): void;
}

/** The browser's Notification API; replaced in tests (jsdom has none). */
export interface BrowserNotifications {
  permission(): NotificationPermissionState;
  /** Must be called from a user gesture (click), or browsers ignore or block the request. */
  requestPermission(): Promise<NotificationPermission>;
  /** Throws where only a service worker may show notifications (e.g. Chrome on Android). */
  show(title: string, options: NotificationOptions): ShownNotification;
}

export const BROWSER_NOTIFICATIONS = new InjectionToken<BrowserNotifications>(
  'BROWSER_NOTIFICATIONS',
  { providedIn: 'root', factory: () => windowNotifications(inject(DOCUMENT).defaultView) },
);

function windowNotifications(window: (Window & typeof globalThis) | null): BrowserNotifications {
  const api = window && 'Notification' in window ? window.Notification : undefined;
  return {
    permission: () => api?.permission ?? 'unsupported',
    requestPermission: () => (api ? api.requestPermission() : Promise.resolve('denied')),
    show: (title, options) => {
      if (!api) throw new Error('Notifications are not supported');
      return new api(title, options);
    },
  };
}
