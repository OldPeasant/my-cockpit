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
    const api = {
      invoke: async (fn: unknown, params: Record<string, unknown> = {}) => {
        calls.push({ fn, params });
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
    const events = {
      resync$: new Subject<void>(),
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
});
