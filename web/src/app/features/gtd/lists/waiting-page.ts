import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, signal } from '@angular/core';
import { TaskResponse } from '../../../api/generated';
import { TaskQuery, TaskStore } from '../data';
import { TaskRow } from '../ui';
import { ListPage } from './list-page';
import { byWaitingSince, daysBetween, followUpTask, LONG_WAIT_DAYS } from './list-views';

/**
 * Waiting For (SPEC.md §5.3): delegated tasks, longest waiting first, each with how long it has
 * been waiting and a "Follow up" action that creates a next action to chase it.
 */
@Component({
  selector: 'app-waiting-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TaskRow, DatePipe],
  providers: [TaskStore],
  templateUrl: './waiting-page.html',
  styleUrl: './lists.scss',
})
export class WaitingPage extends ListPage {
  protected readonly waiting = computed(() => byWaitingSince(this.tasks.items()));
  /** The last follow-up created, announced to the user. */
  protected readonly created = signal<string | null>(null);

  protected query(): TaskQuery {
    return { list: 'WAITING' };
  }

  protected days(task: TaskResponse): number | undefined {
    return task.waitingSince ? daysBetween(task.waitingSince, this.today()) : undefined;
  }

  protected long(task: TaskResponse): boolean {
    return (this.days(task) ?? 0) > LONG_WAIT_DAYS;
  }

  protected async followUp(task: TaskResponse): Promise<void> {
    const body = followUpTask(task);
    if (await this.run(task, () => this.tasks.create(body))) this.created.set(body.title);
  }

  protected followUpLabel(title: string): string {
    return $localize`:@@gtd.waiting.followUpLabel:Follow up on “${title}:title:”`;
  }
}
