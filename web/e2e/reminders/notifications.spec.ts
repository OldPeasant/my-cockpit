import { Page } from '@playwright/test';
import { expect, test } from '../support/fixtures';

/** What the stubbed Notification API saw in the page. */
interface NotificationLog {
  /** `navigator.userActivation.isActive` at each permission request: true = from a user gesture. */
  requests: boolean[];
  shown: { title: string; tag?: string }[];
}

declare global {
  interface Window {
    notificationLog: NotificationLog;
  }
}

/**
 * Replaces the browser's Notification API before the app starts: headless Chromium neither shows
 * the permission prompt nor notifications, so the stub answers the prompt with "granted" and records
 * what the app asks for and shows.
 */
async function stubNotifications(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const log: NotificationLog = { requests: [], shown: [] };
    window.notificationLog = log;
    let permission: NotificationPermission = 'default';
    class StubNotification {
      static get permission(): NotificationPermission {
        return permission;
      }
      static requestPermission(): Promise<NotificationPermission> {
        log.requests.push(navigator.userActivation.isActive);
        permission = 'granted';
        return Promise.resolve(permission);
      }
      onclick: ((event: Event) => void) | null = null;
      constructor(title: string, options?: NotificationOptions) {
        log.shown.push({ title, tag: options?.tag });
      }
      close(): void {
        // Nothing shown, nothing to close.
      }
    }
    Object.defineProperty(window, 'Notification', { value: StubNotification, configurable: true });
  });
}

/** The CSRF header for a mutating API call; the server renews the cookie, so read it before each call. */
async function xsrfHeader(page: Page): Promise<Record<string, string>> {
  const cookies = await page.context().cookies();
  return { 'X-XSRF-TOKEN': cookies.find((cookie) => cookie.name === 'XSRF-TOKEN')?.value ?? '' };
}

/** Creates a task with a reminder that is already due; the server fires it within its poll interval (15 s). */
async function remindNow(page: Page, title: string): Promise<string> {
  const task = await page.request.post('/api/v1/tasks', {
    headers: await xsrfHeader(page),
    data: { title },
  });
  expect(task.status(), 'create task').toBe(201);
  const { id } = (await task.json()) as { id: string };
  const reminder = await page.request.post('/api/v1/reminders', {
    headers: await xsrfHeader(page),
    data: { itemType: 'TASK', itemId: id, remindAt: new Date(Date.now() - 1000).toISOString() },
  });
  expect(reminder.status(), 'create reminder').toBe(201);
  return id;
}

test.describe('reminder notifications', () => {
  test.describe.configure({ timeout: 60_000 });

  test('the settings switch asks from the click, then a fired reminder shows as a browser notification', async ({
    loggedIn: page,
  }) => {
    await stubNotifications(page);
    await page.goto('/settings');
    const toggle = page.getByRole('switch', { name: 'Show reminders as browser notifications' });
    await expect(toggle).toHaveAttribute('aria-checked', 'false');
    expect(await page.evaluate(() => window.notificationLog.requests)).toEqual([]);

    await toggle.click();

    await expect(toggle).toHaveAttribute('aria-checked', 'true');
    expect(await page.evaluate(() => window.notificationLog.requests)).toEqual([true]);

    await remindNow(page, 'Water the office plants');

    await expect
      .poll(() => page.evaluate(() => window.notificationLog.shown.map((shown) => shown.title)), {
        timeout: 40_000,
      })
      .toEqual(['Water the office plants']);
    await expect(page.getByText('Reminder: Water the office plants')).toHaveCount(0);
  });

  test('without browser notifications, a fired reminder shows as a toast that opens the item', async ({
    loggedIn: page,
  }) => {
    await page.goto('/settings');
    const id = await remindNow(page, 'Renew the library card');

    const toast = page.getByText('Reminder: Renew the library card');
    await expect(toast).toBeVisible({ timeout: 40_000 });
    await page.getByRole('button', { name: 'Open' }).click();
    await expect(page).toHaveURL(new RegExp(`/tasks/${id}$`));
  });
});
