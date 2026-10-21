import { HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import {
  Api,
  AreaResponse,
  ContextPatchRequest,
  ContextResponse,
  createArea,
  createContext,
  deleteArea,
  deleteContext,
  listAreas,
  listContexts,
  listProjects,
  listTasks,
  orderAreas,
  orderContexts,
  ProjectResponse,
  TaskResponse,
  updateArea,
  updateContext,
  updateProject,
  updateTask,
} from '../../../api/generated';
import { SILENT_ERRORS } from '../../../core/http';
import { etag } from '../../gtd/data';

/**
 * A delete stopped while moving the tasks or projects to another context or area, before the
 * context or area itself was deleted; `cause` is the error of that step.
 */
export class ReassignError extends Error {
  constructor(override readonly cause: unknown) {
    super('Moving the assigned items failed');
    this.name = 'ReassignError';
  }
}

/** Page size when collecting the tasks or projects a delete affects. */
const PAGE_SIZE = 200;

/**
 * Contexts and areas of focus (SPEC.md §5.1) for Settings → Tasks & projects. Errors are not
 * toasted: the section shows them next to the list that failed. Other views catch up through the
 * change events the server sends for every change.
 */
@Injectable({ providedIn: 'root' })
export class GtdSettingsService {
  private readonly api = inject(Api);

  /** All contexts in their order. */
  async contexts(): Promise<ContextResponse[]> {
    return (await this.api.invoke(listContexts, undefined, silent())).items;
  }

  createContext(name: string, color?: string): Promise<ContextResponse> {
    return this.api.invoke(createContext, { body: { name, color } }, silent());
  }

  /** Changes fields of `context`; `color: null` clears its color. */
  updateContext(context: ContextResponse, body: ContextPatchRequest): Promise<ContextResponse> {
    return this.api.invoke(
      updateContext,
      { id: context.id, 'If-Match': etag(context.version), body },
      silent(),
    );
  }

  /** Puts all contexts in the order of `ids`; returns them in the new order. */
  async orderContexts(ids: string[]): Promise<ContextResponse[]> {
    return (await this.api.invoke(orderContexts, { body: { ids } }, silent())).items;
  }

  /** The tasks (open and done) that have the context. */
  async tasksWithContext(id: string): Promise<TaskResponse[]> {
    const open = await this.allTasks(id, false);
    const done = await this.allTasks(id, true);
    return [...open, ...done];
  }

  /**
   * Deletes `context`. With a `replacement`, its tasks first get that context instead; without
   * one, the server just removes it from them. A failure while moving the tasks rejects with a
   * {@link ReassignError} and deletes nothing.
   */
  async deleteContext(context: ContextResponse, replacement: string | null): Promise<void> {
    if (replacement) {
      await reassigning(async () => {
        for (const task of await this.tasksWithContext(context.id)) {
          const contextIds = [
            ...new Set(task.contextIds.map((id) => (id === context.id ? replacement : id))),
          ];
          await this.api.invoke(
            updateTask,
            { id: task.id, 'If-Match': etag(task.version), body: { contextIds } },
            silent(),
          );
        }
      });
    }
    await this.api.invoke(
      deleteContext,
      { id: context.id, 'If-Match': etag(context.version) },
      silent(),
    );
  }

  /** All areas in their order. */
  async areas(): Promise<AreaResponse[]> {
    return (await this.api.invoke(listAreas, undefined, silent())).items;
  }

  createArea(title: string): Promise<AreaResponse> {
    return this.api.invoke(createArea, { body: { title } }, silent());
  }

  renameArea(area: AreaResponse, title: string): Promise<AreaResponse> {
    return this.api.invoke(
      updateArea,
      { id: area.id, 'If-Match': etag(area.version), body: { title } },
      silent(),
    );
  }

  /** Puts all areas in the order of `ids`; returns them in the new order. */
  async orderAreas(ids: string[]): Promise<AreaResponse[]> {
    return (await this.api.invoke(orderAreas, { body: { ids } }, silent())).items;
  }

  /** The projects (any status) in the area. */
  async projectsInArea(id: string): Promise<ProjectResponse[]> {
    const items: ProjectResponse[] = [];
    let cursor: string | undefined;
    do {
      const page = await this.api.invoke(
        listProjects,
        { areaId: id, cursor, limit: PAGE_SIZE },
        silent(),
      );
      items.push(...page.items);
      cursor = page.nextCursor ?? undefined;
    } while (cursor);
    return items;
  }

  /**
   * Deletes `area`. With a `target`, its projects first move to that area; without one, the
   * server leaves them without an area. A failure while moving the projects rejects with a
   * {@link ReassignError} and deletes nothing.
   */
  async deleteArea(area: AreaResponse, target: string | null): Promise<void> {
    if (target) {
      await reassigning(async () => {
        for (const project of await this.projectsInArea(area.id)) {
          await this.api.invoke(
            updateProject,
            { id: project.id, 'If-Match': etag(project.version), body: { areaId: target } },
            silent(),
          );
        }
      });
    }
    await this.api.invoke(deleteArea, { id: area.id, 'If-Match': etag(area.version) }, silent());
  }

  private async allTasks(context: string, completed: boolean): Promise<TaskResponse[]> {
    const items: TaskResponse[] = [];
    let cursor: string | undefined;
    do {
      const page = await this.api.invoke(
        listTasks,
        { context, completed, cursor, limit: PAGE_SIZE },
        silent(),
      );
      items.push(...page.items);
      cursor = page.nextCursor ?? undefined;
    } while (cursor);
    return items;
  }
}

/** Runs the reassignment step of a delete; its errors reject as {@link ReassignError}. */
async function reassigning(step: () => Promise<void>): Promise<void> {
  try {
    await step();
  } catch (error) {
    throw new ReassignError(error);
  }
}

function silent(): HttpContext {
  return new HttpContext().set(SILENT_ERRORS, true);
}
