import { HttpContext } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import {
  Api,
  deletePushSubscription,
  getVapidPublicKey,
  putPushSubscription,
} from '../../api/generated';
import { SILENT_ERRORS } from '../http';
import { BROWSER_PUSH, BrowserPushSubscription } from './browser-push';

/** Push is an extra on top of the in-page notifications: its failures never show a toast. */
function silent(): HttpContext {
  return new HttpContext().set(SILENT_ERRORS, true);
}

/**
 * This browser's Web Push subscription (SPEC.md §4.6), so reminders and new mail show while no tab
 * is open. {@link sync} subscribes with the server's VAPID key and registers the subscription with
 * the server; {@link unsubscribe} removes it from both. Everything is best effort: without
 * support, permission or a reachable push service the app keeps its in-page notifications.
 */
@Injectable({ providedIn: 'root' })
export class PushSubscriptionService {
  private readonly api = inject(Api);
  private readonly browser = inject(BROWSER_PUSH);
  private readonly activeState = signal(false);
  /** Calls run one after the other, so a quick off/on can't leave a stale subscription behind. */
  private queue: Promise<unknown> = Promise.resolve();

  /** Whether this browser gets push messages, as of the last {@link sync} or {@link unsubscribe}. */
  readonly active = this.activeState.asReadonly();

  supported(): boolean {
    return this.browser.supported();
  }

  /**
   * Makes sure this browser is subscribed with the server's current key and the server knows the
   * subscription. Call it only while the notification permission is granted. A subscription made
   * with an older key (the server got new keys, e.g. after a restore) is replaced. Registering an
   * existing subscription again is harmless, and restores it on a server that lost it.
   *
   * @returns whether the browser is subscribed now
   */
  sync(): Promise<boolean> {
    return this.enqueue(async () => {
      if (!this.browser.supported()) return false;
      try {
        const { publicKey } = await this.api.invoke(getVapidPublicKey, undefined, silent());
        const key = decodeBase64Url(publicKey);
        let subscription = await this.browser.subscription();
        if (subscription && !sameKey(subscription.applicationServerKey, key)) {
          await this.remove(subscription);
          subscription = null;
        }
        subscription ??= await this.browser.subscribe(key);
        const json = subscription.toJSON();
        await this.api.invoke(
          putPushSubscription,
          {
            body: {
              endpoint: subscription.endpoint,
              keys: { p256dh: json.keys?.['p256dh'] ?? '', auth: json.keys?.['auth'] ?? '' },
            },
          },
          silent(),
        );
        this.activeState.set(true);
        return true;
      } catch {
        this.activeState.set(false);
        return false;
      }
    });
  }

  /** Ends this browser's subscription, on the server and in the browser. */
  unsubscribe(): Promise<void> {
    return this.enqueue(async () => {
      this.activeState.set(false);
      if (!this.browser.supported()) return;
      try {
        const subscription = await this.browser.subscription();
        if (subscription) await this.remove(subscription);
      } catch {
        // Best effort: the server drops a subscription the push service reports gone.
      }
    });
  }

  private async remove(subscription: BrowserPushSubscription): Promise<void> {
    await this.api
      .invoke(deletePushSubscription, { body: { endpoint: subscription.endpoint } }, silent())
      .catch(() => undefined);
    await subscription.unsubscribe();
  }

  private enqueue<T>(task: () => Promise<T>): Promise<T> {
    const next = this.queue.then(task, task);
    this.queue = next.catch(() => undefined);
    return next;
  }
}

/** Decodes unpadded base64url, as the server sends the VAPID key. */
export function decodeBase64Url(value: string): Uint8Array<ArrayBuffer> {
  const base64 = value.replace(/-/g, '+').replace(/_/g, '/');
  const binary = atob(base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '='));
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

function sameKey(current: ArrayBuffer | null, expected: Uint8Array): boolean {
  // A browser that doesn't report the key keeps its subscription.
  if (!current) return true;
  const bytes = new Uint8Array(current);
  return bytes.length === expected.length && bytes.every((b, i) => b === expected[i]);
}
