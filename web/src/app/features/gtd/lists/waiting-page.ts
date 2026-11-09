import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Api, getContact, TaskResponse } from '../../../api/generated';
import { TaskQuery, TaskStore } from '../data';
import { TaskRow } from '../ui';
import { ListPage } from './list-page';
import {
  byWaitingSince,
  daysBetween,
  followUpMail,
  followUpTask,
  LONG_WAIT_DAYS,
} from './list-views';

/**
 * Waiting For (SPEC.md §5.3): delegated tasks, longest waiting first, each with how long it has
 * been waiting, a "Follow up" action that creates a next action to chase it and "Draft email",
 * which opens a new mail to the linked contact (or the address in the waiting-for text) with the
 * task as its subject.
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
  private readonly api = inject(Api);
  private readonly nav = inject(Router);
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

  /** Opens the compose page with To and subject filled in for chasing `task`. */
  protected async draftEmail(task: TaskResponse): Promise<void> {
    let address: string | undefined;
    if (task.waitingForContactId) {
      try {
        const contact = await this.api.invoke(getContact, { id: task.waitingForContactId });
        address = contact.emails[0]?.address;
      } catch {
        // The contact is gone or unreachable: fall back to the waiting-for text.
      }
    }
    await this.nav.navigate(['/mail/compose'], { queryParams: followUpMail(task, address) });
  }

  protected draftEmailLabel(title: string): string {
    return $localize`:@@gtd.waiting.draftEmailLabel:Draft an email about “${title}:title:”`;
  }

  protected followUpLabel(title: string): string {
    return $localize`:@@gtd.waiting.followUpLabel:Follow up on “${title}:title:”`;
  }
}
