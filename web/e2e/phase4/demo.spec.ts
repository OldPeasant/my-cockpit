import { APIRequestContext, Locator, Page } from '@playwright/test';
import { Account, loginViaApi, resetStack, setupAccount } from '../support/api';
import { expect, test } from '../support/fixtures';

/**
 * SPEC.md §13.2, Phase 4 demo script (calendar), the web steps 1, 4 and 5, driven through the web app. Steps 2
 * (CalDAV against Radicale) and 3 (Google/Microsoft against WireMock recordings) are server tests:
 * `cd server && ./gradlew integrationTest --tests 'ch.sonensei.cockpit.calendar.smoke.*'`. Step 4 on the providers
 * is there for CalDAV, and in `GoogleCalendarSyncTests`/`MicrosoftCalendarSyncTests`; here it is the local calendar.
 *
 * Runs in the `phase-demos` project (playwright.config.ts): it resets the stack and sets up its own account, and leaves
 * an empty stack behind. The series runs across the DST change of Sunday 2026-03-29 (CET → CEST) in Europe/Zurich, the
 * zone of the settings and of the browser.
 */
test.describe.configure({ mode: 'serial' });

const CALENDAR = 'Family';
const SERIES = 'Swim practice';
const NEW_SERIES = 'Swim practice (new pool)';

test.describe('Phase 4 demo', () => {
  let page: Page;
  let account: Account;

  test.beforeAll(async ({ browser, request }) => {
    await resetStack(request);
    account = await setupAccount(request, {
      username: 'kim.example',
      password: 'phase4 demo passphrase',
    });
    const context = await browser.newContext();
    await loginViaApi(context.request, account);
    page = await context.newPage();
    await (await apiOf(page.request)).settings({ timeZone: 'Europe/Zurich', weekStart: 'MONDAY' });
  });

  test.afterAll(async ({ request }) => {
    await page?.context().close();
    // The next specs set up their own account on an empty stack.
    await resetStack(request);
  });

  test('1. a local calendar with a weekly event and one moved occurrence, correct across DST', async () => {
    // The local calendar, added in the settings.
    await page.goto('/settings/calendars');
    await page.getByRole('button', { name: 'Local', exact: true }).click();
    await page.getByRole('textbox', { name: 'Name', exact: true }).fill(CALENDAR);
    await page.getByRole('button', { name: 'Add calendar' }).click();
    await expect(page.getByRole('group', { name: 'Local' }).getByText(CALENDAR)).toBeVisible();

    // Mondays 09:00–10:00 from 16 March 2026.
    await page.goto('/calendar?view=week&date=2026-03-16');
    await expect(page.getByRole('checkbox', { name: CALENDAR })).toBeChecked();
    await page.getByRole('button', { name: 'New event' }).click();
    const create = page.getByRole('dialog', { name: 'New event' });
    await create.getByLabel('Title').fill(SERIES);
    await setTimes(create, '2026-03-16', '09:00', '2026-03-16', '10:00');
    await expect(create.getByLabel('Time zone')).toHaveValue('Europe/Zurich');
    await create.getByRole('combobox', { name: 'Repeats' }).click();
    await page.getByRole('option', { name: 'Every week' }).click();
    await create.getByRole('combobox', { name: 'Calendar' }).click();
    await page.getByRole('option', { name: CALENDAR }).click();
    await create.getByRole('button', { name: 'Create event' }).click();
    await expect(create).toBeHidden();
    await expect(eventsOn(page, '2026-03-16', SERIES)).toContainText('9:00 – 10:00');

    // Move the occurrence of Monday 30 March (the first after the change) to Tuesday 31 March, 14:00–15:00.
    await page.goto('/calendar?view=week&date=2026-03-30');
    await eventsOn(page, '2026-03-30', SERIES).click();
    const edit = page.getByRole('dialog', { name: 'Edit event' });
    await expect(edit.getByLabel('Title')).toHaveValue(SERIES);
    await setTimes(edit, '2026-03-31', '14:00', '2026-03-31', '15:00');
    await edit.getByRole('button', { name: 'Save changes' }).click();
    await scope(page, 'Only this event');
    await expect(edit).toBeHidden();
    await expect(eventsOn(page, '2026-03-31', SERIES)).toContainText('2:00 – 3:00');
    await expect(eventsOn(page, '2026-03-30', SERIES)).toHaveCount(0);

    // Week view of the DST week: Monday 23 (CET) at 09:00.
    await page.goto('/calendar?view=week&date=2026-03-25');
    await expect(eventsOn(page, '2026-03-23', SERIES)).toContainText('9:00 – 10:00');
    const before = (await eventsOn(page, '2026-03-23', SERIES).boundingBox())!;
    // The week after (CEST): Monday 6 April still at 09:00, at the same height.
    await page.getByRole('button', { name: 'Next week' }).click();
    await page.getByRole('button', { name: 'Next week' }).click();
    await expect(page).toHaveURL(/date=2026-04-0/);
    await expect(eventsOn(page, '2026-04-06', SERIES)).toContainText('9:00 – 10:00');
    const after = (await eventsOn(page, '2026-04-06', SERIES).boundingBox())!;
    expect(Math.abs(before.y - after.y)).toBeLessThan(2);

    // Day view of the first Monday in summer time.
    await page.goto('/calendar?view=day&date=2026-04-06');
    await expect(eventsOn(page, '2026-04-06', SERIES)).toContainText('9:00 – 10:00');
    await page.goto('/calendar?view=day&date=2026-03-30');
    await expect(page.locator('.app-calendar .app-event', { hasText: SERIES })).toHaveCount(0);

    // Month view: the Mondays of March, Monday 30 moved to Tuesday 31.
    await page.goto('/calendar?view=month&date=2026-03-01');
    for (const day of ['2026-03-16', '2026-03-23', '2026-03-31']) {
      await expect(eventsOn(page, day, SERIES), `month: ${day}`).toHaveCount(1);
    }
    await expect(eventsOn(page, '2026-03-30', SERIES)).toHaveCount(0);

    // The server has it the same way, in UTC: +01:00 before the change, +02:00 after it.
    const api = await apiOf(page.request);
    expect(await api.occurrences('2026-03-16T00:00:00Z', '2026-04-14T00:00:00Z')).toEqual([
      ['2026-03-16T08:00:00Z', SERIES],
      ['2026-03-23T08:00:00Z', SERIES],
      ['2026-03-31T12:00:00Z', SERIES],
      ['2026-04-06T07:00:00Z', SERIES],
      ['2026-04-13T07:00:00Z', SERIES],
    ]);
  });

  test('4. "this and following" splits the series', async () => {
    await page.goto('/calendar?view=week&date=2026-04-13');
    await eventsOn(page, '2026-04-13', SERIES).click();
    const edit = page.getByRole('dialog', { name: 'Edit event' });
    await expect(edit.getByLabel('Title')).toHaveValue(SERIES);
    await edit.getByLabel('Title').fill(NEW_SERIES);
    await setTimes(edit, '2026-04-13', '10:00', '2026-04-13', '11:00');
    await edit.getByRole('button', { name: 'Save changes' }).click();
    await scope(page, 'This and following events');
    await expect(edit).toBeHidden();
    await expect(eventsOn(page, '2026-04-13', NEW_SERIES)).toContainText('10:00 – 11:00');

    // Before the split everything is as it was (the moved occurrence included); from it on, the new series.
    await page.goto('/calendar?view=month&date=2026-04-01');
    await expect(eventsOn(page, '2026-04-06', SERIES)).toHaveCount(1);
    for (const day of ['2026-04-13', '2026-04-20', '2026-04-27']) {
      await expect(eventsOn(page, day, NEW_SERIES), `month: ${day}`).toHaveCount(1);
    }
    const api = await apiOf(page.request);
    expect(await api.occurrences('2026-03-16T00:00:00Z', '2026-04-28T00:00:00Z')).toEqual([
      ['2026-03-16T08:00:00Z', SERIES],
      ['2026-03-23T08:00:00Z', SERIES],
      ['2026-03-31T12:00:00Z', SERIES],
      ['2026-04-06T07:00:00Z', SERIES],
      ['2026-04-13T08:00:00Z', NEW_SERIES],
      ['2026-04-20T08:00:00Z', NEW_SERIES],
      ['2026-04-27T08:00:00Z', NEW_SERIES],
    ]);
    // Two series: the old one ends before the split, the new one starts with it.
    const series = await api.series();
    expect(series.map((event) => event.title).sort()).toEqual([SERIES, NEW_SERIES]);
    expect(series.find((event) => event.title === SERIES)!.rrule).toMatch(/UNTIL=20260413T/);
    expect(series.find((event) => event.title === NEW_SERIES)!.rrule).not.toMatch(/UNTIL/);
  });

  test('5. the task overlay shows due tasks in the week view; "Create note for event" links the two', async () => {
    const api = await apiOf(page.request);
    await api.task({ title: 'Pack swim bag', list: 'NEXT', dueDate: '2026-04-21' });

    await page.goto('/calendar?view=week&date=2026-04-20');
    const toggle = page.getByRole('checkbox', { name: 'Due tasks and ticklers' });
    await toggle.check();
    const mark = page
      .locator('.app-calendar [data-date="2026-04-21"]')
      .locator('.app-task-mark', { hasText: 'Pack swim bag' });
    await expect(mark).toHaveCount(1);
    await expect(mark).toHaveClass(/app-task-due/);

    // A note for the occurrence of 20 April.
    await eventsOn(page, '2026-04-20', NEW_SERIES).click();
    const dialog = page.getByRole('dialog', { name: 'Edit event' });
    await expect(dialog.getByLabel('Title')).toHaveValue(NEW_SERIES);
    await dialog.getByRole('button', { name: 'Create note for event' }).click();
    const noteTitle = `${NEW_SERIES} (2026-04-20)`;
    const noteLink = dialog
      .getByRole('region', { name: /Linked items/ })
      .getByRole('link', { name: noteTitle });
    await expect(noteLink).toBeVisible();

    // The note links back to the event.
    await noteLink.click();
    await expect(page).toHaveURL(/\/notes\//);
    const eventLink = page
      .getByRole('region', { name: /Linked items/ })
      .locator('a[href^="/calendar/events/"]', { hasText: NEW_SERIES });
    await expect(eventLink).toHaveCount(1);
    await eventLink.click();
    await expect(page).toHaveURL(/\/calendar/);
    const reopened = page.getByRole('dialog', { name: 'Edit event' });
    await expect(reopened.getByLabel('Title')).toHaveValue(NEW_SERIES);
    await expect(
      reopened.getByRole('region', { name: /Linked items/ }).getByRole('link', { name: noteTitle }),
    ).toBeVisible();
    await reopened.getByRole('button', { name: 'Cancel' }).click();
  });
});

/** The events titled exactly `title` in the column (time grid) or cell (month) of `day`. */
function eventsOn(page: Page, day: string, title: string): Locator {
  return page
    .locator(`.app-calendar [data-date="${day}"]`)
    .locator('.app-event', { hasText: new RegExp(`${escape(title)}(?! \\()`) });
}

async function setTimes(
  dialog: Locator,
  startDate: string,
  startTime: string,
  endDate: string,
  endTime: string,
): Promise<void> {
  await dialog.getByLabel('Starts').fill(startDate);
  await dialog.getByLabel('Start time').fill(startTime);
  await dialog.getByLabel('Ends').fill(endDate);
  await dialog.getByLabel('End time').fill(endTime);
}

/** Answers the "Change a repeating event" prompt. */
async function scope(page: Page, choice: string): Promise<void> {
  const prompt = page.getByRole('dialog', { name: 'Change a repeating event' });
  await prompt.getByRole('button', { name: choice }).click();
  await expect(prompt).toBeHidden();
}

function escape(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

async function apiOf(request: APIRequestContext) {
  // Read the cookie before every change: the server may send a new token with any answer.
  const headers = async (extra: Record<string, string> = {}): Promise<Record<string, string>> => {
    const xsrf = (await request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN');
    expect(xsrf, 'XSRF-TOKEN cookie').toBeDefined();
    return { 'X-XSRF-TOKEN': xsrf!.value, ...extra };
  };
  interface Occurrence {
    seriesId?: string;
    start: string;
    title: string;
  }
  const occurrences = async (from: string, to: string): Promise<Occurrence[]> => {
    const response = await request.get('/api/v1/calendar/events', { params: { from, to } });
    expect(response.ok(), 'list occurrences').toBe(true);
    return ((await response.json()) as { items: Occurrence[] }).items;
  };
  return {
    async settings(patch: Record<string, unknown>): Promise<void> {
      const current = await request.get('/api/v1/settings');
      expect(current.ok(), 'get settings').toBe(true);
      const response = await request.patch('/api/v1/settings', {
        headers: await headers({ 'If-Match': current.headers()['etag'] }),
        data: patch,
      });
      expect(response.ok(), 'patch settings').toBe(true);
    },
    async task(data: Record<string, unknown>): Promise<void> {
      const response = await request.post('/api/v1/tasks', { headers: await headers(), data });
      expect(response.status(), 'create task').toBe(201);
    },
    /** `[start as UTC instant, title]` of the occurrences in [from, to), by start. */
    async occurrences(from: string, to: string): Promise<[string, string][]> {
      return (await occurrences(from, to))
        .map((item): [string, string] => [
          new Date(item.start).toISOString().replace('.000Z', 'Z'),
          item.title,
        ])
        .sort(([a], [b]) => a.localeCompare(b));
    },
    /** The series the occurrences of March to May 2026 belong to. */
    async series(): Promise<{ title: string; rrule: string }[]> {
      const ids = new Set(
        (await occurrences('2026-03-01T00:00:00Z', '2026-06-01T00:00:00Z'))
          .map((o) => o.seriesId)
          .filter((id): id is string => !!id),
      );
      const series = [];
      for (const id of ids) {
        const response = await request.get(`/api/v1/calendar/events/${id}`);
        expect(response.ok(), 'get event').toBe(true);
        const { title, rrule } = (await response.json()) as { title: string; rrule: string };
        series.push({ title, rrule });
      }
      return series;
    },
  };
}
