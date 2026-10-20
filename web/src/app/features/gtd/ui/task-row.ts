import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { ContextResponse, ProjectResponse, TaskResponse } from '../../../api/generated';
import { localDate } from '../data/dates';
import { energyLabel, estimateLabel } from './labels';

/** How a due date relates to today. */
type DueState = 'overdue' | 'today' | 'later';

/**
 * One task in a list: a completion checkbox, the title (opens the task) and chips for its
 * contexts, energy, estimate, due date and project. Purely presentational: the view passes the
 * contexts and projects (e.g. `contextStore.byId()`) and handles the outputs.
 *
 * ```html
 * <app-task-row [task]="task" [contexts]="contexts.byId()" [projects]="projects.byId()"
 *               (toggle)="store.complete($event)" (open)="openDetail($event)" />
 * ```
 */
@Component({
  selector: 'app-task-row',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe],
  templateUrl: './task-row.html',
  styleUrl: './task-row.scss',
})
export class TaskRow {
  readonly task = input.required<TaskResponse>();
  /** Contexts by id, to show the task's context names. */
  readonly contexts = input<ReadonlyMap<string, ContextResponse>>(new Map());
  /** Projects by id, to show the task's project title. */
  readonly projects = input<ReadonlyMap<string, ProjectResponse>>(new Map());
  /** Whether to show the project chip (off inside a project's own view). */
  readonly showProject = input(true);
  /** Today's ISO date, for overdue/today due dates. */
  readonly today = input(localDate());

  /** The checkbox was clicked: complete the task, or reopen it when it is done. */
  readonly toggle = output<TaskResponse>();
  /** The title was clicked. */
  readonly open = output<TaskResponse>();

  protected readonly done = computed(() => Boolean(this.task().completedAt));
  protected readonly taskContexts = computed(() =>
    this.task()
      .contextIds.map((id) => this.contexts().get(id))
      .filter((context): context is ContextResponse => context !== undefined),
  );
  protected readonly project = computed(() => {
    const id = this.task().projectId;
    return id && this.showProject() ? this.projects().get(id) : undefined;
  });
  protected readonly energy = computed(() => {
    const energy = this.task().energy;
    return energy ? energyLabel(energy) : undefined;
  });
  protected readonly estimate = computed(() => {
    const minutes = this.task().timeEstimateMin;
    return minutes == null ? undefined : estimateLabel(minutes);
  });
  protected readonly due = computed((): DueState | undefined => {
    const due = this.task().dueDate;
    if (!due) return undefined;
    if (this.done()) return 'later';
    if (due < this.today()) return 'overdue';
    return due === this.today() ? 'today' : 'later';
  });
}
