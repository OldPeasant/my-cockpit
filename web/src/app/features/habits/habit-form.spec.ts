import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HabitRequest } from '../../api/generated';
import { emptyDraft, HabitDraft } from './habit-display';
import { HabitForm } from './habit-form';

const settle = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

describe('HabitForm', () => {
  let fixture: ComponentFixture<HabitForm>;
  let root: HTMLElement;
  let saved: HabitRequest[];
  let cancelled: number;

  async function render(
    initial: HabitDraft = { ...emptyDraft(), name: 'Stretch' },
    inputs: Record<string, unknown> = {},
  ): Promise<void> {
    saved = [];
    cancelled = 0;
    fixture = TestBed.createComponent(HabitForm);
    root = fixture.nativeElement as HTMLElement;
    fixture.componentRef.setInput('initial', initial);
    for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
    fixture.componentInstance.save.subscribe((request) => saved.push(request));
    fixture.componentInstance.cancelled.subscribe(() => cancelled++);
    await refresh();
  }

  async function refresh(): Promise<void> {
    await settle();
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function el<T extends HTMLElement>(selector: string): T {
    return root.querySelector(selector) as T;
  }

  async function type(selector: string, value: string): Promise<void> {
    const input = el<HTMLInputElement>(selector);
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await refresh();
  }

  async function click(selector: string): Promise<void> {
    el<HTMLButtonElement>(selector).click();
    await refresh();
  }

  async function submit(): Promise<void> {
    el<HTMLFormElement>('form').dispatchEvent(new Event('submit', { cancelable: true }));
    await refresh();
  }

  function pressed(selector: string): string | null {
    return el(selector).getAttribute('aria-pressed');
  }

  function dayButtons(): HTMLButtonElement[] {
    return [...root.querySelectorAll<HTMLButtonElement>('.day')];
  }

  function text(element: Element | null): string {
    return element?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  }

  it('starts with the given values and focuses the name', async () => {
    await render({
      name: 'Stretch',
      schedule: 'DAILY',
      timesPerWeek: 3,
      weekdays: [],
      targetCount: 2,
      reminderTime: '07:30',
    });
    await refresh();

    expect(el<HTMLInputElement>('.name input').value).toBe('Stretch');
    expect(el<HTMLInputElement>('.target').value).toBe('2');
    expect(el<HTMLInputElement>('.reminder').value).toBe('07:30');
    expect(pressed('.schedule-daily')).toBe('true');
    expect(el('.times-per-week')).toBeNull();
    expect(dayButtons()).toHaveLength(0);
    expect(document.activeElement).toBe(el('.name input'));
    expect(text(el('.save'))).toBe('Add habit');
    expect(el('.error')).toBeNull();
  });

  it('labels the save button for an edit', async () => {
    await render(undefined, { editing: true });
    expect(text(el('.save'))).toBe('Save changes');
  });

  it('switches the schedule and shows only the fields of the chosen one', async () => {
    await render();

    await click('.schedule-per-week');
    expect(pressed('.schedule-per-week')).toBe('true');
    expect(pressed('.schedule-daily')).toBe('false');
    expect(el<HTMLInputElement>('.times-per-week').value).toBe('3');
    expect(dayButtons()).toHaveLength(0);

    await click('.schedule-weekdays');
    expect(pressed('.schedule-weekdays')).toBe('true');
    expect(el('.times-per-week')).toBeNull();
    expect(dayButtons().map(text)).toEqual(['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']);

    await click('.schedule-daily');
    await submit();
    expect(saved).toEqual([
      { name: 'Stretch', schedule: 'DAILY', targetCount: 1, reminderTime: null },
    ]);
  });

  it('toggles weekdays and sends them in week order', async () => {
    await render({ ...emptyDraft(), name: 'Gym', schedule: 'WEEKDAYS', weekdays: ['FRIDAY'] });
    expect(dayButtons().map((b) => b.getAttribute('aria-pressed'))).toEqual([
      'false',
      'false',
      'false',
      'false',
      'true',
      'false',
      'false',
    ]);

    dayButtons()[6].click(); // Sun on
    dayButtons()[4].click(); // Fri off
    dayButtons()[1].click(); // Tue on
    await refresh();
    expect(dayButtons()[4].getAttribute('aria-pressed')).toBe('false');
    expect(dayButtons()[6].getAttribute('aria-pressed')).toBe('true');

    await submit();
    expect(saved).toEqual([
      {
        name: 'Gym',
        schedule: 'WEEKDAYS',
        targetCount: 1,
        weekdays: ['TUESDAY', 'SUNDAY'],
        reminderTime: null,
      },
    ]);
  });

  it('does not change the initial draft when days are toggled', async () => {
    const initial: HabitDraft = {
      ...emptyDraft(),
      name: 'Gym',
      schedule: 'WEEKDAYS',
      weekdays: ['MONDAY'],
    };
    await render(initial);

    await click('.day');
    expect(initial.weekdays).toEqual(['MONDAY']);
  });

  it('refuses a weekday schedule without days', async () => {
    await render({ ...emptyDraft(), name: 'Gym', schedule: 'WEEKDAYS', weekdays: ['MONDAY'] });

    await click('.day'); // Mon off: no day left
    await submit();

    expect(text(el('.error'))).toBe('Pick at least one day.');
    expect(saved).toEqual([]);
  });

  it('sends the times a week, accepting 1 to 7', async () => {
    await render();
    await click('.schedule-per-week');

    for (const bad of ['0', '8', '', '2.5']) {
      await type('.times-per-week', bad);
      await submit();
      expect(text(el('.error'))).toBe('Times a week must be between 1 and 7.');
    }
    expect(saved).toEqual([]);

    await type('.times-per-week', '7');
    expect(el('.error')).toBeNull();
    await submit();
    await type('.times-per-week', '1');
    await submit();
    expect(saved.map((r) => r.timesPerWeek)).toEqual([7, 1]);
    expect(saved[0]).toEqual({
      name: 'Stretch',
      schedule: 'TIMES_PER_WEEK',
      targetCount: 1,
      timesPerWeek: 7,
      reminderTime: null,
    });
  });

  it('sends the times a day, accepting 1 to 1000', async () => {
    await render();

    for (const bad of ['0', '1001', '']) {
      await type('.target', bad);
      await submit();
      expect(text(el('.error'))).toBe('Times a day must be between 1 and 1000.');
    }
    expect(saved).toEqual([]);

    await type('.target', '1000');
    await submit();
    expect(saved).toEqual([
      { name: 'Stretch', schedule: 'DAILY', targetCount: 1000, reminderTime: null },
    ]);
  });

  it('sends a reminder, and a cleared one as null', async () => {
    await render({ ...emptyDraft(), name: 'Stretch', reminderTime: '07:30' });

    await type('.reminder', '21:15');
    await submit();
    await type('.reminder', '');
    await submit();

    expect(saved.map((r) => r.reminderTime)).toEqual(['21:15', null]);
  });

  it('shows a missing name only after a save was tried, and never sends it', async () => {
    await render(emptyDraft());
    expect(el('.error')).toBeNull();

    await submit();
    expect(text(el('.error'))).toBe('Give the habit a name.');
    expect(el('.error').getAttribute('role')).toBe('alert');

    await type('.name input', '   ');
    await submit();
    expect(text(el('.error'))).toBe('Give the habit a name.');
    expect(saved).toEqual([]);

    await type('.name input', '  Journal  ');
    expect(el('.error')).toBeNull();
    await submit();
    expect(saved).toEqual([
      { name: 'Journal', schedule: 'DAILY', targetCount: 1, reminderTime: null },
    ]);
  });

  it('saves with the save button', async () => {
    await render();
    await click('.save');
    expect(saved).toHaveLength(1);
  });

  it('shows why the last save failed, unless the draft itself has a problem', async () => {
    await render(undefined, { error: 'The habit was changed by someone else.' });
    expect(text(el('.error'))).toBe(
      'The habit could not be saved: The habit was changed by someone else.',
    );

    await type('.name input', '');
    await submit();
    expect(text(el('.error'))).toBe('Give the habit a name.');
  });

  it('neither saves nor cancels while saving', async () => {
    await render(undefined, { saving: true });

    expect(el<HTMLButtonElement>('.save').disabled).toBe(true);
    expect(el<HTMLButtonElement>('.cancel').disabled).toBe(true);
    await submit();
    expect(saved).toEqual([]);
    expect(el('.error')).toBeNull();
  });

  it('cancels with the button and with Escape', async () => {
    await render();

    await click('.cancel');
    el('form').dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await refresh();

    expect(cancelled).toBe(2);
    expect(saved).toEqual([]);
  });
});
