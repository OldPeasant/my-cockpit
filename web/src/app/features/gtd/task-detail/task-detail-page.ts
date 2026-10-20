import { Location } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { map } from 'rxjs';
import { TaskDetail } from './task-detail';

/** `/tasks/:id`: the task detail editor as a page; closing goes back (or to the Inbox). */
@Component({
  selector: 'app-task-detail-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TaskDetail],
  template: `
    @if (id(); as taskId) {
      <app-task-detail [taskId]="taskId" (closed)="back()" />
    }
  `,
})
export class TaskDetailPage {
  private readonly router = inject(Router);
  private readonly location = inject(Location);

  protected readonly id = toSignal(
    inject(ActivatedRoute).paramMap.pipe(map((params) => params.get('id'))),
  );

  protected back(): void {
    // The first navigation of the app has id 1: there is no page of this app to go back to.
    const navigationId = (this.location.getState() as { navigationId?: number } | null)
      ?.navigationId;
    if (navigationId && navigationId > 1) this.location.back();
    else void this.router.navigate(['/inbox']);
  }
}
