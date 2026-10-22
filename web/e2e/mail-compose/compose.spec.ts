import { APIRequestContext } from '@playwright/test';
import { expect, test } from '../support/fixtures';
import { GREENMAIL_SERVERS, Imap } from '../support/imap';

/**
 * SPEC.md §7.3 compose against GreenMail: a mail from a colleague arrives, is opened and answered
 * with "Reply"; the reply gets a text and a file picked from Files, is saved to Drafts and sent. The
 * colleague's mailbox then holds the reply with the file attached, and a copy is in Sent.
 */
test.describe('Mail compose', () => {
  test.setTimeout(120_000);

  test('reply with a Files attachment is received by GreenMail', async ({ loggedIn: page }) => {
    const run = Date.now().toString(36);
    const address = `compose-${run}@cockpit.test`;
    const peer = `peer-${run}@cockpit.test`;
    const password = 'e2e-not-secret';
    const subject = `Budget review ${run}`;
    const fileName = `budget-${run}.txt`;

    const imap = await Imap.login(address, password);
    try {
      await imap.create('Drafts');
      await imap.create('Sent');
      await imap.append('INBOX', {
        from: `Max Muster <${peer}>`,
        to: address,
        subject,
        text: 'Could you send me the budget numbers?',
      });
      await post(page.request, '/api/v1/mail/accounts', {
        type: 'IMAP_SMTP',
        displayName: `E2E compose ${run}`,
        emailAddress: address,
        ...GREENMAIL_SERVERS,
        username: address,
        password,
      });
      await syncMail(page.request);
    } finally {
      await imap.logout();
    }

    // The file to attach, in a folder of its own.
    const folder = await post<{ id: string }>(page.request, '/api/v1/folders', {
      name: `E2E compose ${run}`,
    });
    const upload = await page.request.post('/api/v1/files', {
      headers: await xsrf(page.request),
      multipart: {
        file: {
          name: fileName,
          mimeType: 'text/plain',
          buffer: Buffer.from('Fictitious budget: 42\n'),
        },
        folderId: folder.id,
      },
    });
    expect(upload.ok(), `POST /api/v1/files: ${upload.status()}`).toBe(true);

    await page.goto('/mail');
    await page.locator('li.item').filter({ hasText: subject }).locator('button.open').dblclick();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(subject);

    await page.getByRole('link', { name: 'Reply', exact: true }).click();
    await expect(page).toHaveURL(/\/mail\/compose\?draft=[0-9a-f-]{36}$/);
    await expect(page.getByLabel('Subject')).toHaveValue(`Re: ${subject}`);
    await expect(page.locator('mat-chip-row')).toContainText('Max Muster');
    // The quote of the original is in the body.
    const body = page.getByRole('textbox', { name: 'Message' });
    await expect(body).toContainText('Could you send me the budget numbers?');

    await body.click();
    await page.keyboard.press('Control+Home');
    await page.keyboard.type(`Here are the numbers ${run}.`);

    await page.getByRole('button', { name: 'From Files' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('button', { name: `E2E compose ${run}/` }).click();
    await dialog.getByRole('button', { name: new RegExp(fileName) }).click();
    await expect(page.locator('.attachments')).toContainText(fileName);
    await expect(page.getByRole('status').filter({ hasText: 'Draft saved' })).toBeVisible();

    await page.getByRole('button', { name: 'Send', exact: true }).click();
    await expect(page.getByText('Message sent')).toBeVisible();
    await expect(page).toHaveURL(/\/mail$/);

    // The colleague got the reply, with the text and the file.
    const inbox = await Imap.login(peer, password);
    try {
      let found: number[] = [];
      await expect
        .poll(async () => (found = await inbox.search('INBOX', `SUBJECT "Re: ${subject}"`)), {
          timeout: 30_000,
        })
        .toHaveLength(1);
      const raw = await inbox.fetch('INBOX', found[0]);
      expect(raw).toContain(`Here are the numbers ${run}.`);
      expect(raw).toMatch(new RegExp(`filename="?${fileName}`));
      expect(raw).toMatch(/In-Reply-To: <[^>]+@cockpit\.test>/);
    } finally {
      await inbox.logout();
    }

    // A copy is in Sent, the draft is gone.
    const own = await Imap.login(address, password);
    try {
      expect(await own.search('Sent', `SUBJECT "Re: ${subject}"`)).toHaveLength(1);
      expect(await own.search('Drafts', `SUBJECT "Re: ${subject}"`)).toEqual([]);
    } finally {
      await own.logout();
    }
  });
});

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
