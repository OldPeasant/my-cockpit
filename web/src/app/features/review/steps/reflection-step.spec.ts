import { TestBed } from '@angular/core/testing';
import { Api, HabitResponse, listHabits } from '../../../api/generated';
import { ReviewSession } from '../review-session';
import { ReflectionStep } from './reflection-step';

function habit(id: string, name: string, rate: number | undefined, streak: number): HabitResponse {
  return {
    id,
    name,
    archived: false,
    schedule: 'DAILY',
    targetCount: 1,
    weekdays: [],
    createdAt: '2026-09-01T08:00:00Z',
    updatedAt: '2026-09-01T08:00:00Z',
    version: 1,
    stats: {
      completionRateLast30Days: rate,
      currentStreak: streak,
      longestStreak: streak,
      doneLast30Days: 0,
      dueLast30Days: 0,
      doneToday: false,
      dueToday: true,
      streakUnit: 'DAYS',
      today: '2026-10-22',
      todayCount: 0,
    },
  };
}

const settle = () => new Promise((resolve) => setTimeout(resolve));

describe('ReflectionStep', () => {
  let habits: HabitResponse[];
  let habitsFail: boolean;
  let element: HTMLElement;
  let session: ReviewSession;

  const text = () => element.textContent?.replace(/\s+/g, ' ') ?? '';
  const textarea = () => element.querySelector('textarea')!;

  async function render(): Promise<void> {
    const fixture = TestBed.createComponent(ReflectionStep);
    element = fixture.nativeElement;
    await settle();
    TestBed.tick();
    await settle();
    TestBed.tick();
  }

  beforeEach(() => {
    localStorage.clear();
    habits = [];
    habitsFail = false;
    const api = {
      invoke: async (fn: unknown) => {
        if (fn === listHabits) {
          if (habitsFail) throw new Error('offline');
          return { items: habits };
        }
        throw new Error('unexpected call');
      },
    };
    TestBed.configureTestingModule({
      providers: [ReviewSession, { provide: Api, useValue: api }],
    });
    session = TestBed.inject(ReviewSession);
  });

  it('lists the habits with their 30-day rate and streak', async () => {
    habits = [
      habit('h1', 'Fictional stretching', 0.8, 4),
      habit('h2', 'Fictional reading', undefined, 0),
    ];
    await render();

    const rows = [...element.querySelectorAll('ul[aria-label="Habits"] li')];
    expect(rows.map((li) => li.querySelector('.title')?.textContent?.trim())).toEqual([
      'Fictional stretching',
      'Fictional reading',
    ]);
    const meta = rows.map((li) => li.querySelector('.meta')?.textContent ?? '');
    expect(meta[0]).toContain('80% in 30 days');
    expect(meta[0]).toContain('streak 4');
    // No rate yet: only the streak.
    expect(meta[1]).not.toContain('%');
    expect(meta[1]).toContain('streak 0');
  });

  it('shows no habit list when there are no habits', async () => {
    await render();

    expect(element.querySelector('ul')).toBeNull();
    expect(element.querySelector('[role=alert]')).toBeNull();
    expect(textarea()).not.toBeNull();
  });

  it('says when the habits could not be loaded and still offers the reflection', async () => {
    habitsFail = true;
    await render();

    expect(element.querySelector('[role=alert]')?.textContent).toContain(
      'The habits could not be loaded.',
    );
    expect(textarea()).not.toBeNull();
  });

  it('shows the reflection kept in the session', async () => {
    session.setReflection('A fictional calm week.');
    await render();

    expect(textarea().value).toBe('A fictional calm week.');
    expect(text()).toContain("Saved in today's journal entry when you finish the review.");
  });

  it('keeps what the user types in the session', async () => {
    await render();

    textarea().value = 'Fewer fictional meetings next week.';
    textarea().dispatchEvent(new Event('input'));

    expect(session.reflection()).toBe('Fewer fictional meetings next week.');
  });
});
