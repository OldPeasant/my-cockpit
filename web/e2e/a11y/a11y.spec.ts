import AxeBuilder from '@axe-core/playwright';
import { APIRequestContext, Locator, Page } from '@playwright/test';
import { expect, test } from '../support/fixtures';

/**
 * Accessibility audit: an axe-core scan of every main page in the light and the dark theme (no serious or critical
 * violations, WCAG 2.1 A/AA rules), and a keyboard-only walkthrough of quick capture and inbox processing.
 *
 * Runs in the project `inbox-exclusive` (playwright.config.ts): it sets the shared account's theme and processes the
 * whole inbox, so no other spec may run meanwhile.
 */
test.describe.configure({ mode: 'serial' });

const THEMES = ['light', 'dark'] as const;
type Theme = (typeof THEMES)[number];

/** Every section of the shell (app.routes.ts, features/gtd/gtd.routes.ts) with its main heading. */
const PAGES: readonly { path: string; heading: RegExp }[] = [
  { path: '/', heading: /./ },
  { path: '/inbox', heading: /^Inbox$/ },
  { path: '/today', heading: /^Today$/ },
  { path: '/next', heading: /^Next actions?$/ },
  { path: '/projects', heading: /^Projects$/ },
  { path: '/waiting', heading: /^Waiting/ },
  { path: '/scheduled', heading: /^Scheduled$/ },
  { path: '/someday', heading: /^Someday/ },
  { path: '/logbook', heading: /^Logbook$/ },
  { path: '/calendar?view=day', heading: /./ },
  { path: '/calendar?view=week', heading: /./ },
  { path: '/calendar?view=month', heading: /./ },
  { path: '/calendar?view=agenda', heading: /./ },
  { path: '/mail', heading: /./ },
  { path: '/notes', heading: /./ },
  { path: '/files', heading: /./ },
  { path: '/contacts', heading: /./ },
  { path: '/bookmarks', heading: /./ },
  { path: '/journal', heading: /./ },
  { path: '/habits', heading: /./ },
  { path: '/review', heading: /./ },
  { path: '/new', heading: /./ },
  { path: '/settings', heading: /./ },
  { path: '/trash', heading: /./ },
];

test.describe('accessibility', () => {
  test.beforeAll(async ({ browser, sessionState }) => {
    // Some content, so the lists are scanned with rows, not only their empty states.
    const context = await browser.newContext({ storageState: sessionState });
    try {
      const run = Date.now().toString(36);
      await createTask(context.request, { title: `Water the plants ${run}` });
      await createTask(context.request, { title: `Call the bank ${run}`, list: 'NEXT' });
    } finally {
      await context.close();
    }
  });

  test.afterAll(async ({ browser, sessionState }) => {
    const context = await browser.newContext({ storageState: sessionState });
    try {
      await setServerTheme(context.request, 'system');
    } finally {
      await context.close();
    }
  });

  for (const theme of THEMES) {
    test(`the sign-in page has no serious violations (${theme})`, async ({ browser }) => {
      const context = await browser.newContext({ colorScheme: theme });
      try {
        const page = await context.newPage();
        await page.goto('/login');
        await expect(page.getByLabel('Username')).toBeVisible();
        await expectNoSeriousViolations(page, `login (${theme})`);
      } finally {
        await context.close();
      }
    });

    test(`every main page has no serious violations (${theme})`, async ({
      browser,
      sessionState,
    }) => {
      test.setTimeout(120_000);
      const context = await browser.newContext({ storageState: sessionState, colorScheme: theme });
      try {
        await setServerTheme(context.request, theme);
        const page = await context.newPage();
        const failures: string[] = [];
        for (const { path, heading } of PAGES) {
          await page.goto(path);
          await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible();
          await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
          await expect(page.getByRole('heading', { name: heading }).first()).toBeVisible();
          failures.push(...(await seriousViolations(page, `${path} (${theme})`)));
        }

        // The overlays of the shell: quick capture and the command palette.
        await page.goto('/inbox');
        await expect(page.getByRole('heading', { name: 'Inbox' })).toBeVisible();
        const dialog = page.getByRole('dialog');
        await page.keyboard.press('q');
        await expect(page.getByRole('dialog', { name: 'Capture' })).toBeVisible();
        failures.push(...(await seriousViolations(page, `capture dialog (${theme})`)));
        await page.keyboard.press('Escape');
        await expect(dialog).toBeHidden();
        await page.keyboard.press('Control+k');
        await expect(dialog).toBeVisible();
        failures.push(...(await seriousViolations(page, `command palette (${theme})`)));
        await page.keyboard.press('Escape');
        await expect(dialog).toBeHidden();

        // Inbox processing mode.
        await page.keyboard.press('p');
        await expect(page.getByText(/items? left/)).toBeVisible();
        failures.push(...(await seriousViolations(page, `inbox processing (${theme})`)));

        expect(failures, failures.join('\n')).toEqual([]);
      } finally {
        await context.close();
      }
    });
  }

  test('capture and inbox processing work with the keyboard only', async ({
    browser,
    sessionState,
  }) => {
    const context = await browser.newContext({ storageState: sessionState });
    try {
      await emptyInbox(context.request);
      const page = await context.newPage();
      const run = Date.now().toString(36);
      const titles = [`Renew the passport ${run}`, `Read the novel ${run}`];

      await page.goto('/');
      await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible();

      // Go to the inbox through the command palette.
      await page.keyboard.press('Control+k');
      const palette = page.getByRole('dialog');
      await expect(
        palette.getByRole('combobox', { name: 'Search or type a command' }),
      ).toBeFocused();
      await page.keyboard.type('Inbox');
      await expect(palette.getByRole('option', { name: 'Inbox', exact: true })).toHaveAttribute(
        'aria-selected',
        'true',
      );
      await page.keyboard.press('Enter');
      await expect(page).toHaveURL(/\/inbox$/);
      await expect(page.getByRole('heading', { name: 'Inbox' })).toBeVisible();

      // Capture two items: Shift+Enter keeps the dialog open, Enter closes it and focus returns to the page.
      await page.keyboard.press('q');
      const capture = page.getByRole('dialog', { name: 'Capture' });
      await expect(capture).toBeVisible();
      await expect(
        capture.getByRole('textbox', { name: 'What do you want to capture?' }),
      ).toBeFocused();
      await page.keyboard.type(titles[0]);
      await page.keyboard.press('Shift+Enter');
      await expect(capture.getByText('1 item captured')).toBeVisible();
      await page.keyboard.type(titles[1]);
      await page.keyboard.press('Enter');
      await expect(capture).toBeHidden();
      for (const title of titles)
        await expect(page.getByRole('button', { name: title })).toBeVisible();

      // Process them: Tab to the inline attributes, set them with Space, then the action letters.
      await page.keyboard.press('p');
      await expect(page.getByText('2 items left')).toBeVisible();
      await expect(page.getByRole('heading', { name: titles[0] })).toBeVisible();
      await tabTo(
        page,
        page
          .getByRole('group', { name: 'Energy' })
          .getByRole('button', { name: 'Low', exact: true }),
      );
      await page.keyboard.press('Space');
      await expect(
        page
          .getByRole('group', { name: 'Energy' })
          .getByRole('button', { name: 'Low', exact: true }),
      ).toHaveAttribute('aria-pressed', 'true');
      await page.keyboard.press('n');

      await expect(page.getByText('1 item left')).toBeVisible();
      await expect(page.getByRole('heading', { name: titles[1] })).toBeVisible();
      await page.keyboard.press('m');

      await expect(page.getByText('Inbox zero.')).toBeVisible();
      await page.keyboard.press('Escape');
      await expect(page.getByText('Your inbox is empty.')).toBeVisible();

      await expect.poll(async () => (await listTasks(context.request, 'INBOX')).length).toBe(0);
      const next = (await listTasks(context.request, 'NEXT')).find((t) => t.title === titles[0]);
      expect(next).toMatchObject({ energy: 'LOW' });
      expect((await listTasks(context.request, 'SOMEDAY')).map((t) => t.title)).toContain(
        titles[1],
      );
    } finally {
      await context.close();
    }
  });
});

/** WCAG 2.1 A and AA rules; only serious and critical violations fail the audit. */
async function seriousViolations(page: Page, where: string): Promise<string[]> {
  // Fading dialogs would be measured half transparent: wait for the finite animations and transitions.
  await page.evaluate(() =>
    Promise.all(
      document
        .getAnimations()
        .filter((a) => Number.isFinite(a.effect?.getComputedTiming().endTime))
        .map((a) => a.finished.catch(() => undefined)),
    ),
  );
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();
  return results.violations
    .filter((v) => v.impact === 'serious' || v.impact === 'critical')
    .map(
      (v) =>
        `${where}: ${v.id} (${v.impact}) ${v.help}\n` +
        v.nodes
          .map(
            (n) =>
              `    ${n.target.join(' ')} ${n.html.slice(0, 200)}\n      ${n.failureSummary ?? ''}`,
          )
          .join('\n'),
    );
}

async function expectNoSeriousViolations(page: Page, where: string): Promise<void> {
  const failures = await seriousViolations(page, where);
  expect(failures, failures.join('\n')).toEqual([]);
}

/** Presses Tab until [target] has the focus (at most 60 times). */
async function tabTo(page: Page, target: Locator): Promise<void> {
  for (let i = 0; i < 60; i++) {
    if (await target.evaluate((el) => el === document.activeElement)) return;
    await page.keyboard.press('Tab');
  }
  await expect(target).toBeFocused();
}

interface Task {
  id: string;
  title: string;
  version: number;
  energy?: string;
}

async function xsrf(request: APIRequestContext): Promise<Record<string, string>> {
  const cookie = (await request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN');
  expect(cookie, 'XSRF-TOKEN cookie').toBeDefined();
  return { 'X-XSRF-TOKEN': cookie!.value };
}

async function setServerTheme(request: APIRequestContext, theme: Theme | 'system'): Promise<void> {
  const current = await request.get('/api/v1/settings');
  expect(current.status(), 'get settings').toBe(200);
  const response = await request.patch('/api/v1/settings', {
    headers: { ...(await xsrf(request)), 'If-Match': current.headers()['etag'] },
    data: { theme },
  });
  expect(response.status(), 'set theme').toBe(200);
}

async function createTask(
  request: APIRequestContext,
  task: { title: string; list?: string },
): Promise<void> {
  const response = await request.post('/api/v1/tasks', {
    headers: await xsrf(request),
    data: task,
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
