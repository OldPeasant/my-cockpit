import { TaskResponse } from '../../../api/generated';
import {
  chooseList,
  draftOf,
  draftRule,
  hasChanges,
  listChoice,
  moveFor,
  patchFor,
  TaskDraft,
  validateDraft,
} from './task-draft';

const TODAY = '2026-10-20';

function task(fields: Partial<TaskResponse> = {}): TaskResponse {
  return {
    id: 't1',
    title: 'Call the plumber',
    list: 'NEXT',
    position: 1,
    contextIds: ['c1'],
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-01T08:00:00Z',
    version: 3,
    ...fields,
  };
}

function edited(base: TaskResponse, change: Partial<TaskDraft>): TaskDraft {
  return { ...draftOf(base), ...change };
}

describe('draftOf', () => {
  it('shows a task unchanged', () => {
    const t = task({
      dueTime: '09:30:00',
      timeEstimateMin: 15,
      recurrenceRule: 'FREQ=DAILY',
      recurrenceMode: 'FIXED',
    });
    const draft = draftOf(t);
    expect(draft).toMatchObject({
      title: 'Call the plumber',
      dueTime: '09:30',
      estimate: '15',
      repeat: 'daily',
      repeatMode: 'FIXED',
      projectId: '',
    });
    expect(hasChanges(t, draft)).toBe(false);
    expect(patchFor(t, draft)).toEqual({});
  });
});

describe('validateDraft', () => {
  const t = task();
  const errors = (change: Partial<TaskDraft>, base = t) =>
    validateDraft(base, edited(base, change), TODAY);

  it('accepts the unchanged task', () => {
    expect(errors({})).toEqual({});
  });

  it('requires a title and limits its length', () => {
    expect(errors({ title: '   ' }).title).toContain('title');
    expect(errors({ title: 'x'.repeat(501) }).title).toContain('500');
    expect(errors({ title: 'x'.repeat(500) }).title).toBeUndefined();
  });

  it('limits the notes', () => {
    expect(errors({ notesMd: 'x'.repeat(100_001) }).notesMd).toBeDefined();
  });

  it.each(['-5', '1.5', 'abc', '10001'])('rejects the estimate %s', (estimate) => {
    expect(errors({ estimate }).estimate).toBeDefined();
  });

  it.each(['', '0', '45', '10000'])('accepts the estimate "%s"', (estimate) => {
    expect(errors({ estimate }).estimate).toBeUndefined();
  });

  it('needs a due date for a due time', () => {
    expect(errors({ dueTime: '10:00' }).dueTime).toBeDefined();
    expect(errors({ dueTime: '10:00', dueDate: TODAY }).dueTime).toBeUndefined();
  });

  it('needs a date for the scheduled list', () => {
    expect(errors({ list: 'SCHEDULED' }).scheduledDate).toBeDefined();
    expect(
      errors({ list: 'SCHEDULED', scheduledDate: '2026-11-01' }).scheduledDate,
    ).toBeUndefined();
  });

  it('needs a new tickler date after today, but keeps an unchanged one', () => {
    expect(errors({ tickler: true, ticklerDate: TODAY }).ticklerDate).toBe(
      'Pick a date after today.',
    );
    expect(errors({ tickler: true, ticklerDate: '2026-10-21' }).ticklerDate).toBeUndefined();
    const old = task({ ticklerDate: '2026-10-19' });
    expect(errors({}, old).ticklerDate).toBeUndefined();
  });

  it('needs a date when Tickler is chosen', () => {
    expect(errors({ tickler: true, ticklerDate: '' }).ticklerDate).toBe(
      'Pick the date it comes back.',
    );
  });

  it('does not ask for the scheduled date or check waiting-for while Tickler overrides the list', () => {
    const scheduled = task({ list: 'SCHEDULED', scheduledDate: '2026-11-01' });
    expect(
      errors({ tickler: true, ticklerDate: '2026-10-25', scheduledDate: '' }, scheduled),
    ).toEqual({});
    expect(
      errors({
        list: 'WAITING',
        tickler: true,
        ticklerDate: '2026-10-25',
        waitingForText: 'x'.repeat(501),
      }),
    ).toEqual({});
  });

  it('limits waiting-for on the waiting list', () => {
    expect(
      errors({ list: 'WAITING', waitingForText: 'x'.repeat(501) }).waitingForText,
    ).toBeDefined();
  });

  it('checks a custom rule only', () => {
    expect(errors({ repeat: 'custom', customRule: 'FREQ=HOURLY' }).customRule).toBeDefined();
    expect(errors({ repeat: 'custom', customRule: 'FREQ=WEEKLY;BYDAY=TU' })).toEqual({});
    expect(errors({ repeat: 'weekly', customRule: 'garbage' })).toEqual({});
  });
});

describe('draftRule', () => {
  const t = task();
  it('is null for none, the preset rule, or the normalized custom rule', () => {
    expect(draftRule(edited(t, { repeat: 'none', customRule: 'FREQ=DAILY' }))).toBeNull();
    expect(draftRule(edited(t, { repeat: 'monthly' }))).toBe('FREQ=MONTHLY');
    expect(draftRule(edited(t, { repeat: 'custom', customRule: ' freq=daily ' }))).toBe(
      'FREQ=DAILY',
    );
    expect(draftRule(edited(t, { repeat: 'custom', customRule: ' ' }))).toBeNull();
  });
});

describe('moveFor', () => {
  it('is not needed while list and tickler stay', () => {
    const t = task();
    expect(moveFor(t, edited(t, { title: 'Other' }))).toBeUndefined();
  });

  it('moves to the scheduled list with its date', () => {
    const t = task();
    expect(
      moveFor(t, edited(t, { ...chooseList(t, 'SCHEDULED'), scheduledDate: '2026-11-05' })),
    ).toEqual({ list: 'SCHEDULED', scheduledDate: '2026-11-05' });
  });

  it('saves a chosen tickler as INBOX with its date, from any list', () => {
    const t = task({ list: 'SCHEDULED', scheduledDate: '2026-11-05' });
    expect(
      moveFor(t, edited(t, { ...chooseList(t, 'TICKLER'), ticklerDate: '2026-10-25' })),
    ).toEqual({ list: 'INBOX', ticklerDate: '2026-10-25' });
  });

  it('moves a tickler to a new date', () => {
    const t = task({ list: 'INBOX', ticklerDate: '2026-10-25' });
    expect(moveFor(t, edited(t, { ticklerDate: '2026-10-30' }))).toEqual({
      list: 'INBOX',
      ticklerDate: '2026-10-30',
    });
  });

  it('clears the tickler when another list is chosen, also the stored one', () => {
    const tickled = task({ ticklerDate: '2026-10-25' });
    expect(moveFor(tickled, edited(tickled, chooseList(tickled, 'NEXT')))).toEqual({
      list: 'NEXT',
    });
    expect(
      moveFor(
        tickled,
        edited(tickled, { ...chooseList(tickled, 'SCHEDULED'), scheduledDate: '2026-11-05' }),
      ),
    ).toEqual({ list: 'SCHEDULED', scheduledDate: '2026-11-05' });
  });

  it('does not move an unchanged tickler, whatever list it is stored on', () => {
    const t = task({ list: 'NEXT', ticklerDate: '2026-10-25' });
    const draft = draftOf(t);
    expect(listChoice(draft)).toBe('TICKLER');
    expect(draft.ticklerDate).toBe('2026-10-25');
    expect(moveFor(t, draft)).toBeUndefined();
    expect(hasChanges(t, draft)).toBe(false);
  });
});

describe('chooseList', () => {
  it("restores the task's tickler date when Tickler is chosen again", () => {
    const t = task({ ticklerDate: '2026-10-25' });
    const away = edited(t, chooseList(t, 'WAITING'));
    expect(away).toMatchObject({ tickler: false, list: 'WAITING', ticklerDate: '' });
    expect(edited(t, { ...away, ...chooseList(t, 'TICKLER') })).toMatchObject({
      tickler: true,
      ticklerDate: '2026-10-25',
    });
    expect(chooseList(task(), 'TICKLER')).toEqual({ tickler: true, ticklerDate: '' });
  });
});

describe('patchFor', () => {
  it('sends only changed fields and null for cleared ones', () => {
    const t = task({
      notesMd: 'old',
      energy: 'LOW',
      timeEstimateMin: 30,
      dueDate: TODAY,
      dueTime: '09:00:00',
      projectId: 'p1',
    });
    const patch = patchFor(
      t,
      edited(t, {
        title: '  Call the plumber again ',
        notesMd: '',
        energy: undefined,
        creativity: 'CREATIVE',
        estimate: '45',
        dueTime: '',
        projectId: '',
        contextIds: ['c1', 'c2'],
      }),
    );
    expect(patch).toEqual({
      title: 'Call the plumber again',
      notesMd: null,
      energy: null,
      creativity: 'CREATIVE',
      timeEstimateMin: 45,
      dueTime: null,
      projectId: null,
      contextIds: ['c1', 'c2'],
    });
  });

  it('sets and clears the recurrence with its mode', () => {
    const t = task();
    expect(patchFor(t, edited(t, { repeat: 'weekly', repeatMode: 'AFTER_COMPLETION' }))).toEqual({
      recurrenceRule: 'FREQ=WEEKLY',
      recurrenceMode: 'AFTER_COMPLETION',
    });
    const recurring = task({ recurrenceRule: 'FREQ=WEEKLY', recurrenceMode: 'FIXED' });
    expect(patchFor(recurring, edited(recurring, { repeat: 'none' }))).toEqual({
      recurrenceRule: null,
      recurrenceMode: null,
    });
    expect(patchFor(recurring, edited(recurring, { repeatMode: 'AFTER_COMPLETION' }))).toEqual({
      recurrenceMode: 'AFTER_COMPLETION',
    });
  });

  it('patches waiting-for and scheduled date only on their list', () => {
    const waiting = task({ list: 'WAITING', waitingForText: 'Anna', waitingSince: '2026-10-01' });
    expect(
      patchFor(waiting, edited(waiting, { waitingForText: 'Ben ', waitingSince: '2026-10-02' })),
    ).toEqual({ waitingForText: 'Ben', waitingSince: '2026-10-02' });
    // Leaving WAITING: the move clears these fields, the patch must not send them.
    expect(patchFor(waiting, edited(waiting, { list: 'NEXT', waitingForText: 'X' }))).toEqual({});

    const scheduled = task({ list: 'SCHEDULED', scheduledDate: '2026-11-01' });
    expect(patchFor(scheduled, edited(scheduled, { scheduledDate: '2026-11-02' }))).toEqual({
      scheduledDate: '2026-11-02',
    });
  });
});
