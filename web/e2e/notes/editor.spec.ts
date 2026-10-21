import { APIRequestContext } from '@playwright/test';
import { expect, test } from '../support/fixtures';

/** A 1×1 PNG. */
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
  'base64',
);

/**
 * SPEC.md §8.1 note editor: a pasted image is uploaded and shown in the preview, a checklist item is
 * toggled in the preview, both are autosaved and still there after a reload.
 */
test.describe('Notes editor', () => {
  test('paste an image, toggle a checklist, reload: both persisted', async ({ loggedIn: page }) => {
    const run = Date.now().toString(36);
    const note = await post<{ id: string }>(page.request, '/api/v1/notes', {
      title: `Packing list ${run}`,
      bodyMd: '- [ ] Passport\n- [ ] Charger\n',
    });

    await page.goto(`/notes/${note.id}`);
    await page.getByRole('button', { name: 'Side by side' }).click();
    const preview = page.getByRole('region', { name: 'Preview' });
    await expect(preview.getByRole('checkbox', { name: 'Passport' })).not.toBeChecked();

    // Paste an image at the end of the text.
    const editor = page.getByRole('textbox', { name: 'Text' });
    await editor.click();
    await page.keyboard.press('ControlOrMeta+End');
    await editor.evaluate(
      (element, bytes) => {
        const data = new DataTransfer();
        const file = new File([new Uint8Array(bytes)], 'tent.png', { type: 'image/png' });
        data.items.add(file);
        element.dispatchEvent(
          new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }),
        );
      },
      [...PNG],
    );
    await expect(editor).toContainText(/!\[[^\]]*\]\(cockpit-file:[0-9a-f-]{36}\)/);
    const image = preview.locator('img[src*="/api/v1/files/"]');
    await expect(image).toBeVisible();
    await expect.poll(() => image.evaluate((img: HTMLImageElement) => img.naturalWidth)).toBe(1);

    await preview.getByRole('checkbox', { name: 'Passport' }).check();
    await expect(page.getByRole('status').filter({ hasText: /^Saved$/ })).toBeVisible();

    await page.reload();
    const reloaded = page.getByRole('region', { name: 'Preview' });
    await expect(reloaded.getByRole('checkbox', { name: 'Passport' })).toBeChecked();
    await expect(reloaded.getByRole('checkbox', { name: 'Charger' })).not.toBeChecked();
    await expect(reloaded.locator('img[src*="/api/v1/files/"]')).toBeVisible();

    const stored = await get<{ bodyMd: string }>(page.request, `/api/v1/notes/${note.id}`);
    expect(stored.bodyMd).toContain('- [x] Passport');
    expect(stored.bodyMd).toContain('- [ ] Charger');
    expect(stored.bodyMd).toMatch(/!\[[^\]]*\]\(cockpit-file:[0-9a-f-]{36}\)/);
  });
});

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
  expect(response.status(), `GET ${url}`).toBe(200);
  return (await response.json()) as T;
}
