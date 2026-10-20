import { APIRequestContext } from '@playwright/test';
import { expect, test } from '../support/fixtures';

/**
 * SPEC.md §5.3 inbox processing mode: three captured items are processed one at a time with the
 * keyboard (attributes set inline, then Next, Someday and Trash) until the inbox is empty.
 */
test.describe('inbox processing', () => {
  test('process 3 items to an empty inbox', async ({ loggedIn: page }) => {
    // Other specs may leave items in the shared inbox: empty it first.
    await emptyInbox(page.request);
    const run = Date.now().toString(36);
    const titles = [`Call the dentist ${run}`, `Learn the ukulele ${run}`, `Old flyer ${run}`];
    for (const title of titles) await createTask(page.request, title);

    await page.goto('/inbox');
    for (const title of titles)
      await expect(page.getByRole('button', { name: title })).toBeVisible();

    await page.keyboard.press('p');
    await expect(page.getByText('3 items left')).toBeVisible();
    await expect(page.getByRole('heading', { name: titles[0] })).toBeVisible();

    // Clarify the first item inline, then make it a next action.
    await page
      .getByRole('group', { name: 'Energy' })
      .getByRole('button', { name: 'Low', exact: true })
      .click();
    await page
      .getByRole('group', { name: 'Time estimate' })
      .getByRole('button', { name: '15 min' })
      .click();
    await page.keyboard.press('n');

    await expect(page.getByText('2 items left')).toBeVisible();
    await expect(page.getByRole('heading', { name: titles[1] })).toBeVisible();
    await page.keyboard.press('m');

    await expect(page.getByText('1 item left')).toBeVisible();
    await expect(page.getByRole('heading', { name: titles[2] })).toBeVisible();
    await page.keyboard.press('x');

    await expect(page.getByText('Inbox zero.')).toBeVisible();
    await expect(page.getByText('No items left')).toBeVisible();

    // The server agrees: the inbox is empty, the first item is a next action with its attributes.
    await expect.poll(async () => (await listTasks(page.request, 'INBOX')).length).toBe(0);
    const next = (await listTasks(page.request, 'NEXT')).find((t) => t.title === titles[0]);
    expect(next).toMatchObject({ energy: 'LOW', timeEstimateMin: 15 });
    expect((await listTasks(page.request, 'SOMEDAY')).map((t) => t.title)).toContain(titles[1]);

    await page.keyboard.press('Escape');
    await expect(page.getByText('Your inbox is empty.')).toBeVisible();
  });
});

interface Task {
  id: string;
  title: string;
  version: number;
  energy?: string;
  timeEstimateMin?: number;
}

async function xsrf(request: APIRequestContext): Promise<Record<string, string>> {
  const cookie = (await request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN');
  expect(cookie, 'XSRF-TOKEN cookie').toBeDefined();
  return { 'X-XSRF-TOKEN': cookie!.value };
}

async function createTask(request: APIRequestContext, title: string): Promise<void> {
  const response = await request.post('/api/v1/tasks', {
    headers: await xsrf(request),
    data: { title },
  });
  expect(response.status(), 'create task').toBe(201);
}

async function listTasks(request: APIRequestContext, list: string): Promise<Task[]> {
  const response = await request.get(`/api/v1/tasks?list=${list}&limit=200`);
  expect(response.status(), 'list tasks').toBe(200);
  return ((await response.json()) as { items: Task[] }).items;
}

async function emptyInbox(request: APIRequestContext): Promise<void> {
  for (const task of await listTasks(request, 'INBOX')) {
    const response = await request.delete(`/api/v1/tasks/${task.id}`, {
      headers: { ...(await xsrf(request)), 'If-Match': `"${task.version}"` },
    });
    expect(response.status(), 'delete task').toBe(204);
  }
}
