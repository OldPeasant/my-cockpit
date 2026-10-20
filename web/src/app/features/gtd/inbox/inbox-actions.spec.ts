import { TaskResponse } from '../../../api/generated';
import {
  actionForKey,
  clarificationOf,
  clarificationPatch,
  INBOX_ACTION_GROUPS,
  isPromptAction,
} from './inbox-actions';

function task(fields: Partial<TaskResponse> = {}): TaskResponse {
  return {
    id: 't1',
    title: 'Water the plants',
    list: 'INBOX',
    position: 1,
    contextIds: [],
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-01T08:00:00Z',
    version: 1,
    ...fields,
  };
}

describe('INBOX_ACTION_GROUPS', () => {
  const all = INBOX_ACTION_GROUPS.flatMap((group) => group.actions);

  it('offers every action of SPEC.md §5.3 once', () => {
    expect(all.map((info) => info.action).sort()).toEqual(
      [
        'calendar',
        'done',
        'next',
        'project',
        'reference',
        'schedule',
        'someday',
        'tickler',
        'trash',
        'waiting',
      ].sort(),
    );
  });

  it('disables only To reference and To calendar', () => {
    expect(all.filter((info) => info.disabled).map((info) => info.action)).toEqual([
      'reference',
      'calendar',
    ]);
  });

  it('uses each key for one action only, and shows the first key on the button', () => {
    const keys = all.flatMap((info) => info.keys);
    expect(new Set(keys).size).toBe(keys.length);
    for (const info of all) expect(info.key.toLowerCase()).toBe(info.keys[0]);
  });
});

describe('actionForKey', () => {
  it.each([
    ['d', 'done'],
    ['n', 'next'],
    ['p', 'project'],
    ['w', 'waiting'],
    ['s', 'schedule'],
    ['t', 'tickler'],
    ['m', 'someday'],
    ['x', 'trash'],
    ['Delete', 'trash'],
  ])('maps %s to %s', (key, action) => {
    expect(actionForKey(key)).toBe(action);
  });

  it('ignores the case of the key (Shift or Caps Lock)', () => {
    expect(actionForKey('N')).toBe('next');
    expect(actionForKey('DELETE')).toBe('trash');
  });

  it('returns null for the disabled actions', () => {
    expect(actionForKey('r')).toBeNull();
    expect(actionForKey('c')).toBeNull();
  });

  it.each(['q', 'Enter', 'Escape', ' ', '', 'ArrowDown', 'Backspace'])(
    'returns null for the unbound key %j',
    (key) => {
      expect(actionForKey(key)).toBeNull();
    },
  );
});

describe('isPromptAction', () => {
  it('is true for the actions that need a value', () => {
    expect(
      ['waiting', 'schedule', 'tickler', 'project'].every((a) => isPromptAction(a as never)),
    ).toBe(true);
  });

  it('is false for the immediate actions', () => {
    for (const action of ['done', 'next', 'someday', 'trash', 'reference', 'calendar'] as const) {
      expect(isPromptAction(action)).toBe(false);
    }
  });
});

describe('clarificationOf', () => {
  it('copies the attributes of the task', () => {
    expect(
      clarificationOf(
        task({ energy: 'LOW', creativity: 'ROUTINE', timeEstimateMin: 5, contextIds: ['c1'] }),
      ),
    ).toEqual({ energy: 'LOW', creativity: 'ROUTINE', timeEstimateMin: 5, contextIds: ['c1'] });
  });

  it('is empty without a task', () => {
    expect(clarificationOf(undefined)).toEqual({
      energy: undefined,
      creativity: undefined,
      timeEstimateMin: undefined,
      contextIds: [],
    });
  });
});

describe('clarificationPatch', () => {
  const clarified = task({
    energy: 'HIGH',
    creativity: 'CREATIVE',
    timeEstimateMin: 30,
    contextIds: ['c1', 'c2'],
  });

  it('is empty when nothing changed', () => {
    expect(clarificationPatch(clarified, clarificationOf(clarified))).toEqual({});
    expect(clarificationPatch(task(), clarificationOf(task()))).toEqual({});
  });

  it('contains only the changed fields', () => {
    expect(clarificationPatch(clarified, { ...clarificationOf(clarified), energy: 'LOW' })).toEqual(
      { energy: 'LOW' },
    );
    expect(
      clarificationPatch(clarified, { ...clarificationOf(clarified), timeEstimateMin: 5 }),
    ).toEqual({ timeEstimateMin: 5 });
  });

  it('sets fields the task did not have', () => {
    expect(
      clarificationPatch(task(), {
        energy: 'MEDIUM',
        creativity: 'ROUTINE',
        timeEstimateMin: 15,
        contextIds: ['c3'],
      }),
    ).toEqual({ energy: 'MEDIUM', creativity: 'ROUTINE', timeEstimateMin: 15, contextIds: ['c3'] });
  });

  it('clears a removed field with null', () => {
    expect(
      clarificationPatch(clarified, {
        energy: undefined,
        creativity: undefined,
        timeEstimateMin: undefined,
        contextIds: [],
      }),
    ).toEqual({ energy: null, creativity: null, timeEstimateMin: null, contextIds: [] });
  });

  it('ignores the order of contexts', () => {
    expect(
      clarificationPatch(clarified, { ...clarificationOf(clarified), contextIds: ['c2', 'c1'] }),
    ).toEqual({});
  });

  it('sends the whole new context list when one is added or swapped', () => {
    expect(
      clarificationPatch(clarified, {
        ...clarificationOf(clarified),
        contextIds: ['c1', 'c2', 'c3'],
      }),
    ).toEqual({ contextIds: ['c1', 'c2', 'c3'] });
    expect(
      clarificationPatch(clarified, { ...clarificationOf(clarified), contextIds: ['c1', 'c3'] }),
    ).toEqual({ contextIds: ['c1', 'c3'] });
  });

  it('does not change the task or the clarification it is given', () => {
    const contextIds = ['c2', 'c1'];
    const original = task({ contextIds: ['c1'] });
    clarificationPatch(original, { contextIds });
    expect(original.contextIds).toEqual(['c1']);
    expect(contextIds).toEqual(['c2', 'c1']);
  });
});
