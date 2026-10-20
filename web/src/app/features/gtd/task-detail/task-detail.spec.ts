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
});
