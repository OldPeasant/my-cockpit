import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ChecklistItemResponse, UpdateChecklistItemRequest } from '../../../api/generated';
import { ApiError } from '../../../core/http';
import { CHECKLIST_CONFLICT, TaskChecklist } from './task-checklist';
import { TaskDetailService } from './task-detail.service';

function item(id: string, position: number, fields: Partial<ChecklistItemResponse> = {}) {
  return {
    id,
    taskId: 't1',
    text: `Step ${id}`,
    done: false,
    position,
    version: 1,
    ...fields,
  } satisfies ChecklistItemResponse;
}

interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (error: unknown) => void;
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const settle = () => new Promise((resolve) => setTimeout(resolve));

@Component({
  imports: [TaskChecklist],
  template: `<app-task-checklist [taskId]="id()" />`,
})
class Host {
  readonly id = signal('t1');
}

describe('TaskChecklist', () => {
  /** The server's items per task. */
  let stored: Record<string, ChecklistItemResponse[]>;
  let loads: string[];
  let added: { taskId: string; text: string }[];
  let updates: { item: ChecklistItemResponse; body: UpdateChecklistItemRequest }[];
  let deletes: ChecklistItemResponse[];
  /** When set, the next request of that kind uses it instead of the default answer. */
  let failUpdate: ((item: ChecklistItemResponse) => unknown) | undefined;
  let failDelete: unknown;
  let failAdd: unknown;
  let loadAnswer: ((taskId: string) => Promise<ChecklistItemResponse[]>) | undefined;
  let element: HTMLElement;
  let host: Host;

  const service = {
    checklist: async (taskId: string) => {
      loads.push(taskId);
      if (loadAnswer) return loadAnswer(taskId);
      return [...(stored[taskId] ?? [])];
    },
    addChecklistItem: async (taskId: string, text: string) => {
      added.push({ taskId, text });
      if (failAdd) throw failAdd;
      const created = item(`n${added.length}`, (stored[taskId] ?? []).length, { text });
      stored[taskId] = [...(stored[taskId] ?? []), created];
      return created;
    },
    updateChecklistItem: async (old: ChecklistItemResponse, body: UpdateChecklistItemRequest) => {
      updates.push({ item: old, body });
      if (failUpdate) {
        const error = failUpdate(old);
        if (error) throw error;
      }
      const next = { ...old, ...body, version: old.version + 1 } as ChecklistItemResponse;
      stored[old.taskId] = stored[old.taskId].map((other) => (other.id === old.id ? next : other));
      return next;
    },
    deleteChecklistItem: async (old: ChecklistItemResponse) => {
      deletes.push(old);
      if (failDelete) throw failDelete;
      stored[old.taskId] = stored[old.taskId].filter((other) => other.id !== old.id);
    },
  };

  async function refresh(): Promise<void> {
    for (let i = 0; i < 3; i++) {
      await settle();
      TestBed.tick();
    }
  }

  async function render(): Promise<void> {
    const fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    element = fixture.nativeElement;
    document.body.appendChild(element);
    fixture.detectChanges();
    await refresh();
  }

  const texts = () =>
    [...element.querySelectorAll('.step-text')].map((label) => label.textContent?.trim());
  const checkboxes = () => [...element.querySelectorAll<HTMLInputElement>('input[type=checkbox]')];
  const buttons = (label: string) => [
    ...element.querySelectorAll<HTMLButtonElement>(`button[aria-label="${label}"]`),
  ];
  const addButton = () =>
    [...element.querySelectorAll<HTMLButtonElement>('.add-step button')].find((b) =>
      b.textContent?.includes('Add'),
    )!;
  const newInput = () => element.querySelector<HTMLInputElement>('.add-step input')!;
  const alert = () => element.querySelector('[role=alert]')?.textContent?.trim();
  const count = () => element.querySelector('.count')?.textContent?.trim();

  async function typeNew(value: string): Promise<void> {
    newInput().value = value;
    newInput().dispatchEvent(new Event('input'));
    await refresh();
  }

  async function click(button: HTMLElement): Promise<void> {
    button.click();
    await refresh();
  }

  beforeEach(() => {
    stored = {
      t1: [item('a', 0), item('b', 1, { done: true }), item('c', 2)],
      t2: [item('x', 0, { taskId: 't2', text: 'Other task step' })],
    };
    loads = [];
    added = [];
    updates = [];
    deletes = [];
    failUpdate = undefined;
    failDelete = undefined;
    failAdd = undefined;
    loadAnswer = undefined;
    TestBed.configureTestingModule({
      providers: [{ provide: TaskDetailService, useValue: service }],
    });
  });

  afterEach(() => element?.remove());

  it('loads the steps of the task and counts the done ones', async () => {
    await render();
    expect(loads).toEqual(['t1']);
    expect(texts()).toEqual(['Step a', 'Step b', 'Step c']);
    expect(checkboxes().map((box) => box.checked)).toEqual([false, true, false]);
    expect(count()).toBe('1/3');
  });

  it('shows no count for an empty checklist', async () => {
    stored['t1'] = [];
    await render();
    expect(texts()).toEqual([]);
    expect(count()).toBeUndefined();
  });

  it('shows an error when the checklist cannot be loaded', async () => {
    loadAnswer = () => Promise.reject(new ApiError(500, 'Server down'));
    await render();
    expect(alert()).toBe('Server down');
    expect(texts()).toEqual([]);
  });

  describe('add', () => {
    it('adds a trimmed step with the button and clears the input', async () => {
      await render();
      await typeNew('  Call the fictitious landlord  ');
      await click(addButton());

      expect(added).toEqual([{ taskId: 't1', text: 'Call the fictitious landlord' }]);
      expect(texts()).toEqual(['Step a', 'Step b', 'Step c', 'Call the fictitious landlord']);
      expect(newInput().value).toBe('');
      expect(count()).toBe('1/4');
    });

    it('adds a step with Enter', async () => {
      await render();
      await typeNew('Pack the toolbox');
      newInput().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
      await refresh();
      expect(added).toEqual([{ taskId: 't1', text: 'Pack the toolbox' }]);
    });

    it('does not add an empty or blank step', async () => {
      await render();
      expect(addButton().disabled).toBe(true);
      await typeNew('   ');
      expect(addButton().disabled).toBe(true);
      newInput().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
      await refresh();
      expect(added).toEqual([]);
    });

    it('keeps the text and shows the error when adding fails', async () => {
      failAdd = new ApiError(400, 'Bad Request', 'Text is too long.');
      await render();
      await typeNew('Too long, pretend');
      await click(addButton());
      expect(alert()).toBe('Text is too long.');
      expect(texts()).toEqual(['Step a', 'Step b', 'Step c']);
      expect(newInput().value).toBe('Too long, pretend');
      expect(addButton().disabled).toBe(false);
    });

    it('shows a generic message for a non-API error', async () => {
      failAdd = new Error('boom');
      await render();
      await typeNew('Anything');
      await click(addButton());
      expect(alert()).toBe('Something went wrong. Try again.');
    });
  });

  describe('toggle', () => {
    it('checks a step at once and saves it with the item', async () => {
      await render();
      // Hold the request so the optimistic state is observable.
      const pending = deferred<void>();
      const original = service.updateChecklistItem;
      service.updateChecklistItem = async (old, body) => {
        updates.push({ item: old, body });
        await pending.promise;
        return { ...old, ...body, version: old.version + 1 } as ChecklistItemResponse;
      };
      try {
        checkboxes()[0].click();
        await refresh();
        expect(checkboxes()[0].checked).toBe(true);
        expect(count()).toBe('2/3');
        expect(updates).toEqual([{ item: stored['t1'][0], body: { done: true } }]);

        pending.resolve();
        await refresh();
        expect(checkboxes()[0].checked).toBe(true);
        expect(alert()).toBeUndefined();
      } finally {
        service.updateChecklistItem = original;
      }
    });

    it('unchecks a done step', async () => {
      await render();
      checkboxes()[1].click();
      await refresh();
      expect(updates[0].body).toEqual({ done: false });
      expect(checkboxes()[1].checked).toBe(false);
      expect(count()).toBe('0/3');
    });

    it('uses the saved version for the next change of the same step', async () => {
      await render();
      checkboxes()[0].click();
      await refresh();
      checkboxes()[0].click();
      await refresh();
      expect(updates.map((u) => u.item.version)).toEqual([1, 2]);
      expect(updates.map((u) => u.body)).toEqual([{ done: true }, { done: false }]);
    });

    it('rolls the step back and shows the error when saving fails', async () => {
      await render();
      // The failure arrives after the optimistic state was rendered, as with a real request.
      const pending = deferred<never>();
      const original = service.updateChecklistItem;
      service.updateChecklistItem = async (old, body) => {
        updates.push({ item: old, body });
        return pending.promise;
      };
      try {
        checkboxes()[0].click();
        await refresh();
        expect(count()).toBe('2/3');
        pending.reject(new ApiError(503, 'Service Unavailable'));
        await refresh();
      } finally {
        service.updateChecklistItem = original;
      }
      expect(checkboxes()[0].checked).toBe(false);
      expect(count()).toBe('1/3');
      expect(alert()).toBe('Service Unavailable');
    });

    it('on 412 reloads the checklist and says it changed elsewhere', async () => {
      failUpdate = () => {
        // Someone else ticked it and renamed another step meanwhile.
        stored['t1'] = [
          item('a', 0, { done: true, version: 4 }),
          item('b', 1, { done: true, text: 'Renamed elsewhere', version: 2 }),
          item('c', 2),
        ];
        return new ApiError(412, 'Precondition Failed');
      };
      await render();
      checkboxes()[0].click();
      await refresh();

      expect(loads).toEqual(['t1', 't1']);
      expect(alert()).toBe(CHECKLIST_CONFLICT);
      expect(texts()).toEqual(['Step a', 'Renamed elsewhere', 'Step c']);
      expect(checkboxes()[0].checked).toBe(true);

      // The message goes away with the next successful change.
      failUpdate = undefined;
      checkboxes()[2].click();
      await refresh();
      expect(updates[1].item.version).toBe(1);
      expect(alert()).toBeUndefined();
    });

    it('lets no second toggle or remove start while a change is saving', async () => {
      await render();
      const pending = deferred<ChecklistItemResponse>();
      const original = service.updateChecklistItem;
      service.updateChecklistItem = async (old, body) => {
        updates.push({ item: old, body });
        return pending.promise;
      };
      try {
        await click(checkboxes()[0]);
        expect(checkboxes().every((box) => box.disabled)).toBe(true);
        expect(buttons('Remove step').every((b) => b.disabled)).toBe(true);

        // Even an event that reaches the disabled controls starts no overlapping request.
        checkboxes()[0].dispatchEvent(new Event('change'));
        checkboxes()[2].dispatchEvent(new Event('change'));
        buttons('Remove step')[1].dispatchEvent(new Event('click'));
        await refresh();
        expect(updates).toHaveLength(1);
        expect(deletes).toEqual([]);
        expect(count()).toBe('2/3');

        pending.resolve(item('a', 0, { done: true, version: 2 }));
        await refresh();
        expect(checkboxes().every((box) => !box.disabled)).toBe(true);
        expect(buttons('Remove step').every((b) => !b.disabled)).toBe(true);

        // The next toggle sends the version the server answered.
        service.updateChecklistItem = original;
        await click(checkboxes()[0]);
        expect(updates.map((u) => u.item.version)).toEqual([1, 2]);
        expect(alert()).toBeUndefined();
      } finally {
        service.updateChecklistItem = original;
      }
    });
  });

  describe('remove', () => {
    it('removes a step with its version', async () => {
      await render();
      await click(buttons('Remove step')[1]);
      expect(deletes).toEqual([item('b', 1, { done: true })]);
      expect(texts()).toEqual(['Step a', 'Step c']);
      expect(count()).toBe('0/2');
    });

    it('puts the step back and shows the error when removing fails', async () => {
      failDelete = new ApiError(500, 'Internal Server Error', 'Could not remove the step.');
      await render();
      await click(buttons('Remove step')[0]);
      expect(texts()).toEqual(['Step a', 'Step b', 'Step c']);
      expect(alert()).toBe('Could not remove the step.');
    });

    it('on 412 reloads the checklist and says it changed elsewhere', async () => {
      failDelete = new ApiError(412, 'Precondition Failed');
      await render();
      stored['t1'] = [item('a', 0, { version: 9 }), item('c', 1)];
      await click(buttons('Remove step')[0]);
      expect(alert()).toBe(CHECKLIST_CONFLICT);
      expect(texts()).toEqual(['Step a', 'Step c']);
    });
  });

  describe('reorder', () => {
    it('disables moving the first step up and the last step down', async () => {
      await render();
      expect(buttons('Move up').map((b) => b.disabled)).toEqual([true, false, false]);
      expect(buttons('Move down').map((b) => b.disabled)).toEqual([false, false, true]);
    });

    it('moves a step down and saves only the positions that changed', async () => {
      await render();
      await click(buttons('Move down')[0]);
      expect(texts()).toEqual(['Step b', 'Step a', 'Step c']);
      expect(updates.map((u) => [u.item.id, u.body])).toEqual([
        ['b', { position: 0 }],
        ['a', { position: 1 }],
      ]);
      expect(updates.every((u) => u.item.version === 1)).toBe(true);
      expect(stored['t1'].map((i) => [i.id, i.position])).toEqual([
        ['a', 1],
        ['b', 0],
        ['c', 2],
      ]);
      expect(alert()).toBeUndefined();
    });

    it('moves a step up', async () => {
      await render();
      await click(buttons('Move up')[2]);
      expect(texts()).toEqual(['Step a', 'Step c', 'Step b']);
      expect(updates.map((u) => [u.item.id, u.body])).toEqual([
        ['c', { position: 1 }],
        ['b', { position: 2 }],
      ]);
    });

    it('ignores moves outside the list or onto the same place', async () => {
      await render();
      const fixture = TestBed.createComponent(TaskChecklist);
      fixture.componentRef.setInput('taskId', 't1');
      fixture.detectChanges();
      await refresh();
      const checklist = fixture.componentInstance;
      await checklist.reorder(0, 0);
      await checklist.reorder(0, -1);
      await checklist.reorder(2, 3);
      expect(updates).toEqual([]);

      await checklist.reorder(0, 2);
      expect(updates.map((u) => [u.item.id, u.body])).toEqual([
        ['b', { position: 0 }],
        ['c', { position: 1 }],
        ['a', { position: 2 }],
      ]);
      fixture.destroy();
    });

    it('reloads what the server has when a position save fails half-way', async () => {
      failUpdate = (old) => (old.id === 'a' ? new ApiError(500, 'Internal Server Error') : null);
      await render();
      await click(buttons('Move down')[0]);
      // b was saved at position 0, a failed: the list is reloaded from the server, not rolled back.
      expect(updates.map((u) => u.item.id)).toEqual(['b', 'a']);
      expect(loads).toEqual(['t1', 't1']);
      expect(alert()).toBe('Internal Server Error');
      expect(texts()).toEqual(['Step a', 'Step b', 'Step c']);
      expect(buttons('Move up').map((b) => b.disabled)).toEqual([true, false, false]);
    });

    it('on 412 reloads the checklist and says it changed elsewhere', async () => {
      failUpdate = () => new ApiError(412, 'Precondition Failed');
      await render();
      await click(buttons('Move down')[1]);
      expect(loads).toEqual(['t1', 't1']);
      expect(alert()).toBe(CHECKLIST_CONFLICT);
      expect(texts()).toEqual(['Step a', 'Step b', 'Step c']);
    });

    it('disables moving while a change is saving', async () => {
      await render();
      const pending = deferred<ChecklistItemResponse>();
      const original = service.updateChecklistItem;
      service.updateChecklistItem = async (old, body) => {
        updates.push({ item: old, body });
        return pending.promise;
      };
      try {
        await click(buttons('Move down')[0]);
        expect(buttons('Move up').every((b) => b.disabled)).toBe(true);
        expect(buttons('Move down').every((b) => b.disabled)).toBe(true);
        expect(addButton().disabled).toBe(true);
        expect(checkboxes().every((box) => box.disabled)).toBe(true);
        expect(buttons('Remove step').every((b) => b.disabled)).toBe(true);
        pending.resolve(item('b', 0, { done: true, version: 2 }));
        await refresh();
        expect(buttons('Move up').map((b) => b.disabled)).toEqual([true, false, false]);
      } finally {
        service.updateChecklistItem = original;
      }
    });
  });

  describe('task change', () => {
    it('loads the checklist of the new task', async () => {
      await render();
      host.id.set('t2');
      await refresh();
      expect(loads).toEqual(['t1', 't2']);
      expect(texts()).toEqual(['Other task step']);
    });

    it('ignores a late answer for the previous task', async () => {
      const first = deferred<ChecklistItemResponse[]>();
      loadAnswer = (taskId) =>
        taskId === 't1' ? first.promise : Promise.resolve([...stored[taskId]]);
      await render();
      host.id.set('t2');
      await refresh();
      expect(texts()).toEqual(['Other task step']);

      first.resolve([item('a', 0)]);
      await refresh();
      expect(texts()).toEqual(['Other task step']);
    });

    it('ignores a late failure for the previous task', async () => {
      const first = deferred<ChecklistItemResponse[]>();
      loadAnswer = (taskId) =>
        taskId === 't1' ? first.promise : Promise.resolve([...stored[taskId]]);
      await render();
      host.id.set('t2');
      await refresh();
      first.reject(new ApiError(500, 'Old failure'));
      await refresh();
      expect(alert()).toBeUndefined();
      expect(texts()).toEqual(['Other task step']);
    });
  });
});
