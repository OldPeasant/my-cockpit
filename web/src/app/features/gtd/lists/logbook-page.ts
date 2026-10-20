import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed } from '@angular/core';
import { TaskQuery, TaskStore } from '../data';
import { TaskRow } from '../ui';
import { ListPage } from './list-page';
import { groupByCompletedDay, relativeDay } from './list-views';

/**
 * Logbook (SPEC.md §5.1): done tasks of all lists by the day they were completed, newest first.
 * The row checkbox reopens a task (it goes back to its list).
 */
@Component({
  selector: 'app-logbook-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TaskRow, DatePipe],
  providers: [TaskStore],
  templateUrl: './logbook-page.html',
  styleUrl: './lists.scss',
})
export class LogbookPage extends ListPage {
  protected readonly groups = computed(() => groupByCompletedDay(this.tasks.items()));
  protected readonly relativeDay = relativeDay;

  protected query(): TaskQuery {
    return { completed: true };
  }
}
