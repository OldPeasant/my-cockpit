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
