import { ChangeDetectionStrategy, Component } from '@angular/core';
import {
  deleteProject,
  deleteTask,
  getReviewSomeday,
  moveTask,
  updateProject,
} from '../../../api/generated';
import { etag } from '../../gtd/data';
import { StepList } from './step-list';

/** A Someday/Maybe task or project. */
export interface SomedayItem {
  readonly id: string;
  readonly kind: 'task' | 'project';
  readonly title: string;
  readonly version: number;
}

/** Step 7: Someday/Maybe tasks and projects — activate, keep, or delete. */
@Component({
  selector: 'app-review-someday-step',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './steps.scss',
  template: `
    <p class="status" role="status">{{ status() }}</p>
    @if (loaded()) {
      <ul class="items">
        @for (item of items(); track item.id) {
          <li class="item">
            <div class="what">
              <span class="title">{{ item.title }}</span>
              <span class="meta">
                @if (item.kind === 'project') {
                  <ng-container i18n="@@review.someday.project">Project</ng-container>
                } @else {
                  <ng-container i18n="@@review.someday.task">Task</ng-container>
                }
              </span>
            </div>
            <div class="choices" role="group" [attr.aria-label]="item.title">
              <button
                type="button"
                class="choice"
                [disabled]="busy().has(item.id)"
                (click)="activate(item)"
                i18n="@@review.activate"
              >
                Activate
              </button>
              <button
                type="button"
                class="choice"
                [disabled]="busy().has(item.id)"
                (click)="keep(item)"
                i18n="@@review.keep"
              >
                Keep
              </button>
              <button
                type="button"
                class="choice danger"
                [disabled]="busy().has(item.id)"
                (click)="remove(item)"
                i18n="@@review.delete"
              >
                Delete
              </button>
            </div>
          </li>
        } @empty {
          <li class="empty" i18n="@@review.someday.empty">Nothing on Someday/Maybe.</li>
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
export class SomedayStep extends StepList<SomedayItem> {
  protected async fetch(): Promise<SomedayItem[]> {
    const someday = await this.api.invoke(getReviewSomeday);
    return [
      ...someday.tasks.map(({ id, title, version }) => ({
        id,
        kind: 'task' as const,
        title,
        version,
      })),
      ...someday.projects.map(({ id, title, version }) => ({
        id,
        kind: 'project' as const,
        title,
        version,
      })),
    ];
  }

  /** A task becomes a next action, a project becomes active. */
  protected activate(item: SomedayItem): void {
    const ifMatch = etag(item.version);
    void this.decide(item, $localize`:@@review.activated:Activated “${item.title}:title:”.`, () =>
      item.kind === 'task'
        ? this.api.invoke(moveTask, { id: item.id, 'If-Match': ifMatch, body: { list: 'NEXT' } })
        : this.api.invoke(updateProject, {
            id: item.id,
            'If-Match': ifMatch,
            body: { status: 'ACTIVE' },
          }),
    );
  }

  protected keep(item: SomedayItem): void {
    void this.decide(item, $localize`:@@review.kept:Kept “${item.title}:title:”.`);
  }

  protected remove(item: SomedayItem): void {
    const ifMatch = etag(item.version);
    void this.decide(
      item,
      $localize`:@@review.deleted:Moved “${item.title}:title:” to the trash.`,
      () =>
        item.kind === 'task'
          ? this.api.invoke(deleteTask, { id: item.id, 'If-Match': ifMatch })
          : this.api.invoke(deleteProject, { id: item.id, 'If-Match': ifMatch }),
    );
  }
}
