import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ReminderResponse } from '../../../api/generated';
import { ApiError } from '../../../core/http';
import { TaskDetailService } from './task-detail.service';
import {
  availablePresets,
  localDateTime,
  presetTime,
  REMINDER_PAST,
  ReminderAnchors,
  TaskReminders,
} from './task-reminders';

const settle = () => new Promise((resolve) => setTimeout(resolve));

/** A promise resolved or rejected from the test, to control when a call answers. */
function deferred() {
  let resolve!: () => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<void>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function reminder(id: string, remindAt: Date, fields: Partial<ReminderResponse> = {}) {
  return {
    id,
    channel: 'NOTIFICATION',
    createdAt: '2026-11-01T08:00:00Z',
    item: { id: 't1', type: 'TASK', title: 'Call the plumber' },
    remindAt: remindAt.toISOString(),
    ...fields,
  } as ReminderResponse;
}

describe('reminder presets', () => {
  const anchors: ReminderAnchors = {
    dueDate: '2026-11-20',
    dueTime: '14:30',
    scheduledDate: '2026-11-18',
  };

  it('reads a local date and time', () => {
    expect(localDateTime('2026-11-20', '14:30')).toEqual(new Date(2026, 10, 20, 14, 30));
    expect(localDateTime('2026-11-20', '14:30:00')).toEqual(new Date(2026, 10, 20, 14, 30));
    expect(localDateTime('20.11.2026', '14:30')).toBeNull();
    expect(localDateTime('2026-11-20', '')).toBeNull();
  });

  it('places each preset relative to the due or scheduled date', () => {
    expect(presetTime('AT_DUE', anchors)).toEqual(new Date(2026, 10, 20, 14, 30));
    expect(presetTime('HOUR_BEFORE_DUE', anchors)).toEqual(new Date(2026, 10, 20, 13, 30));
    expect(presetTime('DAY_BEFORE_DUE', anchors)).toEqual(new Date(2026, 10, 19, 14, 30));
    expect(presetTime('AT_SCHEDULED', anchors)).toEqual(new Date(2026, 10, 18, 9, 0));
  });

  it('uses 09:00 for a due date without a time and offers no hour before', () => {
    const dateOnly = { dueDate: '2026-11-20' };
    expect(presetTime('AT_DUE', dateOnly)).toEqual(new Date(2026, 10, 20, 9, 0));
    expect(presetTime('HOUR_BEFORE_DUE', dateOnly)).toBeNull();
    expect(presetTime('DAY_BEFORE_DUE', dateOnly)).toEqual(new Date(2026, 10, 19, 9, 0));
    expect(presetTime('AT_SCHEDULED', dateOnly)).toBeNull();
  });

  it('offers only presets the task has a date for that are still ahead', () => {
    const now = new Date(2026, 10, 19, 20, 0);
    expect(availablePresets(anchors, now).map((p) => p.preset)).toEqual([
      'AT_DUE',
      'HOUR_BEFORE_DUE',
    ]);
    expect(availablePresets({}, now)).toEqual([]);
  });

  it('does not offer a preset exactly at the current time, only one strictly ahead', () => {
    const atDue = new Date(2026, 10, 20, 14, 30);
    expect(availablePresets(anchors, atDue).map((p) => p.preset)).toEqual([]);
    const justBefore = new Date(atDue.getTime() - 1);
    expect(availablePresets(anchors, justBefore)).toEqual([{ preset: 'AT_DUE', at: atDue }]);
  });

  it('places the day before across a month and year boundary', () => {
    expect(presetTime('DAY_BEFORE_DUE', { dueDate: '2027-01-01', dueTime: '08:15' })).toEqual(
      new Date(2026, 11, 31, 8, 15),
    );
  });

  it('keeps the time of day for the day before across a DST change', () => {
    // 2026-10-25 / 2026-03-29 are the European DST changes; in any zone the local time stays.
    for (const dueDate of ['2026-10-26', '2026-03-30']) {
      const at = presetTime('DAY_BEFORE_DUE', { dueDate, dueTime: '10:00' })!;
      expect([at.getDate(), at.getHours(), at.getMinutes()]).toEqual([
        +dueDate.slice(8) - 1,
        10,
        0,
      ]);
    }
  });

  it('places the hour before across midnight', () => {
    expect(presetTime('HOUR_BEFORE_DUE', { dueDate: '2026-11-20', dueTime: '00:30' })).toEqual(
      new Date(2026, 10, 19, 23, 30),
    );
  });

  it('ignores an empty due time and rejects malformed times', () => {
    expect(presetTime('AT_DUE', { dueDate: '2026-11-20', dueTime: '' })).toEqual(
      new Date(2026, 10, 20, 9, 0),
    );
    expect(presetTime('HOUR_BEFORE_DUE', { dueDate: '2026-11-20', dueTime: null })).toBeNull();
    expect(localDateTime('2026-11-20', '7:45')).toBeNull();
    expect(localDateTime('', '07:45')).toBeNull();
    expect(presetTime('AT_DUE', { dueDate: 'tomorrow', dueTime: '10:00' })).toBeNull();
  });
});

@Component({
  imports: [TaskReminders],
  template: `<app-task-reminders [taskId]="id()" [anchors]="anchors()" />`,
})
class Host {
  readonly id = signal('t1');
  readonly anchors = signal<ReminderAnchors | null>({ dueDate: '2026-11-20', dueTime: '14:30' });
}

describe('TaskReminders', () => {
  const now = new Date(2026, 10, 10, 12, 0);
  let stored: Record<string, ReminderResponse[]>;
  let loads: string[];
  let added: { taskId: string; remindAt: string }[];
  let deleted: string[];
  let failAdd: unknown;
  let failDelete: unknown;
  let failLoad: unknown;
  let loadGate: Record<string, Promise<void>>;
  let addGate: Promise<void> | undefined;
  let deleteGate: Promise<void> | undefined;
  let element: HTMLElement;
  let host: Host;
  let fixture: ReturnType<typeof TestBed.createComponent<Host>>;

  beforeEach(() => {
    stored = { t1: [], t2: [] };
    loads = [];
    added = [];
    deleted = [];
    failAdd = failDelete = failLoad = undefined;
    loadGate = {};
    addGate = deleteGate = undefined;
    let seq = 0;
    const service = {
      reminders: async (taskId: string) => {
        loads.push(taskId);
        await loadGate[taskId];
        if (failLoad) throw failLoad;
        return stored[taskId] ?? [];
      },
      addReminder: async (taskId: string, remindAt: string) => {
        added.push({ taskId, remindAt });
        await addGate;
        if (failAdd) throw failAdd;
        return reminder(`new-${++seq}`, new Date(remindAt));
      },
      deleteReminder: async (id: string) => {
        deleted.push(id);
        await deleteGate;
        if (failDelete) throw failDelete;
      },
    };
    TestBed.configureTestingModule({
      providers: [{ provide: TaskDetailService, useValue: service }],
    });
  });

  async function render(): Promise<void> {
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    element = fixture.nativeElement;
    fixture.detectChanges();
    const component = fixture.debugElement.children[0].componentInstance as TaskReminders;
    component.now = () => now;
    await refresh();
  }

  async function refresh(): Promise<void> {
    await settle();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const rows = () => [...element.querySelectorAll<HTMLElement>('.reminder')];
  const buttons = (selector: string) => [...element.querySelectorAll<HTMLButtonElement>(selector)];
  const banner = () => element.querySelector('.banner')?.textContent?.trim();

  it('lists the existing reminders, earliest first, marking fired ones', async () => {
    stored['t1'] = [
      reminder('r2', new Date(2026, 10, 20, 8, 0)),
      reminder('r1', new Date(2026, 10, 1, 8, 0), { firedAt: '2026-11-01T07:00:00Z' }),
    ];
    await render();
    expect(rows().length).toBe(2);
    expect(rows()[0].classList).toContain('fired');
    expect(rows()[0].textContent).toContain('Sent');
    expect(rows()[1].textContent).toContain('Nov');
    expect(rows()[1].querySelector('button')?.getAttribute('aria-label')).toMatch(
      /^Remove reminder /,
    );
  });

  it('says so when there are none', async () => {
    await render();
    expect(element.querySelector('.empty')?.textContent).toContain('No reminders.');
  });

  it('adds a reminder from a preset relative to the due date', async () => {
    await render();
    const chips = buttons('.chip');
    expect(chips.map((b) => b.textContent?.trim())).toEqual([
      'When due',
      '1 hour before due',
      '1 day before due',
    ]);
    chips[1].click();
    await refresh();
    expect(added).toEqual([
      { taskId: 't1', remindAt: new Date(2026, 10, 20, 13, 30).toISOString() },
    ]);
    expect(rows().length).toBe(1);
  });

  it('offers the scheduled day for a scheduled task and no presets without dates', async () => {
    await render();
    host.anchors.set({ scheduledDate: '2026-11-18' });
    await refresh();
    expect(buttons('.chip').map((b) => b.textContent?.trim())).toEqual(['On the scheduled day']);
    host.anchors.set(null);
    await refresh();
    expect(element.querySelector('.presets')).toBeNull();
  });

  it('adds a reminder at an exact local date and time', async () => {
    await render();
    const input = element.querySelector<HTMLInputElement>('input[type="datetime-local"]')!;
    input.value = '2026-11-15T07:45';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    element.querySelector<HTMLButtonElement>('.exact button')!.click();
    await refresh();
    expect(added).toEqual([
      { taskId: 't1', remindAt: new Date(2026, 10, 15, 7, 45).toISOString() },
    ]);
    expect(input.value).toBe('');
  });

  it('refuses a time that has passed', async () => {
    await render();
    const input = element.querySelector<HTMLInputElement>('input[type="datetime-local"]')!;
    input.value = '2026-11-01T07:45';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    element.querySelector<HTMLButtonElement>('.exact button')!.click();
    await refresh();
    expect(added).toEqual([]);
    expect(banner()).toBe(REMINDER_PAST);
  });

  it('removes a reminder, and treats one already gone as removed', async () => {
    stored['t1'] = [
      reminder('r1', new Date(2026, 10, 20, 8, 0)),
      reminder('r2', new Date(2026, 10, 21, 8, 0)),
    ];
    await render();
    rows()[0].querySelector('button')!.click();
    await refresh();
    failDelete = new ApiError(404, 'Not Found');
    rows()[0].querySelector('button')!.click();
    await refresh();
    expect(deleted).toEqual(['r1', 'r2']);
    expect(rows().length).toBe(0);
    expect(banner()).toBeUndefined();
  });

  it('puts a reminder back and shows the error when removing fails', async () => {
    stored['t1'] = [reminder('r1', new Date(2026, 10, 20, 8, 0))];
    await render();
    failDelete = new Error('offline');
    rows()[0].querySelector('button')!.click();
    await refresh();
    expect(rows().length).toBe(1);
    expect(banner()).toBe('Something went wrong. Try again.');
  });

  it('shows the error when adding or loading fails', async () => {
    failLoad = new Error('offline');
    await render();
    expect(banner()).toBe('Something went wrong. Try again.');
    failAdd = new Error('offline');
    buttons('.chip')[0].click();
    await refresh();
    expect(rows().length).toBe(0);
    expect(banner()).toBe('Something went wrong. Try again.');
  });

  it('reloads for another task', async () => {
    stored['t2'] = [reminder('x', new Date(2026, 10, 22, 8, 0))];
    await render();
    expect(rows().length).toBe(0);
    host.id.set('t2');
    await refresh();
    expect(rows().length).toBe(1);
  });

  const exactInput = () => element.querySelector<HTMLInputElement>('input[type="datetime-local"]')!;
  const exactButton = () => element.querySelector<HTMLButtonElement>('.exact button')!;
  function typeExact(value: string): void {
    exactInput().value = value;
    exactInput().dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  it('adds the exact time with the Enter key', async () => {
    await render();
    typeExact('2026-11-15T07:45');
    exactInput().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    await refresh();
    expect(added).toEqual([
      { taskId: 't1', remindAt: new Date(2026, 10, 15, 7, 45).toISOString() },
    ]);
  });

  it('disables the exact add until a complete date and time is typed', async () => {
    await render();
    expect(exactButton().disabled).toBe(true);
    // The input sanitizes an incomplete value to empty, as browsers do.
    typeExact('2026-11-15');
    expect(exactButton().disabled).toBe(true);
    exactInput().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    await refresh();
    expect(added).toEqual([]);
    typeExact('2026-11-15T07:45');
    expect(exactButton().disabled).toBe(false);
  });

  it('refuses a reminder exactly at the current time', async () => {
    await render();
    typeExact('2026-11-10T12:00');
    exactButton().click();
    await refresh();
    expect(added).toEqual([]);
    expect(banner()).toBe(REMINDER_PAST);
  });

  it('lists added reminders in time order', async () => {
    stored['t1'] = [reminder('r1', new Date(2026, 10, 20, 8, 0))];
    await render();
    typeExact('2026-11-15T07:45');
    exactButton().click();
    await refresh();
    expect(rows().map((row) => row.textContent)).toEqual([
      expect.stringContaining('15 Nov'),
      expect.stringContaining('20 Nov'),
    ]);
  });

  it('shows the server message of a failed add', async () => {
    await render();
    failAdd = new ApiError(400, 'Bad Request', 'The reminder time is invalid.');
    buttons('.chip')[0].click();
    await refresh();
    expect(banner()).toBe('The reminder time is invalid.');
    expect(rows().length).toBe(0);
  });

  it('clears a previous error when the next change succeeds', async () => {
    await render();
    typeExact('2026-11-01T07:45');
    exactButton().click();
    await refresh();
    expect(banner()).toBe(REMINDER_PAST);
    buttons('.chip')[0].click();
    await refresh();
    expect(banner()).toBeUndefined();
    expect(rows().length).toBe(1);
  });

  it('disables the controls while a change is pending and ignores a second submit', async () => {
    stored['t1'] = [reminder('r1', new Date(2026, 10, 21, 8, 0))];
    await render();
    const gate = deferred();
    addGate = gate.promise;
    buttons('.chip')[0].click();
    await refresh();
    expect(buttons('.chip').every((b) => b.disabled)).toBe(true);
    expect(rows()[0].querySelector('button')!.disabled).toBe(true);
    const component = fixture.debugElement.children[0].componentInstance as TaskReminders;
    await component.add(new Date(2026, 10, 19, 8, 0));
    await component.remove(stored['t1'][0]);
    expect(added.length).toBe(1);
    expect(deleted).toEqual([]);
    gate.resolve();
    await refresh();
    expect(buttons('.chip').some((b) => b.disabled)).toBe(false);
    expect(rows().length).toBe(2);
  });

  it('removes a reminder from the list before the server answers', async () => {
    stored['t1'] = [reminder('r1', new Date(2026, 10, 20, 8, 0))];
    await render();
    const gate = deferred();
    deleteGate = gate.promise;
    rows()[0].querySelector('button')!.click();
    await refresh();
    expect(rows().length).toBe(0);
    gate.resolve();
    await refresh();
    expect(rows().length).toBe(0);
  });

  it('does not add a reminder to another task when the task changed while adding', async () => {
    await render();
    const gate = deferred();
    addGate = gate.promise;
    buttons('.chip')[0].click();
    await refresh();
    host.id.set('t2');
    await refresh();
    gate.resolve();
    await refresh();
    expect(added.map((a) => a.taskId)).toEqual(['t1']);
    expect(rows().length).toBe(0);
  });

  it('shows no error on another task when an add of the previous one fails late', async () => {
    await render();
    const gate = deferred();
    addGate = gate.promise;
    failAdd = new Error('offline');
    buttons('.chip')[0].click();
    await refresh();
    host.id.set('t2');
    await refresh();
    gate.resolve();
    await refresh();
    expect(banner()).toBeUndefined();
  });

  it('does not put a reminder back on another task when a remove fails late', async () => {
    stored['t1'] = [reminder('r1', new Date(2026, 10, 20, 8, 0))];
    stored['t2'] = [reminder('x', new Date(2026, 10, 22, 8, 0))];
    await render();
    const gate = deferred();
    deleteGate = gate.promise;
    failDelete = new Error('offline');
    rows()[0].querySelector('button')!.click();
    await refresh();
    host.id.set('t2');
    await refresh();
    gate.resolve();
    await refresh();
    expect(rows().length).toBe(1);
    expect(rows()[0].textContent).toContain('22 Nov');
    expect(banner()).toBeUndefined();
  });

  it('ignores a late answer for the previous task when loading', async () => {
    stored['t1'] = [reminder('old', new Date(2026, 10, 20, 8, 0))];
    stored['t2'] = [reminder('x', new Date(2026, 10, 22, 8, 0))];
    const slow = deferred();
    loadGate['t1'] = slow.promise;
    await render();
    host.id.set('t2');
    await refresh();
    slow.resolve();
    await refresh();
    expect(rows().length).toBe(1);
    expect(rows()[0].textContent).toContain('22 Nov');
  });

  it('ignores a late load failure for the previous task', async () => {
    stored['t2'] = [reminder('x', new Date(2026, 10, 22, 8, 0))];
    const slow = deferred();
    loadGate['t1'] = slow.promise;
    await render();
    host.id.set('t2');
    await refresh();
    slow.reject(new Error('offline'));
    await refresh();
    expect(banner()).toBeUndefined();
    expect(rows().length).toBe(1);
  });

  it('keeps the controls disabled until the list has loaded, so it cannot overwrite an add', async () => {
    stored['t1'] = [reminder('r1', new Date(2026, 10, 21, 8, 0))];
    const slow = deferred();
    loadGate['t1'] = slow.promise;
    await render();
    expect(buttons('.chip').every((b) => b.disabled)).toBe(true);
    typeExact('2026-11-15T07:45');
    expect(exactButton().disabled).toBe(true);
    exactInput().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    const component = fixture.debugElement.children[0].componentInstance as TaskReminders;
    await component.add(new Date(2026, 10, 19, 8, 0));
    expect(added).toEqual([]);
    expect(element.querySelector('.empty')).toBeNull();
    slow.resolve();
    await refresh();
    expect(rows().length).toBe(1);
    expect(buttons('.chip').some((b) => b.disabled)).toBe(false);
    exactButton().click();
    await refresh();
    expect(added.length).toBe(1);
    expect(rows().length).toBe(2);
  });

  it('does not let a reloading list bring back a reminder removed meanwhile', async () => {
    stored['t1'] = [reminder('r1', new Date(2026, 10, 21, 8, 0))];
    failLoad = new Error('offline');
    await render();
    buttons('.chip')[0].click();
    await refresh();
    expect(rows().length).toBe(1);
    failLoad = undefined;
    const slow = deferred();
    loadGate['t1'] = slow.promise;
    buttons('.retry')[0].click();
    await refresh();
    expect(rows()[0].querySelector('button')!.disabled).toBe(true);
    const component = fixture.debugElement.children[0].componentInstance as TaskReminders;
    await component.remove(stored['t1'][0]);
    expect(deleted).toEqual([]);
    slow.resolve();
    await refresh();
    expect(rows().length).toBe(1);
    rows()[0].querySelector('button')!.click();
    await refresh();
    expect(deleted).toEqual(['r1']);
    expect(rows().length).toBe(0);
  });

  it('offers a retry instead of claiming there are none when loading fails', async () => {
    stored['t1'] = [reminder('r1', new Date(2026, 10, 21, 8, 0))];
    failLoad = new Error('offline');
    await render();
    expect(banner()).toBe('Something went wrong. Try again.');
    expect(element.querySelector('.empty')).toBeNull();
    expect(buttons('.retry').map((b) => b.textContent?.trim())).toEqual(['Retry']);
    // A later successful add keeps the retry, since the other reminders are still unknown.
    buttons('.chip')[0].click();
    await refresh();
    expect(banner()).toBeUndefined();
    expect(buttons('.retry').length).toBe(1);
    failLoad = undefined;
    buttons('.retry')[0].click();
    await refresh();
    expect(buttons('.retry').length).toBe(0);
    expect(banner()).toBeUndefined();
    expect(rows().length).toBe(1);
  });

  it('shows the error again and keeps the retry when the retry fails too', async () => {
    failLoad = new Error('offline');
    await render();
    buttons('.retry')[0].click();
    await refresh();
    expect(loads).toEqual(['t1', 't1']);
    expect(banner()).toBe('Something went wrong. Try again.');
    expect(buttons('.retry').length).toBe(1);
    expect(element.querySelector('.empty')).toBeNull();
  });

  it('does not reload while an add after a failed load is pending', async () => {
    failLoad = new Error('offline');
    await render();
    const gate = deferred();
    addGate = gate.promise;
    buttons('.chip')[0].click();
    await refresh();
    // A retry while the add is pending would load a list without it, so it waits.
    expect(buttons('.retry')[0].disabled).toBe(true);
    buttons('.retry')[0].click();
    await refresh();
    expect(loads).toEqual(['t1']);
    gate.resolve();
    await refresh();
    expect(rows().length).toBe(1);
    expect(buttons('.retry').length).toBe(1);
    expect(buttons('.retry')[0].disabled).toBe(false);
  });

  it('drops the retry when switching to a task whose list loads', async () => {
    failLoad = new Error('offline');
    await render();
    expect(buttons('.retry').length).toBe(1);
    failLoad = undefined;
    host.id.set('t2');
    await refresh();
    expect(buttons('.retry').length).toBe(0);
    expect(banner()).toBeUndefined();
    expect(element.querySelector('.empty')?.textContent).toContain('No reminders.');
  });

  it('ignores a late answer from an earlier visit of the same task', async () => {
    stored['t1'] = [reminder('r1', new Date(2026, 10, 21, 8, 0))];
    const slow = deferred();
    loadGate['t1'] = slow.promise;
    await render();
    host.id.set('t2');
    await refresh();
    // Back on t1: this visit's load answers at once, and the user adds a reminder.
    delete loadGate['t1'];
    host.id.set('t1');
    await refresh();
    expect(rows().length).toBe(1);
    buttons('.chip')[0].click();
    await refresh();
    expect(rows().length).toBe(2);
    // The load of the first visit answers last, with the list from before the add.
    slow.resolve();
    await refresh();
    expect(rows().length).toBe(2);
  });

  it('ignores a late load failure from an earlier visit of the same task', async () => {
    const slow = deferred();
    loadGate['t1'] = slow.promise;
    await render();
    host.id.set('t2');
    await refresh();
    delete loadGate['t1'];
    host.id.set('t1');
    await refresh();
    slow.reject(new Error('offline'));
    await refresh();
    expect(banner()).toBeUndefined();
    expect(buttons('.retry').length).toBe(0);
    expect(element.querySelector('.empty')?.textContent).toContain('No reminders.');
  });

  it('clears the typed time and the error when the task changes', async () => {
    await render();
    typeExact('2026-11-01T07:45');
    exactButton().click();
    await refresh();
    expect(banner()).toBe(REMINDER_PAST);
    typeExact('2026-11-16T07:45');
    host.id.set('t2');
    await refresh();
    expect(banner()).toBeUndefined();
    expect(exactInput().value).toBe('');
  });
});
