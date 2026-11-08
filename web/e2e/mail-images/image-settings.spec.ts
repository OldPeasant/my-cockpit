import { APIRequestContext, Page } from '@playwright/test';
import { expect, test } from '../support/fixtures';
import { GREENMAIL_SERVERS, Imap } from '../support/imap';

/** A 1×1 PNG: the image proxy's answer (the e2e stack has no internet to fetch the real one). */
const PIXEL = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
);
const REMOTE_IMAGE = 'https://images.example.test/promo/banner.png';

/**
 * The mail server as the Cockpit server reaches it: `greenmail` on the e2e stack; a server started
 * with `./gradlew bootRun` next to a GreenMail container reaches it on the host
 * (`E2E_SERVER_GREENMAIL=localhost` plus `E2E_IMAP_PORT` / `E2E_SMTP_PORT`).
 */
const SERVERS = process.env['E2E_SERVER_GREENMAIL']
  ? {
      imap: {
        host: process.env['E2E_SERVER_GREENMAIL'],
        port: Number(process.env['E2E_IMAP_PORT']),
        security: 'NONE',
      },
      smtp: {
        host: process.env['E2E_SERVER_GREENMAIL'],
        port: Number(process.env['E2E_SMTP_PORT']),
        security: 'NONE',
      },
    }
  : GREENMAIL_SERVERS;

/**
 * The read view follows the synced image settings (SPEC.md §4.1, §4.7): with `mailImagePolicy`
 * `block`, "Load images" shows the remote and the inline (`cid:`) image; with `allow` (set in
 * Settings → General) both show at once; "Always load from this sender" adds the sender to
 * `mailImageAllowedSenders`, which Settings lists and removes. The policy is shared by every spec of
 * the account, so this spec runs on its own (project `inbox-exclusive`) and puts it back at the end.
 */
test.describe('Mail image settings', () => {
  test.setTimeout(120_000);

  test('the read view follows "Remote images in mail" and the allowed senders', async ({
    loggedIn: page,
  }) => {
    const run = Date.now().toString(36);
    const address = `images-${run}@cockpit.test`;
    const sender = `promo-${run}@example.test`;
    const subject = `Promotion ${run}`;
    const password = 'e2e-not-secret';

    const imap = await Imap.login(address, password);
    try {
      await imap.appendRaw('INBOX', htmlMail(sender, address, subject));
      await send(page.request, 'POST', '/api/v1/mail/accounts', {
        type: 'IMAP_SMTP',
        displayName: `E2E images ${run}`,
        emailAddress: address,
        ...SERVERS,
        username: address,
        password,
      });
      const sync = await page.request.post('/api/test/mail/sync');
      expect(sync.ok(), `POST /api/test/mail/sync: ${sync.status()}`).toBe(true);
    } finally {
      await imap.logout();
    }

    const proxied: string[] = [];
    await page.route('**/api/v1/mail/image-proxy**', async (route) => {
      proxied.push(new URL(route.request().url()).searchParams.get('url') ?? '');
      await route.fulfill({ status: 200, contentType: 'image/png', body: PIXEL });
    });

    try {
      // 1. Blocked (the default): the banner shows; "Load images" shows both images.
      await page.goto('/mail');
      await page.locator('li.item').filter({ hasText: subject }).locator('a.open').click();
      await expect(page).toHaveURL(/\/mail\/messages\/[0-9a-f-]{36}$/);
      const messageUrl = page.url();
      await expect(page.getByText('Remote images are hidden')).toBeVisible();
      expect(await frameImages(page)).toEqual([]);
      expect(proxied).toEqual([]);
      await page.screenshot({ path: test.info().outputPath('1-blocked.png'), fullPage: true });

      await page.getByRole('button', { name: 'Load images', exact: true }).click();
      await expect(page.getByText('Remote images are hidden')).toBeHidden();
      await expect.poll(() => frameImages(page)).toEqual([{ proxied: true, loaded: true }]);
      await expect.poll(() => inlineImages(page)).toEqual([{ loaded: true }]);
      expect(proxied).toEqual([REMOTE_IMAGE]);
      await page.screenshot({ path: test.info().outputPath('2-loaded.png'), fullPage: true });

      // 2. Settings → General: "Always load".
      await page.goto('/settings');
      const policy = page.locator('mat-button-toggle-group[aria-labelledby="mail-images-label"]');
      await policy.getByText('Always load', { exact: true }).click();
      await expect(page.getByText('Saved')).toBeVisible();
      await policy.scrollIntoViewIfNeeded();
      await page.screenshot({ path: test.info().outputPath('3-settings.png') });

      // Opening the mail again shows both images at once, without the banner.
      proxied.length = 0;
      await page.goto(messageUrl);
      await expect(page.getByRole('heading', { level: 1 })).toHaveText(subject);
      await expect.poll(() => frameImages(page)).toEqual([{ proxied: true, loaded: true }]);
      await expect.poll(() => inlineImages(page)).toEqual([{ loaded: true }]);
      await expect(page.getByText('Remote images are hidden')).toBeHidden();
      expect(proxied).toEqual([REMOTE_IMAGE]);
      await page.screenshot({ path: test.info().outputPath('4-allowed.png'), fullPage: true });

      // 3. Back to "Ask"; "Always load from this sender" adds the sender to the synced list.
      await page.goto('/settings');
      await policy.getByText('Ask', { exact: true }).click();
      await expect(page.getByText('Saved')).toBeVisible();
      await page.goto(messageUrl);
      await expect(page.getByText('Remote images are hidden')).toBeVisible();
      await page.getByRole('button', { name: 'Always load from this sender' }).click();
      await expect(page.getByText('Images from this sender load automatically.')).toBeVisible();
      await expect.poll(() => frameImages(page)).toEqual([{ proxied: true, loaded: true }]);
      await expect
        .poll(async () => (await settings(page.request)).body.mailImageAllowedSenders)
        .toContain(sender);

      // A fresh page load (no client state) loads them at once for this sender.
      await page.reload();
      await expect.poll(() => frameImages(page)).toEqual([{ proxied: true, loaded: true }]);
      await expect(page.getByText('Images from this sender load automatically.')).toBeVisible();

      // Settings lists the sender and removes it.
      await page.goto('/settings');
      const entry = page.locator('.senders li').filter({ hasText: sender });
      await expect(entry).toBeVisible();
      await entry.scrollIntoViewIfNeeded();
      await page.screenshot({ path: test.info().outputPath('5-senders.png') });
      await entry.getByRole('button', { name: `Remove ${sender}` }).click();
      await expect(entry).toBeHidden();
      await expect
        .poll(async () => (await settings(page.request)).body.mailImageAllowedSenders)
        .not.toContain(sender);
    } finally {
      // The defaults again, for the other specs of the shared account.
      const { etag } = await settings(page.request);
      await send(
        page.request,
        'PATCH',
        '/api/v1/settings',
        { mailImagePolicy: null, mailImageAllowedSenders: null },
        { 'If-Match': etag },
      );
    }
  });
});

function htmlMail(from: string, to: string, subject: string): string {
  const related = `r-${Date.now()}`;
  return [
    `From: Promo Team <${from}>`,
    `To: ${to}`,
    `Subject: ${subject}`,
    `Date: ${new Date().toUTCString()}`,
    `Message-ID: <${Date.now()}.images@cockpit.test>`,
    'MIME-Version: 1.0',
    `Content-Type: multipart/related; boundary="${related}"`,
    '',
    `--${related}`,
    'Content-Type: text/html; charset=utf-8',
    '',
    '<html><body>',
    '<p>Our new offers.</p>',
    '<img src="cid:logo@cockpit.test" alt="Logo" width="1" height="1">',
    `<img src="${REMOTE_IMAGE}" alt="Banner" width="1" height="1">`,
    '</body></html>',
    '',
    `--${related}`,
    'Content-Type: image/png; name="logo.png"',
    'Content-Transfer-Encoding: base64',
    'Content-ID: <logo@cockpit.test>',
    'Content-Disposition: inline; filename="logo.png"',
    '',
    PIXEL.toString('base64'),
    '',
    `--${related}--`,
    '',
  ].join('\r\n');
}

/** The images in the frame that point outside the message (remote or proxied). */
function frameImages(page: Page): Promise<{ proxied: boolean; loaded: boolean }[]> {
  return page.evaluate(() => {
    const doc = document.querySelector<HTMLIFrameElement>('app-mail-frame iframe')?.contentDocument;
    return [...(doc?.querySelectorAll('img') ?? [])]
      .filter((img) => /^https?:|image-proxy/.test(img.getAttribute('src') ?? ''))
      .map((img) => ({
        proxied: (img.getAttribute('src') ?? '').includes('/api/v1/mail/image-proxy'),
        loaded: img.complete && img.naturalWidth > 0,
      }));
  });
}

/** The inline (`cid:`) images in the frame, resolved by the server to the message's own parts. */
function inlineImages(page: Page): Promise<{ loaded: boolean }[]> {
  return page.evaluate(() => {
    const doc = document.querySelector<HTMLIFrameElement>('app-mail-frame iframe')?.contentDocument;
    return [...(doc?.querySelectorAll('img') ?? [])]
      .filter((img) => /\/attachments\/.+[?&]inline=true/.test(img.getAttribute('src') ?? ''))
      .map((img) => ({ loaded: img.complete && img.naturalWidth > 0 }));
  });
}

async function settings(
  request: APIRequestContext,
): Promise<{ body: { mailImageAllowedSenders: string[] }; etag: string }> {
  const response = await request.get('/api/v1/settings');
  expect(response.ok(), `GET /api/v1/settings: ${response.status()}`).toBe(true);
  return { body: await response.json(), etag: response.headers()['etag'] ?? '' };
}

async function send(
  request: APIRequestContext,
  method: 'POST' | 'PATCH',
  url: string,
  data: object,
  headers: Record<string, string> = {},
): Promise<void> {
  const cookie = (await request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN');
  expect(cookie, 'XSRF-TOKEN cookie').toBeDefined();
  const response = await request.fetch(url, {
    method,
    headers: { 'X-XSRF-TOKEN': cookie!.value, ...headers },
    data,
  });
  expect(response.ok(), `${method} ${url}: ${response.status()}`).toBe(true);
}
