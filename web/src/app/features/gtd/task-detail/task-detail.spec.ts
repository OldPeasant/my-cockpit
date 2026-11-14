import { HttpContext } from '@angular/common/http';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideRouter, Router } from '@angular/router';
import { Subject } from 'rxjs';
import {
  Api,
  ChangeEvent,
  ChecklistItemResponse,
  completeTask,
  createProject,
  convertTaskToEvent,
  convertTaskToNote,
  ConvertToEventRequest,
  deleteTask,
  EventResponse,
  getTask,
  listChecklistItems,
  listContexts,
  listLinks,
  listProjects,
  listReminders,
  moveTask,
  NoteResponse,
  ProjectResponse,
  TaskResponse,
  uncompleteTask,
  updateTask,
} from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { ApiError, SILENT_ERRORS } from '../../../core/http';
import { ProjectStore } from '../data';
import { TaskUndo } from '../ui';
import { CONFLICT_MESSAGE, TaskDetail } from './task-detail';
import { ToCalendarData, ToCalendarDialog } from './to-calendar-dialog';

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
  template: `<app-task-detail
    [taskId]="id()"
    [backLabel]="backLabel()"
    (closed)="closed = closed + 1"
  />`,
})
class Host {
  readonly id = signal('t1');
  readonly backLabel = signal<string | null>(null);
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

  let host: Host;

  async function render(initial: TaskResponse = task()): Promise<void> {
    server = initial;
    const fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
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
        if (fn === listReminders) return { items: [] };
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
    // Notes show rendered; Edit switches to the Markdown source.
    expect(element.querySelector('#td-notes-view h3')?.textContent).toBe('Notes');
    expect(element.querySelector('#td-notes')).toBeNull();
    field<HTMLButtonElement>('button.edit-notes').click();
    await refresh();
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
    expect(element.querySelector('app-task-reminders')).not.toBeNull();
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

  describe('close', () => {
    const closeButton = () => field<HTMLButtonElement>('button[aria-label="Close"]');

    afterEach(() => vi.restoreAllMocks());

    it('closes at once without changes', async () => {
      const confirm = vi.spyOn(window, 'confirm');
      await render();
      closeButton().click();
      expect(confirm).not.toHaveBeenCalled();
      expect(host.closed).toBe(1);
    });

    it('asks before dropping unsaved changes and stays when the user cancels', async () => {
      const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
      await render();
      await type('#td-title', 'Half typed');
      closeButton().click();
      await refresh();
      expect(confirm).toHaveBeenCalledWith('Discard your unsaved changes?');
      expect(host.closed).toBe(0);
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Half typed');
    });

    it('closes after the user confirms dropping the changes', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      await render();
      await type('#td-title', 'Half typed');
      closeButton().click();
      expect(host.closed).toBe(1);
      expect(callsOf(updateTask)).toHaveLength(0);
    });

    it('names where closing leads on the close button', async () => {
      await render();
      host.backLabel.set('Today');
      await refresh();
      const back = field<HTMLButtonElement>('header.top button');
      expect(back.getAttribute('aria-label')).toBe('Back to Today');
      expect(back.querySelector('.back-label')?.textContent?.trim()).toBe('Today');
      expect(back.classList).toContain('back');
      expect(back.classList).not.toContain('icon');
      back.click();
      expect(host.closed).toBe(1);
    });

    it('is an icon button reading Close without a destination name', async () => {
      await render();
      const button = field<HTMLButtonElement>('header.top button');
      expect(button).toBe(closeButton());
      expect(button.querySelector('.back-label')).toBeNull();
      expect(button.classList).toContain('icon');
      expect(button.classList).not.toContain('back');
    });

    it('goes back to the Close icon when the destination name is dropped', async () => {
      await render();
      host.backLabel.set('Launch the website');
      await refresh();
      expect(field('header.top button').getAttribute('aria-label')).toBe(
        'Back to Launch the website',
      );
      host.backLabel.set(null);
      await refresh();
      expect(field('header.top button').getAttribute('aria-label')).toBe('Close');
      expect(element.querySelector('.back-label')).toBeNull();
    });

    it('goes back from a missing task', async () => {
      answer = (fn) =>
        fn === getTask ? Promise.reject(new ApiError(404, 'Not Found')) : undefined;
      await render();
      [...element.querySelectorAll('button')].find((b) => b.textContent?.includes('Back'))!.click();
      expect(host.closed).toBe(1);
    });

    it('goes back from a task moved to the Trash', async () => {
      await render();
      events.next({ type: 'TASK', id: 't1', op: 'DELETED' } as ChangeEvent);
      await refresh();
      [...element.querySelectorAll('button')].find((b) => b.textContent?.includes('Back'))!.click();
      expect(host.closed).toBe(1);
    });
  });

  describe('save errors', () => {
    it('shows a generic API error, keeps the changes and allows another try', async () => {
      await render();
      await type('#td-title', 'Keep me');
      answer = (fn) =>
        fn === updateTask
          ? Promise.reject(new ApiError(500, 'Internal Server Error', 'The server failed.'))
          : undefined;

      await save();

      expect(element.querySelector('.bar [role=alert]')?.textContent).toContain(
        'The server failed.',
      );
      expect(text()).not.toContain(CONFLICT_MESSAGE);
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Keep me');
      expect(saveButton().disabled).toBe(false);
      expect(callsOf(getTask)).toHaveLength(1);

      answer = undefined;
      await save();
      expect(callsOf(updateTask)).toHaveLength(2);
      expect(element.querySelector('.bar [role=alert]')).toBeNull();
      expect(text()).toContain('Saved');
    });

    it('shows a fallback message for an unexpected error', async () => {
      await render();
      await type('#td-title', 'Keep me');
      answer = (fn) => (fn === updateTask ? Promise.reject(new TypeError('boom')) : undefined);
      await save();
      expect(text()).toContain('Something went wrong. Try again.');
    });

    it('keeps a move that went through when the patch after it fails', async () => {
      await render();
      await choose('#td-list', 'WAITING');
      await type('#td-waiting', 'Bea');
      answer = (fn) =>
        fn === updateTask ? Promise.reject(new ApiError(503, 'Service Unavailable')) : undefined;

      await save();
      expect(text()).toContain('Service Unavailable');
      expect(callsOf(moveTask)).toHaveLength(1);

      // The retry does not move again and patches the moved version.
      answer = undefined;
      await save();
      expect(callsOf(moveTask)).toHaveLength(1);
      expect(callsOf(updateTask)[1].params).toMatchObject({
        'If-Match': '"2"',
        body: { waitingForText: 'Bea' },
      });
    });

    it('shows a server error on the estimate under the estimate field', async () => {
      await render();
      await type('#td-estimate', '30');
      answer = (fn) =>
        fn === updateTask
          ? Promise.reject(
              new ApiError(400, 'Bad Request', undefined, undefined, undefined, [
                { field: 'timeEstimateMin', message: 'is too large' },
              ]),
            )
          : undefined;
      await save();
      expect(field('#td-estimate').getAttribute('aria-invalid')).toBe('true');
      expect(text()).toContain('is too large');

      // Editing clears the server errors.
      await type('#td-estimate', '20');
      expect(text()).not.toContain('is too large');
    });

    it('shows a 400 error of a field the form does not show in the save bar', async () => {
      await render();
      await type('#td-title', 'Keep me');
      await type('#td-estimate', '30');
      answer = (fn) =>
        fn === updateTask
          ? Promise.reject(
              new ApiError(400, 'Bad Request', undefined, undefined, undefined, [
                { field: 'contextIds', message: 'Unknown context.' },
                { field: 'timeEstimateMin', message: 'is too large' },
              ]),
            )
          : undefined;

      await save();

      const bar = element.querySelector('.bar [role=alert]')?.textContent ?? '';
      expect(bar).toContain('Unknown context.');
      // A field the form shows keeps its error at the field, not in the save bar.
      expect(bar).not.toContain('is too large');
      expect(field('#td-estimate').getAttribute('aria-invalid')).toBe('true');
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Keep me');
      expect(saveButton().disabled).toBe(false);
    });

    it('falls back to the message of the answer when a hidden field error has no text', async () => {
      await render();
      await type('#td-title', 'Keep me');
      answer = (fn) =>
        fn === updateTask
          ? Promise.reject(
              new ApiError(400, 'Bad Request', 'The task is invalid.', undefined, undefined, [
                { field: 'projectId', message: '' },
              ]),
            )
          : undefined;

      await save();

      expect(element.querySelector('.bar [role=alert]')?.textContent).toContain(
        'The task is invalid.',
      );
      expect(saveButton().disabled).toBe(false);
    });

    it('shows no save bar error when every 400 error is at a field of the form', async () => {
      await render();
      await type('#td-title', 'Keep me');
      answer = (fn) =>
        fn === updateTask
          ? Promise.reject(
              new ApiError(400, 'Bad Request', undefined, undefined, undefined, [
                { field: 'title', message: 'Too long.' },
                { field: 'recurrenceRule', message: 'Not a valid rule.' },
              ]),
            )
          : undefined;

      await save();

      expect(text()).toContain('Too long.');
      expect(element.querySelector('.bar [role=alert]')).toBeNull();
    });

    it('on 412 of a move says the task changed elsewhere and reloads it', async () => {
      await render();
      await choose('#td-list', 'SOMEDAY');
      answer = (fn) => {
        if (fn !== moveTask) return undefined;
        server = task({ list: 'NEXT', title: 'Moved elsewhere', version: 3 });
        return Promise.reject(new ApiError(412, 'Precondition Failed'));
      };
      await save();
      expect(text()).toContain(CONFLICT_MESSAGE);
      expect(callsOf(updateTask)).toHaveLength(0);
      expect(field<HTMLSelectElement>('#td-list').value).toBe('NEXT');
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Moved elsewhere');
    });
  });

  describe('tickler', () => {
    it('moves the task with its tickler date', async () => {
      await render();
      await type('#td-tickler', '2099-03-01');
      await save();
      expect(callsOf(moveTask)).toHaveLength(1);
      expect(callsOf(moveTask)[0].params).toMatchObject({
        id: 't1',
        'If-Match': '"1"',
        body: { list: 'NEXT', ticklerDate: '2099-03-01' },
      });
      expect(callsOf(updateTask)).toHaveLength(0);
    });

    it('refuses a tickler date that is not in the future', async () => {
      await render();
      await type('#td-tickler', '2001-01-01');
      expect(text()).toContain('Pick a date after today.');
      expect(saveButton().disabled).toBe(true);
    });

    it('sends the scheduled date together with the list', async () => {
      await render();
      await choose('#td-list', 'SCHEDULED');
      await type('#td-scheduled', '2099-04-02');
      await save();
      expect(callsOf(moveTask)[0].params['body']).toEqual({
        list: 'SCHEDULED',
        scheduledDate: '2099-04-02',
      });
    });
  });

  describe('loading', () => {
    it('shows a load failure and loads again on retry', async () => {
      let fail = true;
      answer = (fn) =>
        fn === getTask && fail ? Promise.reject(new ApiError(0, 'Offline')) : undefined;
      await render();
      expect(text()).toContain('The task could not be loaded.');

      fail = false;
      [...element.querySelectorAll('button')]
        .find((b) => b.textContent?.includes('Try again'))!
        .click();
      await refresh();
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Call the plumber');
    });

    it('ignores a change event of another task', async () => {
      await render();
      events.next({ type: 'TASK', id: 'other', op: 'DELETED' } as ChangeEvent);
      events.next({ type: 'TASK', id: 'other', op: 'UPDATED' } as ChangeEvent);
      await refresh();
      expect(callsOf(getTask)).toHaveLength(1);
      expect(field<HTMLTextAreaElement>('#td-title')).not.toBeNull();
    });

    it('shows a background reload failure without leaving the form', async () => {
      await render();
      answer = (fn) =>
        fn === getTask ? Promise.reject(new ApiError(503, 'Service Unavailable')) : undefined;
      events.next({ type: 'TASK', id: 't1', op: 'UPDATED' } as ChangeEvent);
      await refresh();
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Call the plumber');
      expect(text()).toContain('Service Unavailable');
    });

    it('shows only the newest task when the id changes during a load', async () => {
      let releaseFirst!: () => void;
      const first = new Promise<void>((resolve) => (releaseFirst = resolve));
      answer = (fn, params) => {
        if (fn !== getTask) return undefined;
        if (params['id'] === 't1') return first.then(() => task({ title: 'Stale first task' }));
        return Promise.resolve(task({ id: 't2', title: 'Second task' }));
      };
      await render();
      host.id.set('t2');
      await refresh();
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Second task');

      releaseFirst();
      await refresh();
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Second task');
    });

    /** Holds the next GET of the task until `release` is called with its answer. */
    function holdNextGet(): (answer: TaskResponse) => void {
      let release!: (answer: TaskResponse) => void;
      const held = new Promise<TaskResponse>((resolve) => (release = resolve));
      let used = false;
      answer = (fn) => {
        if (fn !== getTask || used) return undefined;
        used = true;
        return held;
      };
      return release;
    }

    it('keeps what the user typed while a background reload was running', async () => {
      await render();
      const release = holdNextGet();
      events.next({ type: 'TASK', id: 't1', op: 'UPDATED' } as ChangeEvent);
      await refresh();
      await type('#td-title', 'Typed meanwhile');

      release(task({ title: 'Renamed elsewhere', version: 2 }));
      await refresh();

      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Typed meanwhile');
      expect(saveButton().disabled).toBe(false);
    });

    it('does not replace a newer save with an older background reload', async () => {
      await render();
      const release = holdNextGet();
      events.next({ type: 'TASK', id: 't1', op: 'UPDATED' } as ChangeEvent);
      await refresh();
      await type('#td-title', 'Saved title');
      await save();
      expect(callsOf(updateTask)[0].params).toMatchObject({ 'If-Match': '"1"' });

      // The GET started before the save answers with version 1.
      release(task({ version: 1 }));
      await refresh();
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Saved title');
      expect(text()).not.toContain(CONFLICT_MESSAGE);

      await type('#td-title', 'Saved again');
      await save();
      expect(callsOf(updateTask)[1].params).toMatchObject({ 'If-Match': '"2"' });
    });

    it('still applies the conflict reload when a background reload was pending', async () => {
      await render();
      let releaseBackground!: (answer: TaskResponse) => void;
      const background = new Promise<TaskResponse>((resolve) => (releaseBackground = resolve));
      let gets = 0;
      answer = (fn) => {
        if (fn === getTask && ++gets === 1) return background;
        if (fn === updateTask) {
          server = task({ title: 'Theirs', version: 5 });
          return Promise.reject(new ApiError(412, 'Precondition Failed'));
        }
        return undefined;
      };
      events.next({ type: 'TASK', id: 't1', op: 'UPDATED' } as ChangeEvent);
      await refresh();
      await type('#td-title', 'Mine');
      await save();

      expect(text()).toContain(CONFLICT_MESSAGE);
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Theirs');

      // The background GET started before the save answers late with the old copy.
      releaseBackground(task({ title: 'Old copy', version: 1 }));
      await refresh();
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Theirs');

      answer = undefined;
      await type('#td-title', 'Mine again');
      await save();
      expect(callsOf(updateTask)[1].params).toMatchObject({ 'If-Match': '"5"' });
    });

    it('shows a change made elsewhere after the own save', async () => {
      await render();
      await type('#td-title', 'Saved title');
      await save();
      server = task({ title: 'Renamed elsewhere', version: 3 });
      events.next({ type: 'TASK', id: 't1', op: 'UPDATED' } as ChangeEvent);
      await refresh();
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Renamed elsewhere');
      expect(saveButton().disabled).toBe(true);
    });

    it('ignores a background answer older than the version shown', async () => {
      await render(task({ version: 3 }));
      server = task({ title: 'Older copy', version: 2 });
      events.next({ type: 'TASK', id: 't1', op: 'UPDATED' } as ChangeEvent);
      await refresh();
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Call the plumber');

      server = task({ title: 'Newer copy', version: 4 });
      events.next({ type: 'TASK', id: 't1', op: 'UPDATED' } as ChangeEvent);
      await refresh();
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Newer copy');
    });

    it('ignores a late load failure of the previous task', async () => {
      let failFirst!: () => void;
      const first = new Promise<never>(
        (_, reject) => (failFirst = () => reject(new ApiError(404, 'Not Found'))),
      );
      answer = (fn, params) => {
        if (fn !== getTask) return undefined;
        if (params['id'] === 't1') return first;
        return Promise.resolve(task({ id: 't2', title: 'Second task' }));
      };
      await render();
      host.id.set('t2');
      await refresh();

      failFirst();
      await refresh();
      expect(text()).not.toContain('This task does not exist');
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Second task');
    });
  });

  it('keeps the repeat mode when the chosen mode is pressed again', async () => {
    await render(task({ recurrenceRule: 'FREQ=DAILY', recurrenceMode: 'AFTER_COMPLETION' }));
    const after = [...element.querySelectorAll<HTMLButtonElement>('app-segmented button')].find(
      (b) => b.textContent?.includes('After completion'),
    )!;
    expect(after.getAttribute('aria-pressed')).toBe('true');
    after.click();
    await refresh();
    expect(after.getAttribute('aria-pressed')).toBe('true');
    expect(saveButton().disabled).toBe(true);
  });

  it('switches the repeat mode after the chosen one was pressed again', async () => {
    await render(task({ recurrenceRule: 'FREQ=DAILY', recurrenceMode: 'AFTER_COMPLETION' }));
    const modeButton = (label: string) =>
      [...element.querySelectorAll<HTMLButtonElement>('app-segmented button')].find((b) =>
        b.textContent?.includes(label),
      )!;
    modeButton('After completion').click();
    await refresh();
    modeButton('On schedule').click();
    await refresh();
    expect(modeButton('On schedule').getAttribute('aria-pressed')).toBe('true');
    expect(modeButton('After completion').getAttribute('aria-pressed')).toBe('false');

    await save();
    expect(callsOf(updateTask)[0].params).toMatchObject({ body: { recurrenceMode: 'FIXED' } });
  });

  it('links a server rule error to the repeat choice when no custom rule is edited', async () => {
    await render(task({ recurrenceRule: 'FREQ=DAILY', recurrenceMode: 'FIXED' }));
    expect(field('#td-repeat').getAttribute('aria-describedby')).toBeNull();
    await type('#td-title', 'Renamed');
    answer = (fn) =>
      fn === updateTask
        ? Promise.reject(
            new ApiError(400, 'Bad Request', undefined, undefined, undefined, [
              { field: 'recurrenceRule', message: 'is not supported' },
            ]),
          )
        : undefined;
    await save();
    const describedBy = field('#td-repeat').getAttribute('aria-describedby');
    expect(describedBy).toBe('td-repeat-error');
    expect(element.querySelector(`#${describedBy}`)?.textContent).toContain('is not supported');
  });

  it('links every field error to its field', async () => {
    await render(task({ list: 'SCHEDULED', scheduledDate: '2026-11-01' }));
    const describedBy = (selector: string) => field(selector).getAttribute('aria-describedby');
    const errorText = (selector: string) =>
      element.querySelector(`#${describedBy(selector)}`)?.textContent?.trim();

    expect(describedBy('#td-scheduled')).toBeNull();
    await type('#td-scheduled', '');
    expect(errorText('#td-scheduled')).toBeTruthy();

    await type('#td-estimate', '1.5');
    expect(errorText('#td-estimate')).toBeTruthy();

    await type('#td-tickler', '2000-01-01');
    expect(errorText('#td-tickler')).toBeTruthy();

    await choose('#td-repeat', 'custom');
    await type('#td-rule', 'FREQ=HOURLY');
    expect(errorText('#td-rule')).toBe('Tasks repeat DAILY, WEEKLY, MONTHLY or YEARLY.');
    // The rule's error sits in the rule's row.
    expect(field('#td-rule').closest('.row')!.textContent).toContain(
      'Tasks repeat DAILY, WEEKLY, MONTHLY or YEARLY.',
    );

    await choose('#td-list', 'WAITING');
    await type('#td-waiting', 'x'.repeat(501));
    expect(errorText('#td-waiting')).toBe('Keep this under 500 characters.');
  });

  it('links a server error on notes and due time to the field', async () => {
    await render();
    await type('#td-notes', 'Some notes');
    answer = (fn) =>
      fn === updateTask
        ? Promise.reject(
            new ApiError(400, 'Bad Request', undefined, undefined, undefined, [
              { field: 'notesMd', message: 'is too long' },
              { field: 'dueTime', message: 'needs a due date' },
            ]),
          )
        : undefined;
    await save();
    expect(
      element.querySelector(`#${field('#td-notes').getAttribute('aria-describedby')}`)?.textContent,
    ).toContain('is too long');
    const time = field('input[type=time]');
    expect(
      element.querySelector(`#${time.getAttribute('aria-describedby')}`)?.textContent,
    ).toContain('needs a due date');
  });

  it('announces the save shortcuts on the save button', async () => {
    await render();
    expect(saveButton().getAttribute('aria-keyshortcuts')).toBe(
      'Control+Enter Meta+Enter Control+S Meta+S',
    );
    expect(saveButton().querySelector('kbd')?.getAttribute('aria-hidden')).toBe('true');
  });

  it('removes the recurrence with "none"', async () => {
    await render(task({ recurrenceRule: 'FREQ=DAILY', recurrenceMode: 'FIXED' }));
    await choose('#td-repeat', 'none');
    await save();
    expect(callsOf(updateTask)[0].params['body']).toEqual({
      recurrenceRule: null,
      recurrenceMode: null,
    });
  });

  it('edits energy, creativity, estimate, project and due date and saves them', async () => {
    await render(task({ energy: 'LOW', timeEstimateMin: 15 }));
    const option = (group: string, label: string) =>
      [
        ...element.querySelectorAll<HTMLButtonElement>(
          `app-segmented [aria-label="${group}"] button`,
        ),
      ].find((b) => b.textContent?.trim() === label)!;

    expect(option('Energy', 'Low').getAttribute('aria-pressed')).toBe('true');
    option('Energy', 'High').click();
    option('Creativity', 'Creative').click();
    await refresh();
    // Choosing a quick pick fills the minutes; pressing it again clears the estimate.
    option('Time estimate', '1 h').click();
    await refresh();
    expect(field('#td-estimate').value).toBe('60');
    option('Time estimate', '1 h').click();
    await refresh();
    expect(field('#td-estimate').value).toBe('');
    await choose('#td-project', 'p1');
    await type('#td-due', '2099-05-06');
    await type('input[type=time]', '07:45');

    await save();

    expect(callsOf(updateTask)[0].params['body']).toEqual({
      energy: 'HIGH',
      creativity: 'CREATIVE',
      timeEstimateMin: null,
      projectId: 'p1',
      dueDate: '2099-05-06',
      dueTime: '07:45',
    });
  });

  it('marks a typed estimate that is not whole minutes', async () => {
    await render();
    await type('#td-estimate', '1.5');
    expect(field('#td-estimate').getAttribute('aria-invalid')).toBe('true');
    expect(saveButton().disabled).toBe(true);
    // No quick pick is shown as chosen for an invalid value.
    expect(
      element.querySelectorAll('app-segmented [aria-label="Time estimate"] [aria-pressed=true]'),
    ).toHaveLength(0);
  });

  it('saves with Ctrl+S and not with a plain key', async () => {
    await render();
    await type('#td-title', 'Shortcut');
    field('#td-title').dispatchEvent(new KeyboardEvent('keydown', { key: 's', bubbles: true }));
    await refresh();
    expect(callsOf(updateTask)).toHaveLength(0);
    field('#td-title').dispatchEvent(
      new KeyboardEvent('keydown', { key: 's', metaKey: true, bubbles: true }),
    );
    await refresh();
    expect(callsOf(updateTask)).toHaveLength(1);
  });

  it('does not save twice while a save is running', async () => {
    await render();
    await type('#td-title', 'Once');
    let release!: () => void;
    answer = (fn, params) => {
      if (fn !== updateTask) return undefined;
      return new Promise<void>((resolve) => (release = resolve)).then(() => {
        server = { ...server, ...(params['body'] as object), version: server.version + 1 };
        return server;
      });
    };
    saveButton().click();
    await refresh();
    expect(text()).toContain('Saving…');
    expect(saveButton().disabled).toBe(true);
    field('#td-title').dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, bubbles: true }),
    );
    await refresh();
    expect(callsOf(updateTask)).toHaveLength(1);
    release();
    await refresh();
    expect(text()).toContain('Saved');
  });

  it('keeps what the user typed while a save was running', async () => {
    await render(task({ notesMd: 'Old notes' }));
    await type('#td-title', 'Call the plumber today');
    let release!: () => void;
    answer = (fn, params) => {
      if (fn !== updateTask || callsOf(updateTask).length > 1) return undefined;
      return new Promise<void>((resolve) => (release = resolve)).then(() => {
        server = { ...server, ...(params['body'] as object), version: server.version + 1 };
        return server;
      });
    };
    saveButton().click();
    await refresh();
    field<HTMLButtonElement>('button.edit-notes').click();
    await refresh();
    await type('#td-notes', 'Typed while saving');
    release();
    await refresh();

    expect(field<HTMLTextAreaElement>('#td-notes').value).toBe('Typed while saving');
    expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Call the plumber today');
    expect(text()).toContain('Unsaved changes');
    expect(saveButton().disabled).toBe(false);

    await save();

    expect(callsOf(updateTask)).toHaveLength(2);
    expect(callsOf(updateTask)[1].params).toMatchObject({ 'If-Match': '"2"' });
    expect(callsOf(updateTask)[1].params['body']).toEqual({ notesMd: 'Typed while saving' });
    expect(text()).toContain('Saved');
    expect(text()).not.toContain('Unsaved changes');
  });

  it('is not dirty when the text typed during a save matches the saved version', async () => {
    await render();
    await type('#td-notes', 'Draft');
    let release!: () => void;
    answer = (fn, params) => {
      if (fn !== updateTask) return undefined;
      return new Promise<void>((resolve) => (release = resolve)).then(() => {
        server = { ...server, ...(params['body'] as object), version: server.version + 1 };
        return server;
      });
    };
    saveButton().click();
    await refresh();
    await type('#td-notes', 'Draft!');
    await type('#td-notes', 'Draft');
    release();
    await refresh();

    expect(field<HTMLTextAreaElement>('#td-notes').value).toBe('Draft');
    expect(text()).not.toContain('Unsaved changes');
    expect(saveButton().disabled).toBe(true);
  });

  it('keeps text typed during a move and saves only that text next, without moving again', async () => {
    await render();
    await choose('#td-list', 'WAITING');
    await type('#td-waiting', 'Anna');
    let release!: () => void;
    answer = (fn, params) => {
      if (fn !== moveTask) return undefined;
      return new Promise<void>((resolve) => (release = resolve)).then(() => {
        server = { ...server, ...(params['body'] as object), version: server.version + 1 };
        return server;
      });
    };
    saveButton().click();
    await refresh();
    await type('#td-notes', 'Typed during the move');
    release();
    await refresh();

    expect(callsOf(moveTask)).toHaveLength(1);
    expect(callsOf(updateTask)[0].params).toMatchObject({
      'If-Match': '"2"',
      body: { waitingForText: 'Anna' },
    });
    expect(field<HTMLSelectElement>('#td-list').value).toBe('WAITING');
    expect(field<HTMLInputElement>('#td-waiting').value).toBe('Anna');
    expect(field<HTMLTextAreaElement>('#td-notes').value).toBe('Typed during the move');
    expect(text()).toContain('Unsaved changes');

    await save();

    expect(callsOf(moveTask)).toHaveLength(1);
    expect(callsOf(updateTask)).toHaveLength(2);
    expect(callsOf(updateTask)[1].params).toMatchObject({ 'If-Match': '"3"' });
    expect(callsOf(updateTask)[1].params['body']).toEqual({ notesMd: 'Typed during the move' });
    expect(text()).not.toContain('Unsaved changes');
  });

  it('keeps text typed during a save that fails and sends it with the retry', async () => {
    await render();
    await type('#td-title', 'Call the plumber today');
    let fail!: () => void;
    answer = (fn) => {
      if (fn !== updateTask || callsOf(updateTask).length > 1) return undefined;
      return new Promise<never>((_, reject) => {
        fail = () => reject(new ApiError(503, 'Service Unavailable'));
      });
    };
    saveButton().click();
    await refresh();
    await type('#td-notes', 'Typed while saving');
    fail();
    await refresh();

    expect(text()).toContain('Service Unavailable');
    expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Call the plumber today');
    expect(field<HTMLTextAreaElement>('#td-notes').value).toBe('Typed while saving');
    expect(saveButton().disabled).toBe(false);

    await save();

    expect(callsOf(updateTask)[1].params).toMatchObject({ 'If-Match': '"1"' });
    expect(callsOf(updateTask)[1].params['body']).toEqual({
      title: 'Call the plumber today',
      notesMd: 'Typed while saving',
    });
    expect(text()).toContain('Saved');
  });

  it('hides the "Saved" note again after a moment', async () => {
    await render();
    await type('#td-title', 'Saved for a moment');
    const status = () => field<HTMLElement>('.bar-status').textContent?.trim();
    vi.useFakeTimers();
    try {
      saveButton().click();
      for (let i = 0; i < 3; i++) {
        await vi.advanceTimersByTimeAsync(0);
        TestBed.tick();
      }
      expect(status()).toBe('Saved');

      await vi.advanceTimersByTimeAsync(2400);
      TestBed.tick();
      expect(status()).toBe('Saved');

      await vi.advanceTimersByTimeAsync(200);
      TestBed.tick();
      expect(status()).toBe('');
    } finally {
      vi.useRealTimers();
    }
  });

  it('keeps showing the new task when a save of the previous task answers late', async () => {
    let releaseSave!: () => void;
    answer = (fn, params) => {
      if (fn === getTask && params['id'] === 't2') {
        return Promise.resolve(task({ id: 't2', title: 'Water the plants', version: 7 }));
      }
      if (fn === updateTask) {
        return new Promise<void>((resolve) => (releaseSave = resolve)).then(() => ({
          ...server,
          ...(params['body'] as object),
          version: server.version + 1,
        }));
      }
      return undefined;
    };
    await render();
    await type('#td-title', 'Call the plumber today');
    await save();
    expect(text()).toContain('Saving…');

    // The user opens another task (e.g. through a linked item) while the save is running.
    host.id.set('t2');
    await refresh();
    expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Water the plants');

    releaseSave();
    await refresh();
    expect(callsOf(updateTask)[0].params).toMatchObject({ id: 't1' });
    expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Water the plants');

    // A later edit saves t2 with t2's version, not t1.
    await type('#td-title', 'Water the plants twice');
    await save();
    expect(callsOf(updateTask)[1].params).toMatchObject({ id: 't2', 'If-Match': '"7"' });
  });

  it('shows no conflict for the new task when a save of the previous task gets a 412 late', async () => {
    let rejectSave!: () => void;
    answer = (fn, params) => {
      if (fn === getTask && params['id'] === 't2') {
        return Promise.resolve(task({ id: 't2', title: 'Water the plants', version: 7 }));
      }
      if (fn === updateTask) {
        return new Promise<never>(
          (_, reject) => (rejectSave = () => reject(new ApiError(412, 'Precondition Failed'))),
        );
      }
      return undefined;
    };
    await render();
    await type('#td-title', 'Call the plumber today');
    await save();
    host.id.set('t2');
    await refresh();
    const getsBefore = callsOf(getTask).length;

    // The save of t1 meets a newer version after the editor switched to t2.
    rejectSave();
    await refresh();
    expect(text()).not.toContain(CONFLICT_MESSAGE);
    expect(callsOf(getTask)).toHaveLength(getsBefore);
    expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Water the plants');
    expect(saveButton().disabled).toBe(true);
  });

  it('shows no save error for the new task when a save of the previous task fails late', async () => {
    let rejectSave!: () => void;
    let firstSave = true;
    answer = (fn, params) => {
      if (fn === getTask && params['id'] === 't2') {
        return Promise.resolve(task({ id: 't2', title: 'Water the plants', version: 7 }));
      }
      if (fn === updateTask && firstSave) {
        firstSave = false;
        return new Promise<never>(
          (_, reject) =>
            (rejectSave = () =>
              reject(new ApiError(500, 'Internal Server Error', 'The server failed.'))),
        );
      }
      return undefined;
    };
    await render();
    await type('#td-title', 'Call the plumber today');
    await save();
    host.id.set('t2');
    await refresh();

    // The save of t1 fails after the editor switched to t2.
    rejectSave();
    await refresh();
    expect(element.querySelector('.bar [role=alert]')).toBeNull();
    expect(text()).not.toContain('The server failed.');
    expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Water the plants');

    // The finished save no longer blocks saving t2.
    await type('#td-title', 'Water the plants twice');
    expect(saveButton().disabled).toBe(false);
    await save();
    expect(callsOf(updateTask)).toHaveLength(2);
    expect(callsOf(updateTask)[1].params).toMatchObject({ id: 't2', 'If-Match': '"7"' });
  });

  it('finishes a move of the previous task without touching the new one or showing "Saved"', async () => {
    let releaseMove!: () => void;
    answer = (fn, params) => {
      if (fn === getTask && params['id'] === 't2') {
        return Promise.resolve(task({ id: 't2', title: 'Water the plants', version: 7 }));
      }
      if (fn === moveTask) {
        return new Promise<void>((resolve) => (releaseMove = resolve)).then(() => {
          server = { ...server, ...(params['body'] as object), version: server.version + 1 };
          return server;
        });
      }
      return undefined;
    };
    await render();
    await choose('#td-list', 'WAITING');
    await type('#td-title', 'Call the plumber again');
    await save();
    host.id.set('t2');
    await refresh();

    // The move of t1 answers after the editor switched to t2; the PATCH of t1 still follows.
    releaseMove();
    await refresh();
    expect(callsOf(moveTask)[0].params).toMatchObject({ id: 't1' });
    expect(callsOf(updateTask)).toHaveLength(1);
    expect(callsOf(updateTask)[0].params).toMatchObject({ id: 't1', 'If-Match': '"2"' });
    expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Water the plants');
    expect(field<HTMLSelectElement>('#td-list').value).toBe('NEXT');
    expect(field<HTMLElement>('.bar-status').textContent?.trim()).toBe('');
    expect(saveButton().disabled).toBe(true);
  });

  describe('to reference', () => {
    const NOTE: NoteResponse = {
      id: 'n1',
      title: 'Call the plumber',
      displayTitle: 'Call the plumber',
      bodyMd: '',
      tags: [],
      pinned: false,
      archived: false,
      createdAt: '2026-10-20T10:00:00Z',
      updatedAt: '2026-10-20T10:00:00Z',
      version: 1,
    };
    const referenceButton = () => field<HTMLButtonElement>('button.reference');
    let navigate: ReturnType<typeof vi.spyOn>;

    async function toReference(): Promise<void> {
      referenceButton().click();
      await refresh();
    }

    beforeEach(() => {
      navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    });

    afterEach(() => vi.restoreAllMocks());

    const converting = (result: () => Promise<unknown>) => {
      answer = (fn) => (fn === convertTaskToNote ? result() : undefined);
    };

    it('does nothing when the user cancels the confirmation', async () => {
      const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
      await render();

      await toReference();

      expect(confirm).toHaveBeenCalledWith(
        'Move this task to reference? It becomes a note and the task goes to the Trash.',
      );
      expect(callsOf(convertTaskToNote)).toHaveLength(0);
      expect(navigate).not.toHaveBeenCalled();
      expect(referenceButton().textContent?.trim()).toBe('To reference (note)');
      expect(referenceButton().disabled).toBe(false);
    });

    it('converts the task silently with If-Match after confirming, then opens the note', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      converting(() => Promise.resolve(NOTE));
      await render(task({ version: 4 }));

      await toReference();

      expect(callsOf(convertTaskToNote)).toHaveLength(1);
      const call = callsOf(convertTaskToNote)[0];
      expect(call.params).toEqual({ id: 't1', 'If-Match': '"4"' });
      expect(call.context?.get(SILENT_ERRORS)).toBe(true);
      expect(navigate).toHaveBeenCalledWith(['/notes', 'n1']);
    });

    it('warns that unsaved changes are discarded and converts the saved version', async () => {
      const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
      converting(() => Promise.resolve(NOTE));
      await render();
      await type('#td-title', 'Call the plumber tomorrow');

      await toReference();

      expect(confirm).toHaveBeenCalledWith(
        'Move this task to reference? Your unsaved changes are discarded; the note gets the saved version.',
      );
      expect(callsOf(updateTask)).toHaveLength(0);
      expect(callsOf(moveTask)).toHaveLength(0);
      expect(callsOf(convertTaskToNote)[0].params).toEqual({ id: 't1', 'If-Match': '"1"' });
      expect(navigate).toHaveBeenCalledWith(['/notes', 'n1']);
    });

    it('does not convert a dirty task when the user cancels, and keeps the changes', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(false);
      await render();
      await type('#td-title', 'Half typed');

      await toReference();

      expect(callsOf(convertTaskToNote)).toHaveLength(0);
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Half typed');
      expect(text()).toContain('Unsaved changes');
    });

    it('shows the running conversion and neither converts nor saves twice meanwhile', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      let release!: () => void;
      converting(() => new Promise((resolve) => (release = () => resolve(NOTE))));
      await render();
      await type('#td-title', 'Typed before');

      await toReference();
      expect(referenceButton().textContent?.trim()).toBe('Moving to reference…');
      expect(referenceButton().disabled).toBe(true);
      referenceButton().click();
      field('#td-title').dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, bubbles: true }),
      );
      await refresh();
      expect(callsOf(convertTaskToNote)).toHaveLength(1);
      expect(callsOf(updateTask)).toHaveLength(0);

      release();
      await refresh();
      expect(navigate).toHaveBeenCalledTimes(1);
    });

    it('does not reload on a change event while the conversion runs', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      let release!: () => void;
      await render();
      // The task is already in the Trash on the server: a reload would answer 404.
      answer = (fn) => {
        if (fn === getTask) return Promise.reject(new ApiError(404, 'Not Found'));
        if (fn === convertTaskToNote)
          return new Promise((resolve) => (release = () => resolve(NOTE)));
        return undefined;
      };

      await toReference();
      events.next({ type: 'TASK', id: 't1', op: 'UPDATED' } as ChangeEvent);
      await refresh();

      expect(callsOf(getTask)).toHaveLength(1);
      expect(text()).not.toContain('This task does not exist or is in the Trash.');
      expect(field<HTMLTextAreaElement>('#td-title')).not.toBeNull();

      release();
      await refresh();
      expect(navigate).toHaveBeenCalledWith(['/notes', 'n1']);
    });

    it('drops a background reload that was already running when the conversion started', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      let release!: () => void;
      let notFound!: () => void;
      await render();
      answer = (fn) => {
        if (fn === getTask) {
          return new Promise<never>(
            (_, reject) => (notFound = () => reject(new ApiError(404, 'Not Found'))),
          );
        }
        if (fn === convertTaskToNote)
          return new Promise((resolve) => (release = () => resolve(NOTE)));
        return undefined;
      };
      // Someone else changed the task: a background reload starts before the conversion.
      events.next({ type: 'TASK', id: 't1', op: 'UPDATED' } as ChangeEvent);
      await refresh();
      expect(callsOf(getTask)).toHaveLength(2);

      await toReference();
      // The reload answers late with 404 (the task is in the Trash by now).
      notFound();
      await refresh();

      expect(text()).not.toContain('This task does not exist or is in the Trash.');
      expect(field<HTMLTextAreaElement>('#td-title')).not.toBeNull();
      expect(referenceButton().textContent?.trim()).toBe('Moving to reference…');

      release();
      await refresh();
      expect(navigate).toHaveBeenCalledWith(['/notes', 'n1']);
    });

    it('still opens the note when the Trash event of the task arrives before the answer', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      let release!: () => void;
      converting(() => new Promise((resolve) => (release = () => resolve(NOTE))));
      await render();

      await toReference();
      events.next({ type: 'TASK', id: 't1', op: 'DELETED' } as ChangeEvent);
      await refresh();
      expect(navigate).not.toHaveBeenCalled();

      release();
      await refresh();
      expect(navigate).toHaveBeenCalledTimes(1);
      expect(navigate).toHaveBeenCalledWith(['/notes', 'n1']);
    });

    it('shows field errors of a 400 answer and opens no note', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      converting(() =>
        Promise.reject(
          new ApiError(400, 'Bad Request', undefined, undefined, undefined, [
            { field: 'title', message: 'Too long for a note title.' },
          ]),
        ),
      );
      await render();

      await toReference();

      expect(text()).toContain('Too long for a note title.');
      expect(navigate).not.toHaveBeenCalled();
      expect(referenceButton().disabled).toBe(false);
    });

    it('cannot convert while a save is running', async () => {
      const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
      let release!: () => void;
      answer = (fn) => {
        if (fn !== updateTask) return undefined;
        return new Promise<void>((resolve) => (release = resolve)).then(() => {
          server = { ...server, title: 'Saving now', version: 2 };
          return server;
        });
      };
      await render();
      await type('#td-title', 'Saving now');
      await save();

      expect(referenceButton().disabled).toBe(true);
      referenceButton().click();
      await refresh();
      expect(confirm).not.toHaveBeenCalled();
      expect(callsOf(convertTaskToNote)).toHaveLength(0);

      release();
      await refresh();
      expect(referenceButton().disabled).toBe(false);
    });

    it('on 412 says the task changed elsewhere and reloads it, without opening a note', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      converting(() => {
        server = task({ title: 'Changed elsewhere', version: 5 });
        return Promise.reject(new ApiError(412, 'Precondition Failed'));
      });
      await render();

      await toReference();

      expect(text()).toContain(CONFLICT_MESSAGE);
      expect(callsOf(getTask)).toHaveLength(2);
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Changed elsewhere');
      expect(navigate).not.toHaveBeenCalled();
      expect(referenceButton().disabled).toBe(false);
      expect(referenceButton().textContent?.trim()).toBe('To reference (note)');

      // A retry goes out with the reloaded version.
      converting(() => Promise.resolve(NOTE));
      await toReference();
      expect(callsOf(convertTaskToNote)[1].params).toEqual({ id: 't1', 'If-Match': '"5"' });
      expect(text()).not.toContain(CONFLICT_MESSAGE);
      expect(navigate).toHaveBeenCalledWith(['/notes', 'n1']);
    });

    it('shows another error in the save bar, keeps the form and allows another try', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      converting(() =>
        Promise.reject(new ApiError(500, 'Internal Server Error', 'The server failed.')),
      );
      await render();
      await type('#td-title', 'Keep me');

      await toReference();

      expect(element.querySelector('.bar [role=alert]')?.textContent).toContain(
        'The server failed.',
      );
      expect(text()).not.toContain(CONFLICT_MESSAGE);
      expect(callsOf(getTask)).toHaveLength(1);
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Keep me');
      expect(navigate).not.toHaveBeenCalled();
      expect(referenceButton().disabled).toBe(false);

      converting(() => Promise.resolve(NOTE));
      await toReference();
      expect(callsOf(convertTaskToNote)).toHaveLength(2);
      expect(element.querySelector('.bar [role=alert]')).toBeNull();
      expect(navigate).toHaveBeenCalledWith(['/notes', 'n1']);
    });

    it('shows a fallback message for an unexpected conversion error', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      converting(() => Promise.reject(new TypeError('boom')));
      await render();
      await toReference();
      expect(text()).toContain('Something went wrong. Try again.');
      expect(navigate).not.toHaveBeenCalled();
    });

    it('shows no error for the new task when a conversion of the previous task fails late', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      let fail!: () => void;
      answer = (fn, params) => {
        if (fn === getTask && params['id'] === 't2') {
          return Promise.resolve(task({ id: 't2', title: 'Water the plants', version: 7 }));
        }
        if (fn === convertTaskToNote) {
          return new Promise<never>(
            (_, reject) =>
              (fail = () =>
                reject(new ApiError(500, 'Internal Server Error', 'The server failed.'))),
          );
        }
        return undefined;
      };
      await render();
      await toReference();
      host.id.set('t2');
      await refresh();

      fail();
      await refresh();

      expect(text()).not.toContain('The server failed.');
      expect(text()).not.toContain(CONFLICT_MESSAGE);
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Water the plants');
      expect(navigate).not.toHaveBeenCalled();
      expect(referenceButton().disabled).toBe(false);
    });

    it('does not open the note nor block the new task when a conversion of the previous task succeeds late', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      let succeed!: () => void;
      answer = (fn, params) => {
        if (fn === getTask && params['id'] === 't2') {
          return Promise.resolve(task({ id: 't2', title: 'Water the plants', version: 7 }));
        }
        if (fn === convertTaskToNote) {
          return new Promise<NoteResponse>((resolve) => (succeed = () => resolve(NOTE)));
        }
        return undefined;
      };
      await render();
      await toReference();
      expect(referenceButton().textContent?.trim()).toBe('Moving to reference…');
      host.id.set('t2');
      await refresh();

      // t2 is not the task being converted: it can be edited and saved meanwhile.
      expect(referenceButton().textContent?.trim()).toBe('To reference (note)');
      expect(referenceButton().disabled).toBe(false);
      await type('#td-title', 'Water the plants twice');
      expect(saveButton().disabled).toBe(false);

      succeed();
      await refresh();

      expect(navigate).not.toHaveBeenCalled();
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Water the plants twice');
      expect(referenceButton().disabled).toBe(false);
      await save();
      expect(callsOf(updateTask)).toHaveLength(1);
      expect(callsOf(updateTask)[0].params).toMatchObject({ id: 't2', 'If-Match': '"7"' });
    });

    it('keeps the conversion of the new task running when the previous one answers first, then opens its note', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      const pending = new Map<string, () => void>();
      const T2_NOTE: NoteResponse = { ...NOTE, id: 'n2', title: 'Water the plants' };
      answer = (fn, params) => {
        if (fn === getTask && params['id'] === 't2') {
          return Promise.resolve(task({ id: 't2', title: 'Water the plants', version: 7 }));
        }
        if (fn === convertTaskToNote) {
          const id = String(params['id']);
          return new Promise<NoteResponse>((resolve) =>
            pending.set(id, () => resolve(id === 't2' ? T2_NOTE : NOTE)),
          );
        }
        return undefined;
      };
      await render();
      await toReference();
      host.id.set('t2');
      await refresh();
      await toReference();
      expect(callsOf(convertTaskToNote).map((call) => call.params['id'])).toEqual(['t1', 't2']);
      expect(callsOf(convertTaskToNote)[1].params).toMatchObject({ 'If-Match': '"7"' });

      // The conversion of t1 answers while t2 is still being converted.
      pending.get('t1')!();
      await refresh();
      expect(navigate).not.toHaveBeenCalled();
      expect(referenceButton().textContent?.trim()).toBe('Moving to reference…');
      expect(referenceButton().disabled).toBe(true);

      pending.get('t2')!();
      await refresh();
      expect(navigate).toHaveBeenCalledTimes(1);
      expect(navigate).toHaveBeenCalledWith(['/notes', 'n2']);
    });

    it('shows the running conversion again and opens the note when the editor comes back to the task', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      let succeed!: () => void;
      answer = (fn, params) => {
        if (fn === getTask && params['id'] === 't2') {
          return Promise.resolve(task({ id: 't2', title: 'Water the plants', version: 7 }));
        }
        if (fn === convertTaskToNote) {
          return new Promise<NoteResponse>((resolve) => (succeed = () => resolve(NOTE)));
        }
        return undefined;
      };
      await render();
      await toReference();
      host.id.set('t2');
      await refresh();
      expect(referenceButton().disabled).toBe(false);

      host.id.set('t1');
      await refresh();
      expect(referenceButton().textContent?.trim()).toBe('Moving to reference…');
      expect(referenceButton().disabled).toBe(true);

      succeed();
      await refresh();
      expect(callsOf(convertTaskToNote)).toHaveLength(1);
      expect(navigate).toHaveBeenCalledTimes(1);
      expect(navigate).toHaveBeenCalledWith(['/notes', 'n1']);
    });
  });

  describe('to calendar', () => {
    /** Thursday 2026-10-22, 09:00-10:00 in Zurich. */
    const REQUEST: ConvertToEventRequest = {
      calendarId: 'work',
      allDay: false,
      start: '2026-10-22T07:00:00.000Z',
      end: '2026-10-22T08:00:00.000Z',
      timeZone: 'Europe/Zurich',
    };
    const event = (fields: Partial<EventResponse> = {}) =>
      ({
        id: 'e1',
        calendarId: 'work',
        title: 'Call the plumber',
        allDay: false,
        start: '2026-10-22T07:00:00Z',
        end: '2026-10-22T08:00:00Z',
        timeZone: 'Europe/Zurich',
        version: 1,
        ...fields,
      }) as EventResponse;
    const calendarButton = () => field<HTMLButtonElement>('button.to-calendar');
    const referenceButton = () => field<HTMLButtonElement>('button.reference');
    let navigate: ReturnType<typeof vi.spyOn>;
    /** The dialogs opened, each answered by the test through its subject. */
    let dialogs: {
      component: unknown;
      data: ToCalendarData;
      closed: Subject<ConvertToEventRequest | undefined>;
    }[];

    beforeEach(() => {
      dialogs = [];
      TestBed.overrideProvider(MatDialog, {
        useValue: {
          open: (component: unknown, config: { data: ToCalendarData }) => {
            const closed = new Subject<ConvertToEventRequest | undefined>();
            dialogs.push({ component, data: config.data, closed });
            return { afterClosed: () => closed };
          },
        },
      });
      navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    });

    afterEach(() => vi.restoreAllMocks());

    const converting = (result: () => Promise<unknown>) => {
      answer = (fn) => (fn === convertTaskToEvent ? result() : undefined);
    };

    async function toCalendar(): Promise<void> {
      calendarButton().click();
      await refresh();
    }

    async function close(result: ConvertToEventRequest | undefined, index = 0): Promise<void> {
      dialogs[index].closed.next(result);
      dialogs[index].closed.complete();
      await refresh();
    }

    it('does nothing when the dialog is cancelled', async () => {
      await render();

      await toCalendar();
      expect(dialogs).toHaveLength(1);
      expect(dialogs[0].component).toBe(ToCalendarDialog);
      await close(undefined);

      expect(callsOf(convertTaskToEvent)).toHaveLength(0);
      expect(navigate).not.toHaveBeenCalled();
      expect(calendarButton().textContent?.trim()).toBe('To calendar (event)');
      expect(calendarButton().disabled).toBe(false);
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Call the plumber');
    });

    it('converts the saved version silently with the chosen calendar and times, then opens the calendar on that day', async () => {
      converting(() => Promise.resolve(event()));
      await render(task({ version: 4 }));

      await toCalendar();
      expect(dialogs[0].data).toEqual({ title: 'Call the plumber', unsaved: false });
      expect(callsOf(convertTaskToEvent)).toHaveLength(0);
      await close(REQUEST);

      expect(callsOf(convertTaskToEvent)).toHaveLength(1);
      const call = callsOf(convertTaskToEvent)[0];
      expect(call.params).toEqual({ id: 't1', 'If-Match': '"4"', body: REQUEST });
      expect(call.context?.get(SILENT_ERRORS)).toBe(true);
      expect(navigate).toHaveBeenCalledTimes(1);
      expect(navigate).toHaveBeenCalledWith(['/calendar'], {
        queryParams: { date: '2026-10-22' },
      });
    });

    it('opens the calendar on the start day in the zone of the event', async () => {
      // 00:30 on Friday in Zurich, still Thursday in UTC.
      converting(() =>
        Promise.resolve(event({ start: '2026-10-22T22:30:00Z', end: '2026-10-22T23:30:00Z' })),
      );
      await render();
      await toCalendar();
      await close(REQUEST);
      expect(navigate).toHaveBeenCalledWith(['/calendar'], {
        queryParams: { date: '2026-10-23' },
      });
    });

    it('opens the calendar on the start day in the local zone when the event has no zone', async () => {
      // 12:00 UTC: the same day on the clocks of every zone from UTC-11 to UTC+11.
      converting(() =>
        Promise.resolve(
          event({
            start: '2026-10-22T12:00:00Z',
            end: '2026-10-22T13:00:00Z',
            timeZone: undefined,
          }),
        ),
      );
      await render();
      await toCalendar();
      await close(REQUEST);
      expect(navigate).toHaveBeenCalledWith(['/calendar'], {
        queryParams: { date: '2026-10-22' },
      });
    });

    it('opens the calendar on the start date of an all-day event', async () => {
      converting(() =>
        Promise.resolve(
          event({
            allDay: true,
            start: undefined,
            end: undefined,
            startDate: '2026-11-03',
            endDate: '2026-11-04',
          }),
        ),
      );
      await render();
      await toCalendar();
      await close({
        calendarId: 'work',
        allDay: true,
        startDate: '2026-11-03',
        endDate: '2026-11-04',
      });
      expect(navigate).toHaveBeenCalledWith(['/calendar'], {
        queryParams: { date: '2026-11-03' },
      });
    });

    it('tells the dialog about unsaved changes and converts the saved version without saving', async () => {
      converting(() => Promise.resolve(event()));
      await render();
      await type('#td-title', 'Call the plumber tomorrow');

      await toCalendar();
      expect(dialogs[0].data).toEqual({ title: 'Call the plumber', unsaved: true });
      await close(REQUEST);

      expect(callsOf(updateTask)).toHaveLength(0);
      expect(callsOf(moveTask)).toHaveLength(0);
      expect(callsOf(convertTaskToEvent)[0].params).toMatchObject({
        id: 't1',
        'If-Match': '"1"',
      });
      expect(navigate).toHaveBeenCalledTimes(1);
    });

    it('keeps the unsaved changes when the dialog is cancelled', async () => {
      await render();
      await type('#td-title', 'Half typed');
      await toCalendar();
      await close(undefined);

      expect(callsOf(convertTaskToEvent)).toHaveLength(0);
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Half typed');
      expect(text()).toContain('Unsaved changes');
    });

    it('sends the version reloaded while the dialog was open', async () => {
      converting(() => Promise.resolve(event()));
      await render(task({ version: 2 }));
      await toCalendar();
      // Someone else changed the task meanwhile: the form shows the new version.
      server = task({ title: 'Call the plumber at 9', version: 3 });
      events.next({ type: 'TASK', id: 't1', op: 'UPDATED' } as ChangeEvent);
      await refresh();
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Call the plumber at 9');

      await close(REQUEST);

      expect(callsOf(convertTaskToEvent)[0].params).toMatchObject({ 'If-Match': '"3"' });
    });

    it('shows the running conversion and neither converts nor saves twice meanwhile', async () => {
      let release!: () => void;
      converting(() => new Promise((resolve) => (release = () => resolve(event()))));
      await render();
      await type('#td-title', 'Typed before');
      await toCalendar();
      await close(REQUEST);

      expect(calendarButton().textContent?.trim()).toBe('Moving to the calendar…');
      expect(calendarButton().disabled).toBe(true);
      expect(referenceButton().disabled).toBe(true);
      expect(referenceButton().textContent?.trim()).toBe('To reference (note)');
      calendarButton().click();
      field('#td-title').dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, bubbles: true }),
      );
      await refresh();
      expect(dialogs).toHaveLength(1);
      expect(callsOf(convertTaskToEvent)).toHaveLength(1);
      expect(callsOf(updateTask)).toHaveLength(0);

      release();
      await refresh();
      expect(navigate).toHaveBeenCalledTimes(1);
    });

    it('cannot open the dialog while a save is running', async () => {
      let release!: () => void;
      answer = (fn) => {
        if (fn !== updateTask) return undefined;
        return new Promise<void>((resolve) => (release = resolve)).then(() => {
          server = { ...server, title: 'Saving now', version: 2 };
          return server;
        });
      };
      await render();
      await type('#td-title', 'Saving now');
      await save();

      expect(calendarButton().disabled).toBe(true);
      calendarButton().click();
      await refresh();
      expect(dialogs).toHaveLength(0);

      release();
      await refresh();
      expect(calendarButton().disabled).toBe(false);
    });

    it('does not convert when a save started while the dialog was open', async () => {
      let release!: () => void;
      answer = (fn) => {
        if (fn !== updateTask) return undefined;
        return new Promise<void>((resolve) => (release = resolve)).then(() => {
          server = { ...server, title: 'Saving now', version: 2 };
          return server;
        });
      };
      await render();
      await type('#td-title', 'Saving now');
      await toCalendar();
      await save();

      await close(REQUEST);

      expect(callsOf(convertTaskToEvent)).toHaveLength(0);
      release();
      await refresh();
    });

    it('on 412 says the task changed elsewhere and reloads it, without opening the calendar', async () => {
      converting(() => {
        server = task({ title: 'Changed elsewhere', version: 5 });
        return Promise.reject(new ApiError(412, 'Precondition Failed'));
      });
      await render();

      await toCalendar();
      await close(REQUEST);

      expect(text()).toContain(CONFLICT_MESSAGE);
      expect(callsOf(getTask)).toHaveLength(2);
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Changed elsewhere');
      expect(navigate).not.toHaveBeenCalled();
      expect(calendarButton().disabled).toBe(false);
      expect(calendarButton().textContent?.trim()).toBe('To calendar (event)');

      // A retry goes out with the reloaded version.
      converting(() => Promise.resolve(event()));
      await toCalendar();
      await close(REQUEST, 1);
      expect(callsOf(convertTaskToEvent)[1].params).toMatchObject({ 'If-Match': '"5"' });
      expect(text()).not.toContain(CONFLICT_MESSAGE);
      expect(navigate).toHaveBeenCalledTimes(1);
    });

    it('shows field errors of a 400 answer and opens no calendar', async () => {
      converting(() =>
        Promise.reject(
          new ApiError(400, 'Bad Request', undefined, undefined, undefined, [
            { field: 'title', message: 'Too long for an event title.' },
          ]),
        ),
      );
      await render();

      await toCalendar();
      await close(REQUEST);

      expect(text()).toContain('Too long for an event title.');
      expect(navigate).not.toHaveBeenCalled();
      expect(calendarButton().disabled).toBe(false);
    });

    it('shows 400 errors of the calendar in the save bar and opens no calendar', async () => {
      converting(() =>
        Promise.reject(
          new ApiError(400, 'Bad Request', undefined, undefined, undefined, [
            { field: 'calendarId', message: 'The calendar does not exist.' },
          ]),
        ),
      );
      await render();

      await toCalendar();
      await close(REQUEST);

      expect(element.querySelector('.bar [role=alert]')?.textContent).toContain(
        'The calendar does not exist.',
      );
      expect(navigate).not.toHaveBeenCalled();
      expect(calendarButton().disabled).toBe(false);
      expect(calendarButton().textContent?.trim()).toBe('To calendar (event)');
    });

    it('shows 400 errors of the times in the save bar next to errors of the form', async () => {
      converting(() =>
        Promise.reject(
          new ApiError(400, 'Bad Request', undefined, undefined, undefined, [
            { field: 'end', message: 'The event must end after it starts.' },
            { field: 'title', message: 'Too long for an event title.' },
          ]),
        ),
      );
      await render();

      await toCalendar();
      await close(REQUEST);

      const bar = element.querySelector('.bar [role=alert]')?.textContent ?? '';
      expect(bar).toContain('The event must end after it starts.');
      // The title error stays at the title only.
      expect(bar).not.toContain('Too long for an event title.');
      expect(text()).toContain('Too long for an event title.');
      expect(navigate).not.toHaveBeenCalled();
      expect(calendarButton().disabled).toBe(false);
    });

    it('shows another error in the save bar, keeps the form and allows another try', async () => {
      converting(() =>
        Promise.reject(new ApiError(500, 'Internal Server Error', 'The server failed.')),
      );
      await render();
      await type('#td-title', 'Keep me');

      await toCalendar();
      await close(REQUEST);

      expect(element.querySelector('.bar [role=alert]')?.textContent).toContain(
        'The server failed.',
      );
      expect(callsOf(getTask)).toHaveLength(1);
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Keep me');
      expect(navigate).not.toHaveBeenCalled();
      expect(calendarButton().disabled).toBe(false);

      converting(() => Promise.resolve(event()));
      await toCalendar();
      await close(REQUEST, 1);
      expect(callsOf(convertTaskToEvent)).toHaveLength(2);
      expect(element.querySelector('.bar [role=alert]')).toBeNull();
      expect(navigate).toHaveBeenCalledTimes(1);
    });

    it('shows a fallback message for an unexpected conversion error', async () => {
      converting(() => Promise.reject(new TypeError('boom')));
      await render();
      await toCalendar();
      await close(REQUEST);
      expect(text()).toContain('Something went wrong. Try again.');
      expect(navigate).not.toHaveBeenCalled();
    });

    it('does not convert when the editor switched to another task while the dialog was open', async () => {
      answer = (fn, params) =>
        fn === getTask && params['id'] === 't2'
          ? Promise.resolve(task({ id: 't2', title: 'Water the plants', version: 7 }))
          : undefined;
      await render();
      await toCalendar();
      host.id.set('t2');
      await refresh();

      await close(REQUEST);

      expect(callsOf(convertTaskToEvent)).toHaveLength(0);
      expect(navigate).not.toHaveBeenCalled();
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Water the plants');
    });

    it('does not open the calendar nor block the new task when a conversion of the previous task succeeds late', async () => {
      let succeed!: () => void;
      answer = (fn, params) => {
        if (fn === getTask && params['id'] === 't2') {
          return Promise.resolve(task({ id: 't2', title: 'Water the plants', version: 7 }));
        }
        if (fn === convertTaskToEvent) {
          return new Promise<EventResponse>((resolve) => (succeed = () => resolve(event())));
        }
        return undefined;
      };
      await render();
      await toCalendar();
      await close(REQUEST);
      expect(calendarButton().textContent?.trim()).toBe('Moving to the calendar…');
      host.id.set('t2');
      await refresh();

      expect(calendarButton().textContent?.trim()).toBe('To calendar (event)');
      expect(calendarButton().disabled).toBe(false);

      succeed();
      await refresh();

      expect(navigate).not.toHaveBeenCalled();
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Water the plants');
      expect(calendarButton().disabled).toBe(false);
    });

    it('shows no error for the new task when a conversion of the previous task fails late', async () => {
      let fail!: () => void;
      answer = (fn, params) => {
        if (fn === getTask && params['id'] === 't2') {
          return Promise.resolve(task({ id: 't2', title: 'Water the plants', version: 7 }));
        }
        if (fn === convertTaskToEvent) {
          return new Promise<never>(
            (_, reject) => (fail = () => reject(new ApiError(412, 'Precondition Failed'))),
          );
        }
        return undefined;
      };
      await render();
      await toCalendar();
      await close(REQUEST);
      host.id.set('t2');
      await refresh();

      fail();
      await refresh();

      expect(text()).not.toContain(CONFLICT_MESSAGE);
      expect(callsOf(getTask).filter((c) => c.params['id'] === 't1')).toHaveLength(1);
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Water the plants');
      expect(navigate).not.toHaveBeenCalled();
    });
  });

  describe('complete, reopen and delete', () => {
    const DONE_AT = '2026-11-08T10:00:00Z';
    let undo: {
      completed: ReturnType<typeof vi.fn>;
      deleted: ReturnType<typeof vi.fn>;
    };
    /** When set, the request of that kind answers with it instead of the default. */
    let hold: Partial<Record<'complete' | 'uncomplete' | 'remove', () => Promise<unknown>>>;

    const completeButton = () => field<HTMLButtonElement>('button.complete');
    const deleteButton = () => field<HTMLButtonElement>('button.delete');
    const label = (button: HTMLButtonElement) => button.textContent?.trim();
    /** The "Undo" of the n-th completion snackbar: runs its reopen. */
    const undoCompletion = (n = 0) => (undo.completed.mock.calls[n][1] as () => Promise<unknown>)();

    async function click(button: HTMLButtonElement): Promise<void> {
      button.click();
      await refresh();
    }

    beforeEach(() => {
      undo = {
        completed: vi.fn().mockResolvedValue(false),
        deleted: vi.fn().mockResolvedValue(false),
      };
      TestBed.overrideProvider(TaskUndo, { useValue: undo });
      hold = {};
      answer = (fn) => {
        if (fn === completeTask) {
          if (hold.complete) return hold.complete();
          server = { ...server, completedAt: DONE_AT, version: server.version + 1 };
          return Promise.resolve({ ...server, needsNextStep: false });
        }
        if (fn === uncompleteTask) {
          if (hold.uncomplete) return hold.uncomplete();
          server = { ...server, completedAt: undefined, version: server.version + 1 };
          return Promise.resolve(server);
        }
        if (fn === deleteTask) return hold.remove ? hold.remove() : Promise.resolve(undefined);
        return undefined;
      };
    });

    afterEach(() => vi.restoreAllMocks());

    it('completes the task silently with If-Match, stays and offers Undo', async () => {
      await render(task({ version: 3 }));
      expect(label(completeButton())).toBe('Complete');
      expect(text()).not.toContain('Done');

      await click(completeButton());

      expect(callsOf(completeTask)).toHaveLength(1);
      const call = callsOf(completeTask)[0];
      expect(call.params).toEqual({ id: 't1', 'If-Match': '"3"' });
      expect(call.context?.get(SILENT_ERRORS)).toBe(true);
      expect(label(completeButton())).toBe('Reopen');
      expect(text()).toContain('Done');
      expect(host.closed).toBe(0);
      expect(undo.completed).toHaveBeenCalledTimes(1);
      expect(undo.completed.mock.calls[0][0]).toMatchObject({
        id: 't1',
        title: 'Call the plumber',
      });
      expect(undo.deleted).not.toHaveBeenCalled();
      // The completed version is the saved one: nothing is dirty.
      expect(text()).not.toContain('Unsaved changes');
    });

    it('Undo reopens the completed version with the HTTP layer showing errors, and shows it', async () => {
      await render();
      await click(completeButton());

      await undoCompletion();
      await refresh();

      expect(callsOf(uncompleteTask)).toHaveLength(1);
      const call = callsOf(uncompleteTask)[0];
      expect(call.params).toEqual({ id: 't1', 'If-Match': '"2"' });
      // Not silent: the snackbar may outlive the editor, so the HTTP layer toasts a failure.
      expect(call.context?.get(SILENT_ERRORS) ?? false).toBe(false);
      expect(label(completeButton())).toBe('Complete');
      expect(text()).not.toContain('Done');
    });

    it('Undo reopens the newest version when the task was saved after completing', async () => {
      await render();
      await click(completeButton());
      await type('#td-title', 'Call the plumber again');
      await save();
      expect(server.version).toBe(3);

      await undoCompletion();
      await refresh();

      expect(callsOf(uncompleteTask)[0].params).toEqual({ id: 't1', 'If-Match': '"3"' });
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Call the plumber again');
      expect(label(completeButton())).toBe('Complete');
    });

    it('Undo does not replace unsaved edits made after completing', async () => {
      await render();
      await click(completeButton());
      await type('#td-title', 'Typed after completing');

      await undoCompletion();
      await refresh();

      expect(callsOf(uncompleteTask)).toHaveLength(1);
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Typed after completing');
      expect(text()).toContain('Unsaved changes');
      // The reopened version is the saved one now: the edits are compared with it.
      expect(label(completeButton())).toBe('Complete');
      await save();
      expect(callsOf(updateTask)[0].params).toMatchObject({
        'If-Match': '"3"',
        body: { title: 'Typed after completing' },
      });
    });

    it('Undo keeps edits typed while it runs and shows the task reopened', async () => {
      let release!: () => void;
      hold.uncomplete = () =>
        new Promise((resolve) => {
          release = () => {
            server = { ...server, completedAt: undefined, version: server.version + 1 };
            resolve(server);
          };
        });
      await render();
      await click(completeButton());

      const undone = undoCompletion();
      await type('#td-title', 'Typed while undoing');
      release();
      await undone;
      await refresh();

      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Typed while undoing');
      expect(text()).toContain('Unsaved changes');
      expect(label(completeButton())).toBe('Complete');
    });

    it('keeps edits typed while completing runs, compared with the completed version', async () => {
      let release!: () => void;
      hold.complete = () =>
        new Promise((resolve) => {
          release = () => {
            server = { ...server, completedAt: DONE_AT, version: server.version + 1 };
            resolve({ ...server, needsNextStep: false });
          };
        });
      await render();
      await click(completeButton());
      await type('#td-notes', 'Typed while completing');

      release();
      await refresh();

      expect(field<HTMLTextAreaElement>('#td-notes').value).toBe('Typed while completing');
      expect(text()).toContain('Unsaved changes');
      expect(label(completeButton())).toBe('Reopen');
      expect(undo.completed).toHaveBeenCalledTimes(1);
      await save();
      expect(callsOf(updateTask)[0].params).toMatchObject({
        'If-Match': '"2"',
        body: { notesMd: 'Typed while completing' },
      });
    });

    it('keeps edits typed while reopening runs', async () => {
      let release!: () => void;
      hold.uncomplete = () =>
        new Promise((resolve) => {
          release = () => {
            server = { ...server, completedAt: undefined, version: server.version + 1 };
            resolve(server);
          };
        });
      await render(task({ completedAt: DONE_AT, version: 5 }));
      await click(completeButton());
      await type('#td-title', 'Typed while reopening');

      release();
      await refresh();

      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Typed while reopening');
      expect(text()).toContain('Unsaved changes');
      expect(label(completeButton())).toBe('Complete');
    });

    it('offers Undo for a completion that answers after switching to another task', async () => {
      let release!: () => void;
      answer = ((previous) => (fn: unknown, params: Record<string, unknown>) => {
        if (fn === getTask && params['id'] === 't2') {
          return Promise.resolve(task({ id: 't2', title: 'Water the plants', version: 7 }));
        }
        if (fn === completeTask) {
          return new Promise((resolve) => {
            release = () =>
              resolve({ ...task({ completedAt: DONE_AT, version: 2 }), needsNextStep: false });
          });
        }
        return previous?.(fn, params);
      })(answer);
      await render();
      await click(completeButton());
      host.id.set('t2');
      await refresh();

      release();
      await refresh();

      expect(undo.completed).toHaveBeenCalledTimes(1);
      expect(undo.completed.mock.calls[0][0]).toMatchObject({ id: 't1' });
      await undoCompletion();
      await refresh();
      expect(callsOf(uncompleteTask)[0].params).toEqual({ id: 't1', 'If-Match': '"2"' });
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Water the plants');
      expect(label(completeButton())).toBe('Complete');
    });

    it('Undo after switching to another task reopens the old one without touching the new one', async () => {
      answer = ((previous) => (fn: unknown, params: Record<string, unknown>) => {
        if (fn === getTask && params['id'] === 't2') {
          return Promise.resolve(task({ id: 't2', title: 'Water the plants', version: 7 }));
        }
        return previous?.(fn, params);
      })(answer);
      await render();
      await click(completeButton());
      host.id.set('t2');
      await refresh();

      await undoCompletion();
      await refresh();

      expect(callsOf(uncompleteTask)[0].params).toEqual({ id: 't1', 'If-Match': '"2"' });
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Water the plants');
      expect(label(completeButton())).toBe('Complete');
    });

    it('reopens a done task silently, without an undo snackbar', async () => {
      await render(task({ completedAt: DONE_AT, version: 5 }));
      expect(label(completeButton())).toBe('Reopen');
      expect(text()).toContain('Done');

      await click(completeButton());

      const call = callsOf(uncompleteTask)[0];
      expect(call.params).toEqual({ id: 't1', 'If-Match': '"5"' });
      expect(call.context?.get(SILENT_ERRORS)).toBe(true);
      expect(callsOf(completeTask)).toHaveLength(0);
      expect(label(completeButton())).toBe('Complete');
      expect(undo.completed).not.toHaveBeenCalled();
    });

    it('shows the running action and starts nothing else meanwhile', async () => {
      let release!: () => void;
      hold.complete = () =>
        new Promise((resolve) => {
          release = () => {
            server = { ...server, completedAt: DONE_AT, version: server.version + 1 };
            resolve({ ...server, needsNextStep: false });
          };
        });
      const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
      await render();

      await click(completeButton());
      expect(label(completeButton())).toBe('Completing…');
      expect(completeButton().disabled).toBe(true);
      expect(deleteButton().disabled).toBe(true);
      expect(field<HTMLButtonElement>('button.reference').disabled).toBe(true);
      expect(field<HTMLButtonElement>('button.to-calendar').disabled).toBe(true);

      // Events reaching the disabled buttons start nothing.
      completeButton().dispatchEvent(new Event('click'));
      deleteButton().dispatchEvent(new Event('click'));
      await type('#td-title', 'Typed meanwhile');
      expect(saveButton().disabled).toBe(true);
      field('#td-title').dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, bubbles: true }),
      );
      await refresh();
      expect(callsOf(completeTask)).toHaveLength(1);
      expect(callsOf(deleteTask)).toHaveLength(0);
      expect(callsOf(updateTask)).toHaveLength(0);
      expect(confirm).not.toHaveBeenCalled();

      release();
      await refresh();
      expect(completeButton().disabled).toBe(false);
      expect(deleteButton().disabled).toBe(false);
    });

    it('shows "Reopening…" and "Deleting…" while those run', async () => {
      let reopen!: () => void;
      hold.uncomplete = () =>
        new Promise((resolve) => (reopen = () => resolve(task({ version: 2 }))));
      let removed!: () => void;
      hold.remove = () => new Promise<void>((resolve) => (removed = resolve));
      await render(task({ completedAt: DONE_AT }));

      await click(completeButton());
      expect(label(completeButton())).toBe('Reopening…');
      reopen();
      await refresh();
      expect(label(completeButton())).toBe('Complete');

      await click(deleteButton());
      expect(label(deleteButton())).toBe('Deleting…');
      expect(completeButton().disabled).toBe(true);
      removed();
      await refresh();
      expect(host.closed).toBe(1);
    });

    it('saves unsaved valid changes first, then completes the saved version', async () => {
      await render();
      await type('#td-title', 'Call the plumber at nine');

      await click(completeButton());

      const order = calls
        .map((call) => call.fn)
        .filter((fn) => fn === updateTask || fn === completeTask);
      expect(order).toEqual([updateTask, completeTask]);
      expect(callsOf(updateTask)[0].params).toMatchObject({
        'If-Match': '"1"',
        body: { title: 'Call the plumber at nine' },
      });
      expect(callsOf(completeTask)[0].params).toEqual({ id: 't1', 'If-Match': '"2"' });
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Call the plumber at nine');
      expect(label(completeButton())).toBe('Reopen');
      expect(text()).not.toContain('Unsaved changes');
    });

    it('does not complete when the changes are invalid', async () => {
      await render();
      await type('#td-title', '   ');
      expect(completeButton().disabled).toBe(true);

      completeButton().dispatchEvent(new Event('click'));
      await refresh();

      expect(callsOf(updateTask)).toHaveLength(0);
      expect(callsOf(completeTask)).toHaveLength(0);
    });

    it('does not complete when saving the changes first fails', async () => {
      await render();
      await type('#td-title', 'Mine');
      answer = ((previous) => (fn: unknown, params: Record<string, unknown>) =>
        fn === updateTask
          ? Promise.reject(new ApiError(500, 'Internal Server Error', 'The server failed.'))
          : previous?.(fn, params))(answer);

      await click(completeButton());

      expect(callsOf(updateTask)).toHaveLength(1);
      expect(callsOf(completeTask)).toHaveLength(0);
      expect(text()).toContain('The server failed.');
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Mine');
      expect(undo.completed).not.toHaveBeenCalled();
    });

    it('does not complete when saving the changes first meets a 412', async () => {
      await render();
      await type('#td-title', 'Mine');
      answer = ((previous) => (fn: unknown, params: Record<string, unknown>) => {
        if (fn === updateTask) {
          server = task({ title: 'Theirs', version: 4 });
          return Promise.reject(new ApiError(412, 'Precondition Failed'));
        }
        return previous?.(fn, params);
      })(answer);

      await click(completeButton());

      expect(callsOf(updateTask)).toHaveLength(1);
      expect(callsOf(completeTask)).toHaveLength(0);
      expect(text()).toContain(CONFLICT_MESSAGE);
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Theirs');
      expect(label(completeButton())).toBe('Complete');
      expect(undo.completed).not.toHaveBeenCalled();
    });

    it('does not complete the new task when the editor switches during the save first', async () => {
      let release!: () => void;
      await render();
      await type('#td-title', 'Mine');
      answer = ((previous) => (fn: unknown, params: Record<string, unknown>) => {
        if (fn === getTask && params['id'] === 't2') {
          return Promise.resolve(task({ id: 't2', title: 'Water the plants', version: 7 }));
        }
        if (fn === updateTask) {
          return new Promise((resolve) => {
            release = () => resolve(task({ title: 'Mine', version: 2 }));
          });
        }
        return previous?.(fn, params);
      })(answer);

      await click(completeButton());
      host.id.set('t2');
      await refresh();
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Water the plants');

      release();
      await refresh();

      expect(callsOf(updateTask)).toHaveLength(1);
      expect(callsOf(completeTask)).toHaveLength(0);
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Water the plants');
      expect(label(completeButton())).toBe('Complete');
      expect(undo.completed).not.toHaveBeenCalled();
    });

    it('does not complete when edits are typed while saving the changes first', async () => {
      let release!: () => void;
      await render();
      await type('#td-title', 'Mine');
      answer = ((previous) => (fn: unknown, params: Record<string, unknown>) => {
        if (fn === updateTask) {
          return new Promise((resolve) => {
            release = () => resolve(task({ title: 'Mine', version: 2 }));
          });
        }
        return previous?.(fn, params);
      })(answer);

      await click(completeButton());
      await type('#td-title', 'Mine, typed while saving');

      release();
      await refresh();

      expect(callsOf(updateTask)).toHaveLength(1);
      expect(callsOf(completeTask)).toHaveLength(0);
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Mine, typed while saving');
      expect(text()).toContain('Unsaved changes');
      expect(label(completeButton())).toBe('Complete');
      expect(undo.completed).not.toHaveBeenCalled();
    });

    it('on a 412 while completing says the task changed elsewhere and reloads it', async () => {
      hold.complete = () => {
        server = task({ title: 'Theirs', version: 4 });
        return Promise.reject(new ApiError(412, 'Precondition Failed'));
      };
      await render();

      await click(completeButton());

      expect(text()).toContain(CONFLICT_MESSAGE);
      expect(callsOf(getTask)).toHaveLength(2);
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Theirs');
      expect(label(completeButton())).toBe('Complete');
      expect(completeButton().disabled).toBe(false);
      expect(undo.completed).not.toHaveBeenCalled();
    });

    it('shows the error when completing fails and lets the user retry', async () => {
      hold.complete = () =>
        Promise.reject(new ApiError(503, 'Service Unavailable', 'Try again later.'));
      await render();

      await click(completeButton());

      expect(text()).toContain('Try again later.');
      expect(label(completeButton())).toBe('Complete');
      expect(undo.completed).not.toHaveBeenCalled();

      hold.complete = undefined;
      await click(completeButton());
      expect(text()).not.toContain('Try again later.');
      expect(label(completeButton())).toBe('Reopen');
    });

    it('shows the error when reopening fails', async () => {
      hold.uncomplete = () => Promise.reject(new ApiError(500, 'Internal Server Error', 'Nope.'));
      await render(task({ completedAt: DONE_AT }));

      await click(completeButton());

      expect(text()).toContain('Nope.');
      expect(label(completeButton())).toBe('Reopen');
    });

    it('does not reload on a change event while completing', async () => {
      let release!: () => void;
      hold.complete = () =>
        new Promise((resolve) => {
          release = () => {
            server = { ...server, completedAt: DONE_AT, version: server.version + 1 };
            resolve({ ...server, needsNextStep: false });
          };
        });
      await render();
      await click(completeButton());
      events.next({ type: 'TASK', id: 't1', op: 'UPDATED' } as ChangeEvent);
      await refresh();
      expect(callsOf(getTask)).toHaveLength(1);

      release();
      await refresh();
      expect(label(completeButton())).toBe('Reopen');
    });

    it('drops a background reload that started before completing', async () => {
      let oldAnswer!: () => void;
      await render();
      answer = ((previous) => (fn: unknown, params: Record<string, unknown>) => {
        if (fn === getTask) {
          const before = task({ version: 1 });
          return new Promise((resolve) => (oldAnswer = () => resolve(before)));
        }
        return previous?.(fn, params);
      })(answer);
      events.next({ type: 'TASK', id: 't1', op: 'UPDATED' } as ChangeEvent);
      await refresh();

      await click(completeButton());
      oldAnswer();
      await refresh();

      expect(label(completeButton())).toBe('Reopen');
      expect(text()).toContain('Done');
    });

    it('does not show the completion of the previous task in the new one', async () => {
      let release!: () => void;
      answer = ((previous) => (fn: unknown, params: Record<string, unknown>) => {
        if (fn === getTask && params['id'] === 't2') {
          return Promise.resolve(task({ id: 't2', title: 'Water the plants', version: 7 }));
        }
        if (fn === completeTask) {
          return new Promise((resolve) => {
            release = () =>
              resolve({ ...task({ completedAt: DONE_AT, version: 2 }), needsNextStep: false });
          });
        }
        return previous?.(fn, params);
      })(answer);
      await render();
      await click(completeButton());
      host.id.set('t2');
      await refresh();
      // The new task is not blocked by the previous task's action.
      expect(label(completeButton())).toBe('Complete');
      expect(completeButton().disabled).toBe(false);

      release();
      await refresh();

      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Water the plants');
      expect(label(completeButton())).toBe('Complete');
      expect(text()).not.toContain('Done');
    });

    it('deletes a task without unsaved changes at once, offers Undo and closes', async () => {
      const confirm = vi.spyOn(window, 'confirm');
      await render(task({ version: 6 }));
      expect(label(deleteButton())).toBe('Delete');

      await click(deleteButton());

      expect(confirm).not.toHaveBeenCalled();
      const call = callsOf(deleteTask)[0];
      expect(call.params).toEqual({ id: 't1', 'If-Match': '"6"' });
      expect(call.context?.get(SILENT_ERRORS)).toBe(true);
      expect(undo.deleted).toHaveBeenCalledTimes(1);
      expect(undo.deleted.mock.calls[0][0]).toMatchObject({ id: 't1', title: 'Call the plumber' });
      expect(host.closed).toBe(1);
      expect(undo.completed).not.toHaveBeenCalled();
    });

    it('asks before deleting a task with unsaved changes and keeps them when cancelled', async () => {
      const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
      await render();
      await type('#td-title', 'Half typed');

      await click(deleteButton());

      expect(confirm).toHaveBeenCalledWith(
        'Move this task to the Trash? Your unsaved changes are discarded.',
      );
      expect(callsOf(deleteTask)).toHaveLength(0);
      expect(host.closed).toBe(0);
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Half typed');
      expect(text()).toContain('Unsaved changes');
    });

    it('deletes the saved version, without saving, when the user confirms dropping changes', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      await render();
      await type('#td-title', 'Half typed');

      await click(deleteButton());

      expect(callsOf(updateTask)).toHaveLength(0);
      expect(callsOf(deleteTask)[0].params).toEqual({ id: 't1', 'If-Match': '"1"' });
      expect(host.closed).toBe(1);
    });

    it('does not show "moved to the Trash" for its own deletion event', async () => {
      let release!: () => void;
      hold.remove = () => new Promise<void>((resolve) => (release = resolve));
      await render();

      await click(deleteButton());
      events.next({ type: 'TASK', id: 't1', op: 'DELETED' } as ChangeEvent);
      await refresh();

      expect(text()).not.toContain('This task was moved to the Trash.');
      expect(field<HTMLTextAreaElement>('#td-title')).not.toBeNull();

      release();
      await refresh();
      expect(host.closed).toBe(1);
    });

    it('does not show "moved to the Trash" for its own deletion event arriving after the answer', async () => {
      await render();

      await click(deleteButton());
      expect(host.closed).toBe(1);
      // The page has not navigated away yet when the event arrives.
      events.next({ type: 'TASK', id: 't1', op: 'DELETED' } as ChangeEvent);
      await refresh();

      expect(text()).not.toContain('This task was moved to the Trash.');
      expect(field<HTMLTextAreaElement>('#td-title')).not.toBeNull();
    });

    it('shows "moved to the Trash" when someone else deletes it after its own delete failed', async () => {
      hold.remove = () => Promise.reject(new ApiError(503, 'Service Unavailable'));
      await render();
      await click(deleteButton());
      expect(host.closed).toBe(0);

      events.next({ type: 'TASK', id: 't1', op: 'DELETED' } as ChangeEvent);
      await refresh();

      expect(text()).toContain('This task was moved to the Trash.');
    });

    it('still shows "moved to the Trash" when someone else deletes it during a completion', async () => {
      let release!: () => void;
      hold.complete = () =>
        new Promise((_, reject) => (release = () => reject(new ApiError(404, 'Not Found'))));
      await render();

      await click(completeButton());
      events.next({ type: 'TASK', id: 't1', op: 'DELETED' } as ChangeEvent);
      await refresh();

      expect(text()).toContain('This task was moved to the Trash.');
      release();
      await refresh();
    });

    it('on a 412 while deleting says the task changed elsewhere, reloads and stays', async () => {
      hold.remove = () => {
        server = task({ title: 'Theirs', version: 9 });
        return Promise.reject(new ApiError(412, 'Precondition Failed'));
      };
      await render();

      await click(deleteButton());

      expect(text()).toContain(CONFLICT_MESSAGE);
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Theirs');
      expect(host.closed).toBe(0);
      expect(undo.deleted).not.toHaveBeenCalled();
      expect(label(deleteButton())).toBe('Delete');
      expect(deleteButton().disabled).toBe(false);
    });

    it('shows the error when deleting fails and stays', async () => {
      hold.remove = () =>
        Promise.reject(new ApiError(500, 'Internal Server Error', 'Could not delete.'));
      await render();

      await click(deleteButton());

      expect(text()).toContain('Could not delete.');
      expect(host.closed).toBe(0);
      expect(undo.deleted).not.toHaveBeenCalled();
    });

    it('does not close the new task when the deletion of the previous one answers late', async () => {
      let release!: () => void;
      answer = ((previous) => (fn: unknown, params: Record<string, unknown>) => {
        if (fn === getTask && params['id'] === 't2') {
          return Promise.resolve(task({ id: 't2', title: 'Water the plants', version: 7 }));
        }
        return previous?.(fn, params);
      })(answer);
      hold.remove = () => new Promise<void>((resolve) => (release = resolve));
      await render();
      await click(deleteButton());
      host.id.set('t2');
      await refresh();

      release();
      await refresh();

      expect(host.closed).toBe(0);
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Water the plants');
      // The deleted task can still be restored.
      expect(undo.deleted).toHaveBeenCalledTimes(1);
      expect(undo.deleted.mock.calls[0][0]).toMatchObject({ id: 't1' });
    });

    it('keeps the new task busy when the deletion of the previous one answers during its completion', async () => {
      let removed!: () => void;
      let completed!: () => void;
      answer = ((previous) => (fn: unknown, params: Record<string, unknown>) => {
        if (fn === getTask && params['id'] === 't2') {
          return Promise.resolve(task({ id: 't2', title: 'Water the plants', version: 7 }));
        }
        if (fn === completeTask) {
          return new Promise((resolve) => {
            completed = () =>
              resolve({
                ...task({ id: 't2', title: 'Water the plants', completedAt: DONE_AT, version: 8 }),
                needsNextStep: false,
              });
          });
        }
        return previous?.(fn, params);
      })(answer);
      hold.remove = () => new Promise<void>((resolve) => (removed = resolve));
      await render();
      await click(deleteButton());
      host.id.set('t2');
      await refresh();
      await click(completeButton());
      expect(label(completeButton())).toBe('Completing…');

      removed();
      await refresh();

      // The answer for t1 does not end t2's running action.
      expect(label(completeButton())).toBe('Completing…');
      expect(deleteButton().disabled).toBe(true);
      expect(host.closed).toBe(0);

      completed();
      await refresh();
      expect(label(completeButton())).toBe('Reopen');
      expect(deleteButton().disabled).toBe(false);
    });

    it('shows nothing in the new task when the deletion of the previous one fails late', async () => {
      let fail!: () => void;
      answer = ((previous) => (fn: unknown, params: Record<string, unknown>) => {
        if (fn === getTask && params['id'] === 't2') {
          return Promise.resolve(task({ id: 't2', title: 'Water the plants', version: 7 }));
        }
        return previous?.(fn, params);
      })(answer);
      hold.remove = () =>
        new Promise<void>(
          (_, reject) =>
            (fail = () => reject(new ApiError(500, 'Internal Server Error', 'Could not delete.'))),
        );
      await render();
      await click(deleteButton());
      host.id.set('t2');
      await refresh();

      fail();
      await refresh();

      expect(text()).not.toContain('Could not delete.');
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Water the plants');
      expect(host.closed).toBe(0);
      expect(undo.deleted).not.toHaveBeenCalled();
      expect(deleteButton().disabled).toBe(false);
    });

    it('shows "moved to the Trash" when the task it deleted is opened again and someone else deletes it', async () => {
      answer = ((previous) => (fn: unknown, params: Record<string, unknown>) => {
        if (fn === getTask && params['id'] === 't2') {
          return Promise.resolve(task({ id: 't2', title: 'Water the plants', version: 7 }));
        }
        return previous?.(fn, params);
      })(answer);
      await render();
      await click(deleteButton());
      expect(host.closed).toBe(1);
      // The page moves on; later the task is restored from the Trash and opened again.
      host.id.set('t2');
      await refresh();
      host.id.set('t1');
      await refresh();
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Call the plumber');

      events.next({ type: 'TASK', id: 't1', op: 'DELETED' } as ChangeEvent);
      await refresh();

      expect(text()).toContain('This task was moved to the Trash.');
    });
  });

  describe('rendered notes', () => {
    const view = () => element.querySelector<HTMLElement>('#td-notes-view');
    const source = () => element.querySelector<HTMLTextAreaElement>('#td-notes');
    const editButton = () => element.querySelector<HTMLButtonElement>('button.edit-notes');

    afterEach(() => vi.restoreAllMocks());

    it('shows the textarea directly for empty or blank notes', async () => {
      await render(task({ notesMd: '  \n ' }));
      expect(view()).toBeNull();
      expect(editButton()).toBeNull();
      expect(source()).not.toBeNull();
    });

    it('renders the notes as Markdown, with an Edit button', async () => {
      await render(
        task({
          notesMd: '## Parts\n- **washer**\n- *tape*\n\nSee [shop](https://example.com/shop)',
        }),
      );
      expect(source()).toBeNull();
      expect(editButton()?.textContent?.trim()).toBe('Edit');
      expect(view()!.querySelector('h4')?.textContent).toBe('Parts');
      expect(view()!.querySelector('li strong')?.textContent).toBe('washer');
      expect(view()!.querySelector('li em')?.textContent).toBe('tape');
      expect(view()!.querySelector('a')?.getAttribute('href')).toBe('https://example.com/shop');
      expect(view()!.getAttribute('tabindex')).toBe('0');
    });

    it('escapes HTML in the notes instead of rendering it', async () => {
      await render(task({ notesMd: '<img src=x onerror="alert(1)"> <script>bad()</script>' }));
      expect(view()!.querySelector('img')).toBeNull();
      expect(view()!.querySelector('script')).toBeNull();
      expect(view()!.textContent).toContain('<img src=x');
    });

    it('switches to the focused textarea with Edit', async () => {
      await render(task({ notesMd: 'Buy a **washer**' }));
      editButton()!.click();
      await refresh();
      expect(view()).toBeNull();
      expect(editButton()).toBeNull();
      expect(source()!.value).toBe('Buy a **washer**');
      expect(document.activeElement).toBe(source());
    });

    it('switches to the textarea on a click on the rendered notes', async () => {
      await render(task({ notesMd: 'Buy a **washer**' }));
      view()!.querySelector('strong')!.click();
      await refresh();
      expect(source()!.value).toBe('Buy a **washer**');
    });

    it('switches to the textarea with Enter on the rendered notes', async () => {
      await render(task({ notesMd: 'Buy a washer' }));
      view()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
      await refresh();
      expect(source()).not.toBeNull();
    });

    it('keeps the notes rendered when a link in them is clicked', async () => {
      await render(task({ notesMd: 'See [shop](https://example.com/shop)' }));
      const link = view()!.querySelector('a')!;
      // The test environment has no navigation; only the editor's reaction matters.
      link.addEventListener('click', (event) => event.preventDefault());
      link.click();
      await refresh();
      expect(view()).not.toBeNull();
      expect(source()).toBeNull();
    });

    it('keeps the notes rendered while text in them is being selected', async () => {
      await render(task({ notesMd: 'Copy this part' }));
      vi.spyOn(window, 'getSelection').mockReturnValue({
        toString: () => 'this part',
      } as Selection);
      view()!.click();
      await refresh();
      expect(view()).not.toBeNull();
    });

    it('stays in the textarea while typing, also when the notes become empty', async () => {
      await render(task({ notesMd: 'Old' }));
      editButton()!.click();
      await refresh();
      await type('#td-notes', 'New **text**');
      expect(source()).not.toBeNull();
      await type('#td-notes', '');
      await type('#td-notes', 'Back again');
      expect(source()!.value).toBe('Back again');
      expect(view()).toBeNull();
    });

    it('renders the notes again after saving', async () => {
      await render(task({ notesMd: 'Old' }));
      editButton()!.click();
      await refresh();
      await type('#td-notes', 'New **text**');

      await save();

      expect(callsOf(updateTask)[0].params).toMatchObject({ body: { notesMd: 'New **text**' } });
      expect(source()).toBeNull();
      expect(view()!.querySelector('strong')?.textContent).toBe('text');
    });

    it('renders the notes again after discarding', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      await render(task({ notesMd: 'Old' }));
      editButton()!.click();
      await refresh();
      await type('#td-notes', 'Thrown away');

      [...element.querySelectorAll<HTMLButtonElement>('button')]
        .find((button) => button.textContent?.trim() === 'Discard')!
        .click();
      await refresh();

      expect(source()).toBeNull();
      expect(view()!.textContent).toBe('Old');
    });

    it('renders the notes of the next task when the editor switches', async () => {
      answer = (fn, params) =>
        fn === getTask && params['id'] === 't2'
          ? Promise.resolve(task({ id: 't2', notesMd: 'Second *task*', version: 2 }))
          : undefined;
      await render(task({ notesMd: 'First' }));
      editButton()!.click();
      await refresh();
      expect(source()).not.toBeNull();

      host.id.set('t2');
      await refresh();

      expect(source()).toBeNull();
      expect(view()!.querySelector('em')?.textContent).toBe('task');
    });

    it('shows the source when the server rejects the notes', async () => {
      await render(task({ notesMd: 'Old' }));
      editButton()!.click();
      await refresh();
      await type('#td-notes', 'Too long, pretend');
      answer = (fn) =>
        fn === updateTask
          ? Promise.reject(
              new ApiError(400, 'Bad Request', undefined, undefined, undefined, [
                { field: 'notesMd', message: 'Notes are too long.' },
              ]),
            )
          : undefined;

      await save();

      expect(source()!.value).toBe('Too long, pretend');
      expect(text()).toContain('Notes are too long.');
    });
  });

  describe('New project from the project picker', () => {
    const created = { id: 'p9', title: 'Garage', status: 'ACTIVE' } as ProjectResponse;

    function answerCreate(fail = 0): void {
      let failures = fail;
      answer = (fn) => {
        if (fn !== createProject) return undefined;
        if (failures-- > 0) throw new ApiError(500, 'Server error');
        return created;
      };
    }

    async function create(): Promise<void> {
      field<HTMLButtonElement>('.pair button.primary').click();
      await refresh();
    }

    it('asks for a title, creates the project and assigns the task in one step', async () => {
      answerCreate();
      await render();
      await choose('#td-project', 'new-project');
      expect(field('#td-new-project')).not.toBeNull();
      expect(document.activeElement).toBe(field('#td-new-project'));
      await type('#td-new-project', '  Garage  ');
      await create();

      expect(callsOf(createProject).map((call) => call.params['body'])).toEqual([
        { title: 'Garage' },
      ]);
      expect(callsOf(updateTask)[0].params['body']).toEqual({ projectId: 'p9' });
      expect(element.querySelector('#td-new-project')).toBeNull();
      expect(field<HTMLSelectElement>('#td-project').value).toBe('p9');
      expect(text()).toContain('Garage');
      expect(saveButton().disabled).toBe(true);
    });

    it('Cancel or an empty title leave the project unchanged', async () => {
      answerCreate();
      await render(task({ projectId: 'p1' }));
      await choose('#td-project', 'new-project');
      field<HTMLButtonElement>('.pair button.quiet').click();
      await refresh();
      expect(element.querySelector('#td-new-project')).toBeNull();
      expect(field<HTMLSelectElement>('#td-project').value).toBe('p1');

      await choose('#td-project', 'new-project');
      await type('#td-new-project', '   ');
      expect(field<HTMLButtonElement>('.pair button.primary').disabled).toBe(true);
      field('#td-new-project').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
      await refresh();
      expect(element.querySelector('#td-new-project')).toBeNull();
      expect(field<HTMLSelectElement>('#td-project').value).toBe('p1');
      expect(callsOf(createProject)).toHaveLength(0);
      expect(callsOf(updateTask)).toHaveLength(0);
    });

    it('shows an error when creating fails; Create again retries', async () => {
      answerCreate(1);
      await render();
      await choose('#td-project', 'new-project');
      await type('#td-new-project', 'Garage');
      await create();
      expect(element.querySelector('[role=alert]')).not.toBeNull();
      expect(field('#td-new-project').value).toBe('Garage');
      expect(callsOf(updateTask)).toHaveLength(0);

      await create();
      expect(callsOf(createProject)).toHaveLength(2);
      expect(callsOf(updateTask)[0].params['body']).toEqual({ projectId: 'p9' });
    });

    it('a failed save keeps the new project chosen; a retry does not create another', async () => {
      answerCreate();
      let saveFailures = 1;
      const create = answer!;
      answer = (fn, params) => {
        if (fn === updateTask && saveFailures-- > 0) throw new ApiError(500, 'Server error');
        return create(fn, params);
      };
      await render();
      await choose('#td-project', 'new-project');
      await type('#td-new-project', 'Garage');
      field<HTMLButtonElement>('.pair button.primary').click();
      await refresh();
      expect(element.querySelector('[role=alert]')).not.toBeNull();
      expect(field<HTMLSelectElement>('#td-project').value).toBe('p9');

      // Retrying with "New project…" again reuses the project created before.
      await choose('#td-project', 'new-project');
      await type('#td-new-project', 'Garage');
      field<HTMLButtonElement>('.pair button.primary').click();
      await refresh();
      expect(callsOf(createProject)).toHaveLength(1);
      expect(callsOf(updateTask).at(-1)!.params['body']).toEqual({ projectId: 'p9' });
      expect(element.querySelector('[role=alert]')).toBeNull();
    });

    it('after a failed save, Save retries with the created project without creating another', async () => {
      answerCreate();
      let saveFailures = 1;
      const creating = answer!;
      answer = (fn, params) => {
        if (fn === updateTask && saveFailures-- > 0) throw new ApiError(500, 'Server error');
        return creating(fn, params);
      };
      await render();
      await choose('#td-project', 'new-project');
      await type('#td-new-project', 'Garage');
      await create();
      expect(element.querySelector('[role=alert]')).not.toBeNull();
      expect(saveButton().disabled).toBe(false);

      await save();
      expect(callsOf(createProject)).toHaveLength(1);
      expect(callsOf(updateTask)).toHaveLength(2);
      expect(callsOf(updateTask)[1].params['body']).toEqual({ projectId: 'p9' });
      expect(element.querySelector('[role=alert]')).toBeNull();
      expect(field<HTMLSelectElement>('#td-project').value).toBe('p9');
    });

    it('choosing another project after a failed save forgets the created one', async () => {
      const shed = { id: 'p10', title: 'Shed', status: 'ACTIVE' } as ProjectResponse;
      let saveFailures = 1;
      const bodies: unknown[] = [];
      answer = (fn, params) => {
        if (fn === createProject) {
          bodies.push(params['body']);
          return bodies.length === 1 ? created : shed;
        }
        if (fn === updateTask && saveFailures-- > 0) throw new ApiError(500, 'Server error');
        return undefined;
      };
      await render();
      await choose('#td-project', 'new-project');
      await type('#td-new-project', 'Garage');
      await create();
      expect(element.querySelector('[role=alert]')).not.toBeNull();

      // Choosing an existing project drops the remembered one ...
      await choose('#td-project', 'p1');
      // ... so "New project…" creates a fresh project again.
      await choose('#td-project', 'new-project');
      await type('#td-new-project', 'Shed');
      await create();

      expect(bodies).toEqual([{ title: 'Garage' }, { title: 'Shed' }]);
      expect(callsOf(updateTask).at(-1)!.params['body']).toEqual({ projectId: 'p10' });
      expect(field<HTMLSelectElement>('#td-project').value).toBe('p10');
    });

    it('Escape in the title field cancels without closing anything else', async () => {
      answerCreate();
      await render(task({ projectId: 'p1' }));
      await choose('#td-project', 'new-project');
      await type('#td-new-project', 'Garage');
      const outer = vi.fn();
      element.addEventListener('keydown', outer);

      const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true });
      field('#td-new-project').dispatchEvent(escape);
      await refresh();

      expect(outer).not.toHaveBeenCalled();
      expect(element.querySelector('#td-new-project')).toBeNull();
      expect(field<HTMLSelectElement>('#td-project').value).toBe('p1');
      expect(callsOf(createProject)).toHaveLength(0);
      expect(saveButton().disabled).toBe(true);
    });

    it('Enter in the title field creates the project', async () => {
      answerCreate();
      await render();
      await choose('#td-project', 'new-project');
      await type('#td-new-project', 'Garage');
      field('#td-new-project').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
      await refresh();

      expect(callsOf(createProject).map((call) => call.params['body'])).toEqual([
        { title: 'Garage' },
      ]);
      expect(callsOf(updateTask)[0].params['body']).toEqual({ projectId: 'p9' });
    });

    it('choosing "New project…" alone changes nothing to save', async () => {
      await render(task({ projectId: 'p1' }));
      await choose('#td-project', 'new-project');
      expect(field<HTMLSelectElement>('#td-project').value).toBe('new-project');
      expect(saveButton().disabled).toBe(true);
      expect(text()).not.toContain('Unsaved changes');
    });

    it('the new project is offered in the picker and kept in the project list', async () => {
      answerCreate();
      await render();
      await choose('#td-project', 'new-project');
      await type('#td-new-project', 'Garage');
      await create();

      const options = [...field<HTMLSelectElement>('#td-project').options].map((o) => o.value);
      expect([...options].sort()).toEqual(['', 'new-project', 'p1', 'p9']);
      expect(options.at(-1)).toBe('new-project');
      expect(
        TestBed.inject(ProjectStore)
          .items()
          .map((project) => project.id),
      ).toContain('p9');
      expect(server.projectId).toBe('p9');
    });

    it('saves other unsaved edits together with the new project', async () => {
      answerCreate();
      await render();
      await type('#td-title', 'Call the plumber today');
      await choose('#td-project', 'new-project');
      await type('#td-new-project', 'Garage');
      await create();

      expect(callsOf(updateTask)).toHaveLength(1);
      expect(callsOf(updateTask)[0].params['body']).toEqual({
        title: 'Call the plumber today',
        projectId: 'p9',
      });
    });

    it('locks the picker and buttons while the project is being created', async () => {
      let finish!: (project: ProjectResponse) => void;
      answer = (fn) =>
        fn === createProject
          ? new Promise<ProjectResponse>((resolve) => (finish = resolve))
          : undefined;
      await render(task({ projectId: 'p1' }));
      await choose('#td-project', 'new-project');
      await type('#td-new-project', 'Garage');
      await create();

      expect(field<HTMLSelectElement>('#td-project').disabled).toBe(true);
      expect(field('#td-new-project').disabled).toBe(true);
      expect(field<HTMLButtonElement>('.pair button.primary').disabled).toBe(true);
      expect(field<HTMLButtonElement>('.pair button.quiet').disabled).toBe(true);
      // Escape while creating does not drop the running creation.
      field('#td-new-project').dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }),
      );
      // A second Enter does not create a second project.
      field('#td-new-project').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
      await refresh();
      expect(element.querySelector('#td-new-project')).not.toBeNull();
      expect(callsOf(createProject)).toHaveLength(1);

      finish(created);
      await refresh();
      expect(callsOf(createProject)).toHaveLength(1);
      expect(callsOf(updateTask)[0].params['body']).toEqual({ projectId: 'p9' });
      expect(field<HTMLSelectElement>('#td-project').disabled).toBe(false);
      expect(field<HTMLSelectElement>('#td-project').value).toBe('p9');
    });

    it('switching to another task while creating does not assign the project to it', async () => {
      let finish!: (project: ProjectResponse) => void;
      answer = (fn) =>
        fn === createProject
          ? new Promise<ProjectResponse>((resolve) => (finish = resolve))
          : undefined;
      await render();
      await choose('#td-project', 'new-project');
      await type('#td-new-project', 'Garage');
      await create();

      server = task({ id: 't2', title: 'Buy paint', projectId: 'p1' });
      host.id.set('t2');
      await refresh();
      expect(element.querySelector('#td-new-project')).toBeNull();

      finish(created);
      await refresh();
      expect(callsOf(updateTask)).toHaveLength(0);
      expect(field<HTMLTextAreaElement>('#td-title').value).toBe('Buy paint');
      expect(field<HTMLSelectElement>('#td-project').value).toBe('p1');
      expect(element.querySelector('[role=alert]')).toBeNull();
    });

    it('switching to another task while creating fails shows no error there', async () => {
      let fail!: (error: unknown) => void;
      answer = (fn) =>
        fn === createProject
          ? new Promise<ProjectResponse>((_, reject) => (fail = reject))
          : undefined;
      await render();
      await choose('#td-project', 'new-project');
      await type('#td-new-project', 'Garage');
      await create();

      server = task({ id: 't2', title: 'Buy paint' });
      host.id.set('t2');
      await refresh();
      fail(new ApiError(500, 'Server error'));
      await refresh();

      expect(element.querySelector('[role=alert]')).toBeNull();
      expect(callsOf(updateTask)).toHaveLength(0);
    });

    it('a project remembered for one task is not reused for another', async () => {
      const shed = { id: 'p10', title: 'Shed', status: 'ACTIVE' } as ProjectResponse;
      let saveFailures = 1;
      let creates = 0;
      answer = (fn) => {
        if (fn === createProject) return ++creates === 1 ? created : shed;
        if (fn === updateTask && saveFailures-- > 0) throw new ApiError(500, 'Server error');
        return undefined;
      };
      await render();
      await choose('#td-project', 'new-project');
      await type('#td-new-project', 'Garage');
      await create();
      expect(element.querySelector('[role=alert]')).not.toBeNull();

      server = task({ id: 't2', title: 'Buy paint' });
      host.id.set('t2');
      await refresh();
      await choose('#td-project', 'new-project');
      await type('#td-new-project', 'Shed');
      await create();

      expect(creates).toBe(2);
      const last = callsOf(updateTask).at(-1)!;
      expect(last.params).toMatchObject({ id: 't2', body: { projectId: 'p10' } });
    });

    /** Answers creates with Garage, then Garden; the first task save meets a 412 (conflict). */
    function answerConflictOnce(): unknown[] {
      const garden = { id: 'p11', title: 'Garden', status: 'ACTIVE' } as ProjectResponse;
      let conflicts = 1;
      const bodies: unknown[] = [];
      answer = (fn, params) => {
        if (fn === createProject) {
          bodies.push(params['body']);
          return bodies.length === 1 ? created : garden;
        }
        if (fn === updateTask && conflicts-- > 0) {
          server = task({ version: 5 });
          return Promise.reject(new ApiError(412, 'Precondition Failed'));
        }
        return undefined;
      };
      return bodies;
    }

    it('after a failed save, a retry with a different title creates that project', async () => {
      const bodies = answerConflictOnce();
      await render();
      await choose('#td-project', 'new-project');
      await type('#td-new-project', 'Garage');
      await create();
      expect(text()).toContain(CONFLICT_MESSAGE);

      await choose('#td-project', 'new-project');
      await type('#td-new-project', ' Garden ');
      await create();

      expect(bodies).toEqual([{ title: 'Garage' }, { title: 'Garden' }]);
      expect(callsOf(updateTask).at(-1)!.params).toMatchObject({
        'If-Match': '"5"',
        body: { projectId: 'p11' },
      });
      expect(field<HTMLSelectElement>('#td-project').value).toBe('p11');
    });

    it('after a conflict, a retry with the same title reuses the created project', async () => {
      const bodies = answerConflictOnce();
      await render();
      await choose('#td-project', 'new-project');
      await type('#td-new-project', 'Garage');
      await create();
      expect(text()).toContain(CONFLICT_MESSAGE);

      await choose('#td-project', 'new-project');
      await type('#td-new-project', '  Garage ');
      await create();

      expect(bodies).toEqual([{ title: 'Garage' }]);
      expect(callsOf(updateTask).at(-1)!.params).toMatchObject({
        'If-Match': '"5"',
        body: { projectId: 'p9' },
      });
      expect(field<HTMLSelectElement>('#td-project').value).toBe('p9');
    });

    it('a project created by a retry with another title is the one a later retry reuses', async () => {
      const garden = { id: 'p11', title: 'Garden', status: 'ACTIVE' } as ProjectResponse;
      let saveFailures = 2;
      const bodies: unknown[] = [];
      answer = (fn, params) => {
        if (fn === createProject) {
          bodies.push(params['body']);
          return bodies.length === 1 ? created : garden;
        }
        if (fn === updateTask && saveFailures-- > 0) throw new ApiError(500, 'Server error');
        return undefined;
      };
      await render();
      await choose('#td-project', 'new-project');
      await type('#td-new-project', 'Garage');
      await create();
      await choose('#td-project', 'new-project');
      await type('#td-new-project', 'Garden');
      await create();
      expect(element.querySelector('[role=alert]')).not.toBeNull();

      await choose('#td-project', 'new-project');
      await type('#td-new-project', 'Garden');
      await create();

      expect(bodies).toEqual([{ title: 'Garage' }, { title: 'Garden' }]);
      expect(callsOf(updateTask).at(-1)!.params['body']).toEqual({ projectId: 'p11' });
      expect(element.querySelector('[role=alert]')).toBeNull();
    });

    it('Discard after a failed save forgets the created project', async () => {
      let saveFailures = 1;
      const bodies: unknown[] = [];
      // A plain failure (no reload): the created project stays chosen until Discard.
      answer = (fn, params) => {
        if (fn === createProject) {
          bodies.push(params['body']);
          return created;
        }
        if (fn === updateTask && saveFailures-- > 0) throw new ApiError(500, 'Server error');
        return undefined;
      };
      await render();
      await choose('#td-project', 'new-project');
      await type('#td-new-project', 'Garage');
      await create();
      expect(field<HTMLSelectElement>('#td-project').value).toBe('p9');

      [...element.querySelectorAll('button')]
        .find((b) => b.textContent?.includes('Discard'))!
        .click();
      await refresh();
      expect(field<HTMLSelectElement>('#td-project').value).toBe('');

      await choose('#td-project', 'new-project');
      await type('#td-new-project', 'Garage');
      await create();
      expect(bodies).toEqual([{ title: 'Garage' }, { title: 'Garage' }]);
    });
  });
});
