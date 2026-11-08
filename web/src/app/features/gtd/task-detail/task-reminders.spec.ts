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
  let added: { taskId: string; remindAt: string }[];
  let deleted: string[];
  let failAdd: unknown;
  let failDelete: unknown;
  let failLoad: unknown;
  let element: HTMLElement;
  let host: Host;
  let fixture: ReturnType<typeof TestBed.createComponent<Host>>;

  beforeEach(() => {
    stored = { t1: [], t2: [] };
    added = [];
    deleted = [];
    failAdd = failDelete = failLoad = undefined;
    let seq = 0;
    const service = {
      reminders: async (taskId: string) => {
        if (failLoad) throw failLoad;
        return stored[taskId] ?? [];
      },
      addReminder: async (taskId: string, remindAt: string) => {
        added.push({ taskId, remindAt });
        if (failAdd) throw failAdd;
        return reminder(`new-${++seq}`, new Date(remindAt));
      },
      deleteReminder: async (id: string) => {
        deleted.push(id);
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
});
