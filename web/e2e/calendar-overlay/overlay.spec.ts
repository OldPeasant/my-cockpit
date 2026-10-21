import { APIRequestContext, Page } from '@playwright/test';
import { expect, test } from '../support/fixtures';

/**
 * SPEC.md §6.3 tasks in the calendar: the optional overlay shows due tasks and ticklers in the week
 * view, and an event's dialog creates a note for it that is linked both ways (the event lists the
 * note, the note lists the event, and the event's link leads back to it).
 *
 * The dates are years ahead, so the tickler is still to come whatever the server clock says.
 */
test.describe('calendar task overlay and links', () => {
  test('shows due tasks and ticklers in the week view', async ({ loggedIn: page }) => {
    const run = Date.now().toString(36);
    const api = await apiOf(page.request);
    await api.settings({ timeZone: 'Europe/Zurich', weekStart: 'MONDAY' });
    const due = `Send minutes ${run}`;
    const timed = `Call the printer ${run}`;
    const tickler = `Renew passport ${run}`;
    await api.task({ title: due, list: 'NEXT', dueDate: '2030-05-07' });
    await api.task({ title: timed, list: 'NEXT', dueDate: '2030-05-08', dueTime: '14:00' });
    const ticklered = await api.task({ title: tickler, list: 'NEXT' });
    await api.move(ticklered, { list: 'SOMEDAY', ticklerDate: '2030-05-09' });
    // Not in the shown week.
    await api.task({ title: `Later ${run}`, list: 'NEXT', dueDate: '2030-05-20' });

    await page.goto('/calendar?view=week&date=2030-05-08');
    const toggle = page.getByRole('checkbox', { name: 'Due tasks and ticklers' });
    await expect(toggle).toBeVisible();
    if (await toggle.isChecked()) await toggle.uncheck();
    await expect(page.locator('.app-task-mark', { hasText: run })).toHaveCount(0);

    await toggle.check();
    await expect(marksOn(page, '2030-05-07', due)).toHaveCount(1);
    await expect(marksOn(page, '2030-05-07', due)).toHaveClass(/app-task-due/);
    await expect(marksOn(page, '2030-05-08', timed)).toContainText('2:00');
    await expect(marksOn(page, '2030-05-09', tickler)).toHaveClass(/app-task-tickler/);
    await expect(page.locator('.app-task-mark', { hasText: `Later ${run}` })).toHaveCount(0);

    // The toggle is remembered.
    await page.reload();
    await expect(toggle).toBeChecked();
    await expect(marksOn(page, '2030-05-07', due)).toHaveCount(1);

    // Not in the month view.
    await page.goto('/calendar?view=month&date=2030-05-08');
    await expect(page.locator('.app-calendar [data-date="2030-05-07"]').first()).toBeVisible();
    await expect(page.getByText('Shown in the day and week views.')).toBeVisible();
    await expect(page.locator('.app-task-mark', { hasText: run })).toHaveCount(0);
    await page.goto('/calendar?view=week&date=2030-05-08');
    await expect(marksOn(page, '2030-05-07', due)).toHaveCount(1);

    // A task opens its detail.
    await marksOn(page, '2030-05-07', due).click();
    await expect(page).toHaveURL(/\/tasks\//);
    await page.goBack();

    await toggle.uncheck();
    await expect(page.locator('.app-task-mark', { hasText: run })).toHaveCount(0);
  });

  test('creates a note for an event, linked both ways', async ({ loggedIn: page }) => {
    const run = Date.now().toString(36);
    const api = await apiOf(page.request);
    await api.settings({ timeZone: 'Europe/Zurich', weekStart: 'MONDAY' });
    const calendar = await api.calendar(`Work ${run}`, '#1565c0');
    const title = `Retro ${run}`;
    await api.event({
      calendarId: calendar,
      title,
      start: '2030-05-08T10:00:00+02:00',
      end: '2030-05-08T11:00:00+02:00',
      timeZone: 'Europe/Zurich',
    });

    await page.goto('/calendar?view=week&date=2030-05-08');
    await page.locator('.app-calendar .app-event', { hasText: title }).click();
    const dialog = page.getByRole('dialog', { name: 'Edit event' });
    await expect(dialog.getByLabel('Title')).toHaveValue(title);
    const eventLinks = dialog.getByRole('region', { name: /Linked items/ });
    await dialog.getByRole('button', { name: 'Create note for event' }).click();

    // The event lists the new note.
    const noteTitle = `${title} (2030-05-08)`;
    const noteLink = eventLinks.getByRole('link', { name: new RegExp(escape(noteTitle)) });
    await expect(noteLink).toBeVisible();

    // The note lists the event (following the link closed the dialog).
    await noteLink.click();
    await expect(page).toHaveURL(/\/notes\//);
    await expect(dialog).toBeHidden();
    const noteLinks = page.getByRole('region', { name: /Linked items/ });
    const eventLink = noteLinks.locator('a[href^="/calendar/events/"]', { hasText: title });
    await expect(eventLink).toHaveCount(1);

    // The event's link opens it in the calendar, at its date.
    await eventLink.click();
    await expect(page).toHaveURL(/\/calendar/);
    const reopened = page.getByRole('dialog', { name: 'Edit event' });
    await expect(reopened.getByLabel('Title')).toHaveValue(title);
    await expect(
      reopened
        .getByRole('region', { name: /Linked items/ })
        .getByRole('link', { name: new RegExp(escape(noteTitle)) }),
    ).toBeVisible();
    await reopened.getByRole('button', { name: 'Cancel' }).click();
    await expect(page).toHaveURL(/date=2030-05-0/);
  });
});

/** The overlaid tasks titled `title` in the column (or all-day cell) of `day`. */
function marksOn(page: Page, day: string, title: string) {
  return page
    .locator(`.app-calendar [data-date="${day}"]`)
    .locator('.app-task-mark', { hasText: title });
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
    /** Creates a task, returns its id. */
    async task(data: Record<string, unknown>): Promise<string> {
      const response = await request.post('/api/v1/tasks', { headers: await headers(), data });
      expect(response.status(), 'create task').toBe(201);
      return ((await response.json()) as { id: string }).id;
    },
    async move(id: string, data: Record<string, unknown>): Promise<void> {
      const response = await request.post(`/api/v1/tasks/${id}/move`, {
        headers: await headers(),
        data,
      });
      expect(response.ok(), 'move task').toBe(true);
    },
  };
}
