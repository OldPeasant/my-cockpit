import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { Subject } from 'rxjs';
import {
  Api,
  ChangeEvent,
  completeTask,
  ContextResponse,
  createProject,
  deleteTask,
  listContexts,
  listProjects,
  listTasks,
  moveTask,
  ProjectResponse,
  TaskResponse,
  updateTask,
} from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { addDays, localDate } from '../data';
import { InboxPage } from './inbox-page';

interface Call {
  fn: unknown;
  params: Record<string, unknown>;
}

function task(id: string, fields: Partial<TaskResponse> = {}): TaskResponse {
  return {
    id,
    title: `Item ${id}`,
    list: 'INBOX',
    position: Number(id.replace(/\D/g, '')) || 1,
    contextIds: [],
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-01T08:00:00Z',
    version: 1,
    ...fields,
  };
}

const HOME = { id: 'c1', name: '@home', position: 1 } as ContextResponse;
const KITCHEN = {
  id: 'p1',
  title: 'Renovate kitchen',
  status: 'ACTIVE',
  stalled: false,
} as ProjectResponse;

const settle = () => new Promise((resolve) => setTimeout(resolve));

describe('InboxPage', () => {
  let calls: Call[];
  let inbox: TaskResponse[];
  let element: HTMLElement;
  let resync: Subject<void>;
  /** Answers a call instead of the default fake server when it returns a promise. */
  let answer:
    ((fn: unknown, params: Record<string, unknown>) => Promise<unknown> | undefined) | undefined;

  const callsOf = (fn: unknown) => calls.filter((call) => call.fn === fn);

  async function render(items: TaskResponse[]): Promise<void> {
    inbox = items;
    const fixture = TestBed.createComponent(InboxPage);
    element = fixture.nativeElement;
    document.body.appendChild(element);
    await settle();
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function refresh(): Promise<void> {
    await settle();
    TestBed.tick();
    await settle();
    TestBed.tick();
  }

  async function press(key: string, target: EventTarget = document.body): Promise<void> {
    target.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
    await refresh();
  }

  const title = () => element.querySelector('.item-title')?.textContent?.trim();
  const left = () => element.querySelector('.left')?.textContent?.trim();
  const button = (label: string) =>
    [...element.querySelectorAll<HTMLButtonElement>('button')].find((b) =>
      b.textContent?.includes(label),
    );

  beforeEach(() => {
    calls = [];
    answer = undefined;
    const api = {
      invoke: async (fn: unknown, params: Record<string, unknown> = {}) => {
        calls.push({ fn, params });
        const custom = answer?.(fn, params);
        if (custom) return custom;
        if (fn === listTasks) return { items: inbox };
        if (fn === listContexts) return { items: [HOME] };
        if (fn === listProjects) return { items: [KITCHEN] };
        if (fn === createProject) {
          return { id: 'p2', title: (params['body'] as { title: string }).title, status: 'ACTIVE' };
        }
        const id = params['id'] as string;
        const current = inbox.find((t) => t.id === id)!;
        if (fn === updateTask) return { ...current, ...(params['body'] as object), version: 2 };
        if (fn === moveTask) {
          return { ...current, ...(params['body'] as object), version: current.version + 1 };
        }
        if (fn === completeTask) return { ...current, completedAt: '2026-10-20T10:00:00Z' };
        if (fn === deleteTask) return undefined;
        throw new Error('unexpected call');
      },
    };
    resync = new Subject<void>();
    const events = {
      resync$: resync,
      events$: () => new Subject<ChangeEvent>(),
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: Api, useValue: api },
        { provide: ChangeEventsService, useValue: events },
      ],
    });
  });

  afterEach(() => element?.remove());

  it('lists the inbox and starts processing with P', async () => {
    await render([task('t1'), task('t2')]);

    expect(callsOf(listTasks)[0].params).toMatchObject({ list: 'INBOX' });
    expect(element.querySelectorAll('app-task-row').length).toBe(2);

    await press('p');
    expect(title()).toBe('Item t1');
    expect(left()).toBe('2 items left');
  });

  it('shows an empty inbox without a process button', async () => {
    await render([]);
    expect(element.textContent).toContain('Your inbox is empty');
    expect(button('Process inbox')).toBeUndefined();
  });

  it.each([
    ['n', { list: 'NEXT' }],
    ['m', { list: 'SOMEDAY' }],
  ])('key %s moves the item with %o', async (key, body) => {
    await render([task('t1'), task('t2')]);
    await press('p');

    await press(key);

    expect(callsOf(moveTask)).toHaveLength(1);
    expect(callsOf(moveTask)[0].params).toMatchObject({ id: 't1', 'If-Match': '"1"', body });
    expect(title()).toBe('Item t2');
    expect(left()).toBe('1 item left');
  });

  it('D completes the item', async () => {
    await render([task('t1')]);
    await press('p');
    await press('d');
    expect(callsOf(completeTask)[0].params).toMatchObject({ id: 't1' });
    expect(element.textContent).toContain('Inbox zero.');
  });

  it('X and Delete move the item to the Trash', async () => {
    await render([task('t1'), task('t2')]);
    await press('p');
    await press('x');
    await press('Delete');
    expect(callsOf(deleteTask).map((call) => call.params['id'])).toEqual(['t1', 't2']);
  });

  it('S asks for a date, Enter schedules', async () => {
    await render([task('t1')]);
    await press('p');
    await press('s');

    const date = element.querySelector<HTMLInputElement>('.prompt input[type=date]')!;
    expect(document.activeElement).toBe(date);
    expect(date.value).toBe(addDays(localDate(), 1));
    date.value = '2026-12-01';
    date.dispatchEvent(new Event('input'));
    element.querySelector<HTMLFormElement>('.prompt')!.requestSubmit();
    await refresh();

    expect(callsOf(moveTask)[0].params['body']).toEqual({
      list: 'SCHEDULED',
      scheduledDate: '2026-12-01',
    });
  });

  it('T puts the item in a tickler and it leaves the inbox', async () => {
    await render([task('t1'), task('t2')]);
    await press('p');
    await press('t');
    element.querySelector<HTMLFormElement>('.prompt')!.requestSubmit();
    await refresh();

    expect(callsOf(moveTask)[0].params['body']).toEqual({
      list: 'INBOX',
      ticklerDate: addDays(localDate(), 1),
    });
    expect(title()).toBe('Item t2');
  });

  it('W asks for the person (optional)', async () => {
    await render([task('t1')]);
    await press('p');
    await press('w');
    const text = element.querySelector<HTMLInputElement>('.prompt input[type=text]')!;
    text.value = 'Alex Example';
    text.dispatchEvent(new Event('input'));
    element.querySelector<HTMLFormElement>('.prompt')!.requestSubmit();
    await refresh();

    expect(callsOf(moveTask)[0].params['body']).toEqual({
      list: 'WAITING',
      waitingForText: 'Alex Example',
    });
  });

  it('P moves the item to an existing project', async () => {
    await render([task('t1')]);
    await press('p');
    await press('p');
    const select = element.querySelector<HTMLSelectElement>('.prompt select')!;
    expect(select.value).toBe('p1');
    element.querySelector<HTMLFormElement>('.prompt')!.requestSubmit();
    await refresh();

    expect(callsOf(moveTask)[0].params['body']).toEqual({ list: 'NEXT', projectId: 'p1' });
  });

  it('Enter on the project choice moves the item', async () => {
    await render([task('t1')]);
    await press('p');
    await press('p');
    const select = element.querySelector<HTMLSelectElement>('.prompt select')!;
    expect(document.activeElement).toBe(select);

    await press('Enter', select);

    expect(callsOf(moveTask)).toHaveLength(1);
    expect(callsOf(moveTask)[0].params['body']).toEqual({ list: 'NEXT', projectId: 'p1' });
    expect(element.textContent).toContain('Inbox zero.');
  });

  it('P can create a new project for the item', async () => {
    await render([task('t1')]);
    await press('p');
    await press('p');
    const select = element.querySelector<HTMLSelectElement>('.prompt select')!;
    select.value = '';
    select.dispatchEvent(new Event('change'));
    await refresh();
    const name = element.querySelector<HTMLInputElement>('.prompt input[type=text]')!;
    name.value = 'Plan the trip';
    name.dispatchEvent(new Event('input'));
    element.querySelector<HTMLFormElement>('.prompt')!.requestSubmit();
    await refresh();

    expect(callsOf(createProject)[0].params['body']).toEqual({ title: 'Plan the trip' });
    expect(callsOf(moveTask)[0].params['body']).toEqual({ list: 'NEXT', projectId: 'p2' });
  });

  it('Escape cancels a prompt, then leaves the processing mode', async () => {
    await render([task('t1')]);
    await press('p');
    await press('s');
    await press('Escape', element.querySelector('.prompt input')!);
    expect(element.querySelector('.prompt')).toBeNull();
    expect(callsOf(moveTask)).toHaveLength(0);

    await press('Escape');
    expect(element.querySelector('.desk')).toBeNull();
  });

  it('saves the inline attributes before the action', async () => {
    await render([task('t1')]);
    await press('p');
    button('High')!.click();
    button('Creative')!.click();
    button('15 min')!.click();
    button('@home')!.click();
    await refresh();

    await press('n');

    expect(callsOf(updateTask)[0].params).toMatchObject({
      id: 't1',
      'If-Match': '"1"',
      body: { energy: 'HIGH', creativity: 'CREATIVE', timeEstimateMin: 15, contextIds: ['c1'] },
    });
    // The move uses the version the update returned.
    expect(callsOf(moveTask)[0].params).toMatchObject({ id: 't1', 'If-Match': '"2"' });
  });

  it('sends only the attributes changed inline, keeping a concurrent change', async () => {
    await render([task('t1', { energy: 'LOW' })]);
    await press('p');
    button('Creative')!.click();
    await refresh();

    // Another device changes the item while it is shown: the store refetches it.
    inbox = [task('t1', { energy: 'HIGH', timeEstimateMin: 30, contextIds: ['c1'], version: 2 })];
    resync.next();
    await refresh();
    expect(button('High')!.getAttribute('aria-pressed')).toBe('true');
    expect(button('Creative')!.getAttribute('aria-pressed')).toBe('true');

    await press('n');

    expect(callsOf(updateTask)).toHaveLength(1);
    expect(callsOf(updateTask)[0].params).toMatchObject({ id: 't1', 'If-Match': '"2"' });
    expect(callsOf(updateTask)[0].params['body']).toEqual({ creativity: 'CREATIVE' });
  });

  it('keeps an open prompt when the item is refetched', async () => {
    await render([task('t1')]);
    await press('p');
    await press('s');
    inbox = [task('t1', { notesMd: 'Changed elsewhere', version: 2 })];
    resync.next();
    await refresh();
    expect(element.querySelector('.prompt')).not.toBeNull();
    expect(element.querySelector('.notes')?.textContent).toContain('Changed elsewhere');
  });

  it('does not send an update when nothing was changed', async () => {
    await render([task('t1', { energy: 'LOW' })]);
    await press('p');
    await press('n');
    expect(callsOf(updateTask)).toHaveLength(0);
  });

  it('ignores action keys while typing and with modifiers', async () => {
    await render([task('t1')]);
    await press('p');
    const input = document.createElement('input');
    element.appendChild(input);
    await press('n', input);
    document.body.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'n', ctrlKey: true, bubbles: true }),
    );
    await refresh();
    expect(callsOf(moveTask)).toHaveLength(0);
  });

  it('offers To reference and To calendar disabled, their keys do nothing', async () => {
    await render([task('t1')]);
    await press('p');
    expect(button('To reference')!.disabled).toBe(true);
    expect(button('To calendar')!.disabled).toBe(true);
    await press('r');
    await press('c');
    expect(calls.filter((call) => call.fn !== listTasks && call.fn !== listContexts)).toEqual(
      calls.filter((call) => call.fn === listProjects),
    );
  });

  it('opens a task from the list', async () => {
    await render([task('t1')]);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    element.querySelector<HTMLButtonElement>('app-task-row .title')!.click();
    expect(navigate).toHaveBeenCalledWith(['/tasks', 't1']);
  });

  it('completes an item from its list checkbox', async () => {
    await render([task('t1')]);
    element.querySelector<HTMLInputElement>('app-task-row input.check')!.click();
    await refresh();
    expect(callsOf(completeTask)[0].params).toMatchObject({ id: 't1', 'If-Match': '"1"' });
    expect(element.querySelectorAll('app-task-row').length).toBe(0);
  });

  const progress = () => element.querySelector('[role=progressbar]')?.getAttribute('aria-valuenow');

  describe('when the server refuses', () => {
    it('brings a failed move back as the current item, not counted as processed', async () => {
      await render([task('t1'), task('t2')]);
      answer = (fn) => (fn === moveTask ? Promise.reject(new Error('409')) : undefined);
      await press('p');

      await press('n');

      expect(callsOf(moveTask)).toHaveLength(1);
      expect(title()).toBe('Item t1');
      expect(left()).toBe('2 items left');
      expect(progress()).toBe('0');
    });

    it('brings an item back when its move fails after the next item came up', async () => {
      await render([task('t1'), task('t2')]);
      let reject!: (error: unknown) => void;
      answer = (fn, params) =>
        fn === moveTask && params['id'] === 't1'
          ? new Promise((_, rejectMove) => (reject = rejectMove))
          : undefined;
      await press('p');

      await press('n');
      // t1 is still being moved: t2 is offered meanwhile.
      expect(title()).toBe('Item t2');
      expect(left()).toBe('1 item left');

      reject(new Error('412'));
      await refresh();

      expect(title()).toBe('Item t1');
      expect(left()).toBe('2 items left');
      // It can be processed again, with the version it still has.
      answer = undefined;
      await press('m');
      expect(callsOf(moveTask).at(-1)!.params).toMatchObject({
        id: 't1',
        'If-Match': '"1"',
        body: { list: 'SOMEDAY' },
      });
    });

    it('does not move an item whose attributes could not be saved', async () => {
      await render([task('t1'), task('t2')]);
      answer = (fn) => (fn === updateTask ? Promise.reject(new Error('400')) : undefined);
      await press('p');
      button('High')!.click();
      await refresh();

      await press('n');

      expect(callsOf(updateTask)).toHaveLength(1);
      expect(callsOf(moveTask)).toHaveLength(0);
      expect(title()).toBe('Item t1');
      expect(left()).toBe('2 items left');
    });

    it('keeps saved attributes when only the move fails, then moves with the new version', async () => {
      await render([task('t1')]);
      answer = (fn) => (fn === moveTask ? Promise.reject(new Error('500')) : undefined);
      await press('p');
      button('High')!.click();
      await refresh();

      await press('n');
      expect(callsOf(updateTask)).toHaveLength(1);
      expect(title()).toBe('Item t1');
      expect(button('High')!.getAttribute('aria-pressed')).toBe('true');

      answer = undefined;
      await press('n');
      // The attributes are saved already: no second update, the move sends version 2.
      expect(callsOf(updateTask)).toHaveLength(1);
      expect(callsOf(moveTask).at(-1)!.params).toMatchObject({ id: 't1', 'If-Match': '"2"' });
      expect(element.textContent).toContain('Inbox zero.');
    });

    it('keeps an item in the list when completing it from its checkbox fails', async () => {
      await render([task('t1')]);
      answer = (fn) => (fn === completeTask ? Promise.reject(new Error('412')) : undefined);
      element.querySelector<HTMLInputElement>('app-task-row input.check')!.click();
      await refresh();
      expect(callsOf(completeTask)).toHaveLength(1);
      expect(element.querySelectorAll('app-task-row').length).toBe(1);
    });

    it('offers the created project on a retry when the move after New project failed', async () => {
      await render([task('t1')]);
      answer = (fn) => (fn === moveTask ? Promise.reject(new Error('412')) : undefined);
      await press('p');
      await press('p');
      const select = element.querySelector<HTMLSelectElement>('.prompt select')!;
      select.value = '';
      select.dispatchEvent(new Event('change'));
      await refresh();
      const name = element.querySelector<HTMLInputElement>('.prompt input[type=text]')!;
      name.value = 'Plan the trip';
      name.dispatchEvent(new Event('input'));
      element.querySelector<HTMLFormElement>('.prompt')!.requestSubmit();
      await refresh();
      expect(callsOf(createProject)).toHaveLength(1);
      expect(title()).toBe('Item t1');

      answer = undefined;
      await press('p');
      const retry = element.querySelector<HTMLSelectElement>('.prompt select')!;
      expect(retry.value).toBe('p2');
      expect(element.querySelector('.prompt input[type=text]')).toBeNull();
      element.querySelector<HTMLFormElement>('.prompt')!.requestSubmit();
      await refresh();

      expect(callsOf(createProject)).toHaveLength(1);
      expect(callsOf(moveTask).at(-1)!.params['body']).toEqual({ list: 'NEXT', projectId: 'p2' });
      expect(element.textContent).toContain('Inbox zero.');
    });

    it('brings a failed Trash back', async () => {
      await render([task('t1')]);
      answer = (fn) => (fn === deleteTask ? Promise.reject(new Error('412')) : undefined);
      await press('p');
      await press('x');
      expect(title()).toBe('Item t1');
      expect(left()).toBe('1 item left');
    });

    it('loads the inbox again with Try again after a load error', async () => {
      answer = (fn) => (fn === listTasks ? Promise.reject(new Error('503')) : undefined);
      await render([task('t1')]);
      expect(element.textContent).toContain('The inbox could not be loaded.');

      answer = undefined;
      button('Try again')!.click();
      await refresh();

      expect(callsOf(listTasks)).toHaveLength(2);
      expect(callsOf(listTasks)[1].params).toMatchObject({ list: 'INBOX' });
      expect(element.textContent).not.toContain('The inbox could not be loaded.');
      expect(element.querySelectorAll('app-task-row').length).toBe(1);
    });

    it('shows that the inbox could not be loaded, and P does nothing', async () => {
      answer = (fn) => (fn === listTasks ? Promise.reject(new Error('503')) : undefined);
      await render([task('t1')]);
      expect(element.textContent).toContain('The inbox could not be loaded.');
      await press('p');
      expect(element.querySelector('.desk')).toBeNull();
    });
  });

  describe('keyboard', () => {
    function overlayButton(): HTMLButtonElement {
      const overlay = document.createElement('div');
      overlay.className = 'cdk-overlay-container';
      const inner = document.createElement('button');
      overlay.appendChild(inner);
      document.body.appendChild(overlay);
      return inner;
    }

    afterEach(() => document.querySelectorAll('.cdk-overlay-container').forEach((o) => o.remove()));

    it('ignores keys inside dialogs (the overlay container)', async () => {
      await render([task('t1')]);
      await press('p');
      const inDialog = overlayButton();

      await press('n', inDialog);
      await press('x', inDialog);
      await press('Escape', inDialog);

      expect(callsOf(moveTask)).toHaveLength(0);
      expect(callsOf(deleteTask)).toHaveLength(0);
      expect(title()).toBe('Item t1');
    });

    it('does not start processing from a dialog or an input', async () => {
      await render([task('t1')]);
      const input = document.createElement('input');
      element.appendChild(input);

      await press('p', overlayButton());
      await press('p', input);

      expect(element.querySelector('.desk')).toBeNull();
    });

    it('accepts upper-case keys and ignores repeats and other modifiers', async () => {
      await render([task('t1'), task('t2')]);
      await press('P');
      document.body.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'n', repeat: true, bubbles: true, cancelable: true }),
      );
      for (const modifier of ['altKey', 'metaKey'] as const) {
        document.body.dispatchEvent(
          new KeyboardEvent('keydown', { key: 'n', [modifier]: true, bubbles: true }),
        );
      }
      await refresh();
      expect(callsOf(moveTask)).toHaveLength(0);

      await press('N');
      expect(callsOf(moveTask)[0].params).toMatchObject({ id: 't1', body: { list: 'NEXT' } });
    });

    it('ignores action keys in the list and Escape outside processing', async () => {
      await render([task('t1')]);
      await press('n');
      await press('x');
      await press('Escape');
      expect(callsOf(moveTask)).toHaveLength(0);
      expect(callsOf(deleteTask)).toHaveLength(0);
      expect(element.querySelectorAll('app-task-row').length).toBe(1);
    });

    it('does not start processing an empty inbox with P', async () => {
      await render([]);
      await press('p');
      expect(element.querySelector('.desk')).toBeNull();
    });

    it('ignores action keys while a prompt is open', async () => {
      await render([task('t1')]);
      await press('p');
      await press('s');
      await press('n');
      await press('x');
      expect(callsOf(moveTask)).toHaveLength(0);
      expect(callsOf(deleteTask)).toHaveLength(0);
      expect(element.querySelector('.prompt')).not.toBeNull();
    });

    it('marks the enabled action buttons with their shortcut', async () => {
      await render([task('t1')]);
      await press('p');
      expect(button('Next')!.getAttribute('aria-keyshortcuts')).toBe('N');
      expect(button('Trash')!.getAttribute('aria-keyshortcuts')).toBe('X');
      expect(button('To reference')!.hasAttribute('aria-keyshortcuts')).toBe(false);
    });
  });

  describe('prompts', () => {
    const form = () => element.querySelector<HTMLFormElement>('.prompt')!;
    const submit = () => element.querySelector<HTMLButtonElement>('.prompt button[type=submit]')!;

    async function setDate(value: string): Promise<void> {
      const date = element.querySelector<HTMLInputElement>('.prompt input[type=date]')!;
      date.value = value;
      date.dispatchEvent(new Event('input'));
      await refresh();
    }

    it('rejects a tickler date of today or earlier', async () => {
      await render([task('t1')]);
      await press('p');
      await press('t');
      const date = element.querySelector<HTMLInputElement>('.prompt input[type=date]')!;
      expect(date.min).toBe(addDays(localDate(), 1));

      for (const value of [localDate(), addDays(localDate(), -1), '']) {
        await setDate(value);
        expect(submit().disabled).toBe(true);
        form().requestSubmit();
        await refresh();
      }
      expect(callsOf(moveTask)).toHaveLength(0);
      expect(title()).toBe('Item t1');

      await setDate(addDays(localDate(), 30));
      expect(submit().disabled).toBe(false);
      form().requestSubmit();
      await refresh();
      expect(callsOf(moveTask)[0].params['body']).toEqual({
        list: 'INBOX',
        ticklerDate: addDays(localDate(), 30),
      });
    });

    it('needs a date to schedule', async () => {
      await render([task('t1')]);
      await press('p');
      await press('s');
      await setDate('');
      expect(submit().disabled).toBe(true);
      form().requestSubmit();
      await refresh();
      expect(callsOf(moveTask)).toHaveLength(0);
    });

    it('moves to Waiting without a person when none or only blanks are given', async () => {
      await render([task('t1'), task('t2')]);
      await press('p');
      await press('w');
      form().requestSubmit();
      await refresh();
      await press('w');
      const text = element.querySelector<HTMLInputElement>('.prompt input[type=text]')!;
      expect(text.value).toBe('');
      text.value = '   ';
      text.dispatchEvent(new Event('input'));
      form().requestSubmit();
      await refresh();

      const bodies = callsOf(moveTask).map((call) => call.params['body'] as object);
      expect(bodies).toHaveLength(2);
      for (const body of bodies) {
        expect(JSON.parse(JSON.stringify(body))).toEqual({ list: 'WAITING' });
      }
    });

    it('needs a title for a new project and offers only active projects', async () => {
      answer = (fn) =>
        fn === listProjects
          ? Promise.resolve({
              items: [{ ...KITCHEN, id: 'p9', title: 'Old garage', status: 'COMPLETED' }],
            })
          : undefined;
      await render([task('t1')]);
      await press('p');
      await press('p');

      const select = element.querySelector<HTMLSelectElement>('.prompt select')!;
      expect([...select.options].map((option) => option.textContent?.trim())).toEqual([
        'New project…',
      ]);
      expect(select.value).toBe('');
      const name = element.querySelector<HTMLInputElement>('.prompt input[type=text]')!;
      for (const value of ['', '   ']) {
        name.value = value;
        name.dispatchEvent(new Event('input'));
        await refresh();
        expect(submit().disabled).toBe(true);
        form().requestSubmit();
        await refresh();
      }
      expect(callsOf(createProject)).toHaveLength(0);
      expect(callsOf(moveTask)).toHaveLength(0);
    });

    it('closes the prompt with Cancel without moving', async () => {
      await render([task('t1')]);
      await press('p');
      await press('t');
      button('Cancel')!.click();
      await refresh();
      expect(element.querySelector('.prompt')).toBeNull();
      expect(callsOf(moveTask)).toHaveLength(0);
      // The actions are back and work again.
      await press('n');
      expect(callsOf(moveTask)).toHaveLength(1);
    });
  });

  describe('processing', () => {
    it('runs an action from its button', async () => {
      await render([task('t1'), task('t2')]);
      await press('p');
      button('Someday')!.click();
      await refresh();
      button('Do it')!.click();
      await refresh();
      expect(callsOf(moveTask)[0].params).toMatchObject({ id: 't1', body: { list: 'SOMEDAY' } });
      expect(callsOf(completeTask)[0].params).toMatchObject({ id: 't2' });
    });

    it('shows the progress and the items left until inbox zero', async () => {
      await render([task('t1'), task('t2')]);
      await press('p');
      expect(progress()).toBe('0');
      await press('n');
      expect(progress()).toBe('50');
      expect(left()).toBe('1 item left');
      await press('n');
      expect(progress()).toBe('100');
      expect(left()).toBe('No items left');

      button('Back to the inbox')!.click();
      await refresh();
      expect(element.textContent).toContain('Your inbox is empty');
    });

    it('starts from the Process inbox button and goes back with Back to list', async () => {
      await render([task('t1')]);
      button('Process inbox')!.click();
      await refresh();
      expect(title()).toBe('Item t1');
      button('Back to list')!.click();
      await refresh();
      expect(element.querySelector('.desk')).toBeNull();
      expect(element.querySelectorAll('app-task-row').length).toBe(1);
    });

    it('starts each item with its own attributes, not the draft of the previous one', async () => {
      await render([task('t1'), task('t2', { energy: 'LOW' })]);
      await press('p');
      button('High')!.click();
      await refresh();
      await press('n');

      expect(title()).toBe('Item t2');
      expect(button('High')!.getAttribute('aria-pressed')).toBe('false');
      expect(button('Low')!.getAttribute('aria-pressed')).toBe('true');
      await press('n');
      expect(callsOf(updateTask).map((call) => call.params['id'])).toEqual(['t1']);
    });

    it('clears an attribute with null', async () => {
      await render([task('t1', { energy: 'LOW', contextIds: ['c1'] })]);
      await press('p');
      button('Low')!.click();
      button('@home')!.click();
      await refresh();
      await press('n');
      expect(callsOf(updateTask)[0].params['body']).toEqual({ energy: null, contextIds: [] });
    });

    it('trashes without saving the draft attributes', async () => {
      await render([task('t1')]);
      await press('p');
      button('High')!.click();
      await refresh();
      await press('x');
      expect(callsOf(updateTask)).toHaveLength(0);
      expect(callsOf(deleteTask)[0].params).toMatchObject({ id: 't1', 'If-Match': '"1"' });
    });

    it('shows the notes of the item', async () => {
      await render([task('t1', { notesMd: 'Ask about the warranty' })]);
      await press('p');
      expect(element.querySelector('.notes')?.textContent).toContain('Ask about the warranty');
    });
  });
});
