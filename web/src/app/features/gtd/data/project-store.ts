import { Injectable } from '@angular/core';
import {
  createProject,
  CreateProjectRequest,
  deleteProject,
  listProjects,
  ListProjects$Params,
  ProjectPatchRequest,
  ProjectResponse,
  setProjectNextStep,
  updateProject,
} from '../../../api/generated';
import { ListStore, patched } from './list-store';

/** The filters of a project list (`GET /api/v1/projects` without paging). */
export type ProjectQuery = Omit<ListProjects$Params, 'cursor' | 'limit'>;

/** Page size of list requests: the largest the server allows. */
export const PROJECT_PAGE_SIZE = 200;

/**
 * Projects (SPEC.md §5.1), ordered by title. One instance for the app holds all projects (task
 * rows show project titles); call {@link ensureLoaded} where they are needed. A view that needs a
 * filtered server list provides its own instance and calls {@link load}.
 *
 * Task changes refetch the list: completing or moving a next step changes `stalled`.
 */
@Injectable({ providedIn: 'root' })
export class ProjectStore extends ListStore<ProjectResponse, ProjectQuery> {
  constructor() {
    super('PROJECT', ['TASK']);
  }

  /** Loads all projects unless they are loaded or loading. */
  async ensureLoaded(): Promise<void> {
    if (!this.loaded() && !this.loading()) await this.load({});
  }

  /** Creates a project; it shows once the server answered. */
  async create(body: CreateProjectRequest): Promise<ProjectResponse> {
    const project = await this.api.invoke(createProject, { body });
    this.accept(project);
    return project;
  }

  /** Changes fields of `project` (`null` clears a field). */
  update(project: ProjectResponse, patch: ProjectPatchRequest): Promise<ProjectResponse> {
    return this.mutate(
      project.id,
      (current) => patched(current ?? project, patch),
      () =>
        this.api.invoke(updateProject, {
          id: project.id,
          'If-Match': this.ifMatch(project),
          body: patch,
        }),
    );
  }

  /** Makes `taskId` (an open task of the project) its next step; `null` clears it. */
  setNextStep(project: ProjectResponse, taskId: string | null): Promise<ProjectResponse> {
    return this.mutate(
      project.id,
      (current) => ({
        ...(current ?? project),
        nextTaskId: taskId ?? undefined,
        stalled: (current ?? project).status === 'ACTIVE' && !taskId,
      }),
      () =>
        this.api.invoke(setProjectNextStep, {
          id: project.id,
          'If-Match': this.ifMatch(project),
          body: { taskId },
        }),
    );
  }

  /** Moves `project` to the Trash. */
  async remove(project: ProjectResponse): Promise<void> {
    await this.mutate(
      project.id,
      () => undefined,
      () => this.api.invoke(deleteProject, { id: project.id, 'If-Match': this.ifMatch(project) }),
    );
  }

  protected async fetch(query: ProjectQuery): Promise<ProjectResponse[]> {
    const items: ProjectResponse[] = [];
    let cursor: string | undefined;
    do {
      const page = await this.api.invoke(listProjects, {
        ...query,
        cursor,
        limit: PROJECT_PAGE_SIZE,
      });
      items.push(...page.items);
      cursor = page.nextCursor ?? undefined;
    } while (cursor);
    return items;
  }

  protected matches(project: ProjectResponse, query: ProjectQuery): boolean {
    if (query.status && project.status !== query.status) return false;
    if (query.areaId && project.areaId !== query.areaId) return false;
    if (query.stalled !== undefined && project.stalled !== query.stalled) return false;
    return true;
  }

  protected compare(a: ProjectResponse, b: ProjectResponse): number {
    return a.title.localeCompare(b.title);
  }
}
