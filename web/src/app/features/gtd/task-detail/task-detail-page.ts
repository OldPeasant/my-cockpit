import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { map } from 'rxjs';
import { BACK_LABEL, BackNavigation } from '../../../shared/navigation';
import { TaskDetail } from './task-detail';

/** Where closing a task opened by a deep link (or in a new tab) leads. */
const FALLBACK = '/inbox';

/**
 * `/tasks/:id`: the task detail editor as a page; closing goes back to where the task was opened
 * from (or to the Inbox) and the close button names that page.
 */
@Component({
  selector: 'app-task-detail-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TaskDetail],
  template: `
    @if (id(); as taskId) {
      <app-task-detail [taskId]="taskId" [backLabel]="backLabel()" (closed)="back()" />
    }
  `,
})
export class TaskDetailPage {
  private readonly navigation = inject(BackNavigation);

  protected readonly id = toSignal(
    inject(ActivatedRoute).paramMap.pipe(map((params) => params.get('id'))),
  );

  protected readonly backLabel = computed(() => {
    const previous = this.navigation.previous();
    return previous ? previous.label || BACK_LABEL : $localize`:@@nav.inbox:Inbox`;
  });

  protected back(): void {
    void this.navigation.back(FALLBACK);
  }
}
