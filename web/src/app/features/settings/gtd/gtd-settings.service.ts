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
} from '../../../api/generated';
import { SILENT_ERRORS } from '../../../core/http';
import { etag } from '../../gtd/data';

/** Page size when counting the tasks or projects a delete affects. */
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
   * Deletes `context`. With a `replacement`, the server gives all its tasks (also those in the
   * tickler or the Trash) that context instead, in the same transaction; without one, it just
   * removes it from them.
   */
  async deleteContext(context: ContextResponse, replacement: string | null): Promise<void> {
    await this.api.invoke(
      deleteContext,
      {
        id: context.id,
        'If-Match': etag(context.version),
        replaceWith: replacement ?? undefined,
      },
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
   * Deletes `area`. With a `target`, the server moves all its projects (also those in the Trash)
   * to that area in the same transaction; without one, it leaves them without an area.
   */
  async deleteArea(area: AreaResponse, target: string | null): Promise<void> {
    await this.api.invoke(
      deleteArea,
      { id: area.id, 'If-Match': etag(area.version), moveTo: target ?? undefined },
      silent(),
    );
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

function silent(): HttpContext {
  return new HttpContext().set(SILENT_ERRORS, true);
}
