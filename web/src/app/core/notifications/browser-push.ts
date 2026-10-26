import { DOCUMENT, inject, InjectionToken, LOCALE_ID } from '@angular/core';

/**
 * The service worker that shows push messages (`public/push-sw.js`), relative to the app's base
 * URL: each locale build has its own copy and scope. `lang` picks the language of its texts.
 */
export const PUSH_SERVICE_WORKER_PATH = 'push-sw.js';

/** What the app uses of a browser's `PushSubscription`. */
export interface BrowserPushSubscription {
  readonly endpoint: string;
  /** The server key it was made with; null when the browser doesn't say. */
  readonly applicationServerKey: ArrayBuffer | null;
  toJSON(): PushSubscriptionJSON;
  unsubscribe(): Promise<boolean>;
}

/** The browser's Push API behind the push service worker; replaced in tests (jsdom has none). */
export interface BrowserPush {
  supported(): boolean;
  /** This browser's current subscription; null also when the service worker isn't registered. */
  subscription(): Promise<BrowserPushSubscription | null>;
  /** Subscribes with the server's VAPID public key; needs the notification permission. */
  subscribe(applicationServerKey: Uint8Array<ArrayBuffer>): Promise<BrowserPushSubscription>;
}

export const BROWSER_PUSH = new InjectionToken<BrowserPush>('BROWSER_PUSH', {
  providedIn: 'root',
  factory: () => {
    const document = inject(DOCUMENT);
    return windowPush(document.defaultView, document.baseURI, inject(LOCALE_ID));
  },
});

function windowPush(
  window: (Window & typeof globalThis) | null,
  baseUri: string,
  locale: string,
): BrowserPush {
  const scriptUrl = new URL(PUSH_SERVICE_WORKER_PATH, baseUri);
  scriptUrl.searchParams.set('lang', locale);
  const serviceWorker = window?.navigator?.serviceWorker;
  const supported = !!serviceWorker && 'PushManager' in window;
  return {
    supported: () => supported,
    subscription: async () => {
      // Without a registration there is no subscription; don't register the worker just to ask.
      const registration = supported ? await serviceWorker.getRegistration(baseUri) : undefined;
      return wrap((await registration?.pushManager.getSubscription()) ?? null);
    },
    subscribe: async (applicationServerKey) => {
      if (!supported) throw new Error('Web Push is not supported');
      await serviceWorker.register(scriptUrl.href);
      // subscribe() needs an active worker; a fresh registration may still be installing.
      const registration = await serviceWorker.ready;
      const subscription = await registration.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey,
      });
      return wrap(subscription)!;
    },
  };
}

function wrap(subscription: PushSubscription | null): BrowserPushSubscription | null {
  if (!subscription) return null;
  return {
    endpoint: subscription.endpoint,
    applicationServerKey: subscription.options.applicationServerKey,
    toJSON: () => subscription.toJSON(),
    unsubscribe: () => subscription.unsubscribe(),
  };
}
