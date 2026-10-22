import { APIRequestContext, Locator, Page } from '@playwright/test';
import { Account, loginViaApi, resetStack, setupAccount } from '../support/api';
import { expect, test } from '../support/fixtures';
import { Imap } from '../support/imap';
import { sendSmtp } from './smtp';

/**
 * SPEC.md §13.2, Phase 5 demo script (mail), steps 1–6 against GreenMail, driven through the web app. Step 7 (the real
 * Gmail and Microsoft 365 accounts) is a human task.
 *
 * Runs in the `phase-demos` project (playwright.config.ts): it resets the stack and sets up its own account, and leaves
 * an empty stack behind. GreenMail keeps its mailboxes across runs against a kept stack, so every address and subject
 * carries the run's id. The steps build on each other and run in order.
 */
test.describe.configure({ mode: 'serial' });

const run = Date.now().toString(36);
const ADDRESS = `kim-${run}@cockpit.test`;
const PEER = `max-${run}@cockpit.test`;
const PASSWORD = 'e2e-not-secret';
const ACCOUNT_NAME = 'Kim Example';
const SUBJECTS = {
  report: `Quarterly report ${run}`,
  lunch: `Lunch on Friday ${run}`,
  invoice: `Server invoice ${run}`,
  offer: `Special offer ${run}`,
  minutes: `Old minutes ${run}`,
  newsletter: `Newsletter ${run}`,
  budget: `Budget numbers ${run}`,
};
const FILE_NAME = `budget-${run}.txt`;

/** A 1×1 PNG: the image proxy's answer (the e2e stack has no internet to fetch the real one). */
const PIXEL = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
);
const REMOTE_IMAGE = 'https://images.example.test/newsletter/header.png';

test.describe('Phase 5 demo', () => {
  test.setTimeout(120_000);
  let page: Page;
  let account: Account;
  let imap: Imap;

  test.beforeAll(async ({ browser, request }) => {
    await resetStack(request);
    account = await setupAccount(request, {
      username: 'kim.example',
      password: 'phase5 demo passphrase',
    });
    const context = await browser.newContext();
    await loginViaApi(context.request, account);
    page = await context.newPage();

    // The mailbox on GreenMail: the usual folders, five plain mails and an HTML newsletter in the Inbox.
    imap = await Imap.login(ADDRESS, PASSWORD);
    for (const folder of ['Archive', 'Drafts', 'Sent', 'Trash', 'Junk', 'Projects']) {
      await imap.create(folder);
    }
    for (const subject of [
      SUBJECTS.minutes,
      SUBJECTS.offer,
      SUBJECTS.invoice,
      SUBJECTS.lunch,
      SUBJECTS.report,
    ]) {
      await imap.append('INBOX', {
        from: `Max Muster <${PEER}>`,
        to: ADDRESS,
        subject,
        text: `Body of ${subject}`,
      });
    }
    await imap.appendRaw('INBOX', htmlMail(ADDRESS, SUBJECTS.newsletter));
  });

  test.afterAll(async ({ request }) => {
    await imap?.logout();
    await page?.context().close();
    // The next specs set up their own account on an empty stack.
    await resetStack(request);
  });

  test('1. add an IMAP/SMTP account: the folders appear and the unified inbox lists the messages', async () => {
    await page.goto('/settings/mail');
    await page.getByRole('radio', { name: 'IMAP/SMTP' }).check();
    const form = page.locator('form.mail-form');
    await form.getByLabel('Email address').fill(ADDRESS);
    await form.getByLabel('Your name (optional)').fill(ACCOUNT_NAME);
    await form.getByLabel('User name').fill(ADDRESS);
    await form.getByLabel('Password or app password').fill(PASSWORD);
    await server(page, form.getByRole('group', { name: 'Incoming mail (IMAP)' }), 3143);
    await server(page, form.getByRole('group', { name: 'Outgoing mail (SMTP)' }), 3025);
    await form.getByRole('button', { name: 'Add and test' }).click();
    await expect(page.getByRole('status').filter({ hasText: `${ADDRESS} added` })).toHaveText(
      `${ADDRESS} added and connected.`,
      { timeout: 30_000 },
    );
    await expect(page.getByRole('listitem', { name: ADDRESS })).toContainText(ACCOUNT_NAME);

    // The folder tree has the account's folders, by role where the name tells it.
    await page.goto('/mail');
    const tree = accountTree(page);
    for (const folder of ['Inbox', 'Archive', 'Drafts', 'Sent', 'Trash', 'Spam', 'Projects']) {
      await expect(tree.getByRole('link', { name: folder, exact: true }), folder).toBeVisible();
    }
    // The unified inbox lists the messages: the first sync runs right after adding the account.
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('All inboxes');
    for (const subject of Object.values(SUBJECTS).filter((s) => s !== SUBJECTS.budget)) {
      await expect(row(page, subject), subject).toBeVisible({ timeout: 30_000 });
    }
  });

  test('2. a message arriving via SMTP shows up without reload', async () => {
    await expect(row(page, SUBJECTS.report)).toBeVisible();
    await page.evaluate(
      () => ((window as unknown as Record<string, unknown>)['__noReload'] = true),
    );

    await sendSmtp(
      PEER,
      ADDRESS,
      plainMail({
        from: `Max Muster <${PEER}>`,
        to: ADDRESS,
        subject: SUBJECTS.budget,
        text: 'Could you send me the budget numbers?',
      }),
    );
    // IDLE on the Inbox reports it, the change event reaches the open list over SSE.
    await expect(row(page, SUBJECTS.budget)).toBeVisible({ timeout: 30_000 });
    expect(
      await page.evaluate(() => (window as unknown as Record<string, unknown>)['__noReload']),
      'the page was not reloaded',
    ).toBe(true);
  });

  test('3. read/unread, flag, move, archive, delete and spam reach GreenMail', async () => {
    await accountTree(page).getByRole('link', { name: 'Inbox', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Inbox');
    await expect(row(page, SUBJECTS.report)).toHaveClass(/unread/);

    await act(page, SUBJECTS.report, 'Mark read');
    await expect(row(page, SUBJECTS.report)).not.toHaveClass(/unread/);
    await expectImap(imap, 'INBOX', `SEEN SUBJECT "${SUBJECTS.report}"`);

    await act(page, SUBJECTS.report, 'Mark unread');
    await expect(row(page, SUBJECTS.report)).toHaveClass(/unread/);
    await expectImap(imap, 'INBOX', `UNSEEN SUBJECT "${SUBJECTS.report}"`);

    await act(page, SUBJECTS.report, 'Star');
    await expect(row(page, SUBJECTS.report).getByLabel('Starred')).toBeVisible();
    await expectImap(imap, 'INBOX', `FLAGGED SUBJECT "${SUBJECTS.report}"`);

    await act(page, SUBJECTS.invoice, 'Move');
    const dialog = page.getByRole('dialog', { name: 'Move to folder' });
    await dialog.getByRole('button', { name: 'Projects' }).click();
    await expect(dialog).toHaveCount(0);
    await expect(row(page, SUBJECTS.invoice)).toHaveCount(0);
    await expectImap(imap, 'Projects', `SUBJECT "${SUBJECTS.invoice}"`);
    await expectImap(imap, 'INBOX', `SUBJECT "${SUBJECTS.invoice}"`, 0);

    for (const [subject, action, folder] of [
      [SUBJECTS.lunch, 'Archive', 'Archive'],
      [SUBJECTS.minutes, 'Delete', 'Trash'],
      [SUBJECTS.offer, 'Spam', 'Junk'],
    ] as const) {
      await act(page, subject, action);
      await expect(row(page, subject), action).toHaveCount(0);
      await expectImap(imap, folder, `SUBJECT "${subject}"`);
      await expectImap(imap, 'INBOX', `SUBJECT "${subject}"`, 0);
    }

    // What is left in the Inbox, in the cockpit and on GreenMail.
    await expect(page.locator('li.item .subject')).toHaveText([
      SUBJECTS.budget,
      SUBJECTS.newsletter,
      SUBJECTS.report,
    ]);
    expect(await imap.search('INBOX', 'ALL')).toHaveLength(3);
  });

  test('4. an HTML mail with a script and a remote image renders sanitized, images blocked until "load images"', async () => {
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

    await open(page, SUBJECTS.newsletter);
    await expect(page.locator('app-mail-frame iframe')).toHaveAttribute(
      'sandbox',
      'allow-same-origin allow-popups allow-popups-to-escape-sandbox',
    );
    await expect.poll(() => frameText(page)).toContain('Hello reader');
    expect(await frameFlags(page)).toEqual({ ran: false, handler: false });
    expect(await page.title()).not.toContain('pwned');

    await expect(page.getByText('Remote images are hidden')).toBeVisible();
    expect(await frameImages(page)).toEqual([]);
    expect(proxied).toEqual([]);

    await page.getByRole('button', { name: 'Load images', exact: true }).click();
    await expect(page.getByText('Remote images are hidden')).toBeHidden();
    await expect.poll(() => frameImages(page)).toEqual([{ proxied: true, loaded: true }]);
    expect(proxied).toEqual([REMOTE_IMAGE]);
    expect(await frameFlags(page)).toEqual({ ran: false, handler: false });
    expect(dialogs).toEqual([]);
    await page.unroute('**/api/v1/mail/image-proxy**');
  });

  test('5. reply with an attachment from Files: draft in Drafts, sent mail in Sent, received by GreenMail', async () => {
    const api = apiOf(page.request);
    const folder = await api.post<{ id: string }>('/api/v1/folders', { name: `Finance ${run}` });
    await api.upload(folder.id, FILE_NAME, 'Fictitious budget: 42\n');

    await page.goto('/mail');
    await open(page, SUBJECTS.budget);
    await page.getByRole('link', { name: 'Reply', exact: true }).click();
    await expect(page).toHaveURL(/\/mail\/compose\?draft=[0-9a-f-]{36}$/);
    await expect(page.getByLabel('Subject')).toHaveValue(`Re: ${SUBJECTS.budget}`);
    await expect(page.locator('mat-chip-row')).toContainText('Max Muster');
    const body = page.getByRole('textbox', { name: 'Message' });
    await expect(body).toContainText('Could you send me the budget numbers?');
    await body.click();
    await page.keyboard.press('Control+Home');
    await page.keyboard.type(`Here are the numbers ${run}.`);

    await page.getByRole('button', { name: 'From Files' }).click();
    const picker = page.getByRole('dialog');
    await picker.getByRole('button', { name: `Finance ${run}/` }).click();
    await picker.getByRole('button', { name: new RegExp(FILE_NAME) }).click();
    await expect(page.locator('.attachments')).toContainText(FILE_NAME);

    // Autosave: the draft, with the file, is in Drafts on GreenMail.
    await expect(page.getByRole('status').filter({ hasText: 'Draft saved' })).toBeVisible();
    let drafts: number[] = [];
    await expect
      .poll(
        async () => (drafts = await imap.search('Drafts', `SUBJECT "Re: ${SUBJECTS.budget}"`)),
        {
          timeout: 30_000,
        },
      )
      .toHaveLength(1);
    expect(await imap.fetch('Drafts', drafts[0])).toMatch(new RegExp(`filename="?${FILE_NAME}`));

    await page.getByRole('button', { name: 'Send', exact: true }).click();
    await expect(page.getByText('Message sent')).toBeVisible();

    // The colleague received it, with the text and the file.
    const peer = await Imap.login(PEER, PASSWORD);
    try {
      let found: number[] = [];
      await expect
        .poll(
          async () => (found = await peer.search('INBOX', `SUBJECT "Re: ${SUBJECTS.budget}"`)),
          { timeout: 30_000 },
        )
        .toHaveLength(1);
      const raw = await peer.fetch('INBOX', found[0]);
      expect(raw).toContain(`Here are the numbers ${run}.`);
      expect(raw).toMatch(new RegExp(`filename="?${FILE_NAME}`));
    } finally {
      await peer.logout();
    }
    // The sent mail is in Sent, the draft is gone.
    expect(await imap.search('Sent', `SUBJECT "Re: ${SUBJECTS.budget}"`)).toHaveLength(1);
    expect(await imap.search('Drafts', `SUBJECT "Re: ${SUBJECTS.budget}"`)).toEqual([]);
  });

  test('6. "→ Inbox task" creates a linked task; "→ Waiting For" from a sent mail names the recipient', async () => {
    await page.goto('/mail');
    await open(page, SUBJECTS.report);
    await page.getByRole('button', { name: '→ Inbox task' }).click();
    await expect(page.getByText(`Added to Inbox: ${SUBJECTS.report}`)).toBeVisible();
    const taskLink = page
      .locator('app-linked-items')
      .getByRole('link', { name: SUBJECTS.report, exact: true });
    await expect(taskLink).toHaveAttribute('href', /^\/tasks\//);
    // The task is in the Inbox and links back to the mail.
    await page.goto('/inbox');
    await expect(page.getByText(SUBJECTS.report, { exact: true })).toBeVisible();
    await page.goBack();
    await taskLink.click();
    await expect(page).toHaveURL(/\/tasks\//);
    await expect(
      page.locator('app-linked-items').locator('a[href^="/mail/messages/"]', {
        hasText: SUBJECTS.report,
      }),
    ).toHaveCount(1);

    // The sent reply of step 5, from the Sent folder. The cockpit reads the copy in Sent with its next sync of the
    // folder (every 5 minutes); the e2e hook runs it now.
    await syncMail(page.request);
    await page.goto('/mail');
    await accountTree(page).getByRole('link', { name: 'Sent', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Sent');
    await open(page, `Re: ${SUBJECTS.budget}`);
    await page.getByRole('button', { name: '→ Waiting For' }).click();
    await expect(page.getByText(`Added to Waiting For: Re: ${SUBJECTS.budget}`)).toBeVisible();

    await page.goto('/waiting');
    const entry = page.locator('.entry').filter({ hasText: `Re: ${SUBJECTS.budget}` });
    await expect(entry).toHaveCount(1);
    await expect(entry).toContainText('for Max Muster');
  });
});

function accountTree(page: Page): Locator {
  return page
    .getByRole('navigation', { name: 'Mail folders' })
    .getByRole('region', { name: ACCOUNT_NAME });
}

function row(page: Page, subject: string): Locator {
  return page.locator('li.item').filter({ hasText: subject });
}

/** Opens a message of the shown list in the read view. */
async function open(page: Page, subject: string): Promise<void> {
  await row(page, subject).locator('button.open').dblclick();
  await expect(page).toHaveURL(/\/mail\/messages\/[0-9a-f-]{36}$/);
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(subject);
}

/** Selects only the message [subject] and runs a toolbar action on it. */
async function act(page: Page, subject: string, action: string): Promise<void> {
  const selectAll = page.getByRole('checkbox', { name: 'Select all' });
  if (await selectAll.isChecked()) await selectAll.uncheck();
  for (const box of await page.locator('li.item').getByRole('checkbox').all()) {
    if (await box.isChecked()) await box.uncheck();
  }
  await page.getByRole('checkbox', { name: `Select “${subject}” from Max Muster` }).check();
  await expect(page.getByText('1 selected')).toBeVisible();
  await page
    .getByRole('toolbar', { name: 'Mail actions' })
    .getByRole('button', { name: action, exact: true })
    .click();
}

/** Waits until GreenMail's [folder] has [count] messages matching [criteria]. */
async function expectImap(imap: Imap, folder: string, criteria: string, count = 1): Promise<void> {
  await expect
    .poll(async () => (await imap.search(folder, criteria)).length, {
      message: `${folder}: ${criteria}`,
    })
    .toBe(count);
}

/** Fills an IMAP or SMTP server fieldset with GreenMail as the server container sees it (no TLS). */
async function server(page: Page, fieldset: Locator, port: number): Promise<void> {
  await fieldset.getByLabel('Server').fill('greenmail');
  await fieldset.getByRole('combobox', { name: 'Security' }).click();
  await page.getByRole('option', { name: 'None (unencrypted)' }).click();
  await fieldset.getByLabel('Port').fill(String(port));
}

function plainMail(mail: { from: string; to: string; subject: string; text: string }): string {
  return [
    `From: ${mail.from}`,
    `To: ${mail.to}`,
    `Subject: ${mail.subject}`,
    `Date: ${new Date().toUTCString()}`,
    `Message-ID: <${Date.now()}.smtp@cockpit.test>`,
    'MIME-Version: 1.0',
    'Content-Type: text/plain; charset=utf-8',
    '',
    mail.text,
    '',
  ].join('\r\n');
}

function htmlMail(to: string, subject: string): string {
  return [
    'From: Newsletter Team <news@example.test>',
    `To: ${to}`,
    `Subject: ${subject}`,
    `Date: ${new Date().toUTCString()}`,
    `Message-ID: <${Date.now()}.newsletter@cockpit.test>`,
    'MIME-Version: 1.0',
    'Content-Type: text/html; charset=utf-8',
    '',
    '<html><body>',
    '<p>Hello reader, here is the news.</p>',
    '<script>window.__mailScriptRan = true; parent.document.title = "pwned"; alert("pwned");</script>',
    `<img src="${REMOTE_IMAGE}" alt="Header" width="1" height="1">`,
    '<img src="x" onerror="window.__mailHandlerRan = true; parent.document.title = \'pwned\'">',
    '</body></html>',
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

/** Reads the folder list and new headers of every account now (e2e hook, `mail.testsupport`). */
async function syncMail(request: APIRequestContext): Promise<void> {
  const response = await request.post('/api/test/mail/sync');
  expect(response.ok(), `POST /api/test/mail/sync: ${response.status()}`).toBe(true);
}

function apiOf(request: APIRequestContext) {
  // Read the cookie before every change: the server may send a new token with any answer.
  const xsrf = async (): Promise<Record<string, string>> => {
    const cookie = (await request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN');
    expect(cookie, 'XSRF-TOKEN cookie').toBeDefined();
    return { 'X-XSRF-TOKEN': cookie!.value };
  };
  return {
    async post<T>(url: string, data: object): Promise<T> {
      const response = await request.post(url, { headers: await xsrf(), data });
      expect(response.ok(), `POST ${url}: ${response.status()}`).toBe(true);
      return (await response.json()) as T;
    },
    async upload(folderId: string, name: string, text: string): Promise<void> {
      const response = await request.post('/api/v1/files', {
        headers: await xsrf(),
        multipart: {
          file: { name, mimeType: 'text/plain', buffer: Buffer.from(text) },
          folderId,
        },
      });
      expect(response.ok(), `POST /api/v1/files: ${response.status()}`).toBe(true);
    },
  };
}
