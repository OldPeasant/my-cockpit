import { APIRequestContext, Page } from '@playwright/test';
import { expect, test } from '../support/fixtures';
import { GREENMAIL_SERVERS, Imap } from '../support/imap';

/** A 1×1 PNG: the image proxy's answer (the e2e stack has no internet to fetch the real one). */
const PIXEL = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
);
const REMOTE_IMAGE = 'https://images.example.test/newsletter/header.png';

/**
 * SPEC.md §4.1/§7.3 mail read view against GreenMail: an HTML mail with a script, an inline event
 * handler, a remote image and a text attachment. Opened from the list, its body renders in the
 * sandboxed frame without running anything; the remote image is not requested until "Load images",
 * then it loads through the server's image proxy. The attachment previews, and "→ Inbox task"
 * creates a task that shows in the linked items.
 */
test.describe('Mail read view', () => {
  test.setTimeout(120_000);

  test('script in mail does not run; images load only after the toggle', async ({
    loggedIn: page,
  }) => {
    const run = Date.now().toString(36);
    const address = `read-${run}@cockpit.test`;
    const password = 'e2e-not-secret';
    const subject = `Newsletter ${run}`;

    const imap = await Imap.login(address, password);
    try {
      await imap.appendRaw('INBOX', htmlMail(address, subject));
      await post(page.request, '/api/v1/mail/accounts', {
        type: 'IMAP_SMTP',
        displayName: `E2E read ${run}`,
        emailAddress: address,
        ...GREENMAIL_SERVERS,
        username: address,
        password,
      });
      await syncMail(page.request);
    } finally {
      await imap.logout();
    }

    // The proxy is stubbed so the image can load offline; every request to it is counted.
    const proxied: string[] = [];
    await page.route('**/api/v1/mail/image-proxy**', async (route) => {
      proxied.push(new URL(route.request().url()).searchParams.get('url') ?? '');
      await route.fulfill({ status: 200, contentType: 'image/png', body: PIXEL });
    });
    const dialogs: string[] = [];
    page.on('dialog', (dialog) => {
      dialogs.push(dialog.message());
      void dialog.dismiss();
    });

    await page.goto('/mail');
    const row = page.locator('li.item').filter({ hasText: subject });
    await row.locator('button.open').dblclick();
    await expect(page).toHaveURL(/\/mail\/messages\/[0-9a-f-]{36}$/);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(subject);

    const frame = page.locator('app-mail-frame iframe');
    await expect(frame).toHaveAttribute(
      'sandbox',
      'allow-same-origin allow-popups allow-popups-to-escape-sandbox',
    );
    await expect.poll(() => frameText(page)).toContain('Hello reader');

    // Nothing in the mail ran: no flag, no title change, no dialog.
    expect(await frameFlags(page)).toEqual({ ran: false, handler: false });
    expect(await page.title()).not.toContain('pwned');
    expect(dialogs).toEqual([]);

    // Remote images are blocked: none in the frame, none requested.
    await expect(page.getByText('Remote images are hidden')).toBeVisible();
    expect(await frameImages(page)).toEqual([]);
    expect(proxied).toEqual([]);

    await page.getByRole('button', { name: 'Load images', exact: true }).click();
    await expect(page.getByText('Remote images are hidden')).toBeHidden();
    await expect.poll(() => frameImages(page)).toEqual([{ proxied: true, loaded: true }]);
    expect(proxied).toEqual([REMOTE_IMAGE]);
    // Still nothing ran after the reload.
    expect(await frameFlags(page)).toEqual({ ran: false, handler: false });
    expect(dialogs).toEqual([]);

    // Attachment: listed, previewed as text.
    const attachment = page.locator('.attachments li').filter({ hasText: 'agenda.txt' });
    await expect(attachment.getByRole('link', { name: 'Download' })).toHaveAttribute(
      'href',
      /\/api\/v1\/mail\/messages\/.+\/attachments\/.+/,
    );
    await attachment.getByRole('button', { name: 'Preview' }).click();
    await expect(page.locator('.preview pre')).toContainText('1. Budget');

    // → Inbox task: a linked task appears in the panel.
    await page.getByRole('button', { name: '→ Inbox task' }).click();
    await expect(page.getByText(`Added to Inbox: ${subject}`)).toBeVisible();
    await expect(page.locator('app-linked-items')).toContainText(subject);
  });
});

function htmlMail(to: string, subject: string): string {
  const boundary = `b-${Date.now()}`;
  return [
    'From: Newsletter Team <news@example.test>',
    `To: ${to}`,
    `Subject: ${subject}`,
    `Date: ${new Date().toUTCString()}`,
    `Message-ID: <${Date.now()}.read@cockpit.test>`,
    'MIME-Version: 1.0',
    `Content-Type: multipart/mixed; boundary="${boundary}"`,
    '',
    `--${boundary}`,
    'Content-Type: text/html; charset=utf-8',
    '',
    '<html><body>',
    '<p>Hello reader, here is the news.</p>',
    '<script>window.__mailScriptRan = true; parent.document.title = "pwned"; alert("pwned");</script>',
    `<img src="${REMOTE_IMAGE}" alt="Header" width="1" height="1">`,
    '<img src="x" onerror="window.__mailHandlerRan = true; parent.document.title = \'pwned\'">',
    '</body></html>',
    '',
    `--${boundary}`,
    'Content-Type: text/plain; charset=utf-8; name="agenda.txt"',
    'Content-Disposition: attachment; filename="agenda.txt"',
    '',
    '1. Budget',
    '2. Roadmap',
    '',
    `--${boundary}--`,
    '',
  ].join('\r\n');
}

/** The text of the mail frame's document (same origin, read from the page). */
function frameText(page: Page): Promise<string> {
  return page.evaluate(
    () =>
      document.querySelector<HTMLIFrameElement>('app-mail-frame iframe')?.contentDocument?.body
        ?.textContent ?? '',
  );
}

function frameFlags(page: Page): Promise<{ ran: boolean; handler: boolean }> {
  return page.evaluate(() => {
    const win = document.querySelector<HTMLIFrameElement>('app-mail-frame iframe')
      ?.contentWindow as (Window & Record<string, unknown>) | null;
    return { ran: !!win?.['__mailScriptRan'], handler: !!win?.['__mailHandlerRan'] };
  });
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

async function post<T>(request: APIRequestContext, url: string, data: object): Promise<T> {
  const response = await request.post(url, { headers: await xsrf(request), data });
  expect(response.ok(), `POST ${url}: ${response.status()}`).toBe(true);
  return (await response.json()) as T;
}
