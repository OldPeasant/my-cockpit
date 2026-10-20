import { HttpContext } from '@angular/common/http';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject } from 'rxjs';
import {
  Api,
  ChangeEvent,
  ChecklistItemResponse,
  getTask,
  listChecklistItems,
  listContexts,
  listLinks,
  listProjects,
  moveTask,
  ProjectResponse,
  TaskResponse,
  updateTask,
} from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { ApiError, SILENT_ERRORS } from '../../../core/http';
import { CONFLICT_MESSAGE, TaskDetail } from './task-detail';

interface Call {
  fn: unknown;
  params: Record<string, unknown>;
  context?: HttpContext;
}

function task(fields: Partial<TaskResponse> = {}): TaskResponse {
  return {
    id: 't1',
    title: 'Call the plumber',
    list: 'NEXT',
    position: 1,
    contextIds: [],
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-01T08:00:00Z',
    version: 1,
    ...fields,
  };
}

const PROJECTS = [
  { id: 'p1', title: 'Kitchen', status: 'ACTIVE' },
  { id: 'p2', title: 'Old garden', status: 'COMPLETED' },
] as ProjectResponse[];

const settle = () => new Promise((resolve) => setTimeout(resolve));

@Component({
  imports: [TaskDetail],
  template: `<app-task-detail [taskId]="id()" (closed)="closed = closed + 1" />`,
})
class Host {
  readonly id = signal('t1');
  closed = 0;
}

describe('TaskDetail', () => {
  let calls: Call[];
  let server: TaskResponse;
  let checklist: ChecklistItemResponse[];
  let element: HTMLElement;
  let events: Subject<ChangeEvent>;
  let answer: ((fn: unknown, params: Record<string, unknown>) => unknown) | undefined;

  const callsOf = (fn: unknown) => calls.filter((call) => call.fn === fn);

  async function refresh(): Promise<void> {
    for (let i = 0; i < 3; i++) {
      await settle();
      TestBed.tick();
    }
  }

  async function render(initial: TaskResponse = task()): Promise<void> {
    server = initial;
    const fixture = TestBed.createComponent(Host);
    element = fixture.nativeElement;
    document.body.appendChild(element);
    fixture.detectChanges();
    await refresh();
  }

  const field = <T extends HTMLElement = HTMLInputElement>(selector: string) =>
    element.querySelector<T>(selector)!;
  const saveButton = () => field<HTMLButtonElement>('button[type=submit]');
  const text = () => element.textContent ?? '';

  async function type(selector: string, value: string): Promise<void> {
    const input = field<HTMLInputElement | HTMLTextAreaElement>(selector);
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await refresh();
  }

  async function choose(selector: string, value: string): Promise<void> {
    const select = field<HTMLSelectElement>(selector);
    select.value = value;
    select.dispatchEvent(new Event('change'));
    await refresh();
  }

  async function save(): Promise<void> {
    saveButton().click();
    await refresh();
  }

  beforeEach(() => {
    calls = [];
    checklist = [];
    answer = undefined;
    const api = {
      invoke: async (fn: unknown, params: Record<string, unknown> = {}, context?: HttpContext) => {
        calls.push({ fn, params, context });
        const custom = answer?.(fn, params);
        if (custom) return custom;
        if (fn === getTask) return server;
        if (fn === listChecklistItems) return { items: checklist };
        if (fn === listContexts) return { items: [{ id: 'c1', name: '@home', position: 1 }] };
        if (fn === listProjects) return { items: PROJECTS };
        if (fn === listLinks) return { items: [] };
        if (fn === updateTask || fn === moveTask) {
          server = { ...server, ...(params['body'] as object), version: server.version + 1 };
          return server;
        }
        throw new Error('unexpected call');
      },
    };
    events = new Subject<ChangeEvent>();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: Api, useValue: api },
        {
          provide: ChangeEventsService,
          useValue: { resync$: new Subject<void>(), events$: () => events },
        },
      ],
    });
  });

  afterEach(() => element?.remove());

  it('shows every field of the task', async () => {
    await render(
      task({
        notesMd: '# Notes',
        projectId: 'p1',
        dueDate: '2026-10-30',
        dueTime: '09:30:00',
        recurrenceRule: 'FREQ=WEEKLY;BYDAY=MO',
        recurrenceMode: 'AFTER_COMPLETION',
      }),
    );
    expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Call the plumber');
    expect(field<HTMLTextAreaElement>('#td-notes').value).toBe('# Notes');
    expect(field<HTMLSelectElement>('#td-list').value).toBe('NEXT');
    expect(field<HTMLSelectElement>('#td-project').value).toBe('p1');
    expect(field('#td-due').value).toBe('2026-10-30');
    expect(field('input[type=time]').value).toBe('09:30');
    expect(field<HTMLSelectElement>('#td-repeat').value).toBe('custom');
    expect(field('#td-rule').value).toBe('FREQ=WEEKLY;BYDAY=MO');
    expect(text()).toContain('@home');
    expect(element.querySelector('app-linked-items')).not.toBeNull();
    expect(element.querySelector('app-task-checklist')).not.toBeNull();
    // Completed projects are not offered.
    expect(text()).not.toContain('Old garden');
    expect(saveButton().disabled).toBe(true);
  });

  it('saves changed fields with If-Match, silently', async () => {
    await render();
    await type('#td-title', 'Call the plumber today');
    await type('#td-estimate', '15');
    expect(text()).toContain('Unsaved changes');

    await save();

    expect(callsOf(updateTask)).toHaveLength(1);
    const call = callsOf(updateTask)[0];
    expect(call.params).toMatchObject({
      id: 't1',
      'If-Match': '"1"',
      body: { title: 'Call the plumber today', timeEstimateMin: 15 },
    });
    expect(call.context?.get(SILENT_ERRORS)).toBe(true);
    expect(text()).toContain('Saved');
    expect(saveButton().disabled).toBe(true);
  });

  it('moves first, then patches with the new version', async () => {
    await render();
    await choose('#td-list', 'WAITING');
    await type('#td-waiting', 'Anna');

    await save();

    expect(callsOf(moveTask)[0].params).toMatchObject({
      'If-Match': '"1"',
      body: { list: 'WAITING' },
    });
    expect(callsOf(updateTask)[0].params).toMatchObject({
      'If-Match': '"2"',
      body: { waitingForText: 'Anna' },
    });
  });

  it('does not save an invalid form and shows why', async () => {
    await render();
    await type('#td-title', '  ');
    expect(text()).toContain('Give the task a title.');
    expect(saveButton().disabled).toBe(true);

    await type('#td-title', 'Fine');
    await choose('#td-list', 'SCHEDULED');
    expect(text()).toContain('Scheduled tasks need a date.');
    expect(saveButton().disabled).toBe(true);

    field('form').dispatchEvent(new Event('submit'));
    await refresh();
    expect(callsOf(moveTask)).toHaveLength(0);
  });

  it('on 412 says the task changed elsewhere and reloads it', async () => {
    await render();
    await type('#td-title', 'Mine');
    answer = (fn) => {
      if (fn !== updateTask) return undefined;
      server = task({ title: 'Theirs', version: 5 });
      return Promise.reject(new ApiError(412, 'Precondition Failed'));
    };

    await save();

    expect(text()).toContain(CONFLICT_MESSAGE);
    expect(callsOf(getTask)).toHaveLength(2);
    expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Theirs');
    expect(saveButton().disabled).toBe(true);

    // The next save goes out with the reloaded version.
    answer = undefined;
    await type('#td-title', 'Mine again');
    await save();
    expect(callsOf(updateTask)[1].params).toMatchObject({ 'If-Match': '"5"' });
    expect(text()).not.toContain(CONFLICT_MESSAGE);
  });

  it('shows server field errors next to the field', async () => {
    await render();
    await choose('#td-repeat', 'custom');
    await type('#td-rule', 'FREQ=DAILY;BYMONTH=2');
    answer = (fn) =>
      fn === updateTask
        ? Promise.reject(
            new ApiError(400, 'Bad Request', undefined, undefined, undefined, [
              { field: 'recurrenceRule', message: 'is not a valid RRULE' },
            ]),
          )
        : undefined;

    await save();

    expect(text()).toContain('is not a valid RRULE');
  });

  it('applies a recurrence preset and its mode', async () => {
    await render();
    await choose('#td-repeat', 'weekdays');
    const after = [...element.querySelectorAll<HTMLButtonElement>('app-segmented button')].find(
      (b) => b.textContent?.includes('After completion'),
    )!;
    after.click();
    await refresh();

    await save();

    expect(callsOf(updateTask)[0].params['body']).toEqual({
      recurrenceRule: 'FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR',
      recurrenceMode: 'AFTER_COMPLETION',
    });
  });

  it('starts a custom rule from the chosen preset', async () => {
    await render(task({ recurrenceRule: 'FREQ=MONTHLY', recurrenceMode: 'FIXED' }));
    await choose('#td-repeat', 'custom');
    expect(field('#td-rule').value).toBe('FREQ=MONTHLY');
    await type('#td-rule', 'FREQ=HOURLY');
    expect(text()).toContain('Tasks repeat DAILY, WEEKLY, MONTHLY or YEARLY.');
    expect(saveButton().disabled).toBe(true);
  });

  it('discards the changes', async () => {
    await render();
    await type('#td-title', 'Changed');
    [...element.querySelectorAll('button')]
      .find((b) => b.textContent?.includes('Discard'))!
      .click();
    await refresh();
    expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Call the plumber');
    expect(saveButton().disabled).toBe(true);
  });

  it('saves with Ctrl+Enter', async () => {
    await render();
    await type('#td-title', 'Quick');
    field('#td-title').dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, bubbles: true }),
    );
    await refresh();
    expect(callsOf(updateTask)).toHaveLength(1);
  });

  it('shows a missing task', async () => {
    answer = (fn) => (fn === getTask ? Promise.reject(new ApiError(404, 'Not Found')) : undefined);
    await render();
    expect(text()).toContain('This task does not exist or is in the Trash.');
  });

  it('reloads on a change event unless the user is editing', async () => {
    await render();
    server = task({ title: 'Renamed elsewhere', version: 2 });
    events.next({ type: 'TASK', id: 't1', op: 'UPDATED' } as ChangeEvent);
    await refresh();
    expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Renamed elsewhere');

    await type('#td-title', 'Typing');
    events.next({ type: 'TASK', id: 't1', op: 'UPDATED' } as ChangeEvent);
    await refresh();
    expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Typing');

    events.next({ type: 'TASK', id: 't1', op: 'DELETED' } as ChangeEvent);
    await refresh();
    expect(text()).toContain('This task was moved to the Trash.');
  });
});
