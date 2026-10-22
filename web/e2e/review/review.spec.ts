import { APIRequestContext } from '@playwright/test';
import { expect, test } from '../support/fixtures';

/**
 * SPEC.md §8.6 weekly review: all 8 steps end to end — the inbox processed to zero inside the
 * review, a capture from the calendar step, a Waiting For follow-up, a stalled project given its
 * next step, a Someday task activated, and the reflection saved in today's journal entry.
 */
test.describe('weekly review', () => {
  test('complete a review end to end', async ({ loggedIn: page }) => {
    const { request } = page;
    // Other specs may leave items in the shared inbox, and an earlier run an open review.
    await emptyInbox(request);
    await completeOpenReview(request);

    const run = Date.now().toString(36);
    const inboxItem = `Book a table ${run}`;
    const captured = `Send thank-you card ${run}`;
    const waiting = `Signed lease ${run}`;
    const project = `Garden shed ${run}`;
    const nextStep = `Measure the corner ${run}`;
    const someday = `Learn to juggle ${run}`;
    const reflection = `Calm week, the shed plan is moving (${run}).`;
    await post(request, '/api/v1/tasks', { title: inboxItem });
    await post(request, '/api/v1/tasks', {
      title: waiting,
      list: 'WAITING',
      waitingForText: 'Landlord',
    });
    const projectId = (await post(request, '/api/v1/projects', { title: project })).id;
    const somedayId = (await post(request, '/api/v1/tasks', { title: someday, list: 'SOMEDAY' }))
      .id;

    await page.goto('/review');
    await expect(page.getByRole('heading', { name: 'Weekly review' })).toBeVisible();
    await page.getByRole('button', { name: 'Start review' }).click();

    // 1. Get clear: process the inbox inside the review.
    await expect(page.getByText('Step 1 of 8')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Get clear' })).toBeVisible();
    await expect(page.getByRole('button', { name: inboxItem })).toBeVisible();
    await page.keyboard.press('p');
    await expect(page.getByRole('heading', { name: inboxItem })).toBeVisible();
    await page.keyboard.press('n');
    await expect(page.getByText('Inbox zero.')).toBeVisible();
    await page.keyboard.press('Escape');
    await next(page, 'Past calendar');

    // 2. Past calendar: capture what comes to mind.
    await page.getByRole('textbox', { name: 'Capture a task' }).fill(captured);
    await page.getByRole('button', { name: 'Add to inbox' }).click();
    await expect(page.getByText(`Added “${captured}” to the inbox.`)).toBeVisible();
    await next(page, 'Upcoming calendar');

    // 3. Upcoming calendar.
    await next(page, 'Waiting for');

    // 4. Waiting For: follow up.
    await page
      .getByRole('group', { name: waiting })
      .getByRole('button', { name: 'Follow up' })
      .click();
    await expect(
      page.getByText(`Added “Follow up with Landlord: ${waiting}” to Next.`),
    ).toBeVisible();
    await next(page, 'Projects');

    // 5. Projects: the new project has no next step yet.
    await page.getByRole('textbox', { name: `Next step for “${project}”` }).fill(nextStep);
    await page
      .getByRole('listitem')
      .filter({ has: page.getByRole('textbox', { name: `Next step for “${project}”` }) })
      .getByRole('button', { name: 'Add' })
      .click();
    await expect(
      page.getByText(`Added “${nextStep}” as the next step of “${project}”.`),
    ).toBeVisible();
    await page
      .getByRole('group', { name: project })
      .getByRole('button', { name: 'Looks good' })
      .click();
    await expect(page.getByRole('group', { name: project })).toHaveCount(0);
    await next(page, 'Next actions');

    // 6. Next actions (nothing of this run is stale yet).
    await next(page, 'Someday/Maybe');

    // 7. Someday/Maybe: activate.
    await page
      .getByRole('group', { name: someday })
      .getByRole('button', { name: 'Activate' })
      .click();
    await expect(page.getByText(`Activated “${someday}”.`)).toBeVisible();
    await next(page, 'Reflection');

    // 8. Reflection, then finish. Leaving and coming back resumes the review with the draft.
    await expect(page.getByText('Step 8 of 8')).toBeVisible();
    await page.getByRole('textbox', { name: 'Reflection (optional)' }).fill(reflection);
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Reflection' })).toBeVisible();
    await expect(page.getByRole('textbox', { name: 'Reflection (optional)' })).toHaveValue(
      reflection,
    );
    await page.getByRole('button', { name: 'Finish review' }).click();
    await expect(page.getByRole('heading', { name: 'Review complete' })).toBeVisible();

    // The server agrees.
    const status = await get<{ current?: unknown; daysSinceLastReview?: number }>(
      request,
      '/api/v1/weekly-reviews/status',
    );
    expect(status.current ?? null).toBeNull();
    expect(status.daysSinceLastReview).toBe(0);
    const titles = async (list: string) =>
      (
        await get<{ items: { title: string; id: string }[] }>(
          request,
          `/api/v1/tasks?list=${list}&limit=200`,
        )
      ).items;
    expect((await titles('INBOX')).map((t) => t.title)).toContain(captured);
    const nextTitles = (await titles('NEXT')).map((t) => t.title);
    expect(nextTitles).toEqual(
      expect.arrayContaining([inboxItem, `Follow up with Landlord: ${waiting}`, nextStep, someday]),
    );
    expect((await titles('NEXT')).find((t) => t.title === someday)?.id).toBe(somedayId);
    const saved = await get<{ stalled: boolean }>(request, `/api/v1/projects/${projectId}`);
    expect(saved.stalled).toBe(false);
    const today = await page.evaluate(() => new Date().toLocaleDateString('sv-SE'));
    const entry = await get<{ bodyMd: string }>(request, `/api/v1/journal/entries/${today}`);
    expect(entry.bodyMd).toContain(`## Weekly review\n\n${reflection}`);
  });
});

async function next(page: import('@playwright/test').Page, title: string): Promise<void> {
  await page.getByRole('button', { name: 'Next step' }).click();
  await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible();
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

async function post(
  request: APIRequestContext,
  url: string,
  data: object,
): Promise<{ id: string; version: number }> {
  const response = await request.post(url, { headers: await xsrf(request), data });
  expect(response.status(), `POST ${url}`).toBe(201);
  return (await response.json()) as { id: string; version: number };
}

async function emptyInbox(request: APIRequestContext): Promise<void> {
  const inbox = await get<{ items: { id: string; version: number }[] }>(
    request,
    '/api/v1/tasks?list=INBOX&limit=200',
  );
  for (const task of inbox.items) {
    const response = await request.delete(`/api/v1/tasks/${task.id}`, {
      headers: { ...(await xsrf(request)), 'If-Match': `"${task.version}"` },
    });
    expect(response.status(), 'delete task').toBe(204);
  }
}

async function completeOpenReview(request: APIRequestContext): Promise<void> {
  const status = await get<{ current?: { id: string; version: number } }>(
    request,
    '/api/v1/weekly-reviews/status',
  );
  if (!status.current) return;
  const response = await request.post(`/api/v1/weekly-reviews/${status.current.id}/complete`, {
    headers: { ...(await xsrf(request)), 'If-Match': `"${status.current.version}"` },
  });
  expect(response.status(), 'complete open review').toBe(200);
}
