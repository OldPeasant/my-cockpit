import { APIRequestContext, Page } from '@playwright/test';
import { expect, test } from '../support/fixtures';
import { GREENMAIL_SERVERS, Imap } from '../support/imap';

/**
 * SPEC.md §5.3 New view against GreenMail: a message delivered to a fresh mailbox and a quick
 * capture both show under All, only one of them under each filter (the filter survives a reload).
 * "→ Inbox task" on the message adds an Inbox task and leaves the message where it is.
 */
test.describe('New view', () => {
  test.setTimeout(120_000);

  test('capture and mail under All, filters, → Inbox task', async ({ loggedIn: page }) => {
    const run = Date.now().toString(36);
    const address = `new-${run}@cockpit.test`;
    const password = 'e2e-not-secret';
    const subject = `Offer for the roof ${run}`;
    const capture = `Call the roofer ${run}`;

    const imap = await Imap.login(address, password);
    try {
      await imap.append('INBOX', {
        from: 'Max Muster <max.muster@example.test>',
        to: address,
        subject,
        text: 'Here is the offer.',
      });
      await post(page.request, '/api/v1/mail/accounts', {
        type: 'IMAP_SMTP',
        displayName: `E2E new ${run}`,
        emailAddress: address,
        ...GREENMAIL_SERVERS,
        username: address,
        password,
      });
      await syncMail(page.request);

      // "New" sits between Dashboard and Inbox in the navigation.
      await page.goto('/');
      const nav = page.getByRole('navigation').getByRole('link');
      await expect(nav.filter({ hasText: /^\s*New\s*$/ })).toBeVisible();
      const labels = await nav.allTextContents();
      const at = labels.map((label) => label.trim()).indexOf('New');
      expect(labels.slice(at - 1, at + 2).map((label) => label.trim())).toEqual([
        'Dashboard',
        'New',
        'Inbox',
      ]);
      await nav.filter({ hasText: /^\s*New\s*$/ }).click();
      await expect(page).toHaveURL(/\/new$/);
      await expect(page.getByRole('heading', { level: 1, name: 'New' })).toBeVisible();
      await expect(row(page, subject)).toBeVisible();
      await expect(row(page, subject).locator('.source')).toHaveText('Mail');

      // A capture shows up live, above the older message.
      await page.keyboard.press('q');
      const dialog = page.getByRole('dialog', { name: 'Capture' });
      await dialog.getByRole('textbox', { name: 'What do you want to capture?' }).fill(capture);
      await dialog.getByRole('textbox', { name: 'What do you want to capture?' }).press('Enter');
      await expect(dialog).toBeHidden();
      await expect(row(page, capture)).toBeVisible();
      await expect(row(page, capture).locator('.source')).toHaveText('GTD Inbox');
      const keys = await page
        .locator('li.row')
        .evaluateAll((rows) => rows.map((r) => r.textContent ?? ''));
      expect(keys.findIndex((text) => text.includes(capture))).toBeLessThan(
        keys.findIndex((text) => text.includes(subject)),
      );

      // Each filter shows only its source, also after a reload.
      await page
        .getByRole('group', { name: 'Source' })
        .getByRole('button', { name: 'Mail' })
        .click();
      await expect(page).toHaveURL(/\/new\?source=mail$/);
      await expect(row(page, subject)).toBeVisible();
      await expect(row(page, capture)).toHaveCount(0);
      await page.reload();
      await expect(row(page, subject)).toBeVisible();
      await expect(row(page, capture)).toHaveCount(0);

      await page.keyboard.press('2');
      await expect(page).toHaveURL(/\/new\?source=inbox$/);
      await expect(row(page, capture)).toBeVisible();
      await expect(row(page, subject)).toHaveCount(0);

      await page.keyboard.press('1');
      await expect(page).toHaveURL(/\/new$/);
      await expect(row(page, capture)).toBeVisible();
      await expect(row(page, subject)).toBeVisible();

      // "→ Inbox task": a linked Inbox task appears, the message stays (here and on the server).
      await row(page, subject).getByRole('button', { name: '→ Inbox task' }).click();
      await expect(taskRow(page, subject)).toBeVisible();
      await expect(mailRow(page, subject)).toBeVisible();
      await expect
        .poll(async () => (await inboxTitles(page.request)).filter((t) => t === subject))
        .toHaveLength(1);
      expect(await imap.search('INBOX', `SUBJECT "${subject}"`)).toHaveLength(1);
    } finally {
      await imap.logout();
    }
  });
});

function row(page: Page, text: string) {
  return page.locator('li.row').filter({ hasText: text });
}

function taskRow(page: Page, text: string) {
  return page.locator('li.row[data-source="task"]').filter({ hasText: text });
}

function mailRow(page: Page, text: string) {
  return page.locator('li.row[data-source="mail"]').filter({ hasText: text });
}

async function inboxTitles(request: APIRequestContext): Promise<string[]> {
  const response = await request.get('/api/v1/tasks?list=INBOX&limit=200');
  expect(response.ok(), 'list inbox').toBe(true);
  return ((await response.json()) as { items: { title: string }[] }).items.map((t) => t.title);
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
