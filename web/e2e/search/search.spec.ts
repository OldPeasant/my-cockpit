import { APIRequestContext } from '@playwright/test';
import { expect, test } from '../support/fixtures';

/**
 * SPEC.md §4.4 global search in the command palette (and §13.2 phase 1, step 8: global search for
 * "passport" finds the task): a task created through the API is found from Ctrl+K, Enter opens its deep link.
 */
test.describe('command palette search', () => {
  test("search 'passport' finds the task and Enter opens it", async ({ loggedIn: page }) => {
    // A unique title: other specs or earlier runs on a reused stack may have "passport" tasks too.
    const title = `Renew passport ${Date.now().toString(36)}`;
    await page.goto('/inbox');
    await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible();
    const task = await createTask(page.request, title);

    await page.keyboard.press('Control+k');
    const query = page.getByRole('combobox', { name: 'Search or type a command' });
    await expect(query).toBeFocused();
    await query.pressSequentially('passport');

    const option = page.getByRole('group', { name: 'Task' }).getByRole('option', { name: title });
    await expect(option).toBeVisible();
    await expect(page.getByRole('option').first()).toHaveAttribute('aria-selected', 'true');

    // Select our task with the arrow keys (other "passport" tasks may come first), then open it.
    for (let i = 0; i < 20 && (await option.getAttribute('aria-selected')) !== 'true'; i++) {
      await query.press('ArrowDown');
    }
    await expect(option).toHaveAttribute('aria-selected', 'true');
    await query.press('Enter');

    await expect(query).toBeHidden();
    await expect(page).toHaveURL(new RegExp(`/tasks/${task.id}$`));
  });

  test('the palette offers commands next to the results', async ({ loggedIn: page }) => {
    await page.goto('/');
    await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible();
    await page.keyboard.press('Control+k');
    const query = page.getByRole('combobox', { name: 'Search or type a command' });

    await query.fill('inbox');
    await expect(
      page.getByRole('group', { name: 'Go to' }).getByRole('option', { name: 'Inbox' }),
    ).toBeVisible();

    await query.fill('new task');
    await expect(
      page.getByRole('group', { name: 'Commands' }).getByRole('option', { name: 'New task' }),
    ).toBeVisible();
  });
});

/** Creates an inbox task through the API with the page's session (CSRF header from its cookie). */
async function createTask(request: APIRequestContext, title: string): Promise<{ id: string }> {
  const xsrf = (await request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN');
  expect(xsrf, 'XSRF-TOKEN cookie').toBeDefined();
  const response = await request.post('/api/v1/tasks', {
    headers: { 'X-XSRF-TOKEN': xsrf!.value },
    data: { title },
  });
  expect(response.status(), 'create task').toBe(201);
  return (await response.json()) as { id: string };
}
