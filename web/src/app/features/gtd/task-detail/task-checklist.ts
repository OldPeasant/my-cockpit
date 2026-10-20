import { CdkDrag, CdkDragDrop, CdkDragHandle, CdkDropList } from '@angular/cdk/drag-drop';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { ChecklistItemResponse } from '../../../api/generated';
import { ApiError } from '../../../core/http';
import { TaskDetailService } from './task-detail.service';

/** Shown when a checklist change met a newer version of an item (412). */
export const CHECKLIST_CONFLICT = $localize`:@@taskDetail.checklistConflict:The checklist was changed elsewhere and has been reloaded.`;

let nextUid = 0;

/**
 * The checklist of a task (SPEC.md §5.2): add, toggle, remove and reorder steps (drag, or the
 * up/down buttons). Each change is saved at once with the item's `If-Match`; a 412 reloads the list.
 * Needs a {@link TaskDetailService} from the parent.
 */
@Component({
  selector: 'app-task-checklist',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CdkDrag, CdkDragHandle, CdkDropList],
  templateUrl: './task-checklist.html',
  styleUrls: ['./controls.scss', './task-checklist.scss'],
})
export class TaskChecklist {
  private readonly service = inject(TaskDetailService);

  readonly taskId = input.required<string>();

  protected readonly uid = `app-task-checklist-${nextUid++}`;
  protected readonly items = signal<ChecklistItemResponse[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly newItem = signal('');
  protected readonly busy = signal(false);
  protected readonly doneCount = computed(() => this.items().filter((item) => item.done).length);

  constructor() {
    effect(() => {
      const id = this.taskId();
      untracked(() => {
        this.items.set([]);
        void this.load(id);
      });
    });
  }

  protected text(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected async addItem(event: Event): Promise<void> {
    event.preventDefault();
    const text = this.newItem().trim();
    if (!text || this.busy()) return;
    await this.change(async () => {
      const item = await this.service.addChecklistItem(this.taskId(), text);
      this.items.update((items) => [...items, item]);
      this.newItem.set('');
    });
  }

  /** Changes run one at a time, so each sends the version the server answered last. */
  protected async toggleItem(item: ChecklistItemResponse): Promise<void> {
    if (this.busy()) return;
    this.replace({ ...item, done: !item.done });
    await this.change(
      async () => this.replace(await this.service.updateChecklistItem(item, { done: !item.done })),
      () => this.replace(item),
    );
  }

  protected async removeItem(item: ChecklistItemResponse): Promise<void> {
    if (this.busy()) return;
    const before = this.items();
    this.items.update((items) => items.filter((other) => other.id !== item.id));
    await this.change(
      () => this.service.deleteChecklistItem(item),
      () => this.items.set(before),
    );
  }

  protected drop(event: CdkDragDrop<unknown>): void {
    void this.reorder(event.previousIndex, event.currentIndex);
  }

  /** Moves the item at `from` to `to` and saves the positions that changed (0, 1, 2, ...). */
  async reorder(from: number, to: number): Promise<void> {
    const before = this.items();
    if (from === to || to < 0 || to >= before.length || this.busy()) return;
    const items = [...before];
    const [moved] = items.splice(from, 1);
    items.splice(to, 0, moved);
    this.items.set(items);
    await this.change(
      async () => {
        for (const [index, item] of items.entries()) {
          if (item.position === index) continue;
          this.replace(await this.service.updateChecklistItem(item, { position: index }));
        }
      },
      // Some positions may be saved already: show what the server has.
      () => this.load(this.taskId()),
    );
  }

  private replace(item: ChecklistItemResponse): void {
    this.items.update((items) => items.map((other) => (other.id === item.id ? item : other)));
  }

  /** Runs a change; on an error it is undone (`rollback`) and the error shown. */
  private async change(run: () => Promise<unknown>, rollback?: () => unknown): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await run();
    } catch (error) {
      if (error instanceof ApiError && error.status === 412) {
        await this.load(this.taskId());
        this.error.set(CHECKLIST_CONFLICT);
      } else {
        await rollback?.();
        this.error.set(messageOf(error));
      }
    } finally {
      this.busy.set(false);
    }
  }

  private async load(taskId: string): Promise<void> {
    try {
      const items = await this.service.checklist(taskId);
      if (taskId === this.taskId()) this.items.set(items);
    } catch (error) {
      if (taskId === this.taskId()) this.error.set(messageOf(error));
    }
  }
}

function messageOf(error: unknown): string {
  if (error instanceof ApiError) return error.userMessage;
  return $localize`:@@taskDetail.unknownError:Something went wrong. Try again.`;
}
