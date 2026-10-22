import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { HabitResponse } from '../../../api/generated';
import { provideHttp } from '../../../core/http';
import { habit } from '../../habits/testing';
import { TodayHabits } from './today-habits';

const LIST = '/api/v1/habits';
const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

const READ = habit('0192f0c4-0000-7000-8000-000000000001', {
  name: 'Read 20 pages',
  stats: { currentStreak: 4 },
});
const RUN = habit('0192f0c4-0000-7000-8000-000000000002', {
  name: 'Run',
  schedule: 'TIMES_PER_WEEK',
  timesPerWeek: 3,
  stats: { dueToday: false },
});
const WATER = habit('0192f0c4-0000-7000-8000-000000000003', {
  name: 'Water',
  targetCount: 3,
  stats: { todayCount: 1 },
});

describe('TodayHabits', () => {
  let fixture: ComponentFixture<TodayHabits>;
  let root: HTMLElement;
  let http: HttpTestingController;

  async function create(habits: HabitResponse[] | null = [READ, RUN, WATER]): Promise<void> {
    TestBed.configureTestingModule({
      providers: [provideHttp(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TodayHabits);
    root = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    if (habits) await flushList(habits);
  }

  async function flushList(habits: HabitResponse[]): Promise<void> {
    listRequest().flush({ items: habits });
    await settle();
  }

  function listRequest() {
    return http.expectOne(
      (r) => r.method === 'GET' && r.url === LIST && r.params.get('archived') === 'false',
    );
  }

  async function settle(): Promise<void> {
    await tick();
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function row(h: HabitResponse): HTMLElement | null {
    return root.querySelector(`.habit[data-id="${h.id}"]`);
  }

  function checkButton(h: HabitResponse): HTMLButtonElement {
    return row(h)!.querySelector('.check') as HTMLButtonElement;
  }

  function text(el: Element | null): string {
    return el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  }

  afterEach(() => http.verify());

  it('shows only the habits due today, with progress and streak', async () => {
    await create();

    expect(text(root.querySelector('h2 .count'))).toBe('0 of 2 done');
    expect(row(READ)).not.toBeNull();
    expect(row(WATER)).not.toBeNull();
    expect(row(RUN)).toBeNull();
    expect(text(row(READ)!.querySelector('.streak'))).toBe('4-day streak');
    expect(row(WATER)!.querySelector('.streak')).toBeNull();
    expect(text(checkButton(WATER))).toBe('1/3');
    expect(checkButton(WATER).getAttribute('aria-label')).toBe('“Water”: 1 of 3 today');
    expect(root.querySelector('a.open')?.getAttribute('href')).toBe('/habits');
  });

  it('checks a habit with one tap', async () => {
    await create();
    expect(checkButton(READ).getAttribute('aria-label')).toBe('Check “Read 20 pages” for today');

    checkButton(READ).click();
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
    expect(checkButton(READ).disabled).toBe(false);
    expect(row(READ)!.classList).toContain('done');
    expect(text(row(READ)!.querySelector('.streak'))).toBe('5-day streak');
    expect(text(root.querySelector('h2 .count'))).toBe('1 of 2 done');
  });

  it('unchecks a done habit with the next tap', async () => {
    const done = { ...READ, stats: { ...READ.stats, todayCount: 1, doneToday: true } };
    await create([done]);
    expect(checkButton(done).getAttribute('aria-label')).toBe('Uncheck “Read 20 pages” for today');

    checkButton(done).click();
    const req = http.expectOne((r) => r.method === 'PUT');
    expect(req.request.body).toEqual({ count: 0 });
    req.flush(READ);
    await settle();

    expect(checkButton(READ).getAttribute('aria-pressed')).toBe('false');
  });

  it('counts up towards a target above one', async () => {
    await create([WATER]);

    checkButton(WATER).click();
    const req = http.expectOne((r) => r.method === 'PUT');
    expect(req.request.body).toEqual({ count: 2 });
    req.flush({ ...WATER, stats: { ...WATER.stats, todayCount: 2 } });
    await settle();

    expect(text(checkButton(WATER))).toBe('2/3');
  });

  it('shows a failed check next to the habit', async () => {
    await create();

    checkButton(READ).click();
    http
      .expectOne((r) => r.method === 'PUT')
      .flush({ title: 'Server error' }, { status: 500, statusText: 'Server Error' });
    await settle();

    expect(text(row(READ)!.querySelector('.error'))).toContain(
      '“Read 20 pages” could not be checked',
    );
    expect(checkButton(READ).disabled).toBe(false);
  });

  it('drops a habit deleted meanwhile', async () => {
    await create();

    checkButton(READ).click();
    http
      .expectOne((r) => r.method === 'PUT')
      .flush({ title: 'Not found' }, { status: 404, statusText: 'Not Found' });
    await settle();

    expect(row(READ)).toBeNull();
    expect(root.querySelector('.error')).toBeNull();
  });

  it('says so when no habit is due today', async () => {
    await create([RUN]);

    expect(text(root.querySelector('.note'))).toBe('No habits due today.');
    expect(text(root.querySelector('h2'))).toBe('Habits due');
  });

  it('stays hidden without any active habit', async () => {
    await create([]);

    expect(root.querySelector('section')).toBeNull();
  });

  it('shows loading, then a failed load with a retry', async () => {
    await create(null);
    expect(text(root.querySelector('[role="status"]'))).toBe('Loading habits…');

    listRequest().flush({ title: 'Server error' }, { status: 500, statusText: 'Server Error' });
    await settle();
    const alert = root.querySelector('.load-error');
    expect(text(alert)).toContain("Today's habits could not be loaded.");

    (alert!.querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();
    await flushList([READ]);
    expect(root.querySelector('.load-error')).toBeNull();
    expect(row(READ)).not.toBeNull();
  });

  /** The page is shown again (another tab, the phone unlocked). */
  function becomeVisible(state: DocumentVisibilityState = 'visible'): void {
    const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue(state);
    try {
      document.dispatchEvent(new Event('visibilitychange'));
    } finally {
      visibility.mockRestore();
    }
  }

  function putRequest(h: HabitResponse) {
    return http.expectOne((r) => r.method === 'PUT' && r.url.startsWith(`${LIST}/${h.id}/`));
  }

  for (const [status, statusText] of [
    [409, 'Conflict'],
    [412, 'Precondition Failed'],
  ] as const) {
    it(`shows a ${status} conflict and reloads quietly, keeping the habits on screen`, async () => {
      await create([READ, WATER]);

      checkButton(READ).click();
      putRequest(READ).flush(
        { title: 'Changed meanwhile', status, detail: 'The habit was changed elsewhere.' },
        { status, statusText },
      );
      await settle();

      expect(text(row(READ)!.querySelector('.error'))).toBe(
        '“Read 20 pages” could not be checked: The habit was changed elsewhere.',
      );
      // Quiet: the rows stay while the reload runs, no loading note.
      expect(row(WATER)).not.toBeNull();
      expect(root.querySelector('[role="status"]')).toBeNull();

      const fresh = {
        ...READ,
        version: 2,
        stats: { ...READ.stats, todayCount: 1, doneToday: true },
      };
      await flushList([fresh, WATER]);

      expect(checkButton(READ).getAttribute('aria-pressed')).toBe('true');
      expect(text(root.querySelector('h2 .count'))).toBe('1 of 2 done');
    });
  }

  it('keeps the habits and no load error when the conflict reload fails', async () => {
    await create([READ]);

    checkButton(READ).click();
    putRequest(READ).flush(
      { title: 'Conflict', status: 409 },
      { status: 409, statusText: 'Conflict' },
    );
    await settle();
    listRequest().flush({ title: 'Server error' }, { status: 503, statusText: 'Unavailable' });
    await settle();

    expect(row(READ)).not.toBeNull();
    expect(root.querySelector('.load-error')).toBeNull();
    expect(text(row(READ)!.querySelector('.error'))).toContain('could not be checked: Conflict');
  });

  it('does not reload after a check that failed other than with a conflict', async () => {
    await create([READ]);

    checkButton(READ).click();
    putRequest(READ).flush(
      { title: 'Bad request', status: 400 },
      { status: 400, statusText: 'Bad Request' },
    );
    await settle();

    http.expectNone((r) => r.method === 'GET');
    expect(text(row(READ)!.querySelector('.error'))).toContain('could not be checked: Bad request');
  });

  it('sends one check for a double tap', async () => {
    await create([READ]);

    checkButton(READ).click();
    fixture.detectChanges();
    checkButton(READ).click();
    const req = putRequest(READ);
    req.flush({ ...READ, stats: { ...READ.stats, todayCount: 1, doneToday: true } });
    await settle();

    expect(checkButton(READ).getAttribute('aria-pressed')).toBe('true');
  });

  it('checks two habits at the same time, answered in reverse order', async () => {
    const MEDITATE = habit('0192f0c4-0000-7000-8000-000000000004', { name: 'Meditate' });
    await create([MEDITATE, READ]);

    checkButton(READ).click();
    checkButton(MEDITATE).click();
    fixture.detectChanges();
    const read = putRequest(READ);
    const meditate = putRequest(MEDITATE);
    expect(checkButton(READ).disabled).toBe(true);
    expect(checkButton(MEDITATE).disabled).toBe(true);

    meditate.flush({ ...MEDITATE, stats: { ...MEDITATE.stats, todayCount: 1, doneToday: true } });
    await settle();
    expect(checkButton(MEDITATE).disabled).toBe(false);
    expect(checkButton(READ).disabled).toBe(true);
    read.flush({ ...READ, stats: { ...READ.stats, todayCount: 1, doneToday: true } });
    await settle();

    expect(text(root.querySelector('h2 .count'))).toBe('2 of 2 done');
  });

  it('shows only the error of the last failed check', async () => {
    await create([READ, WATER]);

    checkButton(READ).click();
    putRequest(READ).flush({ title: 'Server error' }, { status: 500, statusText: 'Error' });
    await settle();
    expect(row(READ)!.querySelector('.error')).not.toBeNull();

    checkButton(WATER).click();
    fixture.detectChanges();
    expect(row(READ)!.querySelector('.error')).toBeNull();
    putRequest(WATER).flush({ ...WATER, stats: { ...WATER.stats, todayCount: 2 } });
    await settle();

    expect(root.querySelector('.error')).toBeNull();
  });

  it('drops a quiet reload answered after a check that started meanwhile', async () => {
    await create([READ]);

    becomeVisible();
    const reload = listRequest();
    checkButton(READ).click();
    putRequest(READ).flush({ ...READ, stats: { ...READ.stats, todayCount: 1, doneToday: true } });
    await settle();
    // The reload read the habits before the check: it would undo it on screen.
    reload.flush({ items: [READ] });
    await settle();

    expect(checkButton(READ).getAttribute('aria-pressed')).toBe('true');
  });

  it('drops a quiet reload answered while a check that started after it is running', async () => {
    await create([READ]);

    becomeVisible();
    const reload = listRequest();
    checkButton(READ).click();
    const put = putRequest(READ);
    reload.flush({ items: [{ ...READ, name: 'Read 30 pages' }] });
    await settle();
    expect(text(row(READ)!.querySelector('.name'))).toBe('Read 20 pages');

    put.flush({ ...READ, stats: { ...READ.stats, todayCount: 1, doneToday: true } });
    await settle();
    expect(checkButton(READ).getAttribute('aria-pressed')).toBe('true');
  });

  it('does not reload on return while a check is running', async () => {
    await create([READ]);

    checkButton(READ).click();
    becomeVisible();
    http.expectNone((r) => r.method === 'GET');
    putRequest(READ).flush(READ);
    await settle();
  });

  it('does not reload when the page is hidden', async () => {
    await create([READ]);

    becomeVisible('hidden');

    http.expectNone((r) => r.method === 'GET');
  });

  it('stops listening for visibility changes once destroyed', async () => {
    await create([READ]);

    fixture.destroy();
    becomeVisible();

    http.expectNone((r) => r.method === 'GET');
  });

  // TW-1: a return to the page while the first load runs must not leave the section loading.
  it('ends the loading note with a retry when a reload on return fails during the first load', async () => {
    await create(null);
    const first = listRequest();

    becomeVisible();
    const reload = listRequest();
    first.flush({ items: [READ] });
    await settle();
    reload.flush({ title: 'Server error' }, { status: 503, statusText: 'Unavailable' });
    await settle();

    // Either the first load's habits or the load error with its retry, never loading forever.
    const loading = root.querySelector('[role="status"]');
    expect(loading).toBeNull();
  });

  // TW-2: a reload on return that succeeds after a failed load must not keep the load error.
  it('shows the habits when a reload on return succeeds after a failed load', async () => {
    await create(null);
    listRequest().flush({ title: 'Server error' }, { status: 503, statusText: 'Unavailable' });
    await settle();
    expect(root.querySelector('.load-error')).not.toBeNull();

    becomeVisible();
    await flushList([READ]);

    expect(root.querySelector('.load-error')).toBeNull();
    expect(row(READ)).not.toBeNull();
  });

  it('shows a weekly streak in weeks', async () => {
    const weekly = habit('0192f0c4-0000-7000-8000-000000000005', {
      name: 'Long walk',
      schedule: 'TIMES_PER_WEEK',
      timesPerWeek: 2,
      stats: { currentStreak: 1, streakUnit: 'WEEKS' },
    });
    await create([weekly]);

    expect(text(row(weekly)!.querySelector('.streak'))).toBe('1-week streak');
  });

  it('marks a habit done once its target above one is reached', async () => {
    const almost = { ...WATER, stats: { ...WATER.stats, todayCount: 2 } };
    await create([almost]);
    expect(checkButton(almost).classList).toContain('partial');

    checkButton(almost).click();
    const req = putRequest(almost);
    expect(req.request.body).toEqual({ count: 3 });
    req.flush({ ...WATER, stats: { ...WATER.stats, todayCount: 3, doneToday: true } });
    await settle();

    expect(row(WATER)!.classList).toContain('done');
    expect(checkButton(WATER).querySelector('.tick')).not.toBeNull();
    expect(checkButton(WATER).classList).not.toContain('partial');
    expect(text(root.querySelector('h2 .count'))).toBe('1 of 1 done');
  });

  it('reloads quietly when the page becomes visible again', async () => {
    await create([READ]);

    const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
    try {
      document.dispatchEvent(new Event('visibilitychange'));
    } finally {
      visibility.mockRestore();
    }
    await settle();
    expect(row(READ)).not.toBeNull();
    const nextDay = { ...READ, stats: { ...READ.stats, today: '2026-10-23', currentStreak: 5 } };
    await flushList([nextDay]);

    expect(text(row(READ)!.querySelector('.streak'))).toBe('5-day streak');
  });
});
