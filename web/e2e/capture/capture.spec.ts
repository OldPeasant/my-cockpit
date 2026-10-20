import { expect, test } from '../support/fixtures';

/**
 * SPEC.md §4.5 quick capture: `Q` opens the dialog, Shift+Enter saves and keeps it open, Enter saves
 * and closes, and the three captures show up in the Inbox.
 */
test.describe('quick capture', () => {
  test('Q opens the dialog, three captures appear in the inbox', async ({ loggedIn: page }) => {
    const run = Date.now().toString(36);
    const titles = [`Call plumber ${run}`, `Plan holiday ${run}`, `Buy milk ${run}`];

    await page.goto('/inbox');
    await expect(page.getByRole('heading', { name: 'Inbox' })).toBeVisible();

    await page.keyboard.press('q');
    const dialog = page.getByRole('dialog', { name: 'Capture' });
    await expect(dialog).toBeVisible();
    const text = dialog.getByRole('textbox', { name: 'What do you want to capture?' });
    await expect(text).toBeFocused();

    await text.fill(titles[0]);
    await text.press('Shift+Enter');
    await expect(dialog.getByText('1 item captured')).toBeVisible();
    await expect(text).toHaveValue('');

    await text.fill(titles[1]);
    await text.press('Shift+Enter');
    await expect(dialog.getByText('2 items captured')).toBeVisible();

    // The live preview reads the capture syntax back before saving.
    await text.fill(`${titles[2]} !tomorrow`);
    await expect(dialog.locator('.preview-title')).toHaveText(titles[2]);
    await expect(dialog.getByText(/^Due /)).toBeVisible();
    await text.press('Enter');
    await expect(dialog).toBeHidden();

    for (const title of titles) {
      await expect(page.getByRole('button', { name: title })).toBeVisible();
    }

    // The server has exactly one item per capture.
    const response = await page.request.get('/api/v1/tasks?list=INBOX&limit=200');
    expect(response.ok()).toBe(true);
    const inbox = ((await response.json()) as { items: { title: string; dueDate?: string }[] })
      .items;
    for (const title of titles) {
      expect(inbox.filter((t) => t.title === title)).toHaveLength(1);
    }
    expect(inbox.find((t) => t.title === titles[2])?.dueDate).toBeTruthy();
  });
});
