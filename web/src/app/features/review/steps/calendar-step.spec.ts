import { TestBed } from '@angular/core/testing';
import {
  Api,
  createTask,
  getReviewPastEvents,
  getReviewUpcomingEvents,
  OccurrenceResponse,
} from '../../../api/generated';
import { CalendarStep, eventDay, groupByDay } from './calendar-step';

interface Call {
  fn: unknown;
  params: Record<string, unknown>;
}

/** A timed occurrence starting at the given local time. */
function timed(eventId: string, title: string, start: Date): OccurrenceResponse {
  return {
    eventId,
    title,
    allDay: false,
    calendarId: 'c1',
    start: start.toISOString(),
    end: new Date(start.getTime() + 3_600_000).toISOString(),
    status: 'CONFIRMED',
    transparency: 'OPAQUE',
    version: 1,
  };
}

function allDay(eventId: string, title: string, startDate: string): OccurrenceResponse {
  return {
    eventId,
    title,
    allDay: true,
    calendarId: 'c1',
    startDate,
    endDate: startDate,
    status: 'CONFIRMED',
    transparency: 'TRANSPARENT',
    version: 1,
  };
}

const settle = () => new Promise((resolve) => setTimeout(resolve));

describe('eventDay', () => {
  it('uses the date of an all-day occurrence as is', () => {
    expect(eventDay(allDay('e1', 'Fictional holiday', '2026-10-20'))).toBe('2026-10-20');
  });

  it('uses the local start day of a timed occurrence', () => {
    expect(eventDay(timed('e1', 'Fictional call', new Date(2026, 9, 20, 23, 30)))).toBe(
      '2026-10-20',
    );
    expect(eventDay(timed('e2', 'Fictional call', new Date(2026, 9, 21, 0, 15)))).toBe(
      '2026-10-21',
    );
  });

  it('falls back to the start of an all-day occurrence without a start date', () => {
    const event = { ...allDay('e1', 'Fictional fair', '2026-10-20'), startDate: undefined };
    event.start = new Date(2026, 9, 19, 12).toISOString();
    expect(eventDay(event)).toBe('2026-10-19');
  });
});

describe('groupByDay', () => {
  it('returns no days for no events', () => {
    expect(groupByDay([])).toEqual([]);
  });

  it('groups occurrences by day and keeps the given order', () => {
    const a = timed('e1', 'Fictional standup', new Date(2026, 9, 19, 9));
    const b = allDay('e2', 'Fictional holiday', '2026-10-20');
    const c = timed('e3', 'Fictional lunch', new Date(2026, 9, 19, 12));
    const d = timed('e4', 'Fictional dinner', new Date(2026, 9, 20, 19));

    expect(groupByDay([a, b, c, d])).toEqual([
      { date: '2026-10-19', events: [a, c] },
      { date: '2026-10-20', events: [b, d] },
    ]);
  });
});

describe('CalendarStep', () => {
  let calls: Call[];
  let events: OccurrenceResponse[];
  let element: HTMLElement;
  let loadFails: boolean;
  /** Makes the next createTask fail. */
  let createFails: boolean;
  let createGate: Promise<void> | undefined;

  const callsOf = (fn: unknown) => calls.filter((call) => call.fn === fn);
  const text = () => element.textContent?.replace(/\s+/g, ' ') ?? '';
  const status = () => element.querySelector('[role=status]')?.textContent?.trim();
  const input = () =>
    element.querySelector<HTMLInputElement>('input[aria-label="Capture a task"]')!;
  const add = () => element.querySelector<HTMLButtonElement>('form.capture button')!;

  async function refresh(): Promise<void> {
    await settle();
    TestBed.tick();
    await settle();
    TestBed.tick();
  }

  async function render(range: 'past' | 'upcoming'): Promise<void> {
    const fixture = TestBed.createComponent(CalendarStep);
    fixture.componentRef.setInput('range', range);
    element = fixture.nativeElement;
    document.body.appendChild(element);
    fixture.detectChanges();
    await refresh();
  }

  async function type(value: string): Promise<void> {
    input().value = value;
    input().dispatchEvent(new Event('input'));
    await refresh();
  }

  beforeEach(() => {
    calls = [];
    events = [];
    loadFails = false;
    createFails = false;
    createGate = undefined;
    const api = {
      invoke: async (fn: unknown, params: Record<string, unknown> = {}) => {
        calls.push({ fn, params });
        if (fn === getReviewPastEvents || fn === getReviewUpcomingEvents) {
          if (loadFails) throw new Error('offline');
          return { items: events };
        }
        if (fn === createTask) {
          await createGate;
          if (createFails) {
            createFails = false;
            throw new Error('refused');
          }
          return { id: 't-new', ...(params['body'] as object) };
        }
        throw new Error('unexpected call');
      },
    };
    TestBed.configureTestingModule({ providers: [{ provide: Api, useValue: api }] });
  });

  afterEach(() => element?.remove());

  it('loads the past events for the past range', async () => {
    await render('past');

    expect(callsOf(getReviewPastEvents)).toHaveLength(1);
    expect(callsOf(getReviewUpcomingEvents)).toHaveLength(0);
  });

  it('loads the upcoming events for the upcoming range', async () => {
    await render('upcoming');

    expect(callsOf(getReviewUpcomingEvents)).toHaveLength(1);
    expect(callsOf(getReviewPastEvents)).toHaveLength(0);
  });

  it('shows the events grouped by day with their times', async () => {
    events = [
      timed('e1', 'Fictional standup', new Date(2026, 9, 19, 9, 5)),
      allDay('e2', 'Fictional holiday', '2026-10-20'),
      timed('e3', 'Fictional dinner', new Date(2026, 9, 20, 19, 30)),
    ];
    await render('past');

    const days = [...element.querySelectorAll('section.day')];
    expect(days.map((d) => d.querySelector('h3')?.textContent?.trim())).toEqual([
      'Monday, Oct 19',
      'Tuesday, Oct 20',
    ]);
    expect(days[0].getAttribute('aria-label')).toBe('Monday, October 19, 2026');
    const rows = days.map((d) =>
      [...d.querySelectorAll('li')].map((li) => [
        li.querySelector('.time')?.textContent?.trim(),
        li.querySelector('.title')?.textContent?.trim(),
      ]),
    );
    expect(rows).toEqual([
      [['09:05', 'Fictional standup']],
      [
        ['All day', 'Fictional holiday'],
        ['19:30', 'Fictional dinner'],
      ],
    ]);
  });

  it('shows an empty state for each range', async () => {
    await render('past');
    expect(text()).toContain('No events in the last 7 days.');
    element.remove();

    await render('upcoming');
    expect(text()).toContain('No events in the next 14 days.');
  });

  it('shows a load error with a retry', async () => {
    loadFails = true;
    events = [allDay('e1', 'Fictional holiday', '2026-10-20')];
    await render('upcoming');

    expect(element.querySelector('[role=alert]')?.textContent).toContain(
      'This step could not be loaded.',
    );

    loadFails = false;
    element.querySelector<HTMLButtonElement>('[role=alert] button')!.click();
    await refresh();

    expect(element.querySelector('[role=alert]')).toBeNull();
    expect(text()).toContain('Fictional holiday');
    expect(callsOf(getReviewUpcomingEvents)).toHaveLength(2);
  });

  it('enables quick-add only with non-blank text', async () => {
    await render('past');
    expect(add().disabled).toBe(true);

    await type('   ');
    expect(add().disabled).toBe(true);

    await type('Send fictional thank-you note');
    expect(add().disabled).toBe(false);
  });

  it('quick-add captures the trimmed text into the inbox and clears the field', async () => {
    await render('past');
    await type('  Send fictional thank-you note  ');

    add().click();
    await refresh();

    expect(callsOf(createTask)).toHaveLength(1);
    // No list: the server puts it in the Inbox.
    expect(callsOf(createTask)[0].params).toEqual({
      body: { title: 'Send fictional thank-you note' },
    });
    expect(input().value).toBe('');
    expect(add().disabled).toBe(true);
    expect(status()).toBe('Added “Send fictional thank-you note” to the inbox.');
  });

  it('keeps the text and announces nothing when the capture fails', async () => {
    await render('upcoming');
    await type('Book fictional train');
    createFails = true;

    add().click();
    await refresh();

    expect(input().value).toBe('Book fictional train');
    expect(add().disabled).toBe(false);
    expect(status()).toBe('');
  });

  it('captures once while a capture is running', async () => {
    await render('upcoming');
    await type('Book fictional train');
    let release!: () => void;
    createGate = new Promise<void>((resolve) => (release = resolve));

    add().click();
    await refresh();
    expect(add().disabled).toBe(true);
    // A second submit (e.g. Enter) while saving is ignored.
    element.querySelector('form.capture')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await refresh();

    release();
    await refresh();

    expect(callsOf(createTask)).toHaveLength(1);
    expect(status()).toBe('Added “Book fictional train” to the inbox.');
  });

  it('ignores a submit of blank text', async () => {
    await render('past');
    await type('  ');

    element.querySelector('form.capture')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await refresh();

    expect(callsOf(createTask)).toHaveLength(0);
  });
});
