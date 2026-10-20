import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed } from '@angular/core';
import { TaskQuery, TaskStore } from '../data';
import { TaskRow } from '../ui';
import { ListPage } from './list-page';
import { groupByScheduledDate, relativeDay } from './list-views';

/** Scheduled (SPEC.md §5.1): tasks that become next actions on their date, grouped by date. */
@Component({
  selector: 'app-scheduled-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TaskRow, DatePipe],
  providers: [TaskStore],
  templateUrl: './scheduled-page.html',
  styleUrl: './lists.scss',
})
export class ScheduledPage extends ListPage {
  protected readonly groups = computed(() => groupByScheduledDate(this.tasks.items()));
  protected readonly relativeDay = relativeDay;

  protected query(): TaskQuery {
    return { list: 'SCHEDULED' };
  }
}
