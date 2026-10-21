import { readFile } from 'node:fs/promises';
import { APIRequestContext, Page } from '@playwright/test';
import { expect, test } from '../support/fixtures';

/**
 * SPEC.md §8.2 drive: in a folder of its own, the test creates a folder, renames it, moves it into
 * another folder through the picker and deletes it to the Trash; the server state follows each step.
 * Then it downloads an uploaded file through its menu and its folder as a zip through the toolbar.
 */
test.describe('Files browser', () => {
  test('create folder, rename, move, delete, download', async ({ loggedIn: page }) => {
    const run = Date.now().toString(36);
    const base = await post<{ id: string }>(page.request, '/api/v1/folders', {
      name: `E2E files ${run}`,
    });

    await page.goto(`/files/${base.id}`);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(`E2E files ${run}`);
    const crumbs = page.getByRole('navigation', { name: 'Breadcrumbs' });
    await expect(crumbs.getByRole('link', { name: 'My drive' })).toBeVisible();
    await expect(page.getByText('This folder is empty.')).toBeVisible();

    // Create two folders.
    await createFolder(page, 'Invoices');
    await createFolder(page, 'Archive');
    await expect(entryNames(page)).toHaveText(['Archive', 'Invoices']);

    // Rename.
    await page.getByRole('button', { name: 'Actions for Invoices' }).click();
    await page.getByRole('menuitem', { name: 'Rename' }).click();
    const rename = page.getByRole('dialog', { name: 'Rename' });
    await rename.getByLabel('New name').fill('Invoices 2026');
    await rename.getByRole('button', { name: 'Rename' }).click();
    await expect(rename).toHaveCount(0);
    await expect(entryNames(page)).toHaveText(['Archive', 'Invoices 2026']);

    // Move into Archive.
    await page.getByRole('button', { name: 'Actions for Invoices 2026' }).click();
    await page.getByRole('menuitem', { name: 'Move' }).click();
    const move = page.getByRole('dialog', { name: 'Move “Invoices 2026”' });
    await expect(move.getByRole('button', { name: 'Invoices 2026' })).toBeDisabled();
    await move.getByRole('button', { name: 'Archive' }).click();
    await expect(move.locator('[aria-current="location"]')).toHaveText('Archive');
    await move.getByRole('button', { name: 'Move here' }).click();
    await expect(move).toHaveCount(0);
    await expect(entryNames(page)).toHaveText(['Archive']);

    await page.getByRole('link', { name: 'Open folder Archive' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Archive');
    await expect(crumbs.getByRole('link')).toHaveText(['My drive', `E2E files ${run}`]);
    await expect(entryNames(page)).toHaveText(['Invoices 2026']);
    const tree = page.getByRole('navigation', { name: 'Folders' });
    // The tree expands the shown folder; collapsing it hides the subfolder.
    await expect(tree.getByRole('link', { name: 'Invoices 2026' })).toBeVisible();
    await tree.getByRole('button', { name: 'Collapse Archive' }).click();
    await expect(tree.getByRole('link', { name: 'Invoices 2026' })).toHaveCount(0);
    await tree.getByRole('button', { name: 'Expand Archive' }).click();
    await expect(tree.getByRole('link', { name: 'Invoices 2026' })).toBeVisible();

    // Delete to the Trash.
    await page.getByRole('button', { name: 'Actions for Invoices 2026' }).click();
    await page.getByRole('menuitem', { name: 'Delete' }).click();
    await expect(page.getByText('This folder is empty.')).toBeVisible();
    await expect(tree.getByRole('link', { name: 'Invoices 2026' })).toHaveCount(0);

    const trash = await get<{ items: { type: string; title: string }[] }>(
      page.request,
      '/api/v1/trash?limit=200',
    );
    expect(trash.items).toContainEqual(
      expect.objectContaining({ type: 'FOLDER', title: 'Invoices 2026' }),
    );

    // Downloads: a file through its link, the folder as a zip through the toolbar.
    const text = `Fictitious notes ${run}\n`;
    const upload = await page.request.post('/api/v1/files', {
      headers: await xsrf(page.request),
      multipart: {
        file: { name: 'notes.txt', mimeType: 'text/plain', buffer: Buffer.from(text) },
        folderId: base.id,
      },
    });
    expect(upload.ok(), `POST /api/v1/files: ${upload.status()}`).toBe(true);
    await crumbs.getByRole('link', { name: `E2E files ${run}` }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(`E2E files ${run}`);
    await expect(entryNames(page)).toHaveText(['Archive', 'notes.txt']);

    // A text file opens in the preview; its menu downloads it.
    await page.getByRole('button', { name: 'Actions for notes.txt' }).click();
    const [file] = await Promise.all([
      page.waitForEvent('download'),
      page.getByRole('menuitem', { name: 'Download' }).click(),
    ]);
    expect(file.suggestedFilename()).toBe('notes.txt');
    expect(await readFile(await file.path(), 'utf8')).toBe(text);

    const [zip] = await Promise.all([
      page.waitForEvent('download'),
      page.locator('.toolbar').getByRole('link', { name: 'Download as zip' }).click(),
    ]);
    expect(zip.suggestedFilename()).toBe(`E2E files ${run}.zip`);
    const bytes = await readFile(await zip.path());
    expect(bytes.length).toBeGreaterThan(0);
    // A zip archive starts with the local file header signature "PK\x03\x04".
    expect(bytes.subarray(0, 4)).toEqual(Buffer.from([0x50, 0x4b, 0x03, 0x04]));
  });
});

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
