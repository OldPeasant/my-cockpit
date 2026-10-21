import { APIRequestContext } from '@playwright/test';
import { expect, test } from '../support/fixtures';

/**
 * SPEC.md §5.2/§5.3 projects: completing a project's next step asks for the new one ("Choose next
 * step"); skipping leaves the project stalled, which the detail and the list (grouped by area) show.
 */
test.describe('GTD projects', () => {
  test('completing the next step prompts; skipping leaves the project stalled', async ({
    loggedIn: page,
  }) => {
    const run = Date.now().toString(36);
    const area = await post<{ id: string }>(page.request, '/api/v1/areas', {
      title: `Household ${run}`,
    });
    const project = await post<{ id: string; version: number }>(page.request, '/api/v1/projects', {
      title: `Repaint the hallway ${run}`,
      areaId: area.id,
    });
    const first = await post<{ id: string }>(page.request, '/api/v1/tasks', {
      title: `Buy paint ${run}`,
      list: 'NEXT',
      projectId: project.id,
    });
    await post(page.request, '/api/v1/tasks', {
      title: `Tape the edges ${run}`,
      list: 'NEXT',
      projectId: project.id,
    });
    await post(page.request, `/api/v1/projects/${project.id}/next-step`, { taskId: first.id });

    await page.goto(`/projects/${project.id}`);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(`Repaint the hallway ${run}`);
    await expect(page.locator('.task.next .title')).toHaveText(`Buy paint ${run}`);
    await expect(page.locator('.stalled-badge')).toHaveCount(0);

    await page.getByRole('checkbox', { name: `Complete “Buy paint ${run}”` }).click();
    const prompt = page.getByRole('dialog', { name: 'Choose next step' });
    await expect(prompt).toBeVisible();
    await expect(prompt).toContainText(`“Buy paint ${run}” is done.`);
    await expect(
      prompt.getByRole('button', { name: `Make “Tape the edges ${run}” the next step` }),
    ).toBeVisible();

    await prompt.getByRole('button', { name: 'Skip for now' }).click();
    await expect(prompt).toHaveCount(0);
    await expect(page.locator('.stalled-badge')).toHaveText('Stalled');
    await expect(page.getByText('This project has no next step.')).toBeVisible();

    await page.getByRole('link', { name: 'All projects' }).click();
    const group = page.getByRole('region', { name: `Household ${run}` });
    const row = group.locator('.project', { hasText: `Repaint the hallway ${run}` });
    await expect(row).toHaveClass(/stalled/);
    await expect(row.locator('.stalled-badge')).toHaveText('Stalled');
    const stored = await get<{ stalled: boolean }>(page.request, `/api/v1/projects/${project.id}`);
    expect(stored.stalled).toBe(true);
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
