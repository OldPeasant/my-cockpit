import { APIRequestContext, Page } from '@playwright/test';
import { expect, test } from '../support/fixtures';
import { GREENMAIL_SERVERS, Imap } from '../support/imap';

/**
 * SPEC.md §7.3 mail list against GreenMail: a fresh mailbox with three messages and the folders
 * Archive and Projects is added as an account. In the list the test marks a message read
 * (Shift+I), archives one (selection + toolbar) and moves one (`v` + the folder picker); GreenMail
 * shows each change. A message delivered afterwards appears without a reload (SSE).
 */
test.describe('Mail list', () => {
  test.setTimeout(120_000);

  test('mark read, archive and move against GreenMail; live updates', async ({
    loggedIn: page,
  }) => {
    const run = Date.now().toString(36);
    const address = `list-${run}@cockpit.test`;
    const password = 'e2e-not-secret';
    const subjects = {
      report: `Quarterly report ${run}`,
      lunch: `Lunch on Friday ${run}`,
      invoice: `Server invoice ${run}`,
      late: `Late arrival ${run}`,
    };

    const imap = await Imap.login(address, password);
    try {
      await imap.create('Archive');
      await imap.create('Projects');
      // Oldest first: the list shows the newest on top.
      for (const subject of [subjects.invoice, subjects.lunch, subjects.report]) {
        await imap.append('INBOX', {
          from: 'Max Muster <max.muster@example.test>',
          to: address,
          subject,
          text: `Body of ${subject}`,
        });
        await new Promise((resolve) => setTimeout(resolve, 1100)); // distinct arrival seconds
      }

      const account = await post<{ id: string }>(page.request, '/api/v1/mail/accounts', {
        type: 'IMAP_SMTP',
        displayName: `E2E list ${run}`,
        emailAddress: address,
        ...GREENMAIL_SERVERS,
        username: address,
        password,
      });
      await syncMail(page.request);
      const folders = await get<{ items: { id: string; role?: string; name: string }[] }>(
        page.request,
        `/api/v1/mail/accounts/${account.id}/folders`,
      );
      const inbox = folders.items.find((folder) => folder.role === 'INBOX')!;
      expect(inbox).toBeDefined();

      // The unified Inbox has the messages; the account's tree links to its Inbox.
      await page.goto('/mail');
      await expect(page.getByRole('heading', { level: 1 })).toHaveText('All inboxes');
      await expect(row(page, subjects.report)).toBeVisible();
      const tree = page.getByRole('navigation', { name: 'Mail folders' });
      const accountTree = tree.getByRole('region', { name: `E2E list ${run}` });
      await accountTree.getByRole('link', { name: 'Inbox' }).click();
      await expect(page).toHaveURL(new RegExp(`/mail/folders/${inbox.id}$`));
      await expect(page.getByRole('heading', { level: 1 })).toHaveText('Inbox');
      await expect(subjectsShown(page)).toHaveText([
        subjects.report,
        subjects.lunch,
        subjects.invoice,
      ]);
      await expect(row(page, subjects.report)).toHaveClass(/unread/);

      // Mark read: the cursor starts on the newest message.
      await expect(row(page, subjects.report)).toHaveAttribute('aria-current', 'true');
      await page.keyboard.press('Shift+I');
      await expect(row(page, subjects.report)).not.toHaveClass(/unread/);
      await expect
        .poll(() => imap.search('INBOX', `SEEN SUBJECT "${subjects.report}"`))
        .toHaveLength(1);

      // Archive through the selection and the toolbar.
      await page
        .getByRole('checkbox', { name: `Select “${subjects.lunch}” from Max Muster` })
        .check();
      await expect(page.getByText('1 selected')).toBeVisible();
      await page.getByRole('button', { name: 'Archive' }).click();
      await expect(row(page, subjects.lunch)).toHaveCount(0);
      await expect
        .poll(() => imap.search('Archive', `SUBJECT "${subjects.lunch}"`))
        .toHaveLength(1);
      await expect.poll(() => imap.search('INBOX', `SUBJECT "${subjects.lunch}"`)).toHaveLength(0);

      // Move with the keyboard: j to the invoice, v opens the picker.
      await page.keyboard.press('j');
      await expect(row(page, subjects.invoice)).toHaveAttribute('aria-current', 'true');
      await page.keyboard.press('v');
      const dialog = page.getByRole('dialog', { name: 'Move to folder' });
      await dialog.getByRole('button', { name: 'Projects' }).click();
      await expect(dialog).toHaveCount(0);
      await expect(row(page, subjects.invoice)).toHaveCount(0);
      await expect
        .poll(() => imap.search('Projects', `SUBJECT "${subjects.invoice}"`))
        .toHaveLength(1);
      await expect(subjectsShown(page)).toHaveText([subjects.report]);

      // Live: new mail shows up through the change event of the sync, without a reload.
      await imap.append('INBOX', {
        from: 'Erika Beispiel <erika@example.test>',
        to: address,
        subject: subjects.late,
        text: 'Running late.',
      });
      await syncMail(page.request);
      await expect(subjectsShown(page)).toHaveText([subjects.late, subjects.report]);
    } finally {
      await imap.logout();
    }
  });
});

function row(page: Page, subject: string) {
  return page.locator('li.item').filter({ hasText: subject });
}

function subjectsShown(page: Page) {
  return page.locator('li.item .subject');
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

async function get<T>(request: APIRequestContext, url: string): Promise<T> {
  const response = await request.get(url);
  expect(response.ok(), `GET ${url}: ${response.status()}`).toBe(true);
  return (await response.json()) as T;
}
