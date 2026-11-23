import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TaskQuery, TaskStore } from '../data';
import { TaskRow } from '../ui';
import { ListPage } from './list-page';
import { relativeDay } from './list-views';
import { TicklerStore } from './tickler-store';

/**
 * Tickler (SPEC.md §5.3): the open tasks hidden until their tickler date, soonest first, each with
 * the date it comes back to the Inbox.
 */
@Component({
  selector: 'app-tickler-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TaskRow, DatePipe],
  providers: [{ provide: TaskStore, useClass: TicklerStore }],
  templateUrl: './tickler-page.html',
  styleUrl: './lists.scss',
})
export class TicklerPage extends ListPage {
  protected readonly relativeDay = relativeDay;

  /** The store reads its own date ranges (see {@link TicklerStore}). */
  protected query(): TaskQuery {
    return {};
  }

  /** Whether the ISO date `date` is in the current year (then its year is left out). */
  protected thisYear(date: string): boolean {
    return date.slice(0, 4) === this.today().slice(0, 4);
  }
}
