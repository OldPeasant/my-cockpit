import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component } from '@angular/core';
import {
  deleteTask,
  getReviewStaleNextActions,
  moveTask,
  TaskResponse,
} from '../../../api/generated';
import { etag } from '../../gtd/data';
import { StepList } from './step-list';

/** Step 6: next actions not changed for more than 30 days — keep, someday, or delete. */
@Component({
  selector: 'app-review-next-actions-step',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe],
  styleUrl: './steps.scss',
  template: `
    <p class="status" role="status">{{ status() }}</p>
    @if (loaded()) {
      <ul class="items">
        @for (task of items(); track task.id) {
          <li class="item">
            <div class="what">
              <span class="title">{{ task.title }}</span>
              <span class="meta" i18n="@@review.next.changed"
                >last changed {{ task.updatedAt | date: 'mediumDate' }}</span
              >
            </div>
            <div class="choices" role="group" [attr.aria-label]="task.title">
              <button
                type="button"
                class="choice"
                [disabled]="busy().has(task.id)"
                (click)="keep(task)"
                i18n="@@review.keep"
              >
                Keep
              </button>
              <button
                type="button"
                class="choice"
                [disabled]="busy().has(task.id)"
                (click)="someday(task)"
                i18n="@@review.toSomeday"
              >
                Someday
              </button>
              <button
                type="button"
                class="choice danger"
                [disabled]="busy().has(task.id)"
                (click)="remove(task)"
                i18n="@@review.delete"
              >
                Delete
              </button>
            </div>
          </li>
        } @empty {
          <li class="empty" i18n="@@review.next.empty">No stale next actions.</li>
        }
      </ul>
    } @else if (failed()) {
      <div role="alert">
        <p class="empty" i18n="@@review.loadError">This step could not be loaded.</p>
        <button type="button" class="choice" (click)="load()" i18n="@@review.retry">
          Try again
        </button>
      </div>
    } @else {
      <p class="empty" i18n="@@review.loading">Loading…</p>
    }
  `,
})
export class NextActionsStep extends StepList<TaskResponse> {
  protected async fetch(): Promise<TaskResponse[]> {
    return (await this.api.invoke(getReviewStaleNextActions, { olderThanDays: 30 })).items;
  }

  protected keep(task: TaskResponse): void {
    void this.decide(task, $localize`:@@review.kept:Kept “${task.title}:title:”.`);
  }

  protected someday(task: TaskResponse): void {
    void this.decide(
      task,
      $localize`:@@review.movedSomeday:Moved “${task.title}:title:” to Someday.`,
      () =>
        this.api.invoke(moveTask, {
          id: task.id,
          'If-Match': etag(task.version),
          body: { list: 'SOMEDAY' },
        }),
    );
  }

  protected remove(task: TaskResponse): void {
    void this.decide(
      task,
      $localize`:@@review.deleted:Moved “${task.title}:title:” to the trash.`,
      () => this.api.invoke(deleteTask, { id: task.id, 'If-Match': etag(task.version) }),
    );
  }
}
