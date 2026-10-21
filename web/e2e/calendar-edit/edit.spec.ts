import { APIRequestContext, Locator, Page } from '@playwright/test';
import { expect, test } from '../support/fixtures';

/**
 * SPEC.md §6.3 event editing: dragging one occurrence of a weekly event to another day and time asks
 * which occurrences the move is for; "Only this event" moves that one and leaves the others of the
 * series where they were. The browser runs in another zone than the settings (Europe/Zurich), so the
 * moved time must be taken from the calendar, not the browser clock.
 */
test.describe('calendar editing', () => {
  test('moves one occurrence of a weekly event', async ({ browser, sessionState }) => {
    const context = await browser.newContext({
      storageState: sessionState,
      timezoneId: 'America/New_York',
    });
    const page = await context.newPage();
    try {
      const run = Date.now().toString(36);
      const api = await apiOf(page.request);
      await api.settings({ timeZone: 'Europe/Zurich', weekStart: 'MONDAY' });
      const calendar = await api.calendar(`Team ${run}`, '#1565c0');
      const title = `Standup ${run}`;
      // Mondays 09:00–10:00 Zurich time (CEST, +02:00), from Monday 2026-04-27.
      await api.event({
        calendarId: calendar,
        title,
        start: '2026-04-27T09:00:00+02:00',
        end: '2026-04-27T10:00:00+02:00',
        timeZone: 'Europe/Zurich',
        rrule: 'FREQ=WEEKLY;BYDAY=MO',
      });

      await page.goto('/calendar?view=week&date=2026-05-06');
      const monday = eventsOn(page, '2026-05-04', title);
      await expect(monday).toHaveCount(1);
      await expect(monday).toContainText('9:00 – 10:00');

      // Drag Monday's occurrence to Wednesday, 14:00.
      await drag(page, monday, '2026-05-06', '14:00:00');

      const prompt = page.getByRole('dialog', { name: 'Change a repeating event' });
      await expect(prompt).toBeVisible();
      await expect(prompt.getByRole('button', { name: 'Only this event' })).toBeVisible();
      await expect(prompt.getByRole('button', { name: 'This and following events' })).toBeVisible();
      await expect(prompt.getByRole('button', { name: 'All events in the series' })).toBeVisible();
      await prompt.getByRole('button', { name: 'Only this event' }).click();
      await expect(prompt).toBeHidden();

      // The occurrence is on Wednesday at 14:00, Monday is empty.
      const wednesday = eventsOn(page, '2026-05-06', title);
      await expect(wednesday).toHaveCount(1);
      await expect(wednesday).toContainText('2:00 – 3:00');
      await expect(monday).toHaveCount(0);

      // The server has the move, and only for this occurrence.
      const starts = await api.occurrenceStarts(
        calendar,
        '2026-04-27T00:00:00Z',
        '2026-05-18T00:00:00Z',
      );
      expect(starts).toEqual([
        '2026-04-27T07:00:00Z',
        '2026-05-06T12:00:00Z',
        '2026-05-11T07:00:00Z',
      ]);

      // After a reload the other weeks still show the series on Monday at 09:00.
      await page.reload();
      await expect(eventsOn(page, '2026-05-06', title)).toContainText('2:00 – 3:00');
      await page.getByRole('button', { name: 'Next week' }).click();
      await expect(page).toHaveURL(/date=2026-05-11/);
      await expect(eventsOn(page, '2026-05-11', title)).toContainText('9:00 – 10:00');
      await page.getByRole('button', { name: 'Previous week' }).click();
      await page.getByRole('button', { name: 'Previous week' }).click();
      await expect(page).toHaveURL(/date=2026-04-27/);
      await expect(eventsOn(page, '2026-04-27', title)).toContainText('9:00 – 10:00');

      // The moved occurrence opens in the dialog with its new time.
      await page.getByRole('button', { name: 'Next week' }).click();
      await eventsOn(page, '2026-05-06', title).click();
      const dialog = page.getByRole('dialog', { name: 'Edit event' });
      await expect(dialog.getByLabel('Title')).toHaveValue(title);
      await expect(dialog.getByLabel('Start time')).toHaveValue('14:00');
      await expect(dialog.getByLabel('Time zone')).toHaveValue('Europe/Zurich');
      await dialog.getByRole('button', { name: 'Cancel' }).click();
      await expect(dialog).toBeHidden();
    } finally {
      await context.close();
    }
  });
});

/** The events titled `title` in the column of `day`. */
function eventsOn(page: Page, day: string, title: string): Locator {
  return page
    .locator(`.app-calendar [data-date="${day}"]`)
    .locator('.app-event', { hasText: title });
}

/** Drags `event` by its top edge to `time` (a slot, `HH:mm:ss`) in the column of `day`. */
async function drag(page: Page, event: Locator, day: string, time: string): Promise<void> {
  const box = (await event.boundingBox())!;
  const grab = { x: box.x + box.width / 2, y: box.y + 4 };
  // The day's column of the time grid (the tallest element with its date; the header is short).
  const columns = await page.locator(`.app-calendar [data-date="${day}"]`).all();
  let column = (await columns[0].boundingBox())!;
  for (const candidate of columns) {
    const candidateBox = await candidate.boundingBox();
    if (candidateBox && candidateBox.height > column.height) column = candidateBox;
  }
  const slot = (await page.locator(`.app-calendar [data-time="${time}"]`).first().boundingBox())!;
  const target = { x: column.x + column.width / 2, y: slot.y + 4 };
  await page.mouse.move(grab.x, grab.y);
  await page.mouse.down();
  await page.mouse.move(grab.x + 5, grab.y + 5, { steps: 5 });
  await page.mouse.move(target.x, target.y, { steps: 20 });
  await page.mouse.up();
}

async function apiOf(request: APIRequestContext) {
  // Read the cookie before every change: the server may send a new token with any answer.
  const headers = async (extra: Record<string, string> = {}): Promise<Record<string, string>> => {
    const xsrf = (await request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN');
    expect(xsrf, 'XSRF-TOKEN cookie').toBeDefined();
    return { 'X-XSRF-TOKEN': xsrf!.value, ...extra };
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
    /** Creates a local calendar, returns its id. */
    async calendar(name: string, color: string): Promise<string> {
      const response = await request.post('/api/v1/calendar/calendars', {
        headers: await headers(),
        data: { name, color },
      });
      expect(response.status(), 'create calendar').toBe(201);
      return ((await response.json()) as { id: string }).id;
    },
    async event(data: Record<string, unknown>): Promise<void> {
      const response = await request.post('/api/v1/calendar/events', {
        headers: await headers(),
        data,
      });
      expect(response.status(), 'create event').toBe(201);
    },
    /** The starts of the occurrences of `calendarId` in [from, to), as UTC instants. */
    async occurrenceStarts(calendarId: string, from: string, to: string): Promise<string[]> {
      const response = await request.get('/api/v1/calendar/events', {
        params: { from, to, calendarIds: calendarId },
      });
      expect(response.ok(), 'list occurrences').toBe(true);
      const { items } = (await response.json()) as { items: { start: string }[] };
      return items.map((item) => new Date(item.start).toISOString().replace('.000Z', 'Z')).sort();
    },
  };
}
