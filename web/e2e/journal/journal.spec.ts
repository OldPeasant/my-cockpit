import { APIRequestContext } from '@playwright/test';
import { expect, test } from '../support/fixtures';

/** A 1×1 PNG. */
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
  'base64',
);

interface Entry {
  date: string;
  bodyMd: string;
  mood?: number;
  tags: string[];
  version: number;
}

/**
 * SPEC.md §8.5 journal: "Today" opens today's entry and the first edit creates it (text, a pasted
 * image, mood and tags autosaved); the heatmap marks the day; "on this day" shows last year's entry.
 */
test.describe('Journal', () => {
  test('write today, paste an image, see the heatmap and on this day', async ({
    loggedIn: page,
  }) => {
    const run = Date.now().toString(36);
    // Today in the user's time zone, as the server sees it (its clock may have been moved).
    const today = (await get<{ date: string }>(page.request, '/api/v1/journal/on-this-day')).date;
    const lastYear = `${Number(today.slice(0, 4)) - 1}${today.slice(4)}`;
    const earlier = addDays(today, -3);
    for (const date of [today, lastYear, earlier]) await remove(page.request, date);
    await put(page.request, lastYear, { bodyMd: `Hiked the ridge ${run}`, mood: 5 });
    await put(page.request, earlier, { bodyMd: `Rainy walk ${run}` });

    await page.goto('/journal');
    await expect(
      page.locator('app-journal-page').getByText('Today', { exact: true }),
    ).toBeVisible();
    await expect(page.getByRole('status').filter({ hasText: 'Not written yet' })).toBeVisible();
    const onThisDay = page.getByRole('complementary', { name: 'On this day' });
    await expect(
      onThisDay.getByRole('link', { name: new RegExp(`Hiked the ridge ${run}`) }),
    ).toBeVisible();

    const editor = page.locator('app-markdown-editor .cm-content');
    await editor.click();
    await page.keyboard.type(`Quiet morning ${run}.`);
    await editor.evaluate(
      (element, bytes) => {
        const data = new DataTransfer();
        data.items.add(new File([new Uint8Array(bytes)], 'lake.png', { type: 'image/png' }));
        element.dispatchEvent(
          new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }),
        );
      },
      [...PNG],
    );
    await expect(editor).toContainText(/!\[[^\]]*\]\(cockpit-file:[0-9a-f-]{36}\)/);
    await page.getByRole('button', { name: 'Good' }).click();
    await page.getByLabel('Tags').fill('calm, #morning');
    await expect(page.getByRole('status').filter({ hasText: /^Saved$/ })).toBeVisible();

    const stored = await get<Entry>(page.request, `/api/v1/journal/entries/${today}`);
    expect(stored.bodyMd).toContain(`Quiet morning ${run}.`);
    expect(stored.bodyMd).toMatch(/!\[[^\]]*\]\(cockpit-file:[0-9a-f-]{36}\)/);
    expect(stored.mood).toBe(4);
    expect(stored.tags).toEqual(['calm', 'morning']);

    // The heatmap marks today with its mood, and the image shows in the preview.
    const day = page.locator(`app-journal-heatmap [data-date="${today}"]`);
    await expect(day).toHaveAttribute('data-mood', '4');
    await page.getByRole('button', { name: 'Preview' }).click();
    const image = page.locator('app-note-markdown-view img[src*="/api/v1/files/"]');
    await expect(image).toBeVisible();
    await expect.poll(() => image.evaluate((img: HTMLImageElement) => img.naturalWidth)).toBe(1);

    // After a reload "Today" opens the entry just written.
    await page.reload();
    await expect(page.locator('app-markdown-editor .cm-content')).toContainText(
      `Quiet morning ${run}.`,
    );
    await expect(page.getByRole('button', { name: 'Good' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );

    // A day in the heatmap opens its entry.
    const earlierDay = page.locator(`app-journal-heatmap [data-date="${earlier}"]`);
    await expect(earlierDay).toHaveAttribute('data-mood', 'none');
    await earlierDay.click();
    await expect(page).toHaveURL(new RegExp(`/journal/${earlier}$`));
    await expect(page.locator('app-markdown-editor .cm-content')).toContainText(
      `Rainy walk ${run}`,
    );
  });
});

function addDays(date: string, days: number): string {
  const day = new Date(`${date}T00:00:00Z`);
  day.setUTCDate(day.getUTCDate() + days);
  return day.toISOString().slice(0, 10);
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

/** Creates the entry of `date` (it must have none). */
async function put(request: APIRequestContext, date: string, data: object): Promise<void> {
  const response = await request.put(`/api/v1/journal/entries/${date}`, {
    headers: await xsrf(request),
    data,
  });
  expect(response.status(), `PUT ${date}`).toBe(201);
}

/** Moves the entry of `date` to the Trash, if there is one (another spec may have written it). */
async function remove(request: APIRequestContext, date: string): Promise<void> {
  const response = await request.get(`/api/v1/journal/entries/${date}`);
  if (response.status() === 404) return;
  const entry = (await response.json()) as Entry;
  const deleted = await request.delete(`/api/v1/journal/entries/${date}`, {
    headers: { ...(await xsrf(request)), 'If-Match': `"${entry.version}"` },
  });
  expect(deleted.status(), `DELETE ${date}`).toBe(204);
}
