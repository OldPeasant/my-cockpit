import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ProjectResponse } from '../../../api/generated';
import { localDate, ProjectStore } from '../data';
import { AreaStore } from './area-store';
import { ProjectForm } from './project-form';
import { finished, groupByArea, statusLabel } from './project-groups';

/**
 * The project list (SPEC.md §5.3): projects grouped by area, stalled ones (active without an open
 * next step) marked so they get a next step. Creates projects and edits their status, area and
 * dates; completed and dropped projects are hidden unless asked for.
 */
@Component({
  selector: 'app-projects-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, ProjectForm, RouterLink],
  templateUrl: './projects-page.html',
  styleUrls: ['../lists/lists.scss', './projects-page.scss'],
})
export class ProjectsPage {
  protected readonly projects = inject(ProjectStore);
  protected readonly areas = inject(AreaStore);
  private readonly router = inject(Router);

  protected readonly today = localDate();
  protected readonly showFinished = signal(false);
  /** Whether the new-project form is open. */
  protected readonly creating = signal(false);
  /** The project whose edit form is open. */
  protected readonly editing = signal<string | null>(null);

  protected readonly visible = computed(() =>
    this.projects.items().filter((project) => this.showFinished() || !finished(project)),
  );
  protected readonly groups = computed(() => groupByArea(this.visible(), this.areas.items()));
  protected readonly hiddenCount = computed(() => this.projects.items().filter(finished).length);
  protected readonly stalledCount = computed(
    () => this.visible().filter((project) => project.stalled).length,
  );

  protected readonly statusLabel = statusLabel;

  constructor() {
    void this.projects.ensureLoaded();
    void this.areas.ensureLoaded();
  }

  protected retry(): void {
    void this.projects.refresh();
  }

  protected created(project: ProjectResponse): void {
    this.creating.set(false);
    void this.router.navigate(['/projects', project.id]);
  }

  protected editLabel(title: string): string {
    return $localize`:@@gtd.projects.editLabel:Edit “${title}:title:”`;
  }
}
