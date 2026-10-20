import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TaskResponse } from '../../../api/generated';
import { TaskQuery, TaskStore } from '../data';
import { TaskRow } from '../ui';
import { ListPage } from './list-page';

/** Someday/Maybe (SPEC.md §5.1): ideas parked for later; "Activate" makes one a next action. */
@Component({
  selector: 'app-someday-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TaskRow],
  providers: [TaskStore],
  templateUrl: './someday-page.html',
  styleUrl: './lists.scss',
})
export class SomedayPage extends ListPage {
  protected query(): TaskQuery {
    return { list: 'SOMEDAY' };
  }

  protected activate(task: TaskResponse): void {
    void this.run(task, () => this.tasks.move(task, { list: 'NEXT' }));
  }

  protected activateLabel(title: string): string {
    return $localize`:@@gtd.someday.activateLabel:Activate “${title}:title:”`;
  }
}
