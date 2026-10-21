import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { posix } from 'node:path';
import { APIRequestContext, Browser, BrowserContext, Page } from '@playwright/test';
import { Account, loginViaApi, resetStack, setupAccount, totp } from '../support/api';
import { expect, test } from '../support/fixtures';
import { Zip } from './zip';

/**
 * SPEC.md §13.2, Phase 2 demo script (notes, files, backup), steps 1–5, driven through the web app. Runs in the
 * `phase-demos` project after the phase 0 and 1 scripts (playwright.config.ts): it resets the stack and sets up its
 * own account, and leaves an empty stack behind.
 *
 * Step 5 restores the backup of step 4 into a fresh install: the e2e stack's second server `server-fresh` with its own
 * empty database and data directory (deploy/docker-compose.e2e.yml, address in E2E_FRESH_BASE_URL). Nothing else uses
 * it. There, a throwaway owner is set up only to reach Settings → Backup & restore; the restore replaces it with the
 * backup's owner. Then every API view of the phase 1 and 2 data is compared with the original server's.
 *
 * Set-up data the script presumes: phase 1 data (contexts, an area, projects, tasks on every list with a checklist,
 * a recurrence, a completed and a deleted task, a link between tasks) and changed settings, created through the API,
 * so the round trip covers them.
 */
test.describe.configure({ mode: 'serial' });

const MB = 1024 * 1024;
const OWNER = { username: 'sam.example', password: 'phase2 demo passphrase' } as const;
/** The word the restore form wants typed (Settings → Backup & restore). */
const CONFIRM_WORD = 'RESTORE';
const FRESH_BASE_URL = process.env['E2E_FRESH_BASE_URL'];

/** A 2×2 PNG (fictitious pixels): the pasted image of the note and the photo with a thumbnail. */
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFElEQVR4nGP4z8DAwPAfhP7/ZwAAHu8E/Gj7YDYAAAAASUVORK5CYII=',
  'base64',
);

test.describe('Phase 2 demo', () => {
  let page: Page;
  let account: Account;
  /** Filled by the steps: the downloaded backup and what step 5 compares. */
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
    // The next specs set up their own account on an empty stack.
    await resetStack(request);
  });

  test('0. set-up: phase 1 data and changed settings, through the API', async () => {
    await seedPhase1(page.request);
  });

  test('1. a note with Markdown, a checklist and a pasted image: preview, checklist toggle saved', async () => {
    await page.goto('/notes');
    await page.getByRole('button', { name: 'Write a note…' }).click();
    await page.getByRole('textbox', { name: 'Title' }).fill('Trip to Zanzibar');
    await page
      .getByRole('textbox', { name: 'Text' })
      .fill(
        '## Packing\n\nRemember the **snorkel** and the *sunscreen*.\n\n- [ ] Passport\n- [ ] Charger\n',
      );
    await page.getByRole('button', { name: 'Save note' }).click();
    await page.getByText('Trip to Zanzibar').first().click();
    await expect(page).toHaveURL(/\/notes\/[0-9a-f-]{36}$/);

    await page.getByRole('button', { name: 'Side by side' }).click();
    const preview = page.getByRole('region', { name: 'Preview' });
    await expect(preview.getByRole('heading', { name: 'Packing' })).toBeVisible();
    await expect(preview.locator('strong')).toHaveText('snorkel');
    await expect(preview.getByRole('checkbox', { name: 'Passport' })).not.toBeChecked();

    const editor = page.getByRole('textbox', { name: 'Text' });
    await editor.click();
    await page.keyboard.press('ControlOrMeta+End');
    await editor.evaluate(
      (element, bytes) => {
        const data = new DataTransfer();
        data.items.add(new File([new Uint8Array(bytes)], 'beach.png', { type: 'image/png' }));
        element.dispatchEvent(
          new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }),
        );
      },
      [...PNG],
    );
    await expect(editor).toContainText(/!\[[^\]]*\]\(cockpit-file:[0-9a-f-]{36}\)/);
    const image = preview.locator('img[src*="/api/v1/files/"]');
    await expect(image).toBeVisible();
    await expect.poll(() => image.evaluate((img: HTMLImageElement) => img.naturalWidth)).toBe(2);

    await preview.getByRole('checkbox', { name: 'Passport' }).check();
    await expect(page.getByRole('status').filter({ hasText: /^Saved$/ })).toBeVisible();
    await page.reload();
    await expect(
      page.getByRole('region', { name: 'Preview' }).getByRole('checkbox', { name: 'Passport' }),
    ).toBeChecked();

    const note = await noteByTitle(page.request, 'Trip to Zanzibar');
    expect(note.bodyMd).toContain('- [x] Passport');
    expect(note.bodyMd).toContain('- [ ] Charger');
    expect(note.bodyMd).toMatch(/!\[[^\]]*\]\(cockpit-file:[0-9a-f-]{36}\)/);
  });

  test('2. a 60 MB file (chunked), a PDF and a photo in /Documents: thumbnail, PDF preview, rename, move, zip', async () => {
    const info = test.info();
    test.setTimeout(240_000);
    await page.goto('/files');
    await createFolder(page, 'Documents');
    await page.getByRole('link', { name: 'Open folder Documents' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Documents');

    // 60 MB of fictitious bytes: above the single-request limit, so the upload goes in chunks.
    const big = Buffer.alloc(60 * MB);
    for (let i = 0; i < big.length; i += 4) big.writeUInt32LE(Math.imul(i, 2654435761) >>> 0, i);
    const paths = [
      info.outputPath('archive.bin'),
      info.outputPath('invoice.pdf'),
      info.outputPath('photo.png'),
    ];
    await writeFile(paths[0], big);
    await writeFile(paths[1], pdf('Fictitious invoice 2025'));
    await writeFile(paths[2], PNG);
    const chunk = page.waitForRequest((r) =>
      /\/api\/v1\/uploads\/[^/]+\/chunks\/\d+$/.test(r.url()),
    );
    await page.getByLabel('Files to upload').setInputFiles(paths);
    await chunk;
    const uploads = page.locator('app-upload-panel');
    await expect(uploads.locator('.job[data-state="done"]')).toHaveCount(3, { timeout: 200_000 });
    await expect(entryNames(page)).toHaveText(['archive.bin', 'invoice.pdf', 'photo.png']);
    const stored = await driveEntries(page.request, await folderId(page.request, 'Documents'));
    expect(stored.find((e) => e.file?.name === 'archive.bin')?.file).toMatchObject({
      size: 60 * MB,
      sha256: createHash('sha256').update(big).digest('hex'),
    });

    // The photo's thumbnail is the server's rendering of the image.
    const thumb = page
      .locator('.entries .entry', { hasText: 'photo.png' })
      .locator('img[src*="/thumbnail"]');
    await expect(thumb).toBeVisible();
    await expect
      .poll(() => thumb.evaluate((img: HTMLImageElement) => img.naturalWidth))
      .toBeGreaterThan(0);

    // The PDF preview draws its page.
    await page.getByRole('button', { name: 'Preview invoice.pdf' }).click();
    const preview = page.getByRole('dialog', { name: 'invoice.pdf' });
    await expect(preview.getByText('Page 1 of 1')).toBeVisible();
    await expect(preview.locator('canvas[data-rendered="1"]')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).toHaveCount(0);

    // Rename the PDF and move it into a new subfolder "Tax".
    await createFolder(page, 'Tax');
    await page.getByRole('button', { name: 'Actions for invoice.pdf' }).click();
    await page.getByRole('menuitem', { name: 'Rename' }).click();
    const rename = page.getByRole('dialog', { name: 'Rename' });
    await rename.getByLabel('New name').fill('Invoice 2025.pdf');
    await rename.getByRole('button', { name: 'Rename' }).click();
    await expect(rename).toHaveCount(0);
    await page.getByRole('button', { name: 'Actions for Invoice 2025.pdf' }).click();
    await page.getByRole('menuitem', { name: 'Move' }).click();
    const move = page.getByRole('dialog', { name: 'Move “Invoice 2025.pdf”' });
    await move.getByRole('button', { name: 'Tax' }).click();
    await expect(move.locator('[aria-current="location"]')).toHaveText('Tax');
    await move.getByRole('button', { name: 'Move here' }).click();
    await expect(move).toHaveCount(0);
    await expect(entryNames(page)).toHaveText(['Tax', 'archive.bin', 'photo.png']);

    // The folder as a zip: the tree with the renamed, moved PDF and the 60 MB file intact.
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.locator('.toolbar').getByRole('link', { name: 'Download as zip' }).click(),
    ]);
    expect(download.suggestedFilename()).toBe('Documents.zip');
    const zip = new Zip(await readFile(await download.path()));
    const files = zip.names().filter((name) => !name.endsWith('/'));
    expect(files.map((name) => posix.basename(name)).sort()).toEqual([
      'Invoice 2025.pdf',
      'archive.bin',
      'photo.png',
    ]);
    const pdfEntry = files.find((name) => name.endsWith('Invoice 2025.pdf'))!;
    expect(posix.basename(posix.dirname(pdfEntry))).toBe('Tax');
    expect(zip.text(pdfEntry)).toContain('Fictitious invoice 2025');
    expect(zip.read(files.find((name) => name.endsWith('archive.bin'))!).equals(big)).toBe(true);
  });

  test('3. the note linked to a project and the PDF to a task; search finds the note text', async () => {
    const note = await noteByTitle(page.request, 'Trip to Zanzibar');
    await page.goto(`/notes/${note.id}`);
    await addLink(page, 'Holiday');

    const task = (await listTasks(page.request)).find((t) => t.title === 'File tax return')!;
    await page.goto(`/tasks/${task.id}`);
    await expect(page.getByLabel('Title', { exact: true })).toHaveValue('File tax return');
    await addLink(page, 'Invoice 2025');

    // Each end shows the link: the project lists the note.
    const links = await itemLinks(page.request, 'NOTE', note.id);
    expect(links.map((l) => otherEnd(l, note.id).title)).toContain('Holiday');
    const taskLinks = await itemLinks(page.request, 'TASK', task.id);
    expect(taskLinks.map((l) => otherEnd(l, task.id))).toContainEqual(
      expect.objectContaining({ type: 'FILE', title: 'Invoice 2025.pdf' }),
    );

    await page.goto('/');
    await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible();
    await page.keyboard.press('Control+k');
    const query = page.getByRole('combobox', { name: 'Search or type a command' });
    await expect(query).toBeFocused();
    await query.pressSequentially('snorkel');
    const hit = page
      .getByRole('group', { name: 'Note' })
      .getByRole('option', { name: /Trip to Zanzibar/ });
    await expect(hit).toBeVisible();
    await hit.click();
    await expect(page).toHaveURL(`/notes/${note.id}`);
  });

  test('4. a backup zip: readable/ has the Markdown tasks, projects and notes with working image links, files/ the tree', async () => {
    const info = test.info();
    test.setTimeout(180_000);
    before = await snapshot(page.request);
    expectDemoData(before);

    await page.goto('/settings/backup');
    await page.getByRole('button', { name: 'Create backup' }).click();
    const job = page.locator('.job[data-state="COMPLETED"]');
    await expect(job).toBeVisible({ timeout: 120_000 });
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      job.getByRole('button', { name: 'Download' }).click(),
    ]);
    expect(download.suggestedFilename()).toMatch(/^cockpit-backup-.*\.zip$/);
    backupPath = info.outputPath(download.suggestedFilename());
    await download.saveAs(backupPath);

    const zip = new Zip(await readFile(backupPath));
    const names = zip.names();
    expect(names).toEqual(expect.arrayContaining(['README.md', 'manifest.json']));

    // Tasks by list, projects by area, as Markdown.
    expect(zip.text('readable/tasks/inbox.md')).toContain('Buy milk');
    expect(zip.text('readable/tasks/next.md')).toContain('Call plumber');
    expect(zip.text('readable/tasks/waiting.md')).toContain('Anna');
    expect(zip.text('readable/tasks/someday.md')).toContain('Learn Italian');
    expect(zip.text('readable/tasks/logbook.md')).toContain('Pay the electricity bill');
    const holiday = zip.text('readable/projects/Home/Holiday.md');
    expect(holiday).toMatch(/- \[ \] Book flights/);
    expect(names).toContain('readable/projects/Home/Tax return 2025.md');

    // The note: its text, and its image linked relative to the file in files/.
    const noteFile = names.find(
      (n) =>
        /^readable\/notes\/\d{4}-\d{2}-\d{2}-.*\.md$/.test(n) && zip.text(n).includes('snorkel'),
    );
    expect(noteFile, 'readable note of "Trip to Zanzibar"').toBeDefined();
    const markdown = zip.text(noteFile!);
    expect(markdown).toContain('- [x] Passport');
    const images = [...markdown.matchAll(/!\[[^\]]*\]\(([^)\s]+)\)/g)].map((m) => m[1]);
    expect(images).toHaveLength(1);
    const target = posix.normalize(
      posix.join(posix.dirname(noteFile!), decodeURIComponent(images[0])),
    );
    expect(target).toMatch(/^files\//);
    expect(zip.read(target).equals(PNG), `${images[0]} resolves to the pasted image`).toBe(true);
    // No image link anywhere in readable/ points outside the zip.
    for (const name of names.filter((n) => /^readable\/.*\.md$/.test(n))) {
      for (const [, link] of zip.text(name).matchAll(/!\[[^\]]*\]\(([^)\s]+)\)/g)) {
        const resolved = posix.normalize(posix.join(posix.dirname(name), decodeURIComponent(link)));
        expect(zip.has(resolved), `${name}: image ${link} resolves inside the zip`).toBe(true);
      }
    }

    // files/: the original tree with the original names and bytes.
    expect(zip.text('files/Documents/Tax/Invoice 2025.pdf')).toContain('Fictitious invoice 2025');
    expect(zip.read('files/Documents/photo.png').equals(PNG)).toBe(true);
    expect(sha256(zip.read('files/Documents/archive.bin'))).toBe(
      before.files.find((f) => f.path === 'Documents/archive.bin')?.sha256,
    );
  });

  test('5. restored into a fresh install: identical data, links, files and settings; login with TOTP re-enrollment', async ({
    browser,
  }) => {
    test.skip(!FRESH_BASE_URL, 'E2E_FRESH_BASE_URL is not set: run with `npm run e2e`');
    test.setTimeout(240_000);
    const fresh = await freshContext(browser);
    let web: BrowserContext | undefined;
    try {
      const freshPage = await fresh.newPage();
      // A fresh install: no data, the setup is required.
      const status = await fresh.request.get('/api/v1/setup/status');
      expect(await status.json()).toEqual({ setupRequired: true });

      // A throwaway owner, only to reach Settings → Backup & restore.
      const temp = await setupAccount(fresh.request, {
        username: 'temp.owner',
        password: 'throwaway setup passphrase',
      });
      await loginViaApi(fresh.request, temp);
      await freshPage.goto('/settings/backup');
      await freshPage.getByTestId('restore-file').setInputFiles(backupPath);
      const report = freshPage.getByRole('region', { name: /can be restored/ });
      await expect(report).toBeVisible({ timeout: 60_000 });
      await expect(report.getByText('not included')).toBeVisible();
      await freshPage.getByTestId('restore-confirm').fill(CONFIRM_WORD);
      await freshPage.getByRole('button', { name: 'Replace all data' }).click();
      await expect(freshPage).toHaveURL(/\/login/, { timeout: 120_000 });

      // The backup has no passphrase, so no TOTP secret: the restored owner signs in with a recovery code and must
      // set up TOTP again before anything else works.
      const login = await fresh.request.post('/api/v1/auth/login', {
        headers: await xsrf(fresh.request),
        data: {
          username: account.username,
          password: account.password,
          recoveryCode: account.recoveryCodes[0],
          rememberDevice: true,
        },
      });
      expect(login.status(), 'login with a recovery code').toBe(200);
      expect(await login.json()).toMatchObject({ totpEnrollmentRequired: true });
      expect(
        (await fresh.request.get('/api/v1/tasks')).status(),
        'blocked until TOTP is set up',
      ).toBe(403);
      // The old TOTP secret is not restored.
      const oldTotp = await freshLogin(browser, { ...account, totp: totp(account.totpSecret) });
      expect(oldTotp, 'login with the old TOTP secret').toBe(401);

      const started = await fresh.request.post('/api/v1/auth/totp-enrollment', {
        headers: await xsrf(fresh.request),
      });
      expect(started.status(), 'start TOTP enrollment').toBe(200);
      const { totpSecret } = (await started.json()) as { totpSecret: string };
      const confirmed = await fresh.request.post('/api/v1/auth/totp-enrollment/confirm', {
        headers: await xsrf(fresh.request),
        data: { code: totp(totpSecret) },
      });
      expect(confirmed.status(), 'confirm TOTP enrollment').toBe(204);

      // Everything of phase 1 and 2 is identical.
      const after = await snapshot(fresh.request);
      expect(after).toEqual(before);

      // The web login works with the new TOTP secret, in a browser without a session.
      web = await freshContext(browser);
      const webPage = await web.newPage();
      await webPage.goto('/login');
      await webPage.getByLabel('Username').fill(account.username);
      await webPage.getByLabel('Password', { exact: true }).fill(account.password);
      await webPage.getByLabel('Code from your authenticator app').fill(totp(totpSecret));
      await webPage.getByRole('button', { name: 'Sign in' }).click();
      await expect(webPage.getByRole('navigation', { name: 'Main' })).toBeVisible();
      await webPage.goto('/notes');
      await webPage.getByText('Trip to Zanzibar').first().click();
      await expect(
        webPage.getByRole('region', { name: 'Preview' }).locator('img[src*="/api/v1/files/"]'),
      ).toBeVisible();
    } finally {
      await web?.close();
      await fresh.close();
    }
  });
});

// ---- Phase 1 data ------------------------------------------------------------------------------------------------

/**
 * Phase 1 data of every kind, through the API (the phase 1 script itself covers the UI): contexts, an area, projects,
 * tasks on every list with attributes, a checklist, a recurrence, a completed and a deleted task, a link between two
 * tasks, and settings different from the defaults.
 */
async function seedPhase1(request: APIRequestContext): Promise<void> {
  const phone = await post<{ id: string }>(request, '/api/v1/contexts', {
    name: '@phone',
    color: '#1e88e5',
  });
  const errands = await post<{ id: string }>(request, '/api/v1/contexts', { name: '@errands' });
  const home = await post<{ id: string }>(request, '/api/v1/areas', { title: 'Home' });
  const holiday = await post<{ id: string }>(request, '/api/v1/projects', {
    title: 'Holiday',
    areaId: home.id,
    descriptionMd: 'Two weeks **somewhere warm**.',
  });
  await post(request, '/api/v1/projects', { title: 'Tax return 2025', areaId: home.id });
  const tax = (
    await get<{ items: { id: string; title: string }[] }>(request, '/api/v1/projects?limit=200')
  ).items.find((p) => p.title === 'Tax return 2025')!;
  const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Zurich' }).format(new Date());

  const plumber = await post<Task>(request, '/api/v1/tasks', {
    title: 'Call plumber',
    list: 'NEXT',
    contextIds: [phone.id],
    energy: 'LOW',
    timeEstimateMin: 5,
  });
  const milk = await post<Task>(request, '/api/v1/tasks', {
    title: 'Buy milk',
    list: 'INBOX',
    contextIds: [errands.id],
    dueDate: today,
  });
  const flights = await post<Task>(request, '/api/v1/tasks', {
    title: 'Book flights',
    list: 'NEXT',
    projectId: holiday.id,
    notesMd: 'Window seat, fictitious airline.',
  });
  await post(request, `/api/v1/tasks/${flights.id}/checklist`, {
    text: 'Compare prices',
    done: true,
  });
  await post(request, `/api/v1/tasks/${flights.id}/checklist`, { text: 'Pay deposit' });
  await post(request, '/api/v1/tasks', {
    title: 'File tax return',
    list: 'NEXT',
    projectId: tax.id,
  });
  await post(request, '/api/v1/tasks', {
    title: 'Anna — contract',
    list: 'WAITING',
    waitingForText: 'Anna',
    waitingSince: today,
  });
  await post(request, '/api/v1/tasks', { title: 'Learn Italian', list: 'SOMEDAY' });
  await post(request, '/api/v1/tasks', {
    title: 'Water the plants',
    list: 'NEXT',
    dueDate: today,
    recurrenceRule: 'FREQ=WEEKLY',
    recurrenceMode: 'FIXED',
  });
  const bill = await post<Task>(request, '/api/v1/tasks', {
    title: 'Pay the electricity bill',
    list: 'NEXT',
  });
  const done = await request.post(`/api/v1/tasks/${bill.id}/complete`, {
    headers: { ...(await xsrf(request)), 'If-Match': `"${bill.version}"` },
  });
  expect(done.status(), 'complete task').toBe(200);
  const old = await post<Task>(request, '/api/v1/tasks', { title: 'Old idea', list: 'SOMEDAY' });
  const deleted = await request.delete(`/api/v1/tasks/${old.id}`, {
    headers: { ...(await xsrf(request)), 'If-Match': `"${old.version}"` },
  });
  expect(deleted.ok(), `delete task: ${deleted.status()}`).toBe(true);

  await post(request, '/api/v1/links', {
    source: { type: 'TASK', id: plumber.id },
    target: { type: 'TASK', id: milk.id },
  });

  const settings = await request.get('/api/v1/settings');
  const patched = await request.patch('/api/v1/settings', {
    headers: { ...(await xsrf(request)), 'If-Match': settings.headers()['etag'] },
    data: {
      theme: 'dark',
      weekStart: 'SUNDAY',
      energyLabels: { LOW: 'Tired', MEDIUM: 'Okay', HIGH: 'Fresh' },
    },
  });
  expect(patched.status(), 'patch settings').toBe(200);
}

// ---- Comparing two stacks ----------------------------------------------------------------------------------------

interface Task {
  id: string;
  title: string;
  version: number;
}

interface DriveEntry {
  type: 'FOLDER' | 'FILE';
  folder?: { id: string; name: string };
  file?: { id: string; name: string; sha256: string };
}

interface Link {
  id: string;
  source: { type: string; id: string; title?: string };
  target: { type: string; id: string; title?: string };
}

/** What the API shows of the phase 1 and 2 data; two stacks with the same data have equal snapshots. */
interface Snapshot {
  settings: unknown;
  contexts: unknown;
  areas: unknown;
  projects: { id: string }[];
  tasks: { id: string }[];
  logbook: unknown;
  checklists: Record<string, unknown>;
  notes: unknown[];
  drive: Record<string, DriveEntry[]>;
  /** Every file by path, with the sha256 of the content as downloaded. */
  files: { path: string; sha256: string; content: string }[];
  links: Record<string, Link[]>;
  trash: unknown;
}

async function snapshot(request: APIRequestContext): Promise<Snapshot> {
  const tasks = byId(await listTasks(request));
  const projects = byId(
    (await get<{ items: { id: string }[] }>(request, '/api/v1/projects?limit=200')).items,
  );
  const notes = byId(
    (await get<{ items: { id: string }[] }>(request, '/api/v1/notes?limit=200')).items,
  );

  const checklists: Record<string, unknown> = {};
  for (const task of tasks)
    checklists[task.id] = await get(request, `/api/v1/tasks/${task.id}/checklist`);

  const drive: Record<string, DriveEntry[]> = {};
  const files: Snapshot['files'] = [];
  const walk = async (folder: string | undefined, path: string): Promise<void> => {
    const entries = await driveEntries(request, folder);
    drive[path] = entries;
    for (const entry of entries) {
      if (entry.folder) await walk(entry.folder.id, `${path}${entry.folder.name}/`);
      if (entry.file) {
        const content = await request.get(`/api/v1/files/${entry.file.id}/content`);
        expect(content.ok(), `content of ${path}${entry.file.name}`).toBe(true);
        files.push({
          path: `${path}${entry.file.name}`,
          sha256: entry.file.sha256,
          content: sha256(await content.body()),
        });
      }
    }
  };
  await walk(undefined, '');

  const links: Record<string, Link[]> = {};
  const items = [
    ...tasks.map((t) => ['TASK', t.id]),
    ...projects.map((p) => ['PROJECT', p.id]),
    ...notes.map((n) => ['NOTE', n.id]),
    ...Object.values(drive).flatMap((entries) =>
      entries.map((e) => (e.file ? ['FILE', e.file.id] : ['FOLDER', e.folder!.id])),
    ),
  ];
  for (const [type, id] of items) links[`${type} ${id}`] = byId(await itemLinks(request, type, id));

  return {
    settings: await get(request, '/api/v1/settings'),
    contexts: await get(request, '/api/v1/contexts'),
    areas: await get(request, '/api/v1/areas'),
    projects,
    tasks,
    logbook: await get(request, '/api/v1/tasks?completed=true&limit=200'),
    checklists,
    notes: await Promise.all(notes.map((n) => get(request, `/api/v1/notes/${n.id}`))),
    drive,
    files,
    links,
    trash: await get(request, '/api/v1/trash?limit=200'),
  };
}

/**
 * The snapshot holds the phase 1 and 2 data of steps 0–3, so its comparison with the restored server's is not
 * trivially equal (e.g. two empty lists after an API change).
 */
function expectDemoData(snap: Snapshot): void {
  const field = (items: unknown, key: string) =>
    (items as Record<string, unknown>[]).map((item) => item[key]);
  const itemsOf = (response: unknown) => (response as { items: unknown[] }).items;

  expect(snap.settings).toMatchObject({
    theme: 'dark',
    weekStart: 'SUNDAY',
    energyLabels: { LOW: 'Tired', MEDIUM: 'Okay', HIGH: 'Fresh' },
  });
  expect(field(itemsOf(snap.contexts), 'name')).toEqual(
    expect.arrayContaining(['@phone', '@errands']),
  );
  expect(field(itemsOf(snap.areas), 'title')).toContain('Home');
  expect(field(snap.projects, 'title')).toEqual(
    expect.arrayContaining(['Holiday', 'Tax return 2025']),
  );
  expect(field(snap.tasks, 'title')).toEqual(
    expect.arrayContaining([
      'Call plumber',
      'Buy milk',
      'Book flights',
      'File tax return',
      'Anna — contract',
      'Learn Italian',
      'Water the plants',
    ]),
  );
  expect(field(itemsOf(snap.logbook), 'title')).toContain('Pay the electricity bill');
  expect(field(itemsOf(snap.trash), 'title')).toContain('Old idea');
  const flights = (snap.tasks as { id: string; title?: string }[]).find(
    (t) => t.title === 'Book flights',
  )!;
  expect(field(itemsOf(snap.checklists[flights.id]), 'text')).toEqual([
    'Compare prices',
    'Pay deposit',
  ]);
  expect(field(snap.notes, 'bodyMd')).toContainEqual(
    expect.stringMatching(/!\[[^\]]*\]\(cockpit-file:[0-9a-f-]{36}\)/),
  );
  expect(snap.files.map((f) => f.path)).toEqual(
    expect.arrayContaining([
      'Documents/archive.bin',
      'Documents/photo.png',
      'Documents/Tax/Invoice 2025.pdf',
    ]),
  );
  // Each file's content as downloaded matches its recorded sha256.
  for (const file of snap.files) expect(file.content, file.path).toBe(file.sha256);
  // The three links of steps 0 and 3, seen from both ends.
  const kinds = Object.values(snap.links)
    .flat()
    .map((l) => [l.source.type, l.target.type].sort().join('-'));
  expect(new Set(kinds)).toEqual(new Set(['TASK-TASK', 'NOTE-PROJECT', 'FILE-TASK']));
  expect(kinds).toHaveLength(6);
}

// ---- Helpers -----------------------------------------------------------------------------------------------------

/** A browser context on the fresh-install server. */
async function freshContext(browser: Browser): Promise<BrowserContext> {
  return browser.newContext({ baseURL: FRESH_BASE_URL });
}

/** The status of a password + TOTP login on the fresh server, from a context of its own. */
async function freshLogin(
  browser: Browser,
  credentials: { username: string; password: string; totp: string },
): Promise<number> {
  const context = await freshContext(browser);
  try {
    await context.request.get('/api/v1/setup/status');
    const response = await context.request.post('/api/v1/auth/login', {
      headers: await xsrf(context.request),
      data: { ...credentials, rememberDevice: false },
    });
    return response.status();
  } finally {
    await context.close();
  }
}

/** Links the current page's item to the first search hit for [query], in its "Linked items" panel. */
async function addLink(page: Page, query: string): Promise<void> {
  const links = page.getByRole('region', { name: /Linked items/ });
  await links.getByRole('button', { name: 'Add link' }).click();
  await links.getByRole('combobox', { name: 'Find an item to link' }).pressSequentially(query);
  await links
    .getByRole('option', { name: new RegExp(query) })
    .first()
    .click();
  await expect(links.getByRole('link', { name: new RegExp(query) })).toBeVisible();
}

function otherEnd(link: Link, id: string): Link['source'] {
  return link.source.id === id ? link.target : link.source;
}

async function itemLinks(request: APIRequestContext, type: string, id: string): Promise<Link[]> {
  return (await get<{ items: Link[] }>(request, `/api/v1/links?type=${type}&id=${id}`)).items;
}

async function listTasks(request: APIRequestContext): Promise<Task[]> {
  return (await get<{ items: Task[] }>(request, '/api/v1/tasks?limit=200')).items;
}

async function noteByTitle(
  request: APIRequestContext,
  title: string,
): Promise<{ id: string; bodyMd: string }> {
  const notes = (
    await get<{ items: { id: string; title?: string }[] }>(request, '/api/v1/notes?limit=200')
  ).items;
  const found = notes.find((n) => n.title === title);
  expect(found, `note ${title}`).toBeDefined();
  return get(request, `/api/v1/notes/${found!.id}`);
}

async function driveEntries(request: APIRequestContext, folderId?: string): Promise<DriveEntry[]> {
  const query = folderId ? `folderId=${folderId}&limit=200` : 'limit=200';
  return (await get<{ items: DriveEntry[] }>(request, `/api/v1/drive?${query}`)).items;
}

async function folderId(request: APIRequestContext, name: string): Promise<string> {
  const found = (await driveEntries(request)).find((e) => e.folder?.name === name)?.folder;
  expect(found, `folder ${name}`).toBeDefined();
  return found!.id;
}

function entryNames(page: Page) {
  return page.locator('.entries .entry .name');
}

async function createFolder(page: Page, name: string): Promise<void> {
  await page.getByRole('button', { name: 'New folder' }).click();
  const dialog = page.getByRole('dialog', { name: 'New folder' });
  await dialog.getByLabel('Folder name').fill(name);
  await dialog.getByRole('button', { name: 'Create' }).click();
  await expect(dialog).toHaveCount(0);
  await expect(page.locator('.entries .entry .name', { hasText: name })).toBeVisible();
}

function byId<T extends { id: string }>(items: T[]): T[] {
  return [...items].sort((a, b) => a.id.localeCompare(b.id));
}

function sha256(bytes: Buffer): string {
  return createHash('sha256').update(bytes).digest('hex');
}

/** A one-page PDF that shows [text] (offsets in the xref table computed). */
function pdf(text: string): string {
  const stream = `BT /F1 24 Tf 72 700 Td (${text}) Tj ET`;
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>',
    `<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  ];
  let out = '%PDF-1.4\n';
  const offsets: number[] = [];
  objects.forEach((body, i) => {
    offsets.push(out.length);
    out += `${i + 1} 0 obj\n${body}\nendobj\n`;
  });
  const xref = out.length;
  out += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  for (const offset of offsets) out += `${String(offset).padStart(10, '0')} 00000 n \n`;
  out += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return out;
}

async function xsrf(request: APIRequestContext): Promise<Record<string, string>> {
  const cookie = (await request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN');
  expect(cookie, 'XSRF-TOKEN cookie').toBeDefined();
  return { 'X-XSRF-TOKEN': cookie!.value };
}

async function post<T = unknown>(
  request: APIRequestContext,
  url: string,
  data: object,
): Promise<T> {
  const response = await request.post(url, { headers: await xsrf(request), data });
  expect(response.ok(), `POST ${url}: ${response.status()} ${await response.text()}`).toBe(true);
  return (await response.json().catch(() => null)) as T;
}

async function get<T = unknown>(request: APIRequestContext, url: string): Promise<T> {
  const response = await request.get(url);
  expect(response.status(), `GET ${url}`).toBe(200);
  return (await response.json()) as T;
}
