import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { HabitResponse } from '../../api/generated';
import { NotificationService, provideHttp } from '../../core/http';
import HabitsPage from './habits-page';
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
});
