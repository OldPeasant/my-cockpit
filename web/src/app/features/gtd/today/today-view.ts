import { DatePipe, NgComponentOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  InjectionToken,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { Router, RouterLink } from '@angular/router';
import { TaskResponse } from '../../../api/generated';
import { ContextStore, localDate, ProjectStore, TaskStore } from '../data';
import { TaskRow } from '../ui';
import { TODAY_SECTIONS } from './today-sections';

/** One store per task section of the view (each loads its own query). */
const OVERDUE = new InjectionToken<TaskStore>('TODAY_OVERDUE');
const DUE_TODAY = new InjectionToken<TaskStore>('TODAY_DUE');
const SCHEDULED = new InjectionToken<TaskStore>('TODAY_SCHEDULED');

/** A task section of the view. */
interface TaskSection {
  readonly id: 'overdue' | 'due' | 'scheduled';
  readonly title: string;
  readonly store: TaskStore;
  readonly tasks: readonly TaskResponse[];
}

/**
 * The Today view (SPEC.md §5.3): overdue tasks, tasks due today and tasks scheduled for today, then
 * the sections other features add through {@link TODAY_SECTIONS} (calendar events, habits).
 */
@Component({
  selector: 'app-today-view',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, MatButtonModule, NgComponentOutlet, RouterLink, TaskRow],
  providers: [
    { provide: OVERDUE, useClass: TaskStore },
    { provide: DUE_TODAY, useClass: TaskStore },
    { provide: SCHEDULED, useClass: TaskStore },
  ],
  templateUrl: './today-view.html',
  styleUrl: './today-view.scss',
})
export default class TodayView {
  private readonly router = inject(Router);
  private readonly overdue = inject(OVERDUE);
  private readonly dueToday = inject(DUE_TODAY);
  private readonly scheduled = inject(SCHEDULED);
  protected readonly projects = inject(ProjectStore);
  protected readonly contexts = inject(ContextStore);

  protected readonly today = localDate();
  protected readonly extensions = [...(inject(TODAY_SECTIONS, { optional: true }) ?? [])].sort(
    (a, b) => a.order - b.order,
  );

  protected readonly sections = computed((): readonly TaskSection[] => {
    const shown = new Set<string>();
    const unique = (tasks: readonly TaskResponse[]): TaskResponse[] =>
      tasks.filter((task) => !shown.has(task.id) && shown.add(task.id));
    return [
      {
        id: 'overdue',
        title: $localize`:@@gtd.today.overdue:Overdue`,
        store: this.overdue,
        tasks: unique(this.overdue.items()),
      },
      {
        id: 'due',
        title: $localize`:@@gtd.today.due:Due today`,
        store: this.dueToday,
        tasks: unique(this.dueToday.items()),
      },
      {
        id: 'scheduled',
        title: $localize`:@@gtd.today.scheduled:Scheduled for today`,
        store: this.scheduled,
        // Dates up to today: the server moves them to Next on their date.
        tasks: unique(
          this.scheduled
            .items()
            .filter((task) => task.scheduledDate !== undefined && task.scheduledDate <= this.today),
        ),
      },
    ];
  });
  protected readonly loaded = computed(() =>
    this.sections().every((section) => section.store.loaded()),
  );
  protected readonly failed = computed(() =>
    this.sections().some((section) => section.store.error() && !section.store.loaded()),
  );
  protected readonly nothing = computed(
    () => this.loaded() && this.sections().every((section) => section.tasks.length === 0),
  );

  constructor() {
    void this.overdue.load({ due: 'overdue' });
    void this.dueToday.load({ due: 'today' });
    void this.scheduled.load({ list: 'SCHEDULED' });
    void this.projects.ensureLoaded();
    void this.contexts.ensureLoaded();
  }

  protected retry(): void {
    for (const section of this.sections()) {
      if (section.store.error()) void section.store.refresh();
    }
  }

  protected toggleDone(section: TaskSection, task: TaskResponse): void {
    const store = section.store;
    void (task.completedAt ? store.uncomplete(task) : store.complete(task)).catch(() => undefined);
  }

  protected open(task: TaskResponse): void {
    void this.router.navigate(['/tasks', task.id]);
  }
}
