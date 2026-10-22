import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { HabitResponse } from '../../api/generated';
import { NotificationService, provideHttp } from '../../core/http';
import HabitsPage from './habits-page';
import { HabitsService } from './habits.service';
import { habit } from './testing';

const LIST = '/api/v1/habits';
const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

const READ = habit('0192f0c4-0000-7000-8000-000000000001', {
  name: 'Read 20 pages',
  stats: { currentStreak: 4, longestStreak: 12, completionRateLast30Days: 0.8 },
});
const RUN = habit('0192f0c4-0000-7000-8000-000000000002', {
  name: 'Run',
  schedule: 'TIMES_PER_WEEK',
  timesPerWeek: 3,
  stats: {
    dueToday: false,
    streakUnit: 'WEEKS',
    currentStreak: 1,
    longestStreak: 3,
    completionRateLast30Days: undefined,
  },
});
const WATER = habit('0192f0c4-0000-7000-8000-000000000003', {
  name: 'Water',
  targetCount: 3,
  stats: { todayCount: 1 },
});

describe('HabitsPage', () => {
  let fixture: ComponentFixture<HabitsPage>;
  let root: HTMLElement;
  let http: HttpTestingController;
  let notifications: { error: ReturnType<typeof vi.fn>; info: ReturnType<typeof vi.fn> };

  async function create(habits: HabitResponse[] = [READ, RUN, WATER]): Promise<void> {
    notifications = { error: vi.fn(), info: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: notifications },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(HabitsPage);
    root = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    await flushList(habits);
  }

  async function flushList(habits: HabitResponse[], archived = false): Promise<void> {
    http
      .expectOne(
        (r) => r.method === 'GET' && r.url === LIST && r.params.get('archived') === `${archived}`,
      )
      .flush({ items: habits });
    await settle();
  }

  async function settle(): Promise<void> {
    await tick();
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function row(h: HabitResponse): HTMLElement {
    return root.querySelector(`.habit[data-id="${h.id}"]`) as HTMLElement;
  }

  function checkButton(h: HabitResponse): HTMLButtonElement {
    return row(h).querySelector('.check') as HTMLButtonElement;
  }

  function text(el: Element | null): string {
    return el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  }

  afterEach(() => http.verify());

  it('groups the habits by due today and shows streaks and the 30-day completion', async () => {
    await create();

    expect(text(root.querySelector('.summary'))).toBe('0 of 2 done today');
    const groups = [...root.querySelectorAll('.group')].map(text);
    expect(groups).toEqual(['Due today', 'Not due today']);
    expect(text(row(READ).querySelector('.streak dd'))).toBe('4 days');
    expect(row(READ).querySelector('.streak')?.classList).toContain('hot');
    expect(text(row(READ).querySelector('.best dd'))).toBe('12 days');
    expect(text(row(READ).querySelector('.rate dd'))).toBe('80 %');
    expect(text(row(RUN).querySelector('.streak dd'))).toBe('1 week');
    expect(text(row(RUN).querySelector('.rate dd'))).toBe('–');
    expect(text(row(RUN).querySelector('.schedule'))).toBe('3× a week');
    expect(text(checkButton(WATER))).toBe('1/3');
  });

  it('checks a habit with one tap and shows the new streak', async () => {
    await create();
    const button = checkButton(READ);
    expect(button.getAttribute('aria-pressed')).toBe('false');
    expect(button.getAttribute('aria-label')).toBe('Check “Read 20 pages” for today');

    button.click();
    fixture.detectChanges();
    expect(checkButton(READ).disabled).toBe(true);
    const req = http.expectOne(
      (r) => r.method === 'PUT' && r.url === `${LIST}/${READ.id}/checks/2026-10-22`,
    );
    expect(req.request.body).toEqual({ count: 1 });
    req.flush({
      ...READ,
      stats: { ...READ.stats, todayCount: 1, doneToday: true, currentStreak: 5 },
    });
    await settle();

    expect(checkButton(READ).getAttribute('aria-pressed')).toBe('true');
    expect(checkButton(READ).getAttribute('aria-label')).toBe('Uncheck “Read 20 pages” for today');
    expect(row(READ).classList).toContain('done');
    expect(text(row(READ).querySelector('.streak dd'))).toBe('5 days');
    expect(text(root.querySelector('.summary'))).toBe('1 of 2 done today');
  });

  it('unchecks a done habit with the next tap', async () => {
    const done = { ...READ, stats: { ...READ.stats, todayCount: 1, doneToday: true } };
    await create([done]);

    checkButton(done).click();
    const req = http.expectOne((r) => r.method === 'PUT');
    expect(req.request.body).toEqual({ count: 0 });
    req.flush(READ);
    await settle();

    expect(checkButton(READ).getAttribute('aria-pressed')).toBe('false');
  });

  it('counts a habit with a target up one per tap', async () => {
    await create([WATER]);

    checkButton(WATER).click();
    const req = http.expectOne((r) => r.method === 'PUT');
    expect(req.request.body).toEqual({ count: 2 });
    req.flush({ ...WATER, stats: { ...WATER.stats, todayCount: 2 } });
    await settle();

    expect(text(checkButton(WATER))).toBe('2/3');
    expect(checkButton(WATER).getAttribute('aria-label')).toBe('“Water”: 2 of 3 today');
  });

  it('shows a failed check next to the habit and keeps its state', async () => {
    await create();

    checkButton(READ).click();
    http
      .expectOne((r) => r.method === 'PUT')
      .flush(
        { title: 'Service Unavailable', status: 503, detail: 'Try again later.' },
        { status: 503, statusText: 'Service Unavailable' },
      );
    await settle();

    expect(text(row(READ).querySelector('.error'))).toBe(
      '“Read 20 pages” could not be changed: Try again later.',
    );
    expect(checkButton(READ).getAttribute('aria-pressed')).toBe('false');
    expect(checkButton(READ).disabled).toBe(false);
  });

  it('creates a habit with the form', async () => {
    await create([READ]);

    (root.querySelector('.new') as HTMLButtonElement).click();
    await settle();
    const form = root.querySelector('.new-form') as HTMLElement;
    const name = form.querySelector('.name input') as HTMLInputElement;
    name.value = 'Meditate';
    name.dispatchEvent(new Event('input'));
    (form.querySelector('.schedule-weekdays') as HTMLButtonElement).click();
    fixture.detectChanges();
    (form.querySelector('.save') as HTMLButtonElement).click();
    await settle();

    const req = http.expectOne((r) => r.method === 'POST' && r.url === LIST);
    expect(req.request.body).toEqual({
      name: 'Meditate',
      schedule: 'WEEKDAYS',
      targetCount: 1,
      weekdays: ['MONDAY', 'WEDNESDAY', 'FRIDAY'],
      reminderTime: null,
    });
    const meditate = habit('0192f0c4-0000-7000-8000-000000000009', { name: 'Meditate' });
    req.flush(meditate, { status: 201, statusText: 'Created' });
    await settle();

    expect(root.querySelector('.new-form')).toBeNull();
    expect([...root.querySelectorAll('.habit .name')].map(text)).toEqual([
      'Meditate',
      'Read 20 pages',
    ]);
    expect(notifications.info).toHaveBeenCalledWith('Habit added');
  });

  it('does not send a habit without a name', async () => {
    await create([]);
    expect(text(root.querySelector('.empty'))).toContain('No habits yet');

    (root.querySelector('.new') as HTMLButtonElement).click();
    await settle();
    (root.querySelector('.save') as HTMLButtonElement).click();
    await settle();

    expect(text(root.querySelector('.new-form .error'))).toBe('Give the habit a name.');
  });

  it('edits a habit with its version as If-Match', async () => {
    await create([READ]);

    (row(READ).querySelector('.edit') as HTMLButtonElement).click();
    await settle();
    const name = row(READ).querySelector('.name input') as HTMLInputElement;
    expect(name.value).toBe('Read 20 pages');
    name.value = 'Read 30 pages';
    name.dispatchEvent(new Event('input'));
    (row(READ).querySelector('.save') as HTMLButtonElement).click();
    await settle();

    const req = http.expectOne((r) => r.method === 'PATCH' && r.url === `${LIST}/${READ.id}`);
    expect(req.request.headers.get('If-Match')).toBe('"1"');
    expect(req.request.body).toMatchObject({ name: 'Read 30 pages', schedule: 'DAILY' });
    req.flush({ ...READ, name: 'Read 30 pages', version: 2 });
    await settle();

    expect(text(row(READ).querySelector('.name'))).toBe('Read 30 pages');
    expect(row(READ).querySelector('app-habit-form')).toBeNull();
  });

  it('archives a habit and shows the archived ones without a check', async () => {
    await create([READ]);

    (row(READ).querySelector('.archive') as HTMLButtonElement).click();
    const req = http.expectOne((r) => r.method === 'PATCH');
    expect(req.request.body).toEqual({ archived: true });
    req.flush({ ...READ, archived: true, version: 2 });
    await settle();
    expect(row(READ)).toBeNull();
    expect(notifications.info).toHaveBeenCalledWith('Archived “Read 20 pages”');

    (root.querySelector('.show-archived') as HTMLButtonElement).click();
    await settle();
    await flushList([{ ...READ, archived: true }], true);
    expect(row(READ).querySelector('.check')).toBeNull();
    expect(text(row(READ).querySelector('.archive'))).toBe('Unarchive');
  });

  it('offers to retry a failed load', async () => {
    notifications = { error: vi.fn(), info: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: notifications },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(HabitsPage);
    root = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    http
      .expectOne((r) => r.url === LIST)
      .flush({ title: 'Bad Gateway', status: 502 }, { status: 502, statusText: 'Bad Gateway' });
    await settle();

    expect(text(root.querySelector('.load-error p'))).toContain('could not be loaded');
    (root.querySelector('.load-error button') as HTMLButtonElement).click();
    await settle();
    await flushList([READ]);
    expect(row(READ)).not.toBeNull();
  });

  function problem(status: number, detail: string) {
    return [
      { title: 'Error', status, detail },
      { status, statusText: 'Error' },
    ] as const;
  }

  async function openEditAndType(h: HabitResponse, name: string): Promise<void> {
    (row(h).querySelector('.edit') as HTMLButtonElement).click();
    await settle();
    const input = row(h).querySelector('.name input') as HTMLInputElement;
    input.value = name;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  async function clickSave(h: HabitResponse): Promise<void> {
    (row(h).querySelector('.save') as HTMLButtonElement).click();
    await settle();
  }

  it('shows the loading state until the habits arrive', async () => {
    notifications = { error: vi.fn(), info: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: notifications },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(HabitsPage);
    root = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();

    expect(text(root.querySelector('.hint'))).toBe('Loading habits…');
    await flushList([READ]);
    expect(root.querySelector('.hint')).toBeNull();
  });

  it('shows a habit reminder next to its schedule', async () => {
    await create([{ ...READ, reminderTime: '07:30:00' }]);
    expect(text(row(READ).querySelector('.schedule'))).toBe('Every day , reminder at 07:30');
  });

  it('sends one check for a double tap', async () => {
    await create([READ]);

    checkButton(READ).click();
    checkButton(READ).click();

    const req = http.expectOne((r) => r.method === 'PUT');
    req.flush({ ...READ, stats: { ...READ.stats, todayCount: 1, doneToday: true } });
    await settle();
    expect(checkButton(READ).disabled).toBe(false);
  });

  it('reloads the habits after a check conflict and keeps the error on the row', async () => {
    await create([READ]);

    checkButton(READ).click();
    http.expectOne((r) => r.method === 'PUT').flush(...problem(409, 'Changed elsewhere.'));
    await settle();
    // The list stays on screen while it reloads.
    expect(root.querySelector('.hint')).toBeNull();
    expect(text(row(READ).querySelector('.error'))).toContain('Changed elsewhere.');

    const fresh = { ...READ, version: 2, stats: { ...READ.stats, todayCount: 1, doneToday: true } };
    await flushList([fresh]);
    expect(checkButton(READ).getAttribute('aria-pressed')).toBe('true');
    expect(text(row(READ).querySelector('.error'))).toBe(
      '“Read 20 pages” could not be changed: Changed elsewhere.',
    );
  });

  it('reloads the habits after a 412 on archive', async () => {
    await create([READ]);

    (row(READ).querySelector('.archive') as HTMLButtonElement).click();
    http.expectOne((r) => r.method === 'PATCH').flush(...problem(412, 'Outdated version.'));
    await settle();

    await flushList([{ ...READ, version: 3 }]);
    expect(text(row(READ).querySelector('.error'))).toContain('Outdated version.');
  });

  it('drops a habit that is gone (404) on check or archive, without an error', async () => {
    await create([READ, WATER]);

    checkButton(READ).click();
    http.expectOne((r) => r.method === 'PUT').flush(...problem(404, 'Not found.'));
    await settle();
    expect(row(READ)).toBeNull();

    (row(WATER).querySelector('.archive') as HTMLButtonElement).click();
    http.expectOne((r) => r.method === 'PATCH').flush(...problem(404, 'Not found.'));
    await settle();
    expect(row(WATER)).toBeNull();
    expect(root.querySelector('.error')).toBeNull();
    expect(notifications.info).not.toHaveBeenCalled();
  });

  it('shows a generic message for a failure that is not an API error', async () => {
    await create([READ]);
    vi.spyOn(TestBed.inject(HabitsService), 'setCheck').mockRejectedValue(new Error('boom'));

    checkButton(READ).click();
    await settle();

    expect(text(row(READ).querySelector('.error'))).toBe(
      '“Read 20 pages” could not be changed: Something went wrong.',
    );
  });

  it('keeps the edit form after a 412, reloads, and saves again with the new version', async () => {
    await create([READ]);
    await openEditAndType(READ, 'Read 30 pages');
    await clickSave(READ);

    http.expectOne((r) => r.method === 'PATCH').flush(...problem(412, 'Changed elsewhere.'));
    await settle();
    expect(text(row(READ).querySelector('app-habit-form .error'))).toBe(
      'The habit could not be saved: Changed elsewhere.',
    );

    await flushList([{ ...READ, version: 7, stats: { ...READ.stats, currentStreak: 9 } }]);
    const input = row(READ).querySelector('.name input') as HTMLInputElement;
    expect(input.value).toBe('Read 30 pages');

    await clickSave(READ);
    const retry = http.expectOne((r) => r.method === 'PATCH');
    expect(retry.request.headers.get('If-Match')).toBe('"7"');
    expect(retry.request.body).toMatchObject({ name: 'Read 30 pages' });
    retry.flush({ ...READ, name: 'Read 30 pages', version: 8 });
    await settle();
    expect(row(READ).querySelector('app-habit-form')).toBeNull();
    expect(text(row(READ).querySelector('.name'))).toBe('Read 30 pages');
  });

  it('keeps the form and its error when the reload after a 409 fails', async () => {
    await create([READ]);
    await openEditAndType(READ, 'Read 30 pages');
    await clickSave(READ);

    http.expectOne((r) => r.method === 'PATCH').flush(...problem(409, 'Changed elsewhere.'));
    await settle();
    http.expectOne((r) => r.method === 'GET').flush(...problem(502, 'Bad gateway.'));
    await settle();

    expect(row(READ).querySelector('app-habit-form')).not.toBeNull();
    expect(text(row(READ).querySelector('app-habit-form .error'))).toContain('Changed elsewhere.');
    expect(root.querySelector('.load-error')).toBeNull();
  });

  it('shows a failed create in the form without reloading', async () => {
    await create([READ]);
    (root.querySelector('.new') as HTMLButtonElement).click();
    await settle();
    const input = root.querySelector('.new-form .name input') as HTMLInputElement;
    input.value = 'Read 20 pages';
    input.dispatchEvent(new Event('input'));
    (root.querySelector('.new-form .save') as HTMLButtonElement).click();
    await settle();

    http.expectOne((r) => r.method === 'POST').flush(...problem(409, 'Name already used.'));
    await settle();

    expect(text(root.querySelector('.new-form .error'))).toBe(
      'The habit could not be saved: Name already used.',
    );
    expect(notifications.info).not.toHaveBeenCalled();
    // No reload for a conflict on create (http.verify() in afterEach).
  });

  it('disables the form while saving and closes it on cancel', async () => {
    await create([READ]);
    await openEditAndType(READ, 'Read 30 pages');
    await clickSave(READ);

    expect((row(READ).querySelector('.save') as HTMLButtonElement).disabled).toBe(true);
    expect((row(READ).querySelector('.cancel') as HTMLButtonElement).disabled).toBe(true);
    http.expectOne((r) => r.method === 'PATCH').flush(...problem(503, 'Try again later.'));
    await settle();

    (row(READ).querySelector('.cancel') as HTMLButtonElement).click();
    await settle();
    expect(row(READ).querySelector('app-habit-form')).toBeNull();
    expect(text(row(READ).querySelector('.name'))).toBe('Read 20 pages');

    // Opening the form again starts fresh, without the old error.
    (row(READ).querySelector('.edit') as HTMLButtonElement).click();
    await settle();
    expect(row(READ).querySelector('app-habit-form .error')).toBeNull();
    expect((row(READ).querySelector('.name input') as HTMLInputElement).value).toBe(
      'Read 20 pages',
    );
  });

  it('closes the new-habit form with Escape and shows the button again', async () => {
    await create([READ]);
    (root.querySelector('.new') as HTMLButtonElement).click();
    await settle();
    expect(root.querySelector('.new')).toBeNull();

    root
      .querySelector('.new-form form')
      ?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await settle();

    expect(root.querySelector('.new-form')).toBeNull();
    expect(root.querySelector('.new')).not.toBeNull();
  });

  it('switches between the active and archived habits', async () => {
    await create([READ]);
    (root.querySelector('.new') as HTMLButtonElement).click();
    await settle();

    (root.querySelector('.show-archived') as HTMLButtonElement).click();
    await settle();
    expect(root.querySelector('.new-form')).toBeNull();
    expect(text(root.querySelector('.hint'))).toBe('Loading habits…');
    await flushList([], true);

    expect(root.querySelector('.show-archived')?.getAttribute('aria-pressed')).toBe('true');
    expect(root.querySelector('.new')).toBeNull();
    expect(root.querySelector('.summary')).toBeNull();
    expect(text(root.querySelector('.empty'))).toContain('No archived habits');

    // The current view again: no request.
    (root.querySelector('.show-archived') as HTMLButtonElement).click();
    await settle();

    (root.querySelector('.show-active') as HTMLButtonElement).click();
    await settle();
    await flushList([READ]);
    expect(row(READ)).not.toBeNull();
    expect(root.querySelector('.new')).not.toBeNull();
  });

  it('unarchives a habit from the archived view', async () => {
    await create([]);
    (root.querySelector('.show-archived') as HTMLButtonElement).click();
    await settle();
    const archived = { ...READ, archived: true, version: 2 };
    await flushList([archived], true);
    expect(row(READ).querySelector('.edit')).toBeNull();

    (row(READ).querySelector('.archive') as HTMLButtonElement).click();
    const req = http.expectOne((r) => r.method === 'PATCH');
    expect(req.request.headers.get('If-Match')).toBe('"2"');
    expect(req.request.body).toEqual({ archived: false });
    req.flush({ ...READ, archived: false, version: 3 });
    await settle();

    expect(row(READ)).toBeNull();
    expect(notifications.info).toHaveBeenCalledWith('Moved “Read 20 pages” back to Habits');
  });

  it('ignores habits of an earlier view that arrive late', async () => {
    await create([READ]);
    (root.querySelector('.show-archived') as HTMLButtonElement).click();
    await settle();
    (root.querySelector('.show-active') as HTMLButtonElement).click();
    await settle();

    const [archivedReq, activeReq] = http.match((r) => r.method === 'GET');
    activeReq.flush({ items: [WATER] });
    await settle();
    archivedReq.flush({ items: [{ ...RUN, archived: true }] });
    await settle();

    expect(row(WATER)).not.toBeNull();
    expect(row(RUN)).toBeNull();
  });

  it('ignores a failed load of an earlier view that arrives late', async () => {
    await create([READ]);
    (root.querySelector('.show-archived') as HTMLButtonElement).click();
    await settle();
    (root.querySelector('.show-active') as HTMLButtonElement).click();
    await settle();

    const [archivedReq, activeReq] = http.match((r) => r.method === 'GET');
    activeReq.flush({ items: [READ] });
    await settle();
    archivedReq.flush(...problem(502, 'Bad gateway.'));
    await settle();

    expect(root.querySelector('.load-error')).toBeNull();
    expect(row(READ)).not.toBeNull();
  });

  it('shows only the "not due" group when nothing is due today', async () => {
    await create([RUN]);

    expect([...root.querySelectorAll('.group')].map(text)).toEqual(['Not due today']);
    expect(root.querySelector('.summary')).toBeNull();
  });

  it('keeps the form open when Escape is pressed while saving', async () => {
    await create([READ]);
    await openEditAndType(READ, 'Read 30 pages');
    await clickSave(READ);

    row(READ)
      .querySelector('app-habit-form form')
      ?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await settle();
    expect(row(READ).querySelector('app-habit-form')).not.toBeNull();

    http.expectOne((r) => r.method === 'PATCH').flush({ ...READ, name: 'Read 30 pages' });
    await settle();
    expect(row(READ).querySelector('app-habit-form')).toBeNull();
  });

  it('pre-fills the form with the days of a weekday habit', async () => {
    const gym = habit('0192f0c4-0000-7000-8000-000000000004', {
      name: 'Gym',
      schedule: 'WEEKDAYS',
      weekdays: ['TUESDAY', 'THURSDAY'],
    });
    await create([gym]);

    (row(gym).querySelector('.edit') as HTMLButtonElement).click();
    await settle();
    const pressedDays = [...row(gym).querySelectorAll('.day[aria-pressed="true"]')].map(text);
    expect(pressedDays).toEqual(['Tue', 'Thu']);
    expect(text(row(gym).querySelector('.save'))).toBe('Save changes');
  });

  it('ignores a conflict reload of the active habits that arrives after switching to archived', async () => {
    await create([READ]);
    await openEditAndType(READ, 'Read 30 pages');
    await clickSave(READ);
    http.expectOne((r) => r.method === 'PATCH').flush(...problem(412, 'Changed elsewhere.'));
    await settle();

    (root.querySelector('.show-archived') as HTMLButtonElement).click();
    await settle();
    const archivedReq = http.expectOne((r) => r.params.get('archived') === 'true');
    const reloadReq = http.expectOne((r) => r.params.get('archived') === 'false');
    archivedReq.flush({ items: [] });
    await settle();
    reloadReq.flush({ items: [{ ...READ, version: 2 }] });
    await settle();

    // The archived view must not show the active habit (it would offer "Archive" on it).
    expect(root.querySelector('.show-archived')?.getAttribute('aria-pressed')).toBe('true');
    expect(row(READ)).toBeNull();
    expect(text(root.querySelector('.empty'))).toContain('No archived habits');
  });

  it('uses the singular and plural of days and weeks for streaks', async () => {
    const one = { ...READ, stats: { ...READ.stats, currentStreak: 1, longestStreak: 1 } };
    const none = { ...RUN, stats: { ...RUN.stats, currentStreak: 0, longestStreak: 2 } };
    await create([one, none]);

    expect(text(row(READ).querySelector('.streak dd'))).toBe('1 day');
    expect(text(row(READ).querySelector('.best dd'))).toBe('1 day');
    expect(text(row(RUN).querySelector('.streak dd'))).toBe('0 weeks');
    expect(row(RUN).querySelector('.streak')?.classList).not.toContain('hot');
    expect(text(row(RUN).querySelector('.best dd'))).toBe('2 weeks');
  });

  function becomeVisible(state: DocumentVisibilityState = 'visible'): void {
    const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue(state);
    try {
      document.dispatchEvent(new Event('visibilitychange'));
    } finally {
      visibility.mockRestore();
    }
  }

  it('reloads the habits when the page becomes visible again, keeping the list meanwhile', async () => {
    await create([READ]);

    becomeVisible();
    await settle();
    // The list stays on screen while the new day's habits load.
    expect(root.querySelector('.hint')).toBeNull();
    expect(row(READ)).not.toBeNull();
    const nextDay = { ...READ, stats: { ...READ.stats, today: '2026-10-23', currentStreak: 5 } };
    await flushList([nextDay]);
    expect(text(row(READ).querySelector('.streak dd'))).toBe('5 days');

    // The next check goes to the new day.
    checkButton(READ).click();
    http
      .expectOne((r) => r.method === 'PUT' && r.url === `${LIST}/${READ.id}/checks/2026-10-23`)
      .flush({ ...nextDay, stats: { ...nextDay.stats, todayCount: 1, doneToday: true } });
    await settle();
  });

  it('does not reload when the page is hidden, a form is open or a check is running', async () => {
    await create([READ]);

    becomeVisible('hidden');
    await settle();

    await openEditAndType(READ, 'Read 30 pages');
    becomeVisible();
    await settle();
    (row(READ).querySelector('.cancel') as HTMLButtonElement).click();
    await settle();

    checkButton(READ).click();
    becomeVisible();
    await settle();
    http.expectOne((r) => r.method === 'PUT').flush(READ);
    await settle();
    // No GET besides the first load (http.verify() in afterEach).
  });

  it('does not reload while the habits are still loading', async () => {
    await create([READ]);
    (root.querySelector('.show-archived') as HTMLButtonElement).click();
    await settle();

    becomeVisible();
    await settle();
    await flushList([], true);
  });

  it('tries a failed load again when the page becomes visible', async () => {
    notifications = { error: vi.fn(), info: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: notifications },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(HabitsPage);
    root = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    http.expectOne((r) => r.url === LIST).flush(...problem(502, 'Bad gateway.'));
    await settle();
    expect(root.querySelector('.load-error')).not.toBeNull();

    becomeVisible();
    await settle();
    await flushList([READ]);
    expect(root.querySelector('.load-error')).toBeNull();
    expect(row(READ)).not.toBeNull();
  });

  it('stops listening for the page becoming visible when destroyed', async () => {
    await create([READ]);
    fixture.destroy();

    becomeVisible();
    await tick();
    // No reload (http.verify() in afterEach).
  });

  it('ignores a conflict reload that arrives after a newer load of the same view', async () => {
    await create([READ]);
    await openEditAndType(READ, 'Read 30 pages');
    await clickSave(READ);
    http.expectOne((r) => r.method === 'PATCH').flush(...problem(412, 'Changed elsewhere.'));
    await settle();

    // Away to the archived view and back: a newer load of the active habits.
    (root.querySelector('.show-archived') as HTMLButtonElement).click();
    await settle();
    (root.querySelector('.show-active') as HTMLButtonElement).click();
    await settle();
    const [reloadReq, archivedReq, activeReq] = http.match((r) => r.method === 'GET');
    archivedReq.flush({ items: [] });
    activeReq.flush({ items: [WATER] });
    await settle();
    reloadReq.flush({ items: [{ ...READ, version: 2 }] });
    await settle();

    expect(row(WATER)).not.toBeNull();
    expect(row(READ)).toBeNull();
  });

  it('keeps a check made while the reload on becoming visible is still pending', async () => {
    await create([READ]);

    becomeVisible();
    await settle();
    const reloadReq = http.expectOne((r) => r.method === 'GET');
    // The user taps before the reload answers; the check answers first.
    checkButton(READ).click();
    http
      .expectOne((r) => r.method === 'PUT')
      .flush({
        ...READ,
        stats: { ...READ.stats, todayCount: 1, doneToday: true, currentStreak: 5 },
      });
    await settle();
    expect(checkButton(READ).getAttribute('aria-pressed')).toBe('true');

    // The reload was answered before the check reached the server: it must not undo the check.
    reloadReq.flush({ items: [READ] });
    await settle();
    expect(checkButton(READ).getAttribute('aria-pressed')).toBe('true');
    expect(text(row(READ).querySelector('.streak dd'))).toBe('5 days');
  });

  it('keeps the typed values of an open edit form when a check on another habit gets a 412', async () => {
    await create([READ, WATER]);
    await openEditAndType(READ, 'Read 30 pages');

    checkButton(WATER).click();
    http.expectOne((r) => r.method === 'PUT').flush(...problem(412, 'Outdated version.'));
    await settle();
    await flushList([
      { ...READ, version: 4 },
      { ...WATER, version: 5, stats: { ...WATER.stats, todayCount: 2 } },
    ]);

    const input = row(READ).querySelector('.name input') as HTMLInputElement;
    expect(input.value).toBe('Read 30 pages');
    expect(text(row(WATER).querySelector('.error'))).toContain('Outdated version.');
    expect(checkButton(WATER).getAttribute('aria-label')).toContain('2 of 3 today');

    // The reloaded version is used for the next save of the open form.
    await clickSave(READ);
    const save = http.expectOne((r) => r.method === 'PATCH');
    expect(save.request.headers.get('If-Match')).toBe('"4"');
    save.flush({ ...READ, name: 'Read 30 pages', version: 5 });
    await settle();
  });

  it('drops the reload on becoming visible when the view is switched meanwhile', async () => {
    await create([READ]);

    becomeVisible();
    await settle();
    (root.querySelector('.show-archived') as HTMLButtonElement).click();
    await settle();
    const reloadReq = http.expectOne((r) => r.params.get('archived') === 'false');
    http.expectOne((r) => r.params.get('archived') === 'true').flush({ items: [] });
    await settle();
    reloadReq.flush({ items: [READ] });
    await settle();

    expect(row(READ)).toBeNull();
    expect(text(root.querySelector('.empty'))).toContain('No archived habits');
  });

  it('keeps the list when the reload on becoming visible fails', async () => {
    await create([READ]);

    becomeVisible();
    await settle();
    http.expectOne((r) => r.method === 'GET').flush(...problem(503, 'Try again later.'));
    await settle();

    expect(row(READ)).not.toBeNull();
    expect(root.querySelector('.load-error')).toBeNull();
    expect(checkButton(READ).disabled).toBe(false);
  });

  it('drops the reload on becoming visible when it answers while a check is still running', async () => {
    await create([WATER]);

    becomeVisible();
    await settle();
    const reloadReq = http.expectOne((r) => r.method === 'GET');
    checkButton(WATER).click();
    const put = http.expectOne((r) => r.method === 'PUT');
    expect(put.request.body).toEqual({ count: 2 });

    // The reload answers first, with a count read before the check reached the server.
    reloadReq.flush({ items: [{ ...WATER, stats: { ...WATER.stats, todayCount: 0 } }] });
    await settle();
    expect(text(checkButton(WATER))).toBe('1/3');

    put.flush({ ...WATER, stats: { ...WATER.stats, todayCount: 2 } });
    await settle();
    expect(text(checkButton(WATER))).toBe('2/3');

    // The next tap counts on from the check, not from the stale reload.
    checkButton(WATER).click();
    const next = http.expectOne((r) => r.method === 'PUT');
    expect(next.request.body).toEqual({ count: 3 });
    next.flush({ ...WATER, stats: { ...WATER.stats, todayCount: 3, doneToday: true } });
    await settle();
  });

  it('does not bring back a habit archived while the reload on becoming visible is pending', async () => {
    await create([READ, WATER]);

    becomeVisible();
    await settle();
    const reloadReq = http.expectOne((r) => r.method === 'GET');
    (row(READ).querySelector('.archive') as HTMLButtonElement).click();
    http.expectOne((r) => r.method === 'PATCH').flush({ ...READ, archived: true, version: 2 });
    await settle();
    expect(row(READ)).toBeNull();

    reloadReq.flush({ items: [READ, WATER] });
    await settle();
    expect(row(READ)).toBeNull();
    expect(row(WATER)).not.toBeNull();
  });

  it('keeps a saved edit when an earlier conflict reload answers after the save', async () => {
    await create([READ, WATER]);
    await openEditAndType(READ, 'Read 30 pages');

    // A conflict on another habit starts a quiet reload.
    checkButton(WATER).click();
    http.expectOne((r) => r.method === 'PUT').flush(...problem(409, 'Changed elsewhere.'));
    await settle();
    const reloadReq = http.expectOne((r) => r.method === 'GET');

    await clickSave(READ);
    http
      .expectOne((r) => r.method === 'PATCH')
      .flush({ ...READ, name: 'Read 30 pages', version: 2 });
    await settle();
    expect(text(row(READ).querySelector('.name'))).toBe('Read 30 pages');

    // The reload was read before the save: the saved name and version stay.
    reloadReq.flush({ items: [READ, WATER] });
    await settle();
    expect(text(row(READ).querySelector('.name'))).toBe('Read 30 pages');
    expect(text(row(WATER).querySelector('.error'))).toContain('Changed elsewhere.');

    (row(READ).querySelector('.edit') as HTMLButtonElement).click();
    await settle();
    await clickSave(READ);
    const resave = http.expectOne((r) => r.method === 'PATCH');
    expect(resave.request.headers.get('If-Match')).toBe('"2"');
    resave.flush({ ...READ, name: 'Read 30 pages', version: 3 });
    await settle();
  });

  it('keeps a created habit when an earlier conflict reload answers after the create', async () => {
    await create([READ]);
    (root.querySelector('.new') as HTMLButtonElement).click();
    await settle();

    checkButton(READ).click();
    http.expectOne((r) => r.method === 'PUT').flush(...problem(412, 'Outdated version.'));
    await settle();
    const reloadReq = http.expectOne((r) => r.method === 'GET');

    const input = root.querySelector('.new-form .name input') as HTMLInputElement;
    input.value = 'Meditate';
    input.dispatchEvent(new Event('input'));
    (root.querySelector('.new-form .save') as HTMLButtonElement).click();
    await settle();
    const meditate = habit('0192f0c4-0000-7000-8000-000000000009', { name: 'Meditate' });
    http
      .expectOne((r) => r.method === 'POST')
      .flush(meditate, { status: 201, statusText: 'Created' });
    await settle();

    reloadReq.flush({ items: [READ] });
    await settle();
    expect([...root.querySelectorAll('.habit .name')].map(text)).toEqual([
      'Meditate',
      'Read 20 pages',
    ]);
  });
});
