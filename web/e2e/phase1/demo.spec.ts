import { APIRequestContext, Locator, Page } from '@playwright/test';
import { Account, loginViaApi, resetStack, setClock, setupAccount } from '../support/api';
import { expect, test } from '../support/fixtures';

/**
 * SPEC.md §13.2, Phase 1 demo script (GTD core), steps 1–9, driven through the web app. Runs in the `phase-demos`
 * project after the phase 0 script (playwright.config.ts): it resets the stack, sets up its own account and moves
 * the server clock forward (step 5: two days for the tickler, step 6: to a Monday for the recurrence). It leaves an
 * empty stack with the real clock behind.
 *
 * Set-up data the script presumes: the contexts `@phone` and `@errands` (GTD settings) and the project "Holiday"
 * (Projects), so the captures of step 1 can name them.
 */
test.describe.configure({ mode: 'serial' });

const ZONE = 'Europe/Zurich';
const DAY_MS = 86_400_000;

/** The server clock runs this far ahead of real time (steps 5 and 6 move it). */
let clockOffsetMs = 0;
const serverNow = () => new Date(Date.now() + clockOffsetMs);

test.describe('Phase 1 demo', () => {
  let page: Page;
  let account: Account;

  test.beforeAll(async ({ browser, request }) => {
    await resetStack(request);
    account = await setupAccount(request, {
      username: 'robin.example',
      password: 'phase1 demo passphrase',
    });
    const context = await browser.newContext();
    await loginViaApi(context.request, account);
    page = await context.newPage();
  });

  test.afterAll(async ({ request }) => {
    await page?.context().close();
    // The next specs set up their own account on an empty stack with the real clock.
    await resetStack(request);
  });

  test('0. set-up: the contexts @phone and @errands and the project "Holiday"', async () => {
    await page.goto('/settings/gtd');
    for (const name of ['@phone', '@errands']) {
      await page.getByRole('textbox', { name: 'New context', exact: true }).fill(name);
      await page.getByRole('button', { name: 'Add context' }).click();
      await expect(page.getByRole('textbox', { name: `Name of ${name}` })).toHaveValue(name);
    }

    await page.goto('/projects');
    await page.getByRole('button', { name: 'New project' }).click();
    await page.getByLabel('Project', { exact: true }).fill('Holiday');
    await page.getByRole('button', { name: 'Create project' }).click();
    // Created, the project opens: it has no tasks yet.
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Holiday');
  });

  test('1. quick capture (Q) parses project, due date and context into the Inbox', async () => {
    await page.goto('/inbox');
    await expect(page.getByText('Your inbox is empty.')).toBeVisible();

    await page.keyboard.press('q');
    const dialog = page.getByRole('dialog', { name: 'Capture' });
    const text = dialog.getByRole('textbox', { name: 'What do you want to capture?' });
    await expect(text).toBeFocused();
    for (const capture of ['Call plumber', 'Plan holiday #Holiday']) {
      await text.fill(capture);
      await text.press('Shift+Enter');
      await expect(text).toHaveValue('');
    }
    await text.fill('Buy milk !tomorrow @errands');
    await expect(dialog.locator('.preview-title')).toHaveText('Buy milk');
    await text.press('Enter');
    await expect(dialog).toBeHidden();

    await expect(rowTitles(page)).toHaveText(['Call plumber', 'Plan holiday', 'Buy milk']);
    await expect(row(page, 'Plan holiday').locator('.chip.project')).toHaveText('Holiday');
    await expect(row(page, 'Buy milk').locator('.chip.context')).toHaveText('@errands');
    await expect(row(page, 'Buy milk').locator('.chip.due')).toHaveText(
      `Due ${shortDate(addDays(today(), 1))}`,
    );

    const inbox = await listTasks(page.request, 'INBOX');
    expect(inbox.find((t) => t.title === 'Buy milk')).toMatchObject({
      dueDate: addDays(today(), 1),
    });
  });

  test('2. processing the Inbox: Next with attributes, to the project as its next step', async () => {
    await page.keyboard.press('p');
    await expect(page.getByText('3 items left')).toBeVisible();

    // "Call plumber" → Next, @phone, energy LOW, 5 min.
    await expect(page.getByRole('heading', { name: 'Call plumber' })).toBeVisible();
    const item = page.getByRole('article', { name: 'Call plumber' });
    await item.getByRole('button', { name: '@phone', exact: true }).click();
    await item
      .getByRole('group', { name: 'Energy' })
      .getByRole('button', { name: 'Low', exact: true })
      .click();
    await item
      .getByRole('group', { name: 'Time estimate' })
      .getByRole('button', { name: '5 min', exact: true })
      .click();
    await page.keyboard.press('n');

    // "Plan holiday" → the project "Holiday" (named in the capture, so preselected).
    await expect(page.getByText('2 items left')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Plan holiday' })).toBeVisible();
    await page.keyboard.press('p');
    await expect(page.locator('form.prompt select')).toHaveValue(
      await projectId(page.request, 'Holiday'),
    );
    await page.getByRole('button', { name: 'Move' }).click();

    // "Buy milk" → Next.
    await expect(page.getByText('1 item left')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Buy milk' })).toBeVisible();
    await page.keyboard.press('n');

    await expect(page.getByText('Inbox zero.')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.getByText('Your inbox is empty.')).toBeVisible();

    // The views update optimistically: wait until the server has all three in Next.
    await expect
      .poll(async () => (await listTasks(page.request, 'NEXT')).map((t) => t.title).sort())
      .toEqual(['Buy milk', 'Call plumber', 'Plan holiday']);
    const next = await listTasks(page.request, 'NEXT');
    const phone = await contextId(page.request, '@phone');
    expect(next.find((t) => t.title === 'Call plumber')).toMatchObject({
      contextIds: [phone],
      energy: 'LOW',
      timeEstimateMin: 5,
    });
    const plan = next.find((t) => t.title === 'Plan holiday');
    expect(plan?.projectId).toBe(await projectId(page.request, 'Holiday'));
    await expect
      .poll(async () => (await project(page.request, 'Holiday')).nextTaskId)
      .toBe(plan?.id);
    expect((await project(page.request, 'Holiday')).stalled).toBe(false);
  });

  test('3. Next filtered to @phone and ≤ 15 min shows only "Call plumber"; done, it is in the Logbook', async () => {
    await page.goto('/next');
    const list = page.getByRole('list', { name: 'Next actions' });
    await expect(list.locator('app-task-row')).toHaveCount(3);

    await page
      .getByRole('group', { name: 'Context' })
      .getByRole('button', { name: '@phone', exact: true })
      .click();
    await page
      .getByRole('group', { name: 'Time available' })
      .getByRole('button', { name: '≤ 15 min' })
      .click();
    await expect(page).toHaveURL(/maxMinutes=15/);
    await expect(list.locator('app-task-row .title')).toHaveText(['Call plumber']);

    await list.getByRole('checkbox', { name: 'Complete “Call plumber”' }).click();
    await expect(list.getByRole('button', { name: 'Call plumber' })).toHaveCount(0);

    await page.goto('/logbook');
    const todayGroup = page.locator('.day', { has: page.getByRole('heading', { name: 'Today' }) });
    await expect(todayGroup.getByRole('button', { name: 'Call plumber' })).toBeVisible();
  });

  test('4. completing "Plan holiday" asks for the next step; skipping leaves the project stalled', async () => {
    await page.goto('/projects');
    await page.getByRole('link', { name: 'Holiday', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Holiday');
    await expect(page.locator('.task.next .title')).toHaveText('Plan holiday');

    await page.getByRole('checkbox', { name: 'Complete “Plan holiday”' }).click();
    const prompt = page.getByRole('dialog', { name: 'Choose next step' });
    await expect(prompt).toBeVisible();
    await expect(prompt).toContainText('“Plan holiday” is done.');
    await prompt.getByRole('button', { name: 'Skip for now' }).click();
    await expect(prompt).toHaveCount(0);
    await expect(page.locator('.stalled-badge')).toHaveText('Stalled');

    await page.getByRole('link', { name: 'All projects' }).click();
    const projectRow = page.locator('.project', { hasText: 'Holiday' });
    await expect(projectRow).toHaveClass(/stalled/);
    await expect(projectRow.locator('.stalled-badge')).toHaveText('Stalled');
    expect((await project(page.request, 'Holiday')).stalled).toBe(true);
  });

  test('5. a tickler of today + 2 days hides "Renew passport" until the clock has moved 2 days', async ({
    request,
  }) => {
    const back = addDays(today(), 2);
    await capture(page, 'Renew passport');
    await page.keyboard.press('p');
    await expect(page.getByRole('heading', { name: 'Renew passport' })).toBeVisible();
    await page.keyboard.press('t');
    await page.getByLabel('Back in the inbox on').fill(back);
    await page.getByRole('button', { name: 'Move' }).click();
    await expect(page.getByText('Inbox zero.')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.getByText('Your inbox is empty.')).toBeVisible();

    // Hidden from every list, in the views and on the server.
    for (const view of ['/inbox', '/today', '/next', '/waiting', '/someday', '/scheduled']) {
      await page.goto(view);
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
      await expect(page.getByRole('button', { name: 'Renew passport' })).toHaveCount(0);
    }
    for (const list of ['INBOX', 'NEXT', 'WAITING', 'SOMEDAY', 'SCHEDULED']) {
      expect((await listTasks(page.request, list)).map((t) => t.title)).not.toContain(
        'Renew passport',
      );
    }

    // Two days later (test clock) the tickler job has put it back in the Inbox.
    clockOffsetMs += 2 * DAY_MS;
    await moveClock(request, page);
    await page.goto('/inbox');
    await expect(rowTitles(page)).toHaveText(['Renew passport']);
    const inbox = await listTasks(page.request, 'INBOX');
    expect(inbox.find((t) => t.title === 'Renew passport')?.ticklerDate ?? null).toBeNull();
  });

  test('6. weekly recurrence: on schedule from Monday to Monday, after completion from the late completion', async ({
    request,
  }) => {
    const monday = nextMonday(addDays(today(serverNow()), 1));
    const friday = addDays(monday, -3);
    await capture(page, `Take out the recycling !${monday}`);
    await capture(page, `Water the plants !${friday}`);
    await makeWeekly(page, 'Take out the recycling', 'On schedule');
    await makeWeekly(page, 'Water the plants', 'After completion');

    // Monday morning (Zurich): both are completed today, "Water the plants" three days late.
    clockOffsetMs = Date.parse(`${monday}T08:00:00Z`) - Date.now();
    await moveClock(request, page);
    await page.goto('/next');
    const list = page.getByRole('list', { name: 'Next actions' });
    for (const title of ['Take out the recycling', 'Water the plants']) {
      const open = (await listTasks(page.request, 'NEXT')).filter((t) => t.title === title);
      expect(open).toHaveLength(1);
      await list.getByRole('checkbox', { name: `Complete “${title}”` }).click();
      // The next occurrence replaces it in Next.
      await expect
        .poll(
          async () => (await listTasks(page.request, 'NEXT')).find((t) => t.title === title)?.id,
        )
        .not.toBe(open[0].id);
    }

    const next = await listTasks(page.request, 'NEXT');
    expect(next.find((t) => t.title === 'Take out the recycling')).toMatchObject({
      dueDate: addDays(monday, 7),
      recurrenceRule: 'FREQ=WEEKLY',
      recurrenceMode: 'FIXED',
    });
    // Relative to the completion (Monday), not to the due Friday (which would give Friday + 7).
    expect(next.find((t) => t.title === 'Water the plants')).toMatchObject({
      dueDate: addDays(monday, 7),
      recurrenceMode: 'AFTER_COMPLETION',
    });
    await page.reload();
    for (const title of ['Take out the recycling', 'Water the plants']) {
      await expect(row(page, title, list).locator('.chip.due')).toHaveText(
        `Due ${shortDate(addDays(monday, 7))}`,
      );
    }
  });

  test('7. "Waiting for: Anna — contract" is listed in Waiting For by waitingSince', async () => {
    await capture(page, 'Contract ^Anna');
    // An older item, waiting since last week (its date set in the editor).
    await capture(page, 'Signed lease ^Ben');
    await openTask(page, 'Signed lease', '/waiting');
    await page.getByLabel('Since', { exact: true }).fill(addDays(today(serverNow()), -7));
    await save(page);

    await page.goto('/waiting');
    await expect(page.getByRole('heading', { name: 'Waiting for' })).toBeVisible();
    await expect(page.locator('app-task-row .title')).toHaveText(['Signed lease', 'Contract']);
    const anna = page.locator('.entry', { hasText: 'Contract' });
    await expect(anna.locator('.who')).toHaveText('for Anna');
    await expect(anna.locator('.age')).toHaveText('Today');
    await expect(page.locator('.entry', { hasText: 'Signed lease' }).locator('.age')).toHaveText(
      '7 days',
    );
  });

  test('8. two linked tasks show each other under "Linked items"; search finds "passport"', async () => {
    await openTask(page, 'Renew passport', '/inbox');
    const links = page.getByRole('region', { name: /Linked items/ });
    await links.getByRole('button', { name: 'Add link' }).click();
    const find = links.getByRole('combobox', { name: 'Find an item to link' });
    await find.pressSequentially('Buy milk');
    await links.getByRole('option', { name: /Buy milk/ }).click();
    await expect(links.getByRole('link', { name: 'Buy milk' })).toBeVisible();

    await links.getByRole('link', { name: 'Buy milk' }).click();
    await expect(page.getByLabel('Title', { exact: true })).toHaveValue('Buy milk');
    await expect(
      page
        .getByRole('region', { name: /Linked items/ })
        .getByRole('link', { name: 'Renew passport' }),
    ).toBeVisible();

    await page.keyboard.press('Control+k');
    const query = page.getByRole('combobox', { name: 'Search or type a command' });
    await expect(query).toBeFocused();
    await query.pressSequentially('passport');
    const hit = page
      .getByRole('group', { name: 'Task' })
      .getByRole('option', { name: /Renew passport/ });
    await expect(hit).toBeVisible();
    await hit.click();
    await expect(query).toBeHidden();
    await expect(page.getByLabel('Title', { exact: true })).toHaveValue('Renew passport');
  });

  test('9. a deleted task is in the Trash and comes back on restore', async () => {
    // The web app deletes tasks from the Inbox processing ("Trash", X).
    await page.goto('/inbox');
    await expect(rowTitles(page)).toHaveText(['Renew passport']);
    await page.keyboard.press('p');
    await expect(page.getByRole('heading', { name: 'Renew passport' })).toBeVisible();
    await page.keyboard.press('x');
    await expect(page.getByText('Inbox zero.')).toBeVisible();
    expect((await listTasks(page.request, 'INBOX')).map((t) => t.title)).not.toContain(
      'Renew passport',
    );

    await page.goto('/trash');
    const item = page.locator('.item', { hasText: 'Renew passport' });
    await expect(item.locator('.subtitle')).toContainText('Task');
    await page.getByRole('button', { name: 'Restore Renew passport' }).click();
    await expect(item).toHaveCount(0);

    await page.goto('/inbox');
    await expect(rowTitles(page)).toHaveText(['Renew passport']);
    // Restored with its links.
    await openTask(page, 'Renew passport');
    await expect(
      page.getByRole('region', { name: /Linked items/ }).getByRole('link', { name: 'Buy milk' }),
    ).toBeVisible();
  });
});

/** Rows of the current task list, by title. */
function rowTitles(page: Page): Locator {
  return page.locator('app-task-row .title');
}

/** The task row of `title` in `scope`. */
function row(page: Page, title: string, scope: Page | Locator = page): Locator {
  return scope.locator('app-task-row', {
    has: page.getByRole('button', { name: title, exact: true }),
  });
}

/** Captures `text` with the quick capture dialog from the Inbox. */
async function capture(page: Page, text: string): Promise<void> {
  await page.goto('/inbox');
  await expect(page.getByRole('heading', { name: 'Inbox', exact: true })).toBeVisible();
  await page.keyboard.press('q');
  const dialog = page.getByRole('dialog', { name: 'Capture' });
  const field = dialog.getByRole('textbox', { name: 'What do you want to capture?' });
  await field.fill(text);
  const saved = page.waitForResponse(
    (r) => r.url().endsWith('/api/v1/capture') && r.request().method() === 'POST',
  );
  await field.press('Enter');
  expect((await saved).ok(), `capture "${text}"`).toBe(true);
  await expect(dialog).toBeHidden();
}

/** Opens the task editor of `title` from the list view `from`. */
async function openTask(page: Page, title: string, from = '/inbox'): Promise<void> {
  await page.goto(from);
  await page.getByRole('button', { name: title, exact: true }).click();
  await expect(page).toHaveURL(/\/tasks\//);
  await expect(page.getByLabel('Title', { exact: true })).toHaveValue(title);
}

async function save(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Save changes' }).click();
  await expect(page.locator('.bar-status')).toHaveText('Saved');
}

/** Makes the Inbox item `title` a weekly recurring next action with the given mode, in the task editor. */
async function makeWeekly(page: Page, title: string, mode: string): Promise<void> {
  await openTask(page, title);
  await page.getByLabel('List', { exact: true }).selectOption({ label: 'Next actions' });
  await page.getByLabel('Repeat', { exact: true }).selectOption({ label: 'Every week' });
  const modes = page.getByRole('group', { name: 'Next occurrence' });
  const choice = modes.getByRole('button', { name: mode });
  if ((await choice.getAttribute('aria-pressed')) !== 'true') await choice.click();
  await expect(choice).toHaveAttribute('aria-pressed', 'true');
  await save(page);
}

interface Task {
  id: string;
  title: string;
  version: number;
  projectId?: string;
  contextIds: string[];
  energy?: string;
  timeEstimateMin?: number;
  dueDate?: string;
  ticklerDate?: string;
  recurrenceRule?: string;
  recurrenceMode?: string;
}

interface Project {
  id: string;
  title: string;
  nextTaskId?: string;
  stalled: boolean;
}

async function listTasks(request: APIRequestContext, list: string): Promise<Task[]> {
  const response = await request.get(`/api/v1/tasks?list=${list}&limit=200`);
  expect(response.status(), `list ${list}`).toBe(200);
  return ((await response.json()) as { items: Task[] }).items;
}

async function project(request: APIRequestContext, title: string): Promise<Project> {
  const response = await request.get('/api/v1/projects?limit=200');
  expect(response.status(), 'list projects').toBe(200);
  const found = ((await response.json()) as { items: Project[] }).items.find(
    (p) => p.title === title,
  );
  expect(found, `project ${title}`).toBeDefined();
  return found!;
}

async function projectId(request: APIRequestContext, title: string): Promise<string> {
  return (await project(request, title)).id;
}

async function contextId(request: APIRequestContext, name: string): Promise<string> {
  const response = await request.get('/api/v1/contexts');
  const found = ((await response.json()) as { items: { id: string; name: string }[] }).items.find(
    (c) => c.name === name,
  );
  expect(found, `context ${name}`).toBeDefined();
  return found!.id;
}

/** Moves the server clock and the page's clock to `serverNow()`, so both agree on "today". */
async function moveClock(request: APIRequestContext, page: Page): Promise<void> {
  await setClock(request, serverNow());
  await page.clock.setSystemTime(serverNow());
}

/** The date of `at` in Zurich (the user's zone), as `yyyy-MM-dd`. */
function today(at: Date = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: ZONE }).format(at);
}

function addDays(date: string, days: number): string {
  const d = new Date(`${date}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/** The first Monday on or after `date`. */
function nextMonday(date: string): string {
  const weekday = new Date(`${date}T12:00:00Z`).getUTCDay();
  return addDays(date, (8 - weekday) % 7);
}

/** `date` as the task rows show it (`MMM d`, en-US). */
function shortDate(date: string): string {
  return new Date(`${date}T12:00:00Z`).toLocaleDateString('en-US', {
    month: 'short',
    day: 'numeric',
    timeZone: 'UTC',
  });
}
