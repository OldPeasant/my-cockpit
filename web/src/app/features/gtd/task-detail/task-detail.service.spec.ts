import { HttpContext } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import {
  Api,
  ChecklistItemResponse,
  convertTaskToEvent,
  convertTaskToNote,
  ConvertToEventRequest,
  createChecklistItem,
  deleteChecklistItem,
  EventResponse,
  getTask,
  listChecklistItems,
  moveTask,
  NoteResponse,
  TaskResponse,
  updateChecklistItem,
  updateTask,
} from '../../../api/generated';
import { SILENT_ERRORS } from '../../../core/http';
import { TaskDetailService } from './task-detail.service';

interface Call {
  fn: unknown;
  params: Record<string, unknown>;
  context?: HttpContext;
}

const TASK = {
  id: 't1',
  title: 'Water the fictitious ferns',
  list: 'NEXT',
  position: 1,
  contextIds: [],
  createdAt: '2026-10-01T08:00:00Z',
  updatedAt: '2026-10-01T08:00:00Z',
  version: 7,
} as TaskResponse;

const ITEM: ChecklistItemResponse = {
  id: 'i1',
  taskId: 't1',
  text: 'Buy a watering can',
  done: false,
  position: 0,
  version: 3,
};

describe('TaskDetailService', () => {
  let calls: Call[];
  let service: TaskDetailService;
  let response: unknown;

  beforeEach(() => {
    calls = [];
    response = undefined;
    const api = {
      invoke: async (fn: unknown, params: Record<string, unknown> = {}, context?: HttpContext) => {
        calls.push({ fn, params, context });
        return response;
      },
    };
    TestBed.configureTestingModule({
      providers: [TaskDetailService, { provide: Api, useValue: api }],
    });
    service = TestBed.inject(TaskDetailService);
  });

  const only = () => {
    expect(calls).toHaveLength(1);
    return calls[0];
  };

  it('loads a task silently', async () => {
    response = TASK;
    expect(await service.get('t1')).toBe(TASK);
    const call = only();
    expect(call.fn).toBe(getTask);
    expect(call.params).toEqual({ id: 't1' });
    expect(call.context?.get(SILENT_ERRORS)).toBe(true);
  });

  it('patches a task with If-Match of its version', async () => {
    response = { ...TASK, version: 8 };
    const saved = await service.update(TASK, { title: 'Water the ferns' });
    expect(saved.version).toBe(8);
    const call = only();
    expect(call.fn).toBe(updateTask);
    expect(call.params).toEqual({
      id: 't1',
      'If-Match': '"7"',
      body: { title: 'Water the ferns' },
    });
    expect(call.context?.get(SILENT_ERRORS)).toBe(true);
  });

  it('moves a task with If-Match of its version', async () => {
    await service.move(TASK, { list: 'SOMEDAY' });
    const call = only();
    expect(call.fn).toBe(moveTask);
    expect(call.params).toEqual({ id: 't1', 'If-Match': '"7"', body: { list: 'SOMEDAY' } });
    expect(call.context?.get(SILENT_ERRORS)).toBe(true);
  });

  it('converts a task to a note with If-Match of its version and returns the note', async () => {
    const note: NoteResponse = {
      id: 'n1',
      title: 'Water the fictitious ferns',
      displayTitle: 'Water the fictitious ferns',
      bodyMd: '',
      tags: [],
      pinned: false,
      archived: false,
      createdAt: '2026-10-02T08:00:00Z',
      updatedAt: '2026-10-02T08:00:00Z',
      version: 1,
    };
    response = note;
    expect(await service.convertToNote(TASK)).toBe(note);
    const call = only();
    expect(call.fn).toBe(convertTaskToNote);
    expect(call.params).toEqual({ id: 't1', 'If-Match': '"7"' });
    expect(call.context?.get(SILENT_ERRORS)).toBe(true);
  });

  it('passes a failed conversion on to the caller', async () => {
    const failing = {
      invoke: () => Promise.reject(new Error('412')),
    };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [TaskDetailService, { provide: Api, useValue: failing }],
    });
    await expect(TestBed.inject(TaskDetailService).convertToNote(TASK)).rejects.toThrow('412');
  });

  it('converts a task to an event with If-Match of its version and the request as body', async () => {
    const body: ConvertToEventRequest = {
      calendarId: 'work',
      allDay: false,
      start: '2026-10-22T07:00:00.000Z',
      end: '2026-10-22T08:00:00.000Z',
      timeZone: 'Europe/Zurich',
    };
    const event = {
      id: 'e1',
      calendarId: 'work',
      title: 'Water the fictitious ferns',
      allDay: false,
      start: '2026-10-22T07:00:00Z',
      end: '2026-10-22T08:00:00Z',
      timeZone: 'Europe/Zurich',
      version: 1,
    } as EventResponse;
    response = event;
    expect(await service.convertToEvent(TASK, body)).toBe(event);
    const call = only();
    expect(call.fn).toBe(convertTaskToEvent);
    expect(call.params).toEqual({ id: 't1', 'If-Match': '"7"', body });
    expect(call.context?.get(SILENT_ERRORS)).toBe(true);
  });

  it('passes a failed conversion to an event on to the caller', async () => {
    const failing = {
      invoke: () => Promise.reject(new Error('412')),
    };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [TaskDetailService, { provide: Api, useValue: failing }],
    });
    await expect(
      TestBed.inject(TaskDetailService).convertToEvent(TASK, {
        calendarId: 'work',
        allDay: true,
        startDate: '2026-10-22',
        endDate: '2026-10-23',
      }),
    ).rejects.toThrow('412');
  });

  it('lists the checklist items of a task', async () => {
    response = { items: [ITEM] };
    expect(await service.checklist('t1')).toEqual([ITEM]);
    const call = only();
    expect(call.fn).toBe(listChecklistItems);
    expect(call.params).toEqual({ taskId: 't1' });
    expect(call.context?.get(SILENT_ERRORS)).toBe(true);
  });

  it('adds a checklist item without If-Match', async () => {
    response = ITEM;
    expect(await service.addChecklistItem('t1', 'Buy a watering can')).toBe(ITEM);
    const call = only();
    expect(call.fn).toBe(createChecklistItem);
    expect(call.params).toEqual({ taskId: 't1', body: { text: 'Buy a watering can' } });
    expect(call.context?.get(SILENT_ERRORS)).toBe(true);
  });

  it('updates a checklist item with If-Match of the item version', async () => {
    response = { ...ITEM, done: true, version: 4 };
    expect((await service.updateChecklistItem(ITEM, { done: true })).version).toBe(4);
    const call = only();
    expect(call.fn).toBe(updateChecklistItem);
    expect(call.params).toEqual({
      taskId: 't1',
      itemId: 'i1',
      'If-Match': '"3"',
      body: { done: true },
    });
    expect(call.context?.get(SILENT_ERRORS)).toBe(true);
  });

  it('deletes a checklist item with If-Match of the item version', async () => {
    await expect(service.deleteChecklistItem(ITEM)).resolves.toBeUndefined();
    const call = only();
    expect(call.fn).toBe(deleteChecklistItem);
    expect(call.params).toEqual({ taskId: 't1', itemId: 'i1', 'If-Match': '"3"' });
    expect(call.context?.get(SILENT_ERRORS)).toBe(true);
  });

  it('passes errors on to the caller', async () => {
    const failing = {
      invoke: () => Promise.reject(new Error('offline')),
    };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [TaskDetailService, { provide: Api, useValue: failing }],
    });
    await expect(TestBed.inject(TaskDetailService).checklist('t1')).rejects.toThrow('offline');
  });
});
