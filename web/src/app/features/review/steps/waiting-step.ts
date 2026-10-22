import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component } from '@angular/core';
import {
  completeTask,
  createTask,
  getReviewWaitingFor,
  TaskResponse,
} from '../../../api/generated';
import { etag } from '../../gtd/data';
import { followUpTask } from '../../gtd/lists/list-views';
import { StepList } from './step-list';

/** Step 4: each Waiting For item — still waiting, follow up, or done. */
@Component({
  selector: 'app-review-waiting-step',
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
              <span class="meta">
                @if (task.waitingForText) {
                  <ng-container i18n="@@review.waiting.for"
                    >for {{ task.waitingForText }}</ng-container
                  >
                }
                @if (task.waitingSince) {
                  <ng-container i18n="@@review.waiting.since"
                    >since {{ task.waitingSince | date: 'MMM d' }}</ng-container
                  >
                }
              </span>
            </div>
            <div class="choices" role="group" [attr.aria-label]="task.title">
              <button
                type="button"
                class="choice"
                [disabled]="busy().has(task.id)"
                (click)="keep(task)"
                i18n="@@review.waiting.keep"
              >
                Still waiting
              </button>
              <button
                type="button"
                class="choice"
                [disabled]="busy().has(task.id)"
                (click)="followUp(task)"
                i18n="@@review.waiting.followUp"
              >
                Follow up
              </button>
              <button
                type="button"
                class="choice"
                [disabled]="busy().has(task.id)"
                (click)="done(task)"
                i18n="@@review.waiting.done"
              >
                Done
              </button>
            </div>
          </li>
        } @empty {
          <li class="empty" i18n="@@review.waiting.empty">Nothing left to check here.</li>
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
export class WaitingStep extends StepList<TaskResponse> {
  protected async fetch(): Promise<TaskResponse[]> {
    return (await this.api.invoke(getReviewWaitingFor)).items;
  }

  protected keep(task: TaskResponse): void {
    void this.decide(
      task,
      $localize`:@@review.waiting.kept:Still waiting for “${task.title}:title:”.`,
    );
  }

  protected followUp(task: TaskResponse): void {
    const body = followUpTask(task);
    void this.decide(
      task,
      $localize`:@@review.waiting.followedUp:Added “${body.title}:title:” to Next.`,
      () => this.api.invoke(createTask, { body }),
    );
  }

  protected done(task: TaskResponse): void {
    void this.decide(
      task,
      $localize`:@@review.waiting.completed:Completed “${task.title}:title:”.`,
      () => this.api.invoke(completeTask, { id: task.id, 'If-Match': etag(task.version) }),
    );
  }
}
