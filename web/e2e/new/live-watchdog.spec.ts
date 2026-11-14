import { APIRequestContext, Page } from '@playwright/test';
import { expect, test } from '../support/fixtures';
import { GREENMAIL_SERVERS, Imap } from '../support/imap';

/**
 * The change-event stream recovers from a cut (laptop sleep, network change): with `/new` open,
 * the page goes offline for longer than the watchdog's silence limit, a mail arrives meanwhile,
 * and after going back online it shows without a reload (ChangeEventsService watchdog + resync).
 */
test.describe('New view after a dead event stream', () => {
  test.setTimeout(180_000);

  test('a mail delivered while the stream was cut appears without a reload', async ({
    loggedIn: page,
  }) => {
    const context = page.context();
    const run = Date.now().toString(36);
    const address = `watchdog-${run}@cockpit.test`;
    const password = 'e2e-not-secret';
    const subject = `Delivered while offline ${run}`;

    const imap = await Imap.login(address, password);
    try {
      await post(page.request, '/api/v1/mail/accounts', {
        type: 'IMAP_SMTP',
        displayName: `E2E watchdog ${run}`,
        emailAddress: address,
        ...GREENMAIL_SERVERS,
        username: address,
        password,
      });
      await page.goto('/new');
      await expect(page.getByRole('heading', { level: 1, name: 'New' })).toBeVisible();
      let navigations = 0;
      page.on('framenavigated', (frame) => {
        if (frame === page.mainFrame()) navigations++;
      });

      await context.setOffline(true);
      await imap.append('INBOX', {
        from: 'Max Muster <max.muster@example.test>',
        to: address,
        subject,
        text: 'Arrived while the stream was down.',
      });
      await syncMail(page.request);
      // Longer than the watchdog's default silence limit (65 s).
      await page.waitForTimeout(70_000);
      await expect(row(page, subject)).toHaveCount(0);

      await context.setOffline(false);
      await expect(row(page, subject)).toBeVisible({ timeout: 40_000 });
      expect(navigations, 'no reload').toBe(0);
    } finally {
      await imap.logout();
    }
  });
});

function row(page: Page, text: string) {
  return page.locator('li.row').filter({ hasText: text });
}

/** Reads the new mail of every account (e2e hook): there is no scheduled sync to wait for. */
async function syncMail(request: APIRequestContext): Promise<void> {
  const response = await request.post('/api/test/mail/sync');
  expect(response.ok(), `POST /api/test/mail/sync: ${response.status()}`).toBe(true);
}

async function xsrf(request: APIRequestContext): Promise<Record<string, string>> {
  const cookie = (await request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN');
  expect(cookie, 'XSRF-TOKEN cookie').toBeDefined();
  return { 'X-XSRF-TOKEN': cookie!.value };
}

async function post(request: APIRequestContext, url: string, data: object): Promise<void> {
  const response = await request.post(url, { headers: await xsrf(request), data });
  expect(response.ok(), `POST ${url}: ${response.status()}`).toBe(true);
}
