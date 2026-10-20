import { APIRequestContext, Page } from '@playwright/test';
import { expect, test } from '../support/fixtures';

/**
 * SPEC.md §6.3 calendar views: a weekly event shows at the same wall-clock time in the time zone of the
 * settings (Europe/Zurich) on both sides of the DST change (Sunday 2026-03-29, 02:00 → 03:00), also when the
 * browser runs in another zone, in the week, day, month and agenda views; hiding its calendar hides it.
 */
test.describe('calendar views', () => {
  test('weekly event shows correctly across the DST week', async ({ browser, sessionState }) => {
    // The browser is in New York (DST already on 2026-03-08): only the settings' zone gives 09:00.
    const context = await browser.newContext({
      storageState: sessionState,
      timezoneId: 'America/New_York',
    });
    const page = await context.newPage();
    try {
      const run = Date.now().toString(36);
      const api = await apiOf(page.request);
      await api.settings({ timeZone: 'Europe/Zurich', weekStart: 'MONDAY' });
      const calendar = await api.calendar(`Family ${run}`, '#2e7d32');
      const title = `Swim practice ${run}`;
      // Mondays and Sundays 09:00–10:00 Zurich time, from Monday 2026-03-16 (CET, +01:00).
      await api.event({
        calendarId: calendar,
        title,
        start: '2026-03-16T09:00:00+01:00',
        end: '2026-03-16T10:00:00+01:00',
        timeZone: 'Europe/Zurich',
        rrule: 'FREQ=WEEKLY;BYDAY=MO,SU',
      });

      // The DST week (weeks start on Monday): Mon 23 is CET, Sun 29 is CEST.
      await page.goto('/calendar?view=week&date=2026-03-25');
      const week = page.locator('.app-calendar');
      await expect(page.getByRole('heading', { name: 'Calendar', level: 1 })).toBeVisible();
      const events = week.locator('.app-event', { hasText: title });
      await expect(events).toHaveCount(2);
      for (const day of ['2026-03-23', '2026-03-29']) {
        await expect(eventsOn(page, day, title), `event on ${day}`).toHaveCount(1);
        await expect(eventsOn(page, day, title)).toContainText('9:00 – 10:00');
        expect(await startHour(page, day, title), `starts at 09:00 on ${day}`).toBe(9);
      }
      // Both days place the event at the same height: same wall-clock time on both sides of the change.
      const [monday, sunday] = await Promise.all(
        ['2026-03-23', '2026-03-29'].map((day) => eventsOn(page, day, title).boundingBox()),
      );
      expect(Math.abs(monday!.y - sunday!.y)).toBeLessThan(2);
      expect(Math.abs(monday!.height - sunday!.height)).toBeLessThan(2);

      // The week after the change (CEST): Monday 30 still at 09:00.
      await page.getByRole('button', { name: 'Next week' }).click();
      await expect(page).toHaveURL(/date=2026-03-30/);
      await expect(eventsOn(page, '2026-03-30', title)).toHaveCount(1);
      expect(await startHour(page, '2026-03-30', title)).toBe(9);

      // Day view of the DST Sunday.
      await page.goto('/calendar?view=day&date=2026-03-29');
      await expect(eventsOn(page, '2026-03-29', title)).toHaveCount(1);
      expect(await startHour(page, '2026-03-29', title)).toBe(9);

      // Month view: every Monday and Sunday of March from the 16th (16, 22, 23, 29, 30).
      await page.goto('/calendar?view=month&date=2026-03-01');
      for (const day of ['2026-03-16', '2026-03-22', '2026-03-23', '2026-03-29', '2026-03-30']) {
        await expect(eventsOn(page, day, title), `month: ${day}`).toHaveCount(1);
      }

      // Agenda of the DST week: both occurrences at 9am.
      await page.goto('/calendar?view=agenda&date=2026-03-25');
      const agenda = page.locator('.app-calendar .app-event', { hasText: title });
      await expect(agenda).toHaveCount(2);
      for (const row of await agenda.all()) await expect(row).toContainText('9:00 – 10:00am');

      // Hiding the calendar hides its events; showing it brings them back (also after a reload).
      const toggle = page.getByRole('checkbox', { name: `Family ${run}` });
      await toggle.uncheck();
      await expect(agenda).toHaveCount(0);
      await page.reload();
      await expect(page.getByRole('checkbox', { name: `Family ${run}` })).not.toBeChecked();
      await expect(agenda).toHaveCount(0);
      await page.getByRole('checkbox', { name: `Family ${run}` }).check();
      await expect(agenda).toHaveCount(2);

      // Live update: an event created elsewhere appears without a reload (SSE).
      const live = `Dentist ${run}`;
      await api.event({
        calendarId: calendar,
        title: live,
        start: '2026-03-26T14:00:00+01:00',
        end: '2026-03-26T15:00:00+01:00',
        timeZone: 'Europe/Zurich',
      });
      await expect(page.locator('.app-calendar .app-event', { hasText: live })).toContainText(
        '2:00 – 3:00pm',
      );
    } finally {
      await context.close();
    }
  });
});

/** The events titled `title` in the column (time grid) or cell (month) of `day`. */
function eventsOn(page: Page, day: string, title: string) {
  return page
    .locator(`.app-calendar [data-date="${day}"]`)
    .locator('.app-event', { hasText: title });
}

/** The hour of the time-grid slot at the top edge of the event titled `title` on `day`. */
async function startHour(page: Page, day: string, title: string): Promise<number> {
  const event = await eventsOn(page, day, title).boundingBox();
  const slots = page.locator('.app-calendar [data-time]');
  const times = await slots.evaluateAll((elements) =>
    elements.map((element) => ({
      time: element.getAttribute('data-time')!,
      top: element.getBoundingClientRect().top,
    })),
  );
  // The slot whose top is closest to the event's top edge.
  const slot = times.reduce((best, slot) =>
    Math.abs(slot.top - event!.y) < Math.abs(best.top - event!.y) ? slot : best,
  );
  return Number(slot.time.slice(0, 2));
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
  };
}
