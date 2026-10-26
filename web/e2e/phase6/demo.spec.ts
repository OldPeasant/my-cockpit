import { readFile } from 'node:fs/promises';
import { APIRequestContext, Browser, BrowserContext, Page } from '@playwright/test';
import { Account, loginViaApi, resetStack, setClock, setupAccount, totp } from '../support/api';
import { expect, test } from '../support/fixtures';
import { GREENMAIL_SERVERS, Imap } from '../support/imap';
import { Zip } from '../phase2/zip';

/**
 * SPEC.md §13.2, Phase 6 demo script (contacts, bookmarks, journal & habits, weekly review, dashboard), steps 1–5,
 * driven through the web app. Step 2 shares the URL through the API the way the Android share sheet does
 * (`POST /api/v1/bookmarks` with `readLater`); the Android side itself is a human task (h-android-device).
 *
 * Runs in the `phase-demos` project (playwright.config.ts): it resets the stack, sets up its own account and leaves an
 * empty stack with the real clock behind. The steps build on each other and run in order.
 *
 * The e2e stack has no internet, so the read-later URL of step 2 is the server's own page (`http://localhost:8080/`,
 * the SPA: title "My Cockpit", icon `favicon.svg`); the e2e profile lets the page fetcher reach private addresses
 * (`cockpit.bookmarks.fetch.allow-private-networks`). Step 3 moves the server clock (and the page's) forward to the
 * Monday to Wednesday of next week for the three habit checks; steps 4 and 5 run on that clock. Step 5 restores the
 * backup into the same server (the fresh-install server is phase 2's, single-use): the phase 6 data is changed after
 * the backup, so only a working restore brings it back.
 */
test.describe.configure({ mode: 'serial' });

const run = Date.now().toString(36);
const ZONE = 'Europe/Zurich';
const DAY_MS = 24 * 60 * 60 * 1000;
const OWNER = { username: 'robin.example', password: 'phase6 demo passphrase' } as const;
/** The word the restore form wants typed (Settings → Backup & restore). */
const CONFIRM_WORD = 'RESTORE';

/** The contact created in the form; the mail, the event and the Waiting For item are with them. */
const ALEX = { given: 'Alex', family: `Brightwater${run}`, email: `alex-${run}@cockpit.test` };
const ALEX_NAME = `${ALEX.given} ${ALEX.family}`;
/** The contacts of the imported vCard file; Mira has a birthday. */
const MIRA = { name: `Mira Holloway${run}`, birthday: '1985-12-03' };
const JONAS = { name: `Jonas Feldmann${run}` };
const MAIL_ADDRESS = `robin-${run}@cockpit.test`;
const MAIL_SUBJECT = `Garden party plans ${run}`;
const EVENT_TITLE = `Coffee with Alex ${run}`;
const WAITING_TITLE = `Borrowed ladder ${run}`;
/** The read-later URL: the server's own page, as the server container reaches it. */
const SHARED_URL = `http://localhost:8080/?shared=${run}`;
const JOURNAL_TEXT = `Planted the tulip bulbs ${run}.`;
const HABIT = `Run 5 km ${run}`;
const INBOX_ITEM = `Order garden chairs ${run}`;
const REFLECTION = `A good week in the garden (${run}).`;

/** A 2×2 PNG (fictitious pixels): the journal entry's image. */
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFElEQVR4nGP4z8DAwPAfhP7/ZwAAHu8E/Gj7YDYAAAAASUVORK5CYII=',
  'base64',
);

/** The server clock runs this far ahead of real time (step 3 moves it). */
let clockOffsetMs = 0;
const serverNow = () => new Date(Date.now() + clockOffsetMs);

test.describe('Phase 6 demo', () => {
  test.setTimeout(120_000);
  let page: Page;
  let account: Account;
  /** Filled by the steps. */
  let alexId = '';
  let bookmarkId = '';
  let habitId = '';
  let backupPath = '';
  let before: Snapshot;

  test.beforeAll(async ({ browser, request }) => {
    await resetStack(request);
    account = await setupAccount(request, OWNER);
    const context = await browser.newContext();
    await loginViaApi(context.request, account);
    page = await context.newPage();
  });

  test.afterAll(async ({ request }) => {
    await page?.context().close();
    // The next specs set up their own account on an empty stack with the real clock.
    await resetStack(request);
  });

  test('1. contacts created and imported, the birthdays calendar; the contact page shows mails, events and Waiting For', async () => {
    // A contact through the form.
    await page.goto('/contacts');
    await expect(page.getByRole('heading', { name: 'Contacts', level: 1 })).toBeVisible();
    await page.getByRole('link', { name: 'New contact' }).click();
    await expect(page.getByRole('heading', { name: 'New contact', level: 1 })).toBeVisible();
    await page.getByLabel('First name').fill(ALEX.given);
    await page.getByLabel('Last name').fill(ALEX.family);
    await page.getByLabel('Organization').fill('Example Gardens');
    await page.getByRole('button', { name: 'Add email' }).click();
    await page.getByRole('textbox', { name: 'Email address' }).fill(ALEX.email);
    await page.getByRole('button', { name: 'Create contact' }).click();
    await expect(page).toHaveURL(/\/contacts\/[0-9a-f-]{36}$/);
    await expect(page.getByRole('heading', { name: ALEX_NAME, level: 1 })).toBeVisible();
    alexId = new URL(page.url()).pathname.split('/').pop()!;

    // Two more from a vCard file.
    await page.goto('/contacts');
    const chooser = page.waitForEvent('filechooser');
    await page.getByRole('button', { name: 'Import vCard' }).click();
    await (
      await chooser
    ).setFiles({
      name: `friends-${run}.vcf`,
      mimeType: 'text/vcard',
      buffer: Buffer.from(vcards(), 'utf-8'),
    });
    await expect(page.getByRole('status').filter({ hasText: 'imported' })).toContainText(
      `friends-${run}.vcf: 2 contacts imported.`,
    );
    await page.getByRole('searchbox', { name: 'Search contacts' }).fill(run);
    await expect(page.locator('.contact .row')).toHaveCount(3);
    for (const name of [ALEX_NAME, MIRA.name, JONAS.name]) {
      await expect(page.locator('.contact', { hasText: name }), name).toHaveCount(1);
    }

    // Mira's birthday is a yearly all-day event of the Birthdays calendar.
    const year = Number(today(serverNow()).slice(0, 4)) + 1;
    await page.goto(`/calendar?view=month&date=${year}-12-01`);
    const birthday = page
      .locator(`.app-calendar [data-date="${year}-12-03"]`)
      .locator('.app-event', { hasText: MIRA.name });
    await expect(birthday).toHaveCount(1);
    await expect(birthday).toContainText(`${MIRA.name} (${year - 1985})`);
    // It is the Birthdays calendar's (listed in the panel), read-only and leads back to the contact.
    await expect(
      page.locator('.calendar-list .calendar-name').getByText('Birthdays', { exact: true }),
    ).toBeVisible();
    await birthday.click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.locator('.virtual-title')).toContainText(`${MIRA.name} (${year - 1985})`);
    await expect(dialog.getByText('Birthdays', { exact: true })).toBeVisible();
    await expect(dialog.getByText('This date comes from a contact')).toBeVisible();
    await dialog.getByRole('link', { name: 'Open contact' }).click();
    await expect(dialog).toBeHidden();
    await expect(page).toHaveURL(/\/contacts\/[0-9a-f-]{36}$/);
    await expect(page.getByRole('heading', { name: MIRA.name, level: 1 })).toBeVisible();

    // Alex in a mail, an upcoming event and a Waiting For item.
    const api = apiOf(page.request);
    const imap = await Imap.login(MAIL_ADDRESS, 'e2e-not-secret');
    try {
      await imap.append('INBOX', {
        from: `${ALEX_NAME} <${ALEX.email}>`,
        to: MAIL_ADDRESS,
        subject: MAIL_SUBJECT,
        text: 'Shall we have the garden party on Saturday?',
      });
    } finally {
      await imap.logout();
    }
    await api.post('/api/v1/mail/accounts', {
      type: 'IMAP_SMTP',
      displayName: 'Robin Example',
      emailAddress: MAIL_ADDRESS,
      ...GREENMAIL_SERVERS,
      username: MAIL_ADDRESS,
      password: 'e2e-not-secret',
    });
    const synced = await page.request.post('/api/test/mail/sync');
    expect(synced.ok(), `POST /api/test/mail/sync: ${synced.status()}`).toBe(true);
    const calendar = await api.post<{ id: string }>('/api/v1/calendar/calendars', {
      name: 'Personal',
      color: '#43a047',
    });
    const start = new Date(serverNow().getTime() + DAY_MS);
    await api.post('/api/v1/calendar/events', {
      calendarId: calendar.id,
      title: EVENT_TITLE,
      start: start.toISOString(),
      end: new Date(start.getTime() + 60 * 60 * 1000).toISOString(),
      timeZone: ZONE,
      attendees: [{ email: ALEX.email, name: ALEX_NAME }],
    });
    await api.post('/api/v1/tasks', {
      title: WAITING_TITLE,
      list: 'WAITING',
      waitingForText: ALEX_NAME,
      waitingForContactId: alexId,
      waitingSince: today(serverNow()),
    });

    await page.goto('/contacts');
    await page.getByRole('searchbox', { name: 'Search contacts' }).fill(ALEX.family);
    await page.locator('.contact', { hasText: ALEX_NAME }).getByRole('link').click();
    await expect(page.getByRole('heading', { name: ALEX_NAME, level: 1 })).toBeVisible();
    await expect(page.locator('dd.email')).toContainText(ALEX.email);
    const waiting = page.locator('section.waiting');
    await expect(waiting.getByRole('link', { name: WAITING_TITLE })).toHaveAttribute(
      'href',
      /^\/tasks\/[0-9a-f-]{36}$/,
    );
    await expect(
      page.locator('section.events').getByRole('link', { name: EVENT_TITLE }),
    ).toBeVisible();
    const mail = page.locator('section.mails li', { hasText: MAIL_SUBJECT });
    await expect(mail).toBeVisible({ timeout: 30_000 });
    await expect(mail.locator('.direction')).toHaveText('From');
    await mail.getByRole('link', { name: MAIL_SUBJECT }).click();
    await expect(page).toHaveURL(/\/mail\/messages\/[0-9a-f-]{36}$/);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(MAIL_SUBJECT);
  });

  test('2. a URL shared to Read later (API, as from Android): title and favicon fetched, in the queue on the web', async () => {
    // What the Android share sheet sends for "Read later" (CaptureUploader): the URL and readLater, no title.
    const created = await apiOf(page.request).post<Bookmark>('/api/v1/bookmarks', {
      url: SHARED_URL,
      readLater: true,
    });
    bookmarkId = created.id;
    expect(created).toMatchObject({ title: null, readLater: true });

    // The server fetched the page in the background: its title and icon.
    let bookmark = created;
    await expect
      .poll(
        async () => {
          bookmark = await get<Bookmark>(page.request, `/api/v1/bookmarks/${bookmarkId}`);
          return bookmark.metadataStatus;
        },
        { timeout: 30_000 },
      )
      .toBe('FETCHED');
    expect(bookmark.title).toBe('My Cockpit');
    expect(bookmark.favicon).toBe('http://localhost:8080/favicon.svg');

    // The web's Read later queue lists it with the fetched title.
    await page.goto('/bookmarks');
    await expect(page.getByRole('heading', { name: 'Bookmarks', level: 1 })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Read later' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    const item = page.locator(`li.bookmark[data-id="${bookmarkId}"]`);
    await expect(item.locator('a.title')).toHaveText('My Cockpit');
    await expect(item.locator('a.title')).toHaveAttribute('href', SHARED_URL);
    await expect(item.locator('.pending')).toHaveCount(0);
  });

  test("3. today's journal entry with an image; a 3× per week habit shows its streak after 3 checks", async () => {
    // Journal: today's entry, written in the editor, with a pasted image.
    await page.goto('/journal');
    await expect(page.getByRole('status').filter({ hasText: 'Not written yet' })).toBeVisible();
    const editor = page.locator('app-markdown-editor .cm-content');
    await editor.click();
    await page.keyboard.type(JOURNAL_TEXT);
    await editor.evaluate(
      (element, bytes) => {
        const data = new DataTransfer();
        data.items.add(new File([new Uint8Array(bytes)], 'tulips.png', { type: 'image/png' }));
        element.dispatchEvent(
          new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }),
        );
      },
      [...PNG],
    );
    await expect(editor).toContainText(/!\[[^\]]*\]\(cockpit-file:[0-9a-f-]{36}\)/);
    await expect(page.getByRole('status').filter({ hasText: /^Saved$/ })).toBeVisible();
    const day = today(serverNow());
    const entry = await get<{ bodyMd: string }>(page.request, `/api/v1/journal/entries/${day}`);
    expect(entry.bodyMd).toContain(JOURNAL_TEXT);
    expect(entry.bodyMd).toMatch(/!\[[^\]]*\]\(cockpit-file:[0-9a-f-]{36}\)/);
    await page.getByRole('button', { name: 'Preview' }).click();
    const image = page.locator('app-note-markdown-view img[src*="/api/v1/files/"]');
    await expect(image).toBeVisible();
    await expect.poll(() => image.evaluate((img: HTMLImageElement) => img.naturalWidth)).toBe(2);

    // Habit: 3× per week, created today.
    await page.goto('/habits');
    await page.getByRole('button', { name: 'New habit' }).click();
    const form = page.locator('app-habit-form');
    await form.getByLabel('Name').fill(HABIT);
    await form.getByRole('button', { name: 'Times a week' }).click();
    await form.locator('input.times-per-week').fill('3');
    await form.getByRole('button', { name: 'Add habit' }).click();
    const habit = page.locator('li.habit', { hasText: HABIT });
    await expect(habit.locator('.schedule')).toContainText('3× a week');
    await expect(habit.locator('.streak dd')).toHaveText('0 weeks');
    habitId = (await habit.getAttribute('data-id'))!;

    // Checked on Monday, Tuesday and Wednesday of next week (server and page clock moved there): the week's quota is
    // reached after the third check, the streak is one week.
    const monday = nextMonday(addDays(today(serverNow()), 1));
    for (const [i, date] of [monday, addDays(monday, 1), addDays(monday, 2)].entries()) {
      clockOffsetMs = Date.parse(`${date}T08:00:00Z`) - Date.now();
      await setClock(page.request, serverNow());
      await page.clock.setSystemTime(serverNow());
      await page.reload();
      const row = page.locator('li.habit', { hasText: HABIT });
      await expect(row.locator('.streak dd')).toHaveText('0 weeks');
      await row.getByRole('button', { name: `Check “${HABIT}” for today` }).click();
      await expect(row.getByRole('button', { name: `Uncheck “${HABIT}” for today` })).toBeVisible();
      await expect(row.locator('.streak dd')).toHaveText(i < 2 ? '0 weeks' : '1 week');
    }
    const checks = await get<{ items: { date: string; count: number }[] }>(
      page.request,
      `/api/v1/habits/${habitId}/checks?from=${monday}&to=${addDays(monday, 2)}`,
    );
    expect(checks.items.map((c) => [c.date, c.count])).toEqual([
      [monday, 1],
      [addDays(monday, 1), 1],
      [addDays(monday, 2), 1],
    ]);
    // After a reload too.
    await page.reload();
    await expect(page.locator('li.habit', { hasText: HABIT }).locator('.streak dd')).toHaveText(
      '1 week',
    );
  });

  test('4. a weekly review completed in the wizard; the dashboard shows "Last weekly review: today"', async () => {
    await apiOf(page.request).post('/api/v1/tasks', { title: INBOX_ITEM });

    // Before: no review yet.
    await page.goto('/');
    const widget = page.locator('section[data-widget="weeklyReview"]');
    await expect(widget.getByRole('heading', { name: 'Last weekly review' })).toBeVisible();
    await expect(widget.getByText('No weekly review done yet.')).toBeVisible();

    await page.goto('/review');
    await expect(page.getByRole('heading', { name: 'Weekly review' })).toBeVisible();
    await page.getByRole('button', { name: 'Start review' }).click();

    // 1. Get clear: the inbox processed inside the review.
    await expect(page.getByText('Step 1 of 8')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Get clear' })).toBeVisible();
    await expect(page.getByRole('button', { name: INBOX_ITEM })).toBeVisible();
    await page.keyboard.press('p');
    await expect(page.getByRole('heading', { name: INBOX_ITEM })).toBeVisible();
    await page.keyboard.press('n');
    await expect(page.getByText('Inbox zero.')).toBeVisible();
    await page.keyboard.press('Escape');
    // 2–7: calendar, Waiting For (Alex's ladder), projects, next actions, Someday/Maybe.
    await nextStep(page, 'Past calendar');
    await nextStep(page, 'Upcoming calendar');
    await nextStep(page, 'Waiting for');
    await expect(page.getByRole('group', { name: WAITING_TITLE })).toBeVisible();
    await nextStep(page, 'Projects');
    await nextStep(page, 'Next actions');
    await nextStep(page, 'Someday/Maybe');
    await nextStep(page, 'Reflection');
    // 8. Reflection, then finish.
    await expect(page.getByText('Step 8 of 8')).toBeVisible();
    await page.getByRole('textbox', { name: 'Reflection (optional)' }).fill(REFLECTION);
    await page.getByRole('button', { name: 'Finish review' }).click();
    await expect(page.getByRole('heading', { name: 'Review complete' })).toBeVisible();

    const status = await get<{ current?: unknown; daysSinceLastReview?: number }>(
      page.request,
      '/api/v1/weekly-reviews/status',
    );
    expect(status.current ?? null).toBeNull();
    expect(status.daysSinceLastReview).toBe(0);
    const next = await get<{ items: { title: string }[] }>(
      page.request,
      '/api/v1/tasks?list=NEXT&limit=200',
    );
    expect(next.items.map((t) => t.title)).toContain(INBOX_ITEM);
    const reflection = await get<{ bodyMd: string }>(
      page.request,
      `/api/v1/journal/entries/${today(serverNow())}`,
    );
    expect(reflection.bodyMd).toContain(`## Weekly review\n\n${REFLECTION}`);

    // The dashboard: "Last weekly review: Today", no prompt to start one.
    await page.goto('/');
    await expect(widget.locator('.readout .figure')).toHaveText('Today');
    await expect(widget.getByRole('link', { name: 'Start a weekly review' })).toHaveCount(0);
    await widget.locator('.readout').click();
    await expect(page).toHaveURL(/\/review$/);
  });

  test('5. the backup round trip covers contacts, bookmarks, journal and habits', async ({
    browser,
  }) => {
    const info = test.info();
    test.setTimeout(240_000);
    before = await snapshot(page.request, alexId);
    expectDemoData(before);

    // A backup through Settings → Backup & restore.
    await page.goto('/settings/backup');
    await page.getByRole('button', { name: 'Create backup' }).click();
    const job = page.locator('.job[data-state="COMPLETED"]');
    await expect(job).toBeVisible({ timeout: 120_000 });
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      job.getByRole('button', { name: 'Download' }).click(),
    ]);
    backupPath = info.outputPath(download.suggestedFilename());
    await download.saveAs(backupPath);

    // The zip has the phase 6 entities, canonical and readable.
    const zip = new Zip(await readFile(backupPath));
    const names = zip.names();
    for (const file of [
      'data/contacts.jsonl',
      'data/bookmarks.jsonl',
      'data/journal.jsonl',
      'data/habits.json',
      'data/habit_checks.jsonl',
    ]) {
      expect(names, file).toContain(file);
    }
    const vcf = zip.text('readable/contacts/contacts.vcf');
    for (const name of [ALEX_NAME, MIRA.name, JONAS.name]) expect(vcf).toContain(`FN:${name}`);
    expect(zip.text('readable/bookmarks.html')).toContain(SHARED_URL.replace(/&/g, '&amp;'));
    const day = before.journal.find((e) => e.bodyMd.includes(JOURNAL_TEXT))!.date;
    expect(zip.text(`readable/journal/${day.slice(0, 4)}/${day}.md`)).toContain(JOURNAL_TEXT);
    expect(zip.text('readable/habits.csv')).toContain(HABIT);
    const reviewsInBackup = names.some((n) => /^data\/weekly_reviews\.jsonl?$/.test(n));
    if (!reviewsInBackup) {
      // SPEC.md §14 wants them; the contributor is task backup-contrib-review. Checked below once it exists.
      info.annotations.push({
        type: 'issue',
        description: 'weekly reviews are not in backups yet (backup-contrib-review)',
      });
    }

    // Change the phase 6 data after the backup: a contact and the bookmark to the Trash, a habit check removed.
    const api = apiOf(page.request);
    const mira = before.contacts.find((c) => c.displayName === MIRA.name)!;
    await api.delete(`/api/v1/contacts/${mira.id}`, mira.version);
    const bookmark = before.bookmarks.find((b) => b.id === bookmarkId)!;
    await api.delete(`/api/v1/bookmarks/${bookmarkId}`, bookmark.version);
    const lastCheck = before.habits[0].checks.at(-1)!;
    await api.put(`/api/v1/habits/${habitId}/checks/${lastCheck.date}`, { count: 0 });
    expect(await snapshot(page.request, alexId)).not.toEqual(before);

    // Restore the backup over it.
    await page.goto('/settings/backup');
    await page.getByTestId('restore-file').setInputFiles(backupPath);
    const report = page.getByRole('region', { name: /can be restored/ });
    await expect(report).toBeVisible({ timeout: 60_000 });
    await page.getByTestId('restore-confirm').fill(CONFIRM_WORD);
    await page.getByRole('button', { name: 'Replace all data' }).click();
    await expect(page).toHaveURL(/\/login/, { timeout: 120_000 });

    // The backup has no passphrase, so no TOTP secret: sign in with a recovery code and set up TOTP again.
    const restored = await signInAfterRestore(browser, account);
    try {
      const after = await snapshot(restored.request, alexId);
      expect(after).toEqual(before);
      if (reviewsInBackup) {
        const status = await get<{ daysSinceLastReview?: number }>(
          restored.request,
          '/api/v1/weekly-reviews/status',
        );
        expect(status.daysSinceLastReview, 'last weekly review after the restore').toBe(0);
      }
      // The restored journal image still shows.
      const web = await restored.newPage();
      await web.clock.setSystemTime(serverNow());
      await web.goto(`/journal/${day}`);
      await expect(web.locator('app-markdown-editor .cm-content')).toContainText(JOURNAL_TEXT);
      await web.getByRole('button', { name: 'Preview' }).click();
      await expect(web.locator('app-note-markdown-view img[src*="/api/v1/files/"]')).toBeVisible();
    } finally {
      await restored.close();
    }
  });
});

// ---- Comparing before and after the restore ------------------------------------------------------------------------

interface Bookmark {
  id: string;
  version: number;
  title: string | null;
  favicon: string | null;
  readLater: boolean;
  metadataStatus: 'PENDING' | 'FETCHED' | 'FAILED';
}

/** What the API shows of the phase 6 data. */
interface Snapshot {
  contacts: { id: string; displayName: string; version: number }[];
  /** Alex's open Waiting For items (by the contact link). */
  waitingForAlex: string[];
  bookmarks: Bookmark[];
  journal: { date: string; bodyMd: string }[];
  habits: { id: string; checks: { date: string; count: number }[] }[];
}

async function snapshot(request: APIRequestContext, alexId: string): Promise<Snapshot> {
  const contactList = await get<{ items: { id: string }[] }>(request, '/api/v1/contacts?limit=200');
  const contacts = await Promise.all(
    byId(contactList.items).map((c) =>
      get<Snapshot['contacts'][number]>(request, `/api/v1/contacts/${c.id}`),
    ),
  );
  const related = await get<{ waitingFor: { id: string; title: string }[] }>(
    request,
    `/api/v1/contacts/${alexId}/related`,
  );
  const bookmarks = byId(
    (await get<{ items: Bookmark[] }>(request, '/api/v1/bookmarks?limit=200')).items,
  );
  const journal = (
    await get<{ items: Snapshot['journal'] }>(request, '/api/v1/journal/entries?limit=200')
  ).items;
  const habitList = byId((await get<{ items: { id: string }[] }>(request, '/api/v1/habits')).items);
  const from = addDays(today(serverNow()), -60);
  const habits = await Promise.all(
    habitList.map(async (habit) => ({
      ...habit,
      checks: (
        await get<{ items: { date: string; count: number }[] }>(
          request,
          `/api/v1/habits/${habit.id}/checks?from=${from}`,
        )
      ).items,
    })),
  );
  return {
    contacts,
    waitingForAlex: related.waitingFor.map((t) => `${t.id} ${t.title}`).sort(),
    bookmarks,
    journal,
    habits,
  };
}

/** The snapshot holds the data of steps 1–4, so comparing it after the restore is not trivially equal. */
function expectDemoData(snap: Snapshot): void {
  expect(snap.contacts.map((c) => c.displayName).sort()).toEqual(
    [ALEX_NAME, JONAS.name, MIRA.name].sort(),
  );
  expect(snap.contacts.find((c) => c.displayName === MIRA.name)).toMatchObject({
    birthday: MIRA.birthday,
  });
  expect(snap.waitingForAlex).toEqual([expect.stringContaining(WAITING_TITLE)]);
  expect(snap.bookmarks).toEqual([
    expect.objectContaining({ title: 'My Cockpit', readLater: true, metadataStatus: 'FETCHED' }),
  ]);
  // Today's entry with the image (step 3) and the review's entry (step 4).
  expect(snap.journal.map((e) => e.bodyMd)).toEqual(
    expect.arrayContaining([
      expect.stringMatching(/!\[[^\]]*\]\(cockpit-file:[0-9a-f-]{36}\)/),
      expect.stringContaining(REFLECTION),
    ]),
  );
  expect(snap.habits).toHaveLength(1);
  expect(snap.habits[0]).toMatchObject({
    name: HABIT,
    schedule: 'TIMES_PER_WEEK',
    timesPerWeek: 3,
  });
  expect(snap.habits[0].checks).toHaveLength(3);
}

// ---- Helpers ---------------------------------------------------------------------------------------------------------

/**
 * Signs the restored owner in from a new browser context: a recovery code (the backup holds no TOTP secret), then
 * a new TOTP enrollment. Returns the logged-in context.
 */
async function signInAfterRestore(browser: Browser, account: Account): Promise<BrowserContext> {
  const context = await browser.newContext();
  await context.request.get('/api/v1/setup/status');
  const login = await context.request.post('/api/v1/auth/login', {
    headers: await xsrf(context.request),
    data: {
      username: account.username,
      password: account.password,
      recoveryCode: account.recoveryCodes[0],
      rememberDevice: true,
    },
  });
  expect(login.status(), 'login with a recovery code').toBe(200);
  expect(await login.json()).toMatchObject({ totpEnrollmentRequired: true });
  const started = await context.request.post('/api/v1/auth/totp-enrollment', {
    headers: await xsrf(context.request),
  });
  expect(started.status(), 'start TOTP enrollment').toBe(200);
  const { totpSecret } = (await started.json()) as { totpSecret: string };
  const confirmed = await context.request.post('/api/v1/auth/totp-enrollment/confirm', {
    headers: await xsrf(context.request),
    data: { code: totp(totpSecret, serverNow()) },
  });
  expect(confirmed.status(), 'confirm TOTP enrollment').toBe(204);
  return context;
}

async function nextStep(page: Page, title: string): Promise<void> {
  await page.getByRole('button', { name: 'Next step' }).click();
  await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible();
}

/** A vCard file with two fictitious contacts; Mira has a birthday. */
function vcards(): string {
  const [miraGiven, miraFamily] = MIRA.name.split(' ');
  const [jonasGiven, jonasFamily] = JONAS.name.split(' ');
  return [
    'BEGIN:VCARD',
    'VERSION:3.0',
    `FN:${MIRA.name}`,
    `N:${miraFamily};${miraGiven};;;`,
    `EMAIL;TYPE=home:mira-${run}@example.org`,
    `BDAY:${MIRA.birthday}`,
    'END:VCARD',
    'BEGIN:VCARD',
    'VERSION:3.0',
    `FN:${JONAS.name}`,
    `N:${jonasFamily};${jonasGiven};;;`,
    'TEL;TYPE=cell:+41 44 000 00 01',
    'END:VCARD',
    '',
  ].join('\r\n');
}

/** The date of `at` in Zurich (the user's zone), as `yyyy-MM-dd`. */
function today(at: Date = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: ZONE }).format(at);
}

function addDays(date: string, days: number): string {
  const d = new Date(`${date}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/** The first Monday on or after `date`. */
function nextMonday(date: string): string {
  const weekday = new Date(`${date}T12:00:00Z`).getUTCDay();
  return addDays(date, (8 - weekday) % 7);
}

function byId<T extends { id: string }>(items: T[]): T[] {
  return [...items].sort((a, b) => a.id.localeCompare(b.id));
}

async function xsrf(request: APIRequestContext): Promise<Record<string, string>> {
  const cookie = (await request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN');
  expect(cookie, 'XSRF-TOKEN cookie').toBeDefined();
  return { 'X-XSRF-TOKEN': cookie!.value };
}

async function get<T>(request: APIRequestContext, url: string): Promise<T> {
  const response = await request.get(url);
  expect(response.status(), `GET ${url}`).toBe(200);
  return (await response.json()) as T;
}

function apiOf(request: APIRequestContext) {
  return {
    async post<T = unknown>(url: string, data: object): Promise<T> {
      const response = await request.post(url, { headers: await xsrf(request), data });
      expect(response.ok(), `POST ${url}: ${response.status()} ${await response.text()}`).toBe(
        true,
      );
      return (await response.json().catch(() => null)) as T;
    },
    async put(url: string, data: object): Promise<void> {
      const response = await request.put(url, { headers: await xsrf(request), data });
      expect(response.ok(), `PUT ${url}: ${response.status()} ${await response.text()}`).toBe(true);
    },
    async delete(url: string, version: number): Promise<void> {
      const response = await request.delete(url, {
        headers: { ...(await xsrf(request)), 'If-Match': `"${version}"` },
      });
      expect(response.status(), `DELETE ${url}`).toBe(204);
    },
  };
}
