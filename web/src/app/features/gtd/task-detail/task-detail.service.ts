import { HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
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
  MoveTaskRequest,
  NoteResponse,
  TaskPatchRequest,
  TaskResponse,
  updateChecklistItem,
  UpdateChecklistItemRequest,
  updateTask,
} from '../../../api/generated';
import { SILENT_ERRORS } from '../../../core/http';
import { etag } from '../data';

/**
 * The requests of the task detail editor. Errors are silent (no toast): the editor shows them
 * next to the form, and a 412 as "changed elsewhere".
 */
@Injectable()
export class TaskDetailService {
  private readonly api = inject(Api);

  get(id: string): Promise<TaskResponse> {
    return this.api.invoke(getTask, { id }, silent());
  }

  update(task: TaskResponse, body: TaskPatchRequest): Promise<TaskResponse> {
    return this.api.invoke(
      updateTask,
      { id: task.id, 'If-Match': etag(task.version), body },
      silent(),
    );
  }

  move(task: TaskResponse, body: MoveTaskRequest): Promise<TaskResponse> {
    return this.api.invoke(
      moveTask,
      { id: task.id, 'If-Match': etag(task.version), body },
      silent(),
    );
  }

  /** Moves the task to reference: it becomes a note, the task goes to the Trash. */
  convertToNote(task: TaskResponse): Promise<NoteResponse> {
    return this.api.invoke(
      convertTaskToNote,
      { id: task.id, 'If-Match': etag(task.version) },
      silent(),
    );
  }

  /** Moves the task to the calendar: it becomes an event, the task goes to the Trash. */
  convertToEvent(task: TaskResponse, body: ConvertToEventRequest): Promise<EventResponse> {
    return this.api.invoke(
      convertTaskToEvent,
      { id: task.id, 'If-Match': etag(task.version), body },
      silent(),
    );
  }

  async checklist(taskId: string): Promise<ChecklistItemResponse[]> {
    return (await this.api.invoke(listChecklistItems, { taskId }, silent())).items;
  }

  addChecklistItem(taskId: string, text: string): Promise<ChecklistItemResponse> {
    return this.api.invoke(createChecklistItem, { taskId, body: { text } }, silent());
  }

  updateChecklistItem(
    item: ChecklistItemResponse,
    body: UpdateChecklistItemRequest,
  ): Promise<ChecklistItemResponse> {
    return this.api.invoke(
      updateChecklistItem,
      { taskId: item.taskId, itemId: item.id, 'If-Match': etag(item.version), body },
      silent(),
    );
  }

  async deleteChecklistItem(item: ChecklistItemResponse): Promise<void> {
    await this.api.invoke(
      deleteChecklistItem,
      { taskId: item.taskId, itemId: item.id, 'If-Match': etag(item.version) },
      silent(),
    );
  }
}

function silent(): HttpContext {
  return new HttpContext().set(SILENT_ERRORS, true);
}
