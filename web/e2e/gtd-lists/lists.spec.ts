import { APIRequestContext } from '@playwright/test';
import { expect, test } from '../support/fixtures';

/**
 * SPEC.md §5.3 GTD lists: Waiting For is sorted by `waitingSince` (oldest first) and "Follow up"
 * creates a next action; the Logbook shows completed tasks by day and reopens them.
 */
test.describe('GTD lists', () => {
  test('waiting items are sorted by waitingSince; follow up creates a task', async ({
    loggedIn: page,
  }) => {
    const run = Date.now().toString(36);
    // Created newest first, so list order (position) and waitingSince order differ.
    const recent = `Signed offer ${run}`;
    const oldest = `Parcel from Ada ${run}`;
    await createTask(page.request, {
      title: recent,
      list: 'WAITING',
      waitingForText: 'Alex Example',
      waitingSince: daysAgo(2),
    });
    await createTask(page.request, {
      title: oldest,
      list: 'WAITING',
      waitingForText: 'Ada Example',
      waitingSince: daysAgo(12),
    });

    await page.goto('/waiting');
    await expect(page.getByRole('heading', { name: 'Waiting for' })).toBeVisible();
    const rows = page.locator('app-task-row .title');
    await expect(rows.filter({ hasText: run })).toHaveText([oldest, recent]);
    await expect(page.locator('.entry', { hasText: oldest }).locator('.age')).toHaveText('12 days');

    await page.getByRole('button', { name: `Follow up on “${oldest}”` }).click();
    const followUp = `Follow up with Ada Example: ${oldest}`;
    await expect(page.getByRole('status')).toHaveText(`Added “${followUp}” to Next.`);
    await expect(rows.filter({ hasText: run })).toHaveText([oldest, recent]);
    await expect
      .poll(async () => (await listTasks(page.request, 'list=NEXT')).map((t) => t.title))
      .toContain(followUp);
  });

  test('the logbook shows a completed task and reopens it', async ({ loggedIn: page }) => {
    const title = `Renewed library card ${Date.now().toString(36)}`;
    const task = await createTask(page.request, { title, list: 'NEXT' });
    const done = await page.request.post(`/api/v1/tasks/${task.id}/complete`, {
      headers: { ...(await xsrf(page.request)), 'If-Match': `"${task.version}"` },
    });
    expect(done.status(), 'complete task').toBe(200);

    await page.goto('/logbook');
    const today = page.locator('.day', { has: page.getByRole('heading', { name: 'Today' }) });
    await expect(today.getByRole('button', { name: title })).toBeVisible();

    await page.getByRole('checkbox', { name: `Reopen “${title}”` }).click();
    await expect(page.getByRole('button', { name: title })).toHaveCount(0);
    await expect
      .poll(async () => (await listTasks(page.request, 'list=NEXT')).map((t) => t.title))
      .toContain(title);
  });
});

interface Task {
  id: string;
  title: string;
  version: number;
}

function daysAgo(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() - days);
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

async function xsrf(request: APIRequestContext): Promise<Record<string, string>> {
  const cookie = (await request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN');
  expect(cookie, 'XSRF-TOKEN cookie').toBeDefined();
  return { 'X-XSRF-TOKEN': cookie!.value };
}

async function createTask(request: APIRequestContext, data: object): Promise<Task> {
  const response = await request.post('/api/v1/tasks', { headers: await xsrf(request), data });
  expect(response.status(), 'create task').toBe(201);
  return (await response.json()) as Task;
}

async function listTasks(request: APIRequestContext, query: string): Promise<Task[]> {
  const response = await request.get(`/api/v1/tasks?${query}&limit=200`);
  expect(response.status(), 'list tasks').toBe(200);
  return ((await response.json()) as { items: Task[] }).items;
}
