import { HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import {
  Api,
  ChecklistItemResponse,
  completeTask,
  CompletedTaskResponse,
  convertTaskToEvent,
  convertTaskToNote,
  ConvertToEventRequest,
  createChecklistItem,
  createReminder,
  deleteChecklistItem,
  deleteReminder,
  deleteTask,
  EventResponse,
  getTask,
  listChecklistItems,
  listReminders,
  moveTask,
  MoveTaskRequest,
  NoteResponse,
  ReminderResponse,
  TaskPatchRequest,
  TaskResponse,
  uncompleteTask,
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

  /** Marks the task done; the answer tells whether its project needs a new next step. */
  complete(task: TaskResponse): Promise<CompletedTaskResponse> {
    return this.api.invoke(completeTask, { id: task.id, 'If-Match': etag(task.version) }, silent());
  }

  /**
   * Reopens a done task. `toast`: the HTTP layer shows an error (for the undo snackbar, which may
   * outlive the editor).
   */
  uncomplete(task: TaskResponse, toast = false): Promise<TaskResponse> {
    return this.api.invoke(
      uncompleteTask,
      { id: task.id, 'If-Match': etag(task.version) },
      toast ? undefined : silent(),
    );
  }

  /** Moves the task to the Trash. */
  async remove(task: TaskResponse): Promise<void> {
    await this.api.invoke(deleteTask, { id: task.id, 'If-Match': etag(task.version) }, silent());
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

  /** The task's reminders, fired or not, earliest first. */
  async reminders(taskId: string): Promise<ReminderResponse[]> {
    return (await this.api.invoke(listReminders, { itemType: 'TASK', itemId: taskId }, silent()))
      .items;
  }

  /** Adds a reminder to the task at `remindAt` (an ISO-8601 instant). */
  addReminder(taskId: string, remindAt: string): Promise<ReminderResponse> {
    return this.api.invoke(
      createReminder,
      { body: { itemType: 'TASK', itemId: taskId, remindAt, channel: 'NOTIFICATION' } },
      silent(),
    );
  }

  async deleteReminder(id: string): Promise<void> {
    await this.api.invoke(deleteReminder, { id }, silent());
  }
}

function silent(): HttpContext {
  return new HttpContext().set(SILENT_ERRORS, true);
}
