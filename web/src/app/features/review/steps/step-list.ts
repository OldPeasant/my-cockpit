import { computed, Directive, inject, signal } from '@angular/core';
import { Api } from '../../../api/generated';

/**
 * The items of a review step that the user goes through one decision at a time. A decided item
 * leaves the list; the last decision is announced in {@link status}. Errors are shown by the HTTP
 * layer, and the item stays.
 */
@Directive()
export abstract class StepList<T extends { readonly id: string }> {
  protected readonly api = inject(Api);

  private readonly itemsState = signal<readonly T[]>([]);
  private readonly decided = signal<ReadonlySet<string>>(new Set());
  private readonly busyState = signal<ReadonlySet<string>>(new Set());
  readonly loaded = signal(false);
  readonly failed = signal(false);
  /** What happened to the last decided item. */
  readonly status = signal('');

  /** The items still to decide on. */
  readonly items = computed(() => this.itemsState().filter((item) => !this.decided().has(item.id)));
  /** Items whose action is running. */
  readonly busy = this.busyState.asReadonly();

  constructor() {
    void this.load();
  }

  /** The step's items. Runs from the constructor: it may use only {@link api}. */
  protected abstract fetch(): Promise<readonly T[]>;

  async load(): Promise<void> {
    this.failed.set(false);
    try {
      this.itemsState.set(await this.fetch());
      this.decided.set(new Set());
      this.loaded.set(true);
    } catch {
      this.failed.set(true);
    }
  }

  /** Shows the new state of an item that stays in the list. */
  private replace(item: T): void {
    this.itemsState.update((all) => all.map((other) => (other.id === item.id ? item : other)));
  }

  /**
   * Runs `action` on `item`; when it succeeds, `status` is shown and the item leaves the list, or,
   * with `stays`, is replaced by the item `action` returned.
   */
  protected async decide(
    item: T,
    status: string,
    action?: () => Promise<unknown>,
    stays = false,
  ): Promise<void> {
    if (this.busyState().has(item.id)) return;
    this.busyState.update((ids) => new Set(ids).add(item.id));
    try {
      const result = await action?.();
      if (stays) this.replace(result as T);
      else this.decided.update((ids) => new Set(ids).add(item.id));
      this.status.set(status);
    } catch {
      // The HTTP layer shows the error; the item stays to try again.
    } finally {
      this.busyState.update((ids) => {
        const rest = new Set(ids);
        rest.delete(item.id);
        return rest;
      });
    }
  }
}
