import { Injectable } from '@angular/core';
import {
  ContextPatchRequest,
  ContextResponse,
  createContext,
  CreateContextRequest,
  deleteContext,
  listContexts,
  updateContext,
} from '../../../api/generated';
import { ListStore, patched } from './list-store';

/** Contexts have no filters: the query is always `{}`. */
export type ContextQuery = Record<string, never>;

/**
 * All contexts (`@home`, `@phone`, ...) in their order (SPEC.md §5.1), one instance for the app;
 * call {@link ensureLoaded} where they are needed.
 */
@Injectable({ providedIn: 'root' })
export class ContextStore extends ListStore<ContextResponse, ContextQuery> {
  constructor() {
    super('CONTEXT');
  }

  /** The running {@link ensureLoaded} load, shared by concurrent callers. */
  private pendingLoad?: Promise<void>;

  /**
   * Loads the contexts unless they are loaded; resolves once the load settled (also for callers that
   * arrive while it is running).
   */
  ensureLoaded(): Promise<void> {
    if (this.loaded()) return Promise.resolve();
    this.pendingLoad ??= this.load({}).finally(() => (this.pendingLoad = undefined));
    return this.pendingLoad;
  }

  /** Creates a context; it shows once the server answered. */
  async create(body: CreateContextRequest): Promise<ContextResponse> {
    const context = await this.api.invoke(createContext, { body });
    this.accept(context);
    return context;
  }

  /** Changes fields of `context` (`null` clears the color). */
  update(context: ContextResponse, patch: ContextPatchRequest): Promise<ContextResponse> {
    return this.mutate(
      context.id,
      (current) => patched(current ?? context, patch),
      () =>
        this.api.invoke(updateContext, {
          id: context.id,
          'If-Match': this.ifMatch(context),
          body: patch,
        }),
    );
  }

  /** Deletes `context` (the server removes it from its tasks). */
  async remove(context: ContextResponse): Promise<void> {
    await this.mutate(
      context.id,
      () => undefined,
      () => this.api.invoke(deleteContext, { id: context.id, 'If-Match': this.ifMatch(context) }),
    );
  }

  protected async fetch(): Promise<ContextResponse[]> {
    return (await this.api.invoke(listContexts)).items;
  }

  protected matches(): boolean {
    return true;
  }

  protected compare(a: ContextResponse, b: ContextResponse): number {
    return a.position - b.position;
  }
}
