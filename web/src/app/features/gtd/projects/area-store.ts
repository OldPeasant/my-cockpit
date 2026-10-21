import { Injectable } from '@angular/core';
import { AreaResponse, listAreas } from '../../../api/generated';
import { ListStore } from '../data';

/** Areas have no filters: the query is always `{}`. */
export type AreaQuery = Record<string, never>;

/**
 * All areas of focus in their order (SPEC.md §5.1), read-only: the projects views group by them
 * and pick one for a project. Areas are managed in the settings.
 */
@Injectable({ providedIn: 'root' })
export class AreaStore extends ListStore<AreaResponse, AreaQuery> {
  constructor() {
    super('AREA');
  }

  /** The running {@link ensureLoaded} load, shared by concurrent callers. */
  private pendingLoad?: Promise<void>;

  /** Loads the areas unless they are loaded; resolves once the load settled. */
  ensureLoaded(): Promise<void> {
    if (this.loaded()) return Promise.resolve();
    this.pendingLoad ??= this.load({}).finally(() => (this.pendingLoad = undefined));
    return this.pendingLoad;
  }

  protected async fetch(): Promise<AreaResponse[]> {
    return (await this.api.invoke(listAreas)).items;
  }

  protected matches(): boolean {
    return true;
  }

  protected compare(a: AreaResponse, b: AreaResponse): number {
    return a.position - b.position;
  }
}
