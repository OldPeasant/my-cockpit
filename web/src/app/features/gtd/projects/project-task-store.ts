import { computed, Injectable, signal } from '@angular/core';
import { orderProjectTasks, TaskResponse } from '../../../api/generated';
import { TaskStore } from '../data';

/**
 * The open tasks of one project (`load({ projectId })`) in their order, which the user changes by
 * dragging: {@link reorder} shows the new order at once and keeps it until the server answered.
 */
@Injectable()
export class ProjectTaskStore extends TaskStore {
  /** The order shown while a reorder request runs (task ids), `null` otherwise. */
  private readonly order = signal<readonly string[] | null>(null);

  /** {@link items}, in the pending order while one is being saved. */
  readonly ordered = computed((): readonly TaskResponse[] => {
    const items = this.items();
    const order = this.order();
    if (!order) return items;
    const rank = new Map(order.map((id, index) => [id, index]));
    const at = (task: TaskResponse) => rank.get(task.id) ?? order.length;
    return [...items].sort((a, b) => at(a) - at(b));
  });

  /**
   * Saves `taskIds` (the project's open tasks) as the project's order. Rolls back and rethrows when
   * the server refuses.
   */
  async reorder(projectId: string, taskIds: readonly string[]): Promise<void> {
    this.order.set(taskIds);
    try {
      const answer = await this.api.invoke(orderProjectTasks, {
        id: projectId,
        body: { taskIds: [...taskIds] },
      });
      for (const task of answer.items) this.accept(task);
    } finally {
      if (this.order() === taskIds) this.order.set(null);
    }
  }
}
