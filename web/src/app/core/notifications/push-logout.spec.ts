import { Component, signal } from '@angular/core';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { AuthService } from '../auth/auth.service';
import { NotificationService, provideHttp } from '../http';
import { BROWSER_PUSH } from './browser-push';
import { provideReminderNotifications } from './provide-reminder-notifications';
import { PushSubscriptionService } from './push-subscription.service';
import { ReminderNotificationsService } from './reminder-notifications.service';
import { FakeBrowserPush } from './testing';

/** A fictitious VAPID public key (65 bytes, unpadded base64url). */
const KEY =
  'BAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIjJCUmJygpKissLS4vMDEyMzQ1Njc4OTo7PD0-P0A';
const KEY_URL = '/api/v1/push/vapid-public-key';
const SUBSCRIPTIONS_URL = '/api/v1/push/subscriptions';
const LOGOUT_URL = '/api/v1/auth/logout';
const NO_CONTENT = { status: 204, statusText: 'No Content' };

@Component({ template: '' })
class Blank {}

/**
 * Logout ends this browser's push subscription (review CR-1) while the reminder notifications
 * switch stays on; the shell's provider (app.routes.ts) keeps the subscription in step with that
 * switch. Real AuthService, PushSubscriptionService and router; the Push API is a fake.
 */
describe('Web Push across logout and a new login in the same tab', () => {
  let backend: HttpTestingController;
  let push: FakeBrowserPush;
  let harness: RouterTestingHarness;

  beforeEach(async () => {
    push = new FakeBrowserPush();
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([
          { path: 'login', component: Blank },
          // Like the shell route: the provider lives in the route's environment injector.
          { path: '', component: Blank, providers: [provideReminderNotifications()] },
        ]),
        { provide: BROWSER_PUSH, useValue: push },
        { provide: NotificationService, useValue: { error: vi.fn(), info: vi.fn() } },
        // Switch on and permission granted, unchanged by logging out.
        { provide: ReminderNotificationsService, useValue: { enabled: signal(true) } },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => backend.verify());

  async function next(method: string, url: string): Promise<TestRequest> {
    for (let i = 0; i < 50; i++) {
      TestBed.tick();
      const found = backend.match({ method, url });
      if (found.length) return found[0];
      await new Promise((resolve) => setTimeout(resolve));
    }
    throw new Error(`no ${method} ${url}`);
  }

  async function enterShellAndSubscribe(): Promise<void> {
    await harness.navigateByUrl('/');
    (await next('GET', KEY_URL)).flush({ publicKey: KEY });
    (await next('PUT', SUBSCRIPTIONS_URL)).flush(null, NO_CONTENT);
    await vi.waitFor(() => expect(TestBed.inject(PushSubscriptionService).active()).toBe(true));
  }

  it('removes the subscription on logout', async () => {
    await enterShellAndSubscribe();
    const subscription = push.current!;

    const logout = TestBed.inject(AuthService).logout();
    (await next('DELETE', SUBSCRIPTIONS_URL)).flush(null, NO_CONTENT);
    (await next('POST', LOGOUT_URL)).flush(null, NO_CONTENT);
    await logout;
    await TestBed.inject(Router).navigateByUrl('/login');

    expect(subscription.unsubscribed).toBe(true);
    expect(push.current).toBeNull();
    expect(TestBed.inject(PushSubscriptionService).active()).toBe(false);
  });

  it('subscribes again after logging in again, as the switch is still on', async () => {
    await enterShellAndSubscribe();
    const logout = TestBed.inject(AuthService).logout();
    (await next('DELETE', SUBSCRIPTIONS_URL)).flush(null, NO_CONTENT);
    (await next('POST', LOGOUT_URL)).flush(null, NO_CONTENT);
    await logout;
    await harness.navigateByUrl('/login');

    // Logging in again leads back into the shell without a page reload.
    await harness.navigateByUrl('/');
    (await next('GET', KEY_URL)).flush({ publicKey: KEY });
    (await next('PUT', SUBSCRIPTIONS_URL)).flush(null, NO_CONTENT);

    await vi.waitFor(() => expect(TestBed.inject(PushSubscriptionService).active()).toBe(true));
    expect(push.current).not.toBeNull();
  });
});
