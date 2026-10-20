import { APIRequestContext } from '@playwright/test';
import { expect, test } from '../support/fixtures';

/**
 * SPEC.md §5.3 Next Actions view: filtering by context (@phone) and time available (≤ 15 min)
 * shows only the matching task, the filter is in the URL, and a saved preset brings it back.
 */
test.describe('next actions', () => {
  test('@phone + ≤ 15 min shows only the matching task', async ({ loggedIn: page }) => {
    // Unique titles: other specs or earlier runs on a reused stack may have @phone tasks too.
    const run = Date.now().toString(36);
    const api = await apiOf(page.request);
    const phone = await api.context('@phone');
    const match = await api.task({
      title: `Call the dentist ${run}`,
      list: 'NEXT',
      contextIds: [phone],
      timeEstimateMin: 15,
    });
    const tooLong = await api.task({
      title: `Call the insurance ${run}`,
      list: 'NEXT',
      contextIds: [phone],
      timeEstimateMin: 60,
    });
    const otherContext = await api.task({
      title: `Water the plants ${run}`,
      list: 'NEXT',
      timeEstimateMin: 5,
    });

    await page.goto('/next');
    const list = page.getByRole('list', { name: 'Next actions' });
    for (const title of [match, tooLong, otherContext]) {
      await expect(list.getByRole('button', { name: title, exact: true })).toBeVisible();
    }

    await page
      .getByRole('group', { name: 'Context' })
      .getByRole('button', { name: '@phone', exact: true })
      .click();
    await page
      .getByRole('group', { name: 'Time available' })
      .getByRole('button', { name: '≤ 15 min' })
      .click();

    await expect(page).toHaveURL(/maxMinutes=15/);
    await expect(page).toHaveURL(new RegExp(`context=${phone}`));
    await expect(list.getByRole('button', { name: match, exact: true })).toBeVisible();
    await expect(list.getByRole('button', { name: tooLong, exact: true })).toBeHidden();
    await expect(list.getByRole('button', { name: otherContext, exact: true })).toBeHidden();

    // The filter is in the URL: a reload shows the same list.
    await page.reload();
    await expect(list.getByRole('button', { name: match, exact: true })).toBeVisible();
    await expect(list.getByRole('button', { name: tooLong, exact: true })).toBeHidden();

    // Saved as a preset, it comes back after clearing the filters.
    const preset = `Quick calls ${run}`;
    await page.getByRole('button', { name: 'Save filter…' }).click();
    await page.getByLabel('Preset name').fill(preset);
    await page.getByRole('button', { name: 'Save preset' }).click();
    const presets = page.getByRole('group', { name: 'Saved filters' });
    await expect(presets.getByRole('button', { name: preset })).toHaveAttribute(
      'aria-pressed',
      'true',
    );

    await page.getByRole('button', { name: 'Clear filters' }).first().click();
    await expect(list.getByRole('button', { name: tooLong, exact: true })).toBeVisible();
    await presets.getByRole('button', { name: preset }).click();
    await expect(list.getByRole('button', { name: match, exact: true })).toBeVisible();
    await expect(list.getByRole('button', { name: tooLong, exact: true })).toBeHidden();

    await presets.getByRole('button', { name: `Delete preset “${preset}”` }).click();
    await expect(presets.getByRole('button', { name: preset })).toBeHidden();
  });
});

/** API helpers with the page's session (CSRF header from its cookie). */
async function apiOf(request: APIRequestContext) {
  const xsrf = (await request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN');
  expect(xsrf, 'XSRF-TOKEN cookie').toBeDefined();
  const headers = { 'X-XSRF-TOKEN': xsrf!.value };
  return {
    /** The id of the context `name`, created unless it exists. */
    async context(name: string): Promise<string> {
      const created = await request.post('/api/v1/contexts', { headers, data: { name } });
      if (created.status() === 201) return ((await created.json()) as { id: string }).id;
      expect(created.status(), 'create context').toBe(409);
      const all = (await (await request.get('/api/v1/contexts')).json()) as {
        items: { id: string; name: string }[];
      };
      return all.items.find((context) => context.name === name)!.id;
    },
    /** Creates a task, returns its title. */
    async task(data: Record<string, unknown>): Promise<string> {
      const response = await request.post('/api/v1/tasks', { headers, data });
      expect(response.status(), 'create task').toBe(201);
      return data['title'] as string;
    },
  };
}
